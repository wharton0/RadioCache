package com.radiocache.app.engine.download

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.Show

/**
 * Pure, unit-testable acquisition decision:
 *
 *   Catch-up FIRST → available? YES → Download
 *                              NO  → Live Stream Capture fallback
 *                                      (unless strategy is CATCH_UP_ONLY)
 *
 * This is the class the acceptance criteria revolve around — it must never
 * report "project cannot work" just because RSS is missing.
 */
object DownloadPlanner {

    sealed interface Decision {
        data class DownloadCatchUp(val programme: BroadcastProgramme) : Decision
        data class CaptureLive(val programme: BroadcastProgramme) : Decision
        data class ScheduleCapture(val show: Show) : Decision
        data class Skip(val reason: String) : Decision
    }

    fun decide(
        show: Show,
        programmes: List<BroadcastProgramme>,
    ): Decision {
        val withAudio = programmes.filter { it.catchUpAvailable }
        if (withAudio.isNotEmpty()) {
            // Newest broadcast first.
            return Decision.DownloadCatchUp(withAudio.maxBy { it.scheduledStartEpochMs })
        }
        return when (show.strategy) {
            DownloadStrategy.CATCH_UP_ONLY ->
                Decision.Skip("Catch-up unavailable and strategy is CATCH_UP_ONLY")
            DownloadStrategy.CATCH_UP_PREFERRED,
            DownloadStrategy.STREAM_CAPTURE_FALLBACK -> {
                val latest = programmes.maxByOrNull { it.scheduledStartEpochMs }
                if (latest != null) Decision.CaptureLive(latest)
                else Decision.ScheduleCapture(show)
            }
        }
    }
}
