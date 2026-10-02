package com.radiocache.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class RetentionPolicyTest {

    // 2025-09-29T00:00:00Z (exact date irrelevant — tests use relative math)
    private val midnight = 1_759_104_000_000L

    @Test fun `cutoff is keepDays before today midnight`() {
        assertEquals(midnight - 7 * RetentionPolicy.DAY_MS, RetentionPolicy.cutoffEpochMs(midnight, 7))
    }

    @Test fun `recent broadcast is kept`() {
        assertFalse(RetentionPolicy.isExpired(midnight - 3 * RetentionPolicy.DAY_MS, midnight, 7))
    }

    @Test fun `old broadcast expires`() {
        assertTrue(RetentionPolicy.isExpired(midnight - 8 * RetentionPolicy.DAY_MS, midnight, 7))
    }

    @Test fun `undated imports are never expired`() {
        assertFalse(RetentionPolicy.isExpired(0, midnight, 1))
    }

    @Test fun `trim keeps newest N`() {
        assertEquals(
            listOf("d"),
            RetentionPolicy.idsBeyondLatest(listOf("a", "b", "c", "d"), 3),
        )
        assertTrue(RetentionPolicy.idsBeyondLatest(listOf("a", "b"), 3).isEmpty())
        assertTrue(RetentionPolicy.idsBeyondLatest(listOf("a"), 0) == listOf("a"))
    }
}
