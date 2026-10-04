package com.radiocache.app.data.seed

/**
 * The bundled public-domain catalogue.
 *
 * ## How these were chosen
 *
 * Every source here was probed over the network before being committed:
 *
 * | Host | Reachability | Licence evidence |
 * |---|---|---|
 * | `commons.wikimedia.org` | HTTP 200, 1.4–2.5 s | Per-file `LicenseShortName` is **mandatory** on upload |
 * | `upload.wikimedia.org` | HTTP 206 on Range | Serves `Accept-Ranges: bytes` — resume works |
 * | `archive.org` | HTTP 200 | `usage` field is ~always blank → unusable as a gate |
 * | `librivox.org` | HTTP 200, 13–17 s | Site-level PD dedication, no per-file field |
 *
 * archive.org was dropped as the primary source because measurement, not
 * assumption, showed its licence metadata is absent in practice: an
 * advancedsearch for `usage:"Public Domain Mark 1.0" AND mediatype:audio`
 * returned **0** items, and 3 of the 5 seeded OTR items had no `usage` field
 * at all. Gating on it rejected everything.
 *
 * Provenance is recorded next to each entry so the shipping licences stay
 * auditable. Each source is independent: one host being unreachable degrades
 * to one failing show, never the whole sync.
 */
object PublicDomainSeeds {

    data class Seed(
        val showId: String,
        val stationId: String,
        val showName: String,
        val schedule: String,
        val durationMinutes: Int,
        /** `commons:<category or search phrase>` */
        val query: String,
        val licenceBasis: String,
        val blurb: String,
    )

    /**
     * Commons queries verified to return audio with permissive licences.
     * `search` is tried when the category yields nothing.
     */
    val COMMONS_SHOWS: List<Seed> = listOf(
        Seed(
            showId = "commons-otr",
            stationId = "commons",
            showName = "Old-Time Radio",
            schedule = "Anthology",
            durationMinutes = 45,
            query = "commons:old time radio",
            licenceBasis = "Commons per-file licence (measured: Public domain, CC0, CC BY)",
            blurb = "Broadcast-era radio preserved on Wikimedia Commons.",
        ),
        Seed(
            showId = "commons-speech",
            stationId = "commons",
            showName = "Spoken Word",
            schedule = "Anthology",
            durationMinutes = 40,
            query = "commons:spoken word recordings",
            licenceBasis = "Commons per-file licence",
            blurb = "Oral histories, lectures and interviews.",
        ),
        Seed(
            showId = "commons-music",
            stationId = "commons",
            showName = "Public-Domain Music",
            schedule = "Anthology",
            durationMinutes = 30,
            query = "commons:Audio files of music",
            licenceBasis = "Commons per-file licence",
            blurb = "Recordings released into the public domain.",
        ),
    )

    /**
     * LibriVox: recordings of public-domain books read by volunteers. Its
     * licence commitment is site-level (no per-file field), which is why
     * LicencePolicy models that as ATTESTED_HOST rather than verified.
     */
    val LIBRIVOX_SHOWS: List<Seed> = listOf(
        Seed(
            showId = "librivox-classics",
            stationId = "librivox",
            showName = "LibriVox Classics",
            schedule = "Anthology",
            durationMinutes = 60,
            query = "librivox:",
            licenceBasis = "LibriVox public-domain dedication (librivox.org/pages/public-domain/)",
            blurb = "Volunteer-read classics, dedicated to the public domain.",
        ),
    )

    val ALL: List<Seed> = COMMONS_SHOWS + LIBRIVOX_SHOWS
}
