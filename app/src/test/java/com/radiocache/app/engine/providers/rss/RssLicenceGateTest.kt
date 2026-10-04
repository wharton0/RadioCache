package com.radiocache.app.engine.providers.rss

import com.radiocache.app.data.model.Show
import org.junit.Assert.*
import org.junit.Test

/**
 * The generic RSS adapter is reachable for user-supplied feeds, so its
 * enclosures must pass the same licence gate as the bundled providers.
 * Without this, a feed pointing at NC/ND audio would make the app non-free.
 */
class RssLicenceGateTest {

    private val show = Show(
        id = "custom",
        stationId = "local",
        name = "Custom feed",
        schedule = "Various",
        durationMinutes = 30,
        feedUrl = "https://example.invalid/feed.xml",
    )

    private fun feed(itemBody: String) = """
    <rss version="2.0"><channel>
      <title>Feed</title>
      $itemBody
    </channel></rss>
    """.trimIndent()

    @Test fun `enclosure on a trusted host is offered`() {
        val xml = feed(
            """
            <item>
              <title>Episode one</title>
              <enclosure url="https://upload.wikimedia.org/wikipedia/commons/a/ab/one.ogg" type="audio/ogg"/>
            </item>
            """.trimIndent()
        )
        val out = RssFeedParser.parse(xml, show)
        assertEquals(1, out.size)
        assertNotNull(out.first().resolvedAudioUrl)
        assertTrue(out.first().catchUpAvailable)
    }

    @Test fun `enclosure on an untrusted host is refused`() {
        val xml = feed(
            """
            <item>
              <title>Episode one</title>
              <enclosure url="https://random-pirate.example.com/one.mp3" type="audio/mpeg"/>
            </item>
            """.trimIndent()
        )
        val out = RssFeedParser.parse(xml, show)
        assertEquals(1, out.size)          // metadata still listed
        assertNull(out.first().resolvedAudioUrl)
        assertFalse(out.first().catchUpAvailable)
    }

    @Test fun `non-commercial declaration blocks the enclosure`() {
        val xml = feed(
            """
            <item>
              <title>Restricted</title>
              <copyright>Creative Commons Attribution-NonCommercial 4.0</copyright>
              <enclosure url="https://upload.wikimedia.org/wikipedia/commons/c/cd/x.ogg" type="audio/ogg"/>
            </item>
            """.trimIndent()
        )
        val out = RssFeedParser.parse(xml, show)
        assertNull(out.first().resolvedAudioUrl)
        assertFalse(out.first().catchUpAvailable)
    }

    @Test fun `no-derivatives declaration blocks the enclosure`() {
        val xml = feed(
            """
            <item>
              <title>Restricted</title>
              <copyright>Creative Commons Attribution-NoDerivatives 4.0</copyright>
              <enclosure url="https://upload.wikimedia.org/wikipedia/commons/e/ef/y.ogg" type="audio/ogg"/>
            </item>
            """.trimIndent()
        )
        assertNull(RssFeedParser.parse(xml, show).first().resolvedAudioUrl)
    }

    @Test fun `share-alike link blocks when the project requires it`() {
        // by-sa is detected via the link; the gate must refuse it outright.
        val xml = feed(
            """
            <item>
              <title>SA</title>
              <link>https://creativecommons.org/licenses/by-sa/4.0/</link>
              <enclosure url="https://upload.wikimedia.org/wikipedia/commons/a/ab/z.ogg" type="audio/ogg"/>
            </item>
            """.trimIndent()
        )
        assertNull(RssFeedParser.parse(xml, show).first().resolvedAudioUrl)
    }
}
