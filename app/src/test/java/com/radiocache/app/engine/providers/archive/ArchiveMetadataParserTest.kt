package com.radiocache.app.engine.providers.archive

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.engine.providers.archive.ArchiveFixtures
import org.junit.Assert.*
import org.junit.Test

class ArchiveMetadataParserTest {

    private val show = ArchiveFixtures.show

    @Test fun `public domain item yields one programme per audio file`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.GOLDEN_AGE_PD, show)
        assertNull(item.licenceRejection)
        assertEquals("USA-Golden-Age-of-Radio", item.identifier)
        assertEquals(4, item.programmes.size) // 4 mp3s; meta.xml + jpg skipped
        assertTrue(item.programmes.all { it.catchUpAvailable })
        assertTrue(item.programmes.all { it.resolvedAudioUrl != null })
    }

    @Test fun `duration seconds convert to milliseconds`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.GOLDEN_AGE_PD, show)
        val shadow = item.programmes.first { it.title.contains("Shadow") }
        // 2650.10s * 1000
        assertEquals(2_650_100L, shadow.durationMs)
        assertEquals(AudioFormat.MP3, shadow.audioFormat)
    }

    @Test fun `longest programmes are preferred`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.GOLDEN_AGE_PD, show)
        val durations = item.programmes.map { it.durationMs ?: 0L }
        assertEquals(durations.sortedDescending(), durations)
        assertTrue(durations.first() > 2_700_000L)
    }

    @Test fun `limit caps the result`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.GOLDEN_AGE_PD, show, limit = 2)
        assertEquals(2, item.programmes.size)
    }

    @Test fun `download url uses the stable archive download path`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.GOLDEN_AGE_PD, show)
        assertTrue(
            item.programmes.first().resolvedAudioUrl!!
                .startsWith("https://archive.org/download/USA-Golden-Age-of-Radio/")
        )
    }

    @Test fun `spaces in filenames are percent encoded`() {
        assertEquals(
            "https://archive.org/download/abc/My%20Show%20ep%2001.mp3",
            ArchiveMetadataParser.downloadUrl("abc", "My Show ep 01.mp3"),
        )
    }

    @Test fun `64kbps derivatives and metadata files are excluded`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.LIBRIVOX_DERIVATIVES, show)
        assertNull(item.licenceRejection)
        assertEquals(2, item.programmes.size)
        assertTrue(item.programmes.none { it.title.contains("64kb") })
        assertTrue(item.programmes.none { it.title.contains("128kb") })
    }

    @Test fun `no-derivs licence is refused`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.NO_DERIVS, show)
        assertNotNull("NC-ND must be rejected", item.licenceRejection)
        assertTrue(item.programmes.isEmpty())
    }

    @Test fun `blank licence is refused rather than assumed public domain`() {
        val item = ArchiveMetadataParser.parse(ArchiveFixtures.UNKNOWN_LICENCE, show)
        assertNotNull("unknown provenance must be refused", item.licenceRejection)
        assertTrue(item.programmes.isEmpty())
    }

    @Test fun `cc-by is accepted only when explicitly allowed`() {
        val json = """
        {"metadata":{"identifier":"x","title":"X","usage":"Attribution 3.0"},
         "files":[{"name":"a.mp3","length":"100.0"}]}
        """.trimIndent()
        // Not in the default allow-list...
        assertNotNull(ArchiveMetadataParser.parse(json, show).licenceRejection)
        // ...but accepted once the caller opts in. archive.org spells CC BY as
        // "Attribution 3.0", so the allow-list entry "cc by 3.0" must match.
        assertNull(
            ArchiveMetadataParser.parse(json, show, allow = listOf("cc by 3.0")).licenceRejection
        )
    }

    @Test fun `licence spellings normalise to a canonical form`() {
        assertEquals("cc by 3.0", ArchiveMetadataParser.normaliseLicence("Attribution 3.0"))
        assertEquals("cc by 4.0", ArchiveMetadataParser.normaliseLicence("CC BY 4.0"))
        assertEquals("cc by 4.0", ArchiveMetadataParser.normaliseLicence("Attribution 4.0 International"))
        // A bare major digit still has to match an allow-list entry of "3.0".
        assertEquals("cc by 3.0", ArchiveMetadataParser.normaliseLicence("Attribution 3"))
    }

    @Test fun `non-commercial and no-derivatives are never upgraded to cc-by`() {
        // The dangerous case: stripping the NC/ND suffix would wrongly turn a
        // non-redistributable item into an acceptable one.
        val nc = """
        {"metadata":{"identifier":"y","usage":"Attribution-NonCommercial 3.0"},
         "files":[{"name":"a.mp3","length":"10.0"}]}
        """.trimIndent()
        val nd = """
        {"metadata":{"identifier":"z","usage":"Attribution-NoDerivs 4.0"},
         "files":[{"name":"a.mp3","length":"10.0"}]}
        """.trimIndent()
        assertNotNull(ArchiveMetadataParser.parse(nc, show, allow = listOf("cc by 3.0")).licenceRejection)
        assertNotNull(ArchiveMetadataParser.parse(nd, show, allow = listOf("cc by 4.0")).licenceRejection)
    }

    @Test fun `playable file detection`() {
        assertTrue(ArchiveMetadataParser.isPlayable("Show 01.mp3"))
        assertTrue(ArchiveMetadataParser.isPlayable("track.ogg"))
        assertFalse(ArchiveMetadataParser.isPlayable("notes.txt"))
        assertFalse(ArchiveMetadataParser.isPlayable("show_64kb.mp3"))
        assertFalse(ArchiveMetadataParser.isPlayable("cover.jpg"))
    }

    @Test fun `title drops the extension and tidies separators`() {
        assertEquals("My Show ep 01", ArchiveMetadataParser.titleFromFile("My_Show_ep_01.mp3", "fb"))
        assertEquals("fallback", ArchiveMetadataParser.titleFromFile("", "fallback"))
    }
}
