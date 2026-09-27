package com.calgrid.data.tasks

import com.calgrid.data.tasks.PendingOpType.CREATE
import com.calgrid.data.tasks.PendingOpType.DELETE
import com.calgrid.data.tasks.PendingOpType.UPDATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOpsTest {

    @Test
    fun updatesCoalesceWithQueuedCreateOrUpdate() {
        assertEquals(PendingOps.Plan.Nothing, PendingOps.plan(listOf(CREATE), UPDATE, localOnly = true))
        assertEquals(PendingOps.Plan.Nothing, PendingOps.plan(listOf(UPDATE), UPDATE, localOnly = false))
        assertEquals(PendingOps.Plan.Add(UPDATE), PendingOps.plan(emptyList(), UPDATE, localOnly = false))
    }

    @Test
    fun deletingUnpushedTaskForgetsIt() {
        assertEquals(PendingOps.Plan.Forget, PendingOps.plan(listOf(CREATE, UPDATE), DELETE, localOnly = true))
    }

    @Test
    fun deletingSyncedTaskReplacesQueuedUpdates() {
        assertEquals(PendingOps.Plan.Replace(DELETE), PendingOps.plan(listOf(UPDATE), DELETE, localOnly = false))
    }

    @Test
    fun httpStatusClassification() {
        assertEquals(PendingOps.Failure.DROP, PendingOps.classifyHttpStatus(400))
        assertEquals(PendingOps.Failure.DROP, PendingOps.classifyHttpStatus(404))
        assertEquals(PendingOps.Failure.RETRY, PendingOps.classifyHttpStatus(403))
        assertEquals(PendingOps.Failure.RETRY, PendingOps.classifyHttpStatus(429))
        assertEquals(PendingOps.Failure.RETRY, PendingOps.classifyHttpStatus(503))
    }

    @Test
    fun opsAreDroppedAfterMaxAttempts() {
        assertTrue(PendingOps.keepAfterFailure(0))
        assertTrue(PendingOps.keepAfterFailure(PendingOps.MAX_ATTEMPTS - 2))
        assertFalse(PendingOps.keepAfterFailure(PendingOps.MAX_ATTEMPTS - 1))
    }

    @Test
    fun subtasksFollowTheirParent() {
        fun t(id: String, pos: String, parent: String? = null) =
            TaskEntity(id, "l", id, null, null, false, parent, pos)
        val ordered = orderWithSubtasks(listOf(t("b", "2"), t("child", "1", parent = "a"), t("a", "1")))
        assertEquals(listOf("a" to 0, "child" to 1, "b" to 0), ordered.map { it.first.id to it.second })
    }
}
