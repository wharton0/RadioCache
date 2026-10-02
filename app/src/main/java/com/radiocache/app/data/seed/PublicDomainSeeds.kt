package com.radiocache.app.data.seed

/**
 * The bundled public-domain catalogue.
 *
 * Every entry here must be unambiguously redistributable: the app refuses
 * items whose archive.org licence is unknown, non-commercial, or
 * NoDerivs (see `ArchiveMetadataParser`). Provenance is recorded next to
 * each identifier so the shipping licences stay auditable.
 *
 * Identifiers are plain archive.org item ids. They were chosen because the
 * items are tagged Public Domain; they are trivially swappable, and each one
 * is independent — a single unavailable item degrades to one failing show,
 * never the whole sync.
 */
object PublicDomainSeeds {

    data class Seed(
        val showId: String,
        val stationId: String,
        val showName: String,
        val schedule: String,
        val durationMinutes: Int,
        val identifier: String,
        val licence: String,
        val blurb: String,
    )

    /**
     * Old-time radio on the Internet Archive. These are broadcasts from the
     * 1930s-1950s whose US broadcasts entered the public domain; archive.org
     * marks the items `Public Domain` / `Public Domain Mark 1.0`.
     */
    val OTR_SHOWS: List<Seed> = listOf(
        Seed(
            showId = "golden-age",
            stationId = "archive-radio",
            showName = "Golden Age of Radio",
            schedule = "Anthology",
            durationMinutes = 45,
            identifier = "USA-Golden-Age-of-Radio",
            licence = "Public Domain",
            blurb = "Broadcast-era radio from the 1930s-1950s.",
        ),
        Seed(
            showId = "old-time-radio",
            stationId = "archive-radio",
            showName = "Old Time Radio",
            schedule = "Anthology",
            durationMinutes = 30,
            identifier = "old-time-radio-collection",
            licence = "Public Domain",
            blurb = "Jack Benny, Abbott & Costello, The Shadow and more.",
        ),
        Seed(
            showId = "weird-circle",
            stationId = "archive-radio",
            showName = "Weird Circle",
            schedule = "Anthology",
            durationMinutes = 30,
            identifier = "Weird_Circle_otr",
            licence = "Public Domain",
            blurb = "Supernatural mystery serials from the original broadcasts.",
        ),
    )

    /**
     * LibriVox recordings of public-domain books, stored on archive.org.
     * LibriVox releases everything under public-domain dedication.
     */
    val LIBRIVOX_SHOWS: List<Seed> = listOf(
        Seed(
            showId = "librivox-misc",
            stationId = "archive-radio",
            showName = "LibriVox Recordings",
            schedule = "Anthology",
            durationMinutes = 30,
            identifier = "librivox",
            licence = "Public Domain",
            blurb = "Volunteer-read recordings of public-domain literature.",
        ),
    )

    /**
     * A deliberately tiny, always-available seed: Project Gutenberg's own
     * audio host is a separate origin from archive.org, so one host being
     * unreachable does not take the whole catalogue offline. Reached through
     * the generic RSS/enclosure path, not the archive provider.
     */
    val GUTENBERG_SHOWS: List<Seed> = listOf(
        Seed(
            showId = "gutenberg-audio",
            stationId = "gutenberg",
            showName = "Project Gutenberg Audio",
            schedule = "Anthology",
            durationMinutes = 30,
            identifier = "gutenberg",
            licence = "Public Domain",
            blurb = "Audio books produced from Gutenberg's public-domain texts.",
        ),
    )

    val ALL: List<Seed> = OTR_SHOWS + LIBRIVOX_SHOWS + GUTENBERG_SHOWS
}
