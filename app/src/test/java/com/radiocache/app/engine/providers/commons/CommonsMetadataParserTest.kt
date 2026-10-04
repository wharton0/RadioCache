package com.radiocache.app.engine.providers.commons

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.engine.providers.common.LicencePolicy
import com.radiocache.app.engine.providers.common.TrustLevel
import org.junit.Assert.*
import org.junit.Test

class LicencePolicyTest {

    @Test fun `untrusted host is always refused`() {
        assertEquals(
            TrustLevel.REFUSED,
            LicencePolicy.evaluate("evil.example.com", "Public domain"),
        )
    }

    @Test fun `commons public domain is verified`() {
        assertEquals(
            TrustLevel.VERIFIED_LICENCE,
            LicencePolicy.evaluate("upload.wikimedia.org", "Public domain", "Public domain"),
        )
        assertEquals(
            TrustLevel.VERIFIED_LICENCE,
            LicencePolicy.evaluate("upload.wikimedia.org", "CC0", "Creative Commons Zero"),
        )
    }

    @Test fun `commons cc-by is verified (redistribution permitted)`() {
        assertEquals(
            TrustLevel.VERIFIED_LICENCE,
            LicencePolicy.evaluate("upload.wikimedia.org", "CC BY 3.0", "Creative Commons Attribution 3.0"),
        )
    }

    @Test fun `commons blank licence is refused`() {
        // Commons always has the field; blank means we cannot prove anything.
        assertEquals(
            TrustLevel.REFUSED,
            LicencePolicy.evaluate("upload.wikimedia.org", "", ""),
        )
    }

    @Test fun `non-commercial is vetoed despite containing cc by`() {
        // The substring "cc by" is present — the NC veto must still win.
        assertEquals(
            TrustLevel.REFUSED,
            LicencePolicy.evaluate(
                "upload.wikimedia.org",
                "CC BY-NC-SA 4.0",
                "Creative Commons Attribution-NonCommercial-ShareAlike 4.0",
            ),
        )
    }

    @Test fun `no-derivatives is vetoed`() {
        assertEquals(
            TrustLevel.REFUSED,
            LicencePolicy.evaluate("upload.wikimedia.org", "CC BY-ND 4.0", "Creative Commons Attribution-NoDerivatives 4.0"),
        )
    }

    @Test fun `share-alike is refused when the project requires a matching licence`() {
        assertEquals(
            TrustLevel.VERIFIED_LICENCE,
            LicencePolicy.evaluate("upload.wikimedia.org", "CC BY-SA 4.0", "", shareAlikeReq = false),
        )
        assertEquals(
            TrustLevel.REFUSED,
            LicencePolicy.evaluate("upload.wikimedia.org", "CC BY-SA 4.0", "", shareAlikeReq = true),
        )
    }

    @Test fun `librivox falls back to host attestation when no field exists`() {
        assertEquals(
            TrustLevel.ATTESTED_HOST,
            LicencePolicy.evaluate("librivox.org", ""),
        )
    }

    @Test fun `librivox still honours an explicit restriction`() {
        assertEquals(
            TrustLevel.REFUSED,
            LicencePolicy.evaluate("librivox.org", "CC BY-NC 3.0"),
        )
    }

    @Test fun `host matching is case insensitive`() {
        assertEquals(
            TrustLevel.VERIFIED_LICENCE,
            LicencePolicy.evaluate("Upload.Wikimedia.ORG", "Public domain"),
        )
    }

    @Test fun `attribution string is produced for ccs`() {
        val a = LicencePolicy.attributionFor("upload.wikimedia.org", "A Show", "Some Speaker")
        assertNotNull(a)
        assertTrue(a!!.contains("Some Speaker"))
    }
}

class CommonsMetadataParserTest {

    private val show = CommonsFixtures.show

    @Test fun `radio shows map to programmes`() {
        val out = CommonsMetadataParser.parse(CommonsFixtures.RADIO_SHOWS, show)
        assertEquals(3, out.size)
        assertTrue(out.all { it.catchUpAvailable })
        assertTrue(out.all { it.resolvedAudioUrl!!.startsWith("https://upload.wikimedia.org/") })
    }

    @Test fun `duration seconds convert to milliseconds`() {
        val out = CommonsMetadataParser.parse(CommonsFixtures.RADIO_SHOWS, show)
        val sheffield = out.first { it.title.contains("Sheffield") }
        assertEquals(3_900_000L, sheffield.durationMs)
        assertEquals(AudioFormat.OGG_VORBIS, sheffield.audioFormat)
    }

    @Test fun `pre-epoch dates report as undated so retention can still expire them`() {
        val out = CommonsMetadataParser.parse(CommonsFixtures.RADIO_SHOWS, show)
        val gleason = out.first { it.title.contains("Gleason") }
        // 1944 predates the Unix epoch: a negative epochMs would be skipped by
        // retention's `broadcastDateEpochMs > 0` guard and never expire.
        assertEquals(0L, gleason.broadcastDateEpochMs)
        assertTrue(out.all { it.broadcastDateEpochMs >= 0L })
    }

    @Test fun `non-commercial and no-derivatives files are dropped`() {
        val out = CommonsMetadataParser.parse(CommonsFixtures.NON_COMMERCIAL, show)
        assertTrue("NC/ND must not be offered", out.isEmpty())
    }

    @Test fun `non-audio missing and unlicensed entries are skipped safely`() {
        val out = CommonsMetadataParser.parse(CommonsFixtures.MIXED_NOISE, show)
        // Only the CC0 mp3 survives.
        assertEquals(1, out.size)
        assertEquals(AudioFormat.MP3, out.first().audioFormat)
        assertTrue(out.first().title.contains("Fine one"))
    }

    @Test fun `title strips the File prefix and extension`() {
        assertEquals(
            "Jackie Gleason NBC Show 1944",
            CommonsMetadataParser.pageTitle("File:Jackie Gleason NBC Show 1944.ogg"),
        )
        assertEquals("Untitled recording", CommonsMetadataParser.pageTitle("File:.ogg"))
    }

    @Test fun `limit caps results`() {
        val out = CommonsMetadataParser.parse(CommonsFixtures.RADIO_SHOWS, show, limit = 1)
        assertEquals(1, out.size)
    }

    @Test fun `malformed json yields no programmes rather than throwing`() {
        assertTrue(CommonsMetadataParser.parse("not json", show).isEmpty())
        assertTrue(CommonsMetadataParser.parse("{}", show).isEmpty())
    }

    @Test fun `queries are percent encoded`() {
        assertTrue(CommonsMetadataParser.categoryQuery("Old time radio").contains("Category%3AOld+time+radio"))
        assertTrue(CommonsMetadataParser.searchQuery("old time radio").contains("filetype%3Aaudio"))
    }
}
