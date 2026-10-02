package com.radiocache.app.download

import org.junit.Assert.*
import org.junit.Test

class AudioDownloaderTest {

    @Test fun `content range total parsed`() {
        assertEquals(1234L, AudioDownloader.parseContentRangeTotal("bytes 100-999/1234"))
        assertEquals(85000000L, AudioDownloader.parseContentRangeTotal("bytes 0-1023/85000000"))
    }

    @Test fun `missing or wildcard total returns null`() {
        assertNull(AudioDownloader.parseContentRangeTotal(null))
        assertNull(AudioDownloader.parseContentRangeTotal("bytes 100-999/*"))
        assertNull(AudioDownloader.parseContentRangeTotal("bytes 100-999"))
        assertNull(AudioDownloader.parseContentRangeTotal(""))
        assertNull(AudioDownloader.parseContentRangeTotal("bytes 100-999/0"))
    }
}
