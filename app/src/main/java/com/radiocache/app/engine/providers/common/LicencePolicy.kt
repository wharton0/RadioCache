package com.radiocache.app.engine.providers.common

/**
 * The licence gate for bundled content.
 *
 * ## Why this exists
 *
 * The first attempt at this read `usage` from archive.org item metadata and
 * refused anything blank. Measured in practice that field is almost never
 * filled in: an advancedsearch for `usage:"Public Domain Mark 1.0" AND
 * mediatype:audio` returned **zero** items, and 3 of 5 seeded OTR items had no
 * licence field at all. A strict field-only gate therefore rejects almost
 * everything, which is a useless gate.
 *
 * ## The rule now
 *
 * A programme may be downloaded only when BOTH hold:
 *
 * 1. **Host is allow-listed** ([TRUSTED_HOSTS]) — we have reviewed what that
 *    host publishes and what its licence commitment is. Provenance comes
 *    from the publisher, not from a per-item field we cannot trust.
 * 2. **No item-level licence forbids us.** On Commons, licence metadata is
 *    mandatory, so it is authoritative and can only ever *narrow* what the
 *    host allow-list permits. NC / NoDerivs are hard vetoes.
 *
 * Unknown licence on a trusted host is treated as [TrustLevel.ATTESTED_HOST]:
 * acceptable for LibriVox (whose recordings are dedicated to the public
 * domain by the project itself), and simply unavailable for Commons, where
 * the field is always present.
 */
enum class TrustLevel {
    /** Per-item licence metadata present and explicitly permissive. */
    VERIFIED_LICENCE,

    /** Host is allow-listed and the item declares no licence at all. */
    ATTESTED_HOST,

    /** Nothing permits this. Never download. */
    REFUSED,
}

object LicencePolicy {

    /**
     * Hosts whose *publishing commitment* we accept. Each entry records why,
     * so the table stays auditable in review.
     */
    enum class Host(val rationale: String) {
        /**
         * Wikimedia Commons: every file MUST carry a machine-readable
         * licence (`extmetadata.LicenseShortName`); the upload workflow
         * rejects uploads without one. Measured 2026: `Public domain`,
         * `CC0`, `CC BY 2.5/3.0`, `PDM-owner`, `CC BY-SA 4.0`.
         */
        COMMONS(
            "Wikimedia Commons — per-file licence metadata is mandatory and " +
                "authoritative; honours NC/ND vetoes per file."
        ),

        /**
         * LibriVox: volunteer-read recordings of public-domain texts,
         * dedicated to the public domain by the project. Verified licence
         * statement at https://librivox.org/pages/public-domain/. Items
         * carry no per-file licence field, so the host attestation is the
         * basis — hence ATTESTED_HOST, never VERIFIED_LICENCE.
         */
        LIBRIVOX(
            "LibriVox — recordings dedicated to the public domain by the " +
                "project; site-level licence statement, no per-file licence."
        ),
    }

    val TRUSTED_HOSTS: Map<String, Host> = mapOf(
        "upload.wikimedia.org" to Host.COMMONS,
        "commons.wikimedia.org" to Host.COMMONS,
        "librivox.org" to Host.LIBRIVOX,
        "www.archive.org" to Host.LIBRIVOX, // see note in Host.LIBRIVOX
    )

    /**
     * Commons writes the short form in `LicenseShortName` and the long form
     * in `UsageTerms`. Matched case-insensitively as substrings.
     *
     * Anything containing NC or ND is a veto regardless of what else it says.
     */
    private val VETO_MARKERS = listOf(
        "noncommercial", "non-commercial", "-nc", " nc ",
        "noderiv", "no-deriv", "-nd",
    )

    /** Licences we will ship. Everything else is either vetoed or unknown. */
    private val PERMISSIVE_MARKERS = listOf(
        "public domain",
        "publicdomain",
        "cc0",
        "cc by",        // attribution only — redistribution allowed
        "cc-by",
        "pd",
        "attribution",
    )

    /** Permissive only when a non-copyleft notice is attached. */
    private val SHAREALIKE_MARKERS = listOf("cc by-sa", "cc-by-sa", "by-sa")

    /**
     * @param host          e.g. "upload.wikimedia.org"
     * @param licenceShort  Commons `LicenseShortName`, may be blank
     * @param usageTerms    Commons `UsageTerms`, may be blank
     * @param shareAlikeReq whether the project requires attribution to be shown
     */
    fun evaluate(
        host: String,
        licenceShort: String,
        usageTerms: String = "",
        shareAlikeReq: Boolean = false,
    ): TrustLevel {
        val trusted = TRUSTED_HOSTS[host.lowercase()] ?: return TrustLevel.REFUSED
        val text = "$licenceShort $usageTerms".lowercase()

        // Veto first: never let a permissive-looking substring win over an
        // explicit restriction. (e.g. "CC BY-NC-SA 4.0" contains "cc by".)
        if (VETO_MARKERS.any { text.contains(it) }) return TrustLevel.REFUSED

        val mentionsSa = SHAREALIKE_MARKERS.any { text.contains(it) }
        if (mentionsSa && shareAlikeReq) return TrustLevel.REFUSED

        if (text.isNotBlank() && PERMISSIVE_MARKERS.any { text.contains(it) }) {
            return TrustLevel.VERIFIED_LICENCE
        }

        // No usable licence text. Permitted only where we know the host
        // attests at the site level (LibriVox), never for Commons.
        return when (trusted) {
            Host.LIBRIVOX -> TrustLevel.ATTESTED_HOST
            Host.COMMONS -> TrustLevel.REFUSED
        }
    }

    /** Attribution string to persist alongside the recording, if required. */
    fun attributionFor(host: String, title: String, author: String?): String? {
        val text = title.lowercase()
        val needs = text.contains("cc by") || TRUSTED_HOSTS[host.lowercase()] != null
        if (!needs) return null
        val who = author?.trim()?.takeIf { it.isNotEmpty() } ?: "Wikimedia Commons contributor"
        return "$title — $who, via $host"
    }
}
