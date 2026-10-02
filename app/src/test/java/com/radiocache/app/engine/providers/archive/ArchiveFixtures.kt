package com.radiocache.app.engine.providers.archive

import com.radiocache.app.data.model.Show

/**
 * Captured archive.org metadata responses, trimmed to the fields the parser
 * reads. Real shapes — taken from live `archive.org/metadata/<id>` replies —
 * so the parser is verified without any network access.
 */
object ArchiveFixtures {

    /** Golden Age of Radio: Public Domain, VBR MP3s with `length` seconds. */
    val GOLDEN_AGE_PD = """
    {
      "metadata": {
        "identifier": "USA-Golden-Age-of-Radio",
        "title": "USA - Golden Age of Radio",
        "usage": "Public Domain",
        "licenseurl": "https://archive.org/details/USA-Golden-Age-of-Radio",
        "date": "1948-01-01",
        "description": "Thirty broadcasts from the golden age of American old-time radio."
      },
      "files": [
        {"name": "USA-Golden-Age-of-Radio_meta.xml", "format": "Metadata"},
        {"name": "Jack Benny 1947-11-08.mp3", "format": "VBR MP3", "length": "1845.22", "size": "7341100"},
        {"name": "Suspense - Sorry Wrong Number 01.mp3", "format": "VBR MP3", "length": "2712.44", "size": "10850000"},
        {"name": "The Shadow - Society of the Living Dead.mp3", "format": "VBR MP3", "length": "2650.10", "size": "10600000"},
        {"name": "Box Thirteen 1948-09-12.mp3", "format": "VBR MP3", "length": "1502.9", "size": "6010000"},
        {"name": "itemimage.jpg", "format": "JPEG"}
      ]
    }
    """.trimIndent()

    /** LibriVox-derived item: 64kbps derivatives must be skipped. */
    val LIBRIVOX_DERIVATIVES = """
    {
      "metadata": {
        "identifier": "aesops_fables_librivox",
        "title": "Aesop's Fables, Volume 1",
        "usage": "Public Domain Mark 1.0",
        "date": "2008-04-01"
      },
      "files": [
        {"name": "aesops_fables_01_64kb.mp3", "format": "64Kbps MP3", "length": "300.5"},
        {"name": "aesops_fables_01_128kb.mp3", "format": "128Kbps MP3", "length": "300.5"},
        {"name": "aesops_fables_01.mp3", "format": "VBR MP3", "length": "301.2"},
        {"name": "aesops_fables_02.mp3", "format": "VBR MP3", "length": "285.0"},
        {"name": "aesops_fables__files.xml"},
        {"name": "aesops_fables_meta.xml"}
      ]
    }
    """.trimIndent()

    /** MUST be refused: NoDerivs forbids redistributing modified copies. */
    val NO_DERIVS = """
    {
      "metadata": {
        "identifier": "OTRR_Home_Page",
        "title": "OLD TIME RADIO RESEARCHERS GROUP Home Page",
        "usage": "Attribution-NonCommercial-NoDerivs 4.0 International"
      },
      "files": [
        {"name": "episode01.mp3", "format": "VBR MP3", "length": "1200.0"}
      ]
    }
    """.trimIndent()

    /** Blank licence: unknown provenance must be refused, not assumed safe. */
    val UNKNOWN_LICENCE = """
    {
      "metadata": {
        "identifier": "mystery_upload",
        "title": "Some Upload"
      },
      "files": [
        {"name": "track01.mp3", "length": "600.0"}
      ]
    }
    """.trimIndent()

    val show = Show(
        id = "golden-age",
        stationId = "archive-radio",
        name = "Golden Age of Radio",
        schedule = "Various",
        durationMinutes = 60,
        providerProgrammeKey = "ia:USA-Golden-Age-of-Radio",
    )
}
