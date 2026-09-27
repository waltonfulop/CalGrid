package com.calgrid.data.tasks

/** Rules for queueing local task changes that still have to reach Google Tasks. */
object PendingOps {

    const val MAX_ATTEMPTS = 8

    sealed interface Plan {
        /** An equivalent op is already queued; the push will send the row's current state. */
        data object Nothing : Plan
        data class Add(val type: PendingOpType) : Plan
        /** Drop queued ops and queue only [type]. */
        data class Replace(val type: PendingOpType) : Plan
        /** The task never reached the server: forget it entirely. */
        data object Forget : Plan
    }

    fun plan(queued: List<PendingOpType>, requested: PendingOpType, localOnly: Boolean): Plan =
        when (requested) {
            PendingOpType.CREATE -> Plan.Add(PendingOpType.CREATE)
            PendingOpType.UPDATE ->
                if (PendingOpType.CREATE in queued || PendingOpType.UPDATE in queued) Plan.Nothing
                else Plan.Add(PendingOpType.UPDATE)
            PendingOpType.DELETE ->
                if (localOnly) Plan.Forget else Plan.Replace(PendingOpType.DELETE)
        }

    enum class Failure {
        /** Transient (network, server, rate limit): keep the op and retry later. */
        RETRY,
        /** The server rejected this op for good: drop it. */
        DROP,
    }

    fun classifyHttpStatus(code: Int): Failure = when (code) {
        408, 429 -> Failure.RETRY
        403 -> Failure.RETRY // Tasks API reports quota errors as 403
        in 400..499 -> Failure.DROP
        else -> Failure.RETRY
    }

    /** Whether an op that just failed with a transient error should still be kept. */
    fun keepAfterFailure(attemptsSoFar: Int): Boolean = attemptsSoFar + 1 < MAX_ATTEMPTS
}
