package com.radiocache.app.engine.download

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.Show
import org.junit.Assert.*
import org.junit.Test

class DownloadPlannerTest {

    private fun show(strategy: DownloadStrategy) =
        Show("s1", "capital", "Capital Breakfast", "Mon–Fri", 240, strategy)

    private fun programme(id: String, start: Long, audio: String?) =
        BroadcastProgramme(
            programmeId = id, showId = "s1", stationId = "capital",
            title = id, broadcastDateEpochMs = 0L,
            scheduledStartEpochMs = start, scheduledEndEpochMs = start + 1,
            sourceUrl = null, resolvedAudioUrl = audio,
            catchUpAvailable = audio != null,
        )

    @Test fun `catch-up preferred downloads newest with audio`() {
        val decision = DownloadPlanner.decide(
            show(DownloadStrategy.CATCH_UP_PREFERRED),
            listOf(programme("old", 100, "http://a.m4a"), programme("new", 200, "http://b.m4a")),
        )
        assertTrue(decision is DownloadPlanner.Decision.DownloadCatchUp)
        assertEquals("new", (decision as DownloadPlanner.Decision.DownloadCatchUp).programme.programmeId)
    }

    @Test fun `missing catch-up falls back to capture`() {
        val decision = DownloadPlanner.decide(
            show(DownloadStrategy.CATCH_UP_PREFERRED),
            listOf(programme("meta-only", 100, null)),
        )
        assertTrue(decision is DownloadPlanner.Decision.CaptureLive)
    }

    @Test fun `catch-up only never captures`() {
        val decision = DownloadPlanner.decide(
            show(DownloadStrategy.CATCH_UP_ONLY),
            listOf(programme("meta-only", 100, null)),
        )
        assertTrue(decision is DownloadPlanner.Decision.Skip)
    }

    @Test fun `no programmes schedules capture`() {
        val decision = DownloadPlanner.decide(
            show(DownloadStrategy.STREAM_CAPTURE_FALLBACK), emptyList(),
        )
        assertTrue(decision is DownloadPlanner.Decision.ScheduleCapture)
    }
}
