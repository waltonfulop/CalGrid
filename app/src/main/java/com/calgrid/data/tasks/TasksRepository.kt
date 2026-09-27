package com.calgrid.data.tasks

import androidx.room.withTransaction
import com.calgrid.auth.NotAuthorizedException
import com.calgrid.model.TaskItem
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Offline-first access to Google Tasks. Every change is written to Room first and queued as a
 * pending op; [onLocalChange] then asks for a sync, which pushes the queue and pulls fresh data.
 */
class TasksRepository(
    private val db: TasksDb,
    private val api: TasksApi,
    private val onLocalChange: suspend () -> Unit,
) {
    private val dao = db.dao()

    fun observeLists(): Flow<List<TaskListEntity>> = dao.observeLists()
    fun observeTasks(listId: String): Flow<List<TaskEntity>> = dao.observeTasks(listId)
    fun observeOpenCounts(): Flow<List<ListCount>> = dao.observeOpenCounts()

    suspend fun lists(): List<TaskListEntity> = dao.lists()
    suspend fun task(id: String): TaskEntity? = dao.task(id)?.takeUnless { it.deleted }

    suspend fun widgetTasks(listIds: Set<String>?, includeCompleted: Boolean): List<TaskItem> =
        dao.allTasks()
            .asSequence()
            .filter { listIds == null || it.listId in listIds }
            .filter { includeCompleted || !it.completed }
            .sortedBy { it.position }
            .map { it.toItem() }
            .toList()

    // ---- local edits ----

    suspend fun createTask(listId: String, fields: TaskFields): String {
        val id = TaskEntity.LOCAL_PREFIX + UUID.randomUUID()
        db.withTransaction {
            dao.upsertTask(
                TaskEntity(
                    id = id, listId = listId, title = fields.title, notes = fields.notes,
                    due = fields.due, completed = fields.completed, parent = null, position = "",
                )
            )
            queue(id, listId, PendingOpType.CREATE)
        }
        onLocalChange()
        return id
    }

    suspend fun updateTask(id: String, fields: TaskFields) {
        val changed = db.withTransaction {
            val task = dao.task(id) ?: return@withTransaction false
            dao.upsertTask(
                task.copy(title = fields.title, notes = fields.notes, due = fields.due, completed = fields.completed)
            )
            queue(id, task.listId, PendingOpType.UPDATE)
            true
        }
        if (changed) onLocalChange()
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        val task = dao.task(id) ?: return
        updateTask(id, task.toFields().copy(completed = completed))
    }

    suspend fun deleteTask(id: String) {
        val changed = db.withTransaction {
            val task = dao.task(id) ?: return@withTransaction false
            queue(id, task.listId, PendingOpType.DELETE)
            true
        }
        if (changed) onLocalChange()
    }

    private suspend fun queue(taskId: String, listId: String, type: PendingOpType) {
        val queued = dao.pendingOpsFor(taskId)
        val localOnly = taskId.startsWith(TaskEntity.LOCAL_PREFIX)
        when (val plan = PendingOps.plan(queued.map { it.type }, type, localOnly)) {
            PendingOps.Plan.Nothing -> Unit
            is PendingOps.Plan.Add -> dao.upsertOp(PendingOpEntity(type = plan.type, taskId = taskId, listId = listId))
            is PendingOps.Plan.Replace -> {
                dao.deleteOpsFor(taskId)
                dao.upsertOp(PendingOpEntity(type = plan.type, taskId = taskId, listId = listId))
                if (plan.type == PendingOpType.DELETE) {
                    dao.task(taskId)?.let { dao.upsertTask(it.copy(deleted = true)) }
                }
            }
            PendingOps.Plan.Forget -> {
                dao.deleteOpsFor(taskId)
                dao.deleteTask(taskId)
            }
        }
    }

    // ---- list management (online only) ----

    suspend fun createList(title: String) {
        val dto = api.createList(title)
        dao.upsertList(TaskListEntity(dto.id, dto.title))
    }

    suspend fun renameList(listId: String, title: String) {
        val dto = api.renameList(listId, title)
        dao.upsertList(TaskListEntity(dto.id, dto.title))
    }

    suspend fun deleteList(listId: String) {
        api.deleteList(listId)
        db.withTransaction {
            dao.deleteTasksOfList(listId)
            dao.deleteList(listId)
        }
    }

    // ---- sync ----

    /** Pushes queued changes, then mirrors the server. Throws on errors that should be retried. */
    suspend fun sync() {
        push()
        pull()
    }

    private suspend fun push() {
        for (op in dao.pendingOps()) {
            try {
                pushOne(op)
                dao.deleteOp(op.seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotAuthorizedException) {
                throw e
            } catch (e: ResponseException) {
                val status = e.response.status.value
                if (PendingOps.classifyHttpStatus(status) == PendingOps.Failure.DROP) {
                    dao.deleteOp(op.seq)
                } else {
                    recordFailure(op)
                    throw e
                }
            } catch (e: Exception) {
                recordFailure(op)
                throw e
            }
        }
    }

    private suspend fun recordFailure(op: PendingOpEntity) {
        if (PendingOps.keepAfterFailure(op.attempts)) dao.upsertOp(op.copy(attempts = op.attempts + 1))
        else dao.deleteOp(op.seq)
    }

    private suspend fun pushOne(op: PendingOpEntity) {
        when (op.type) {
            PendingOpType.CREATE -> {
                val task = dao.task(op.taskId) ?: return
                if (task.deleted) return
                val created = api.createTask(task.listId, task.toFields())
                // Adopt the server id; later ops for this task follow the rename.
                db.withTransaction {
                    val current = dao.task(task.id) ?: task
                    dao.deleteTask(task.id)
                    dao.upsertTask(current.copy(id = created.id, position = created.position ?: current.position))
                    dao.renameOpsTask(task.id, created.id)
                }
            }
            PendingOpType.UPDATE -> {
                val task = dao.task(op.taskId) ?: return
                if (task.isLocalOnly) return
                api.updateTask(task.listId, task.id, task.toFields())
            }
            PendingOpType.DELETE -> {
                if (!op.taskId.startsWith(TaskEntity.LOCAL_PREFIX)) api.deleteTask(op.listId, op.taskId)
                dao.deleteTask(op.taskId)
            }
        }
    }

    private suspend fun pull() {
        val lists = api.lists()
        val listIds = lists.map { it.id }
        db.withTransaction {
            lists.forEach { dao.upsertList(TaskListEntity(it.id, it.title)) }
            dao.deleteListsExcept(listIds)
            dao.deleteTasksOfListsExcept(listIds)
        }
        val completedMin = Instant.now().minus(COMPLETED_WINDOW_DAYS, ChronoUnit.DAYS).toString()
        for (list in lists) {
            val remote = api.tasks(list.id, completedMin)
            db.withTransaction {
                val dirty = dao.pendingTaskIds().toSet()
                dao.deleteCleanTasksOfList(list.id)
                dao.upsertTasks(remote.filter { it.id !in dirty }.map { it.toEntity(list.id) })
            }
        }
    }

    suspend fun clearLocal() = dao.clearAll()

    companion object {
        /** Completed tasks older than this are not mirrored locally. */
        const val COMPLETED_WINDOW_DAYS = 30L
    }
}

fun TaskEntity.toFields() = TaskFields(title = title, notes = notes, due = due, completed = completed)

fun TaskEntity.toItem() = TaskItem(
    id = id,
    listId = listId,
    title = title,
    due = due?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    completed = completed,
)

private fun TaskDto.toEntity(listId: String) = TaskEntity(
    id = id,
    listId = listId,
    title = title,
    notes = notes,
    due = due?.take(10),
    completed = status == TaskDto.STATUS_COMPLETED,
    parent = parent,
    position = position.orEmpty(),
)

/** Top-level tasks in server order, each followed by its subtasks; pairs with the nesting depth. */
fun orderWithSubtasks(tasks: List<TaskEntity>): List<Pair<TaskEntity, Int>> {
    val ids = tasks.mapTo(HashSet()) { it.id }
    val children = tasks.filter { it.parent != null && it.parent in ids }.groupBy { it.parent }
    val roots = tasks.filter { it.parent == null || it.parent !in ids }.sortedBy { it.position }
    return roots.flatMap { root ->
        listOf(root to 0) + children[root.id].orEmpty().sortedBy { it.position }.map { it to 1 }
    }
}
