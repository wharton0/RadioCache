package com.radiocache.app.data.local

import com.radiocache.app.data.model.DownloadStatus
import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.Recording
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.SourceType
import com.radiocache.app.data.model.Station
import com.radiocache.app.data.model.StationType
import com.radiocache.app.data.local.Mappers.toDomain
import com.radiocache.app.data.local.Mappers.toEntity
import org.junit.Assert.*
import org.junit.Test

class MappersTest {

    @Test fun `station round-trips through entity`() {
        val station = Station("capital", "Capital London", "UK", StationType.MUSIC, listOf("global"), subscribed = true, liveStreamUrl = "https://x/", providerStationKey = "capital/london")
        assertEquals(station, station.toEntity().toDomain())
    }

    @Test fun `show round-trips through entity`() {
        val show = Show("s", "capital", "Breakfast", "Mon–Fri", 240, DownloadStrategy.STREAM_CAPTURE_FALLBACK, providerProgrammeKey = "46w7Auo", subscribed = true)
        assertEquals(show, show.toEntity().toDomain())
    }

    @Test fun `recording round-trips through entity`() {
        val rec = Recording("r", "s", "capital", "Ep", "desc", 1L, 2L, 3L, 4L, SourceType.CATCH_UP, "http://src", "http://audio", "/tmp/a.m4a", 1000L, DownloadStatus.COMPLETED, 100, 500L, 1.5f)
        assertEquals(rec, rec.toEntity().toDomain())
    }

    @Test fun `unknown enums fall back safely`() {
        val entity = StationEntity("x", "X", "UK", "NOPE", "", null, false, null, null)
        assertEquals(StationType.MUSIC, entity.toDomain().type)
    }
}
