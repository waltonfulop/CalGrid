package com.calgrid.data.tasks

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "task_lists")
data class TaskListEntity(
    @PrimaryKey val id: String,
    val title: String,
)

/**
 * [id] is the Google task id, or a "local-…" id until a locally created task is pushed.
 * [deleted] marks a task whose deletion is still waiting to be pushed.
 */
@Entity(tableName = "tasks", indices = [Index("listId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val title: String,
    val notes: String?,
    /** yyyy-MM-dd */
    val due: String?,
    val completed: Boolean,
    val parent: String?,
    val position: String,
    val deleted: Boolean = false,
) {
    val isLocalOnly: Boolean get() = id.startsWith(LOCAL_PREFIX)

    companion object {
        const val LOCAL_PREFIX = "local-"
    }
}

enum class PendingOpType { CREATE, UPDATE, DELETE }

@Entity(tableName = "pending_ops")
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val type: PendingOpType,
    val taskId: String,
    val listId: String,
    val attempts: Int = 0,
)

@Dao
interface TasksDao {
    @Query("SELECT * FROM task_lists ORDER BY title COLLATE NOCASE")
    fun observeLists(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists ORDER BY title COLLATE NOCASE")
    suspend fun lists(): List<TaskListEntity>

    @Query("SELECT * FROM tasks WHERE listId = :listId AND deleted = 0")
    fun observeTasks(listId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE deleted = 0")
    suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT listId, COUNT(*) AS open FROM tasks WHERE deleted = 0 AND completed = 0 GROUP BY listId")
    fun observeOpenCounts(): Flow<List<ListCount>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    @Upsert
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Upsert
    suspend fun upsertList(list: TaskListEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM task_lists WHERE id = :id")
    suspend fun deleteList(id: String)

    @Query("DELETE FROM tasks WHERE listId = :listId")
    suspend fun deleteTasksOfList(listId: String)

    @Query("DELETE FROM task_lists WHERE id NOT IN (:keep)")
    suspend fun deleteListsExcept(keep: List<String>)

    @Query("DELETE FROM tasks WHERE listId NOT IN (:keep) AND id NOT IN (SELECT taskId FROM pending_ops)")
    suspend fun deleteTasksOfListsExcept(keep: List<String>)

    /** Removes synced tasks of a list that have no local changes waiting. */
    @Query("DELETE FROM tasks WHERE listId = :listId AND id NOT IN (SELECT taskId FROM pending_ops)")
    suspend fun deleteCleanTasksOfList(listId: String)

    @Query("SELECT * FROM pending_ops ORDER BY seq")
    suspend fun pendingOps(): List<PendingOpEntity>

    @Query("SELECT * FROM pending_ops WHERE taskId = :taskId")
    suspend fun pendingOpsFor(taskId: String): List<PendingOpEntity>

    @Query("SELECT taskId FROM pending_ops")
    suspend fun pendingTaskIds(): List<String>

    @Upsert
    suspend fun upsertOp(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE seq = :seq")
    suspend fun deleteOp(seq: Long)

    @Query("DELETE FROM pending_ops WHERE taskId = :taskId")
    suspend fun deleteOpsFor(taskId: String)

    @Query("UPDATE pending_ops SET taskId = :newId WHERE taskId = :oldId")
    suspend fun renameOpsTask(oldId: String, newId: String)

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()

    @Query("DELETE FROM task_lists")
    suspend fun clearLists()

    @Query("DELETE FROM pending_ops")
    suspend fun clearOps()

    @Transaction
    suspend fun clearAll() {
        clearOps()
        clearTasks()
        clearLists()
    }
}

data class ListCount(val listId: String, val open: Int)

@Database(
    entities = [TaskListEntity::class, TaskEntity::class, PendingOpEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TasksDb : RoomDatabase() {
    abstract fun dao(): TasksDao

    companion object {
        fun create(context: Context): TasksDb =
            Room.databaseBuilder(context, TasksDb::class.java, "tasks.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
