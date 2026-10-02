package com.radiocache.app.engine.capture

import org.junit.Assert.*
import org.junit.Test

class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=96000,CODECS="mp4a.40.2"
        low/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=320000,CODECS="mp4a.40.2"
        high/index.m3u8
    """.trimIndent()

    @Test fun `picks highest bandwidth variant with absolute url`() {
        val url = HlsPlaylist.pickVariant(master, "https://x.test/master.m3u8")
        assertEquals("https://x.test/high/index.m3u8", url)
    }

    @Test fun `passes through absolute variant urls`() {
        val abs = master.replace("high/index.m3u8", "https://cdn.test/h.m3u8")
        assertEquals("https://cdn.test/h.m3u8", HlsPlaylist.pickVariant(abs, "https://x.test/m.m3u8"))
    }

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:100
        #EXTINF:6.0,
        seg100.aac
        #EXTINF:6.0,
        seg101.aac
    """.trimIndent()

    @Test fun `parses sliding window segments`() {
        val m = HlsPlaylist.parseMedia(media, "https://x.test/live/playlist.m3u8")
        assertEquals(6L, m.targetDurationSec)
        assertFalse(m.endList)
        assertEquals(2, m.segments.size)
        assertEquals("https://x.test/live/seg100.aac", m.segments[0].uri)
        assertEquals(6000L, m.segments[0].durationMs)
    }

    @Test fun `detects endlist`() {
        val m = HlsPlaylist.parseMedia("#EXTM3U\n#EXT-X-ENDLIST\n", "https://x.test/a.m3u8")
        assertTrue(m.endList)
        assertTrue(m.segments.isEmpty())
    }
}
