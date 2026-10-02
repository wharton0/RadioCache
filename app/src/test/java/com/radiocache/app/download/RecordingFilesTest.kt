package com.radiocache.app.download

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingFilesTest {

    @Test fun `destination layout is station-show-date-id`() {
        val dest = RecordingFiles.destination(
            File("/data"), "capital", "capital-breakfast",
            1_759_104_000_000L, "46w7Auo-ep1", "https://x/ep.m4a",
        )
        assertEquals(
            File("/data/recordings/capital/capital-breakfast/2025-09-29_46w7Auo-ep1.m4a"),
            dest,
        )
    }

    @Test fun `unsafe characters are sanitized`() {
        assertEquals("a_b_c", RecordingFiles.sanitize("a/b:c"))
        assertEquals("episode", RecordingFiles.sanitize("///"))
    }

    @Test fun `mp3 hint keeps mp3 extension`() {
        val dest = RecordingFiles.destination(
            File("/data"), "s", "sh", 0L, "id", "https://x/ep.mp3?token=abc",
        )
        assertTrue(dest.name.endsWith(".mp3"))
    }
}
