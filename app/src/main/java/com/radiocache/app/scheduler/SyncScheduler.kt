package com.radiocache.app.scheduler

/**
 * Wi-Fi sync scheduler contract (Stage 4: real WorkManager implementation
 * in [SyncSchedulerImpl]).
 */
interface SyncScheduler {
    /** Ensures the periodic Wi-Fi sync is scheduled (idempotent, KEEP). */
    suspend fun schedulePeriodic()

    /** Re-reads settings and replaces the periodic schedule (UPDATE). */
    suspend fun reschedulePeriodic()

    /** Cancels only the periodic schedule (manual sync untouched). */
    fun cancelPeriodic()

    /** Enqueues an immediate one-shot sync. */
    fun syncNow()

    /** Enqueues an immediate one-shot sync for a single show (manual refresh). */
    fun syncShow(showId: String)

    fun cancelAll()
}

class NoOpSyncScheduler : SyncScheduler {
    override suspend fun schedulePeriodic() = Unit
    override suspend fun reschedulePeriodic() = Unit
    override fun cancelPeriodic() = Unit
    override fun syncNow() = Unit
    override fun syncShow(showId: String) = Unit
    override fun cancelAll() = Unit
}
