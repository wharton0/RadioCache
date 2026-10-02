package com.radiocache.app.scheduler

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SyncTimeTest {

    private fun at(h: Int, m: Int) =
        ZonedDateTime.of(2025, 9, 29, h, m, 0, 0, ZoneOffset.UTC)

    @Test fun `later today waits hours`() {
        val d = SyncTime.delayToNext(6, 0, at(5, 0))
        assertEquals(60L, d.toMinutes())
    }

    @Test fun `past time rolls to tomorrow`() {
        val d = SyncTime.delayToNext(6, 0, at(7, 30))
        assertEquals(22 * 60 + 30L, d.toMinutes())
    }

    @Test fun `exact minute rolls to tomorrow`() {
        val d = SyncTime.delayToNext(6, 0, at(6, 0))
        assertEquals(24 * 60L, d.toMinutes())
    }

    @Test fun `format pads`() {
        assertEquals("06:05", SyncTime.format(6, 5))
    }
}
