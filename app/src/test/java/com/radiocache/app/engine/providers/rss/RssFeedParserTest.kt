package com.radiocache.app.engine.providers.rss

import com.radiocache.app.data.model.DownloadStrategy
import com.radiocache.app.data.model.Show
import org.junit.Assert.*
import org.junit.Test

class RssFeedParserTest {

    private val show = Show("s1", "test", "Test Show", "daily", 60, DownloadStrategy.CATCH_UP_PREFERRED)

    private val feed = """
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Test Show</title>
            <item>
              <title>Breakfast Mon</title>
              <link>https://example.com/ep1</link>
              <guid>ep1</guid>
              <pubDate>Mon, 29 Sep 2025 06:00:00 +0000</pubDate>
              <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="12345"/>
              <itunes:duration>01:02:03</itunes:duration>
            </item>
            <item>
              <title>Breakfast Tue (no audio yet)</title>
              <link>https://example.com/ep2</link>
              <guid>ep2</guid>
              <pubDate>Tue, 30 Sep 2025 06:00:00 +0000</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test fun `enclosure item resolves audio`() {
        val programmes = RssFeedParser.parse(feed, show)
        assertEquals(2, programmes.size)
        // Newest first.
        assertEquals("ep2", programmes[0].programmeId)
        assertFalse(programmes[0].catchUpAvailable)

        val ep1 = programmes[1]
        assertEquals("ep1", ep1.programmeId)
        assertTrue(ep1.catchUpAvailable)
        assertEquals("https://cdn.example.com/ep1.mp3", ep1.resolvedAudioUrl)
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, ep1.durationMs)
        assertTrue(ep1.scheduledStartEpochMs > 0)
    }

    @Test fun `malformed xml returns empty list`() {
        assertTrue(RssFeedParser.parse("<rss><broken", show).isEmpty())
    }

    @Test fun `feed with doctype still parses`() {
        val withDoctype = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://example.com/rss.dtd">
            <rss version="2.0"><channel><title>T</title>
            <item><title>Ep</title><guid>g1</guid>
            <pubDate>Mon, 29 Sep 2025 06:00:00 +0000</pubDate>
            <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg" length="1"/>
            </item></channel></rss>
        """.trimIndent()
        val programmes = RssFeedParser.parse(withDoctype, show)
        assertEquals(1, programmes.size)
        assertTrue(programmes[0].catchUpAvailable)
    }

    @Test fun `duration formats parse`() {
        assertEquals(3_720_000L, RssFeedParser.parseDuration("3720"))
        assertEquals(90_000L, RssFeedParser.parseDuration("01:30"))
        assertNull(RssFeedParser.parseDuration("live"))
    }

    @Test fun `pubDate variants parse`() {
        assertTrue(RssFeedParser.parseDate("Mon, 29 Sep 2025 06:00:00 +0000") > 0)
        assertTrue(RssFeedParser.parseDate("Mon, 29 Sep 2025 06:00:00 GMT") > 0)
        assertEquals(0L, RssFeedParser.parseDate("not a date"))
    }
}
