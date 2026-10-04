package com.radiocache.app.engine.providers.commons

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.engine.providers.common.LicencePolicy
import com.radiocache.app.engine.providers.common.TrustLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mapping from a Wikimedia Commons API reply to [BroadcastProgramme]s.
 * No I/O and no Android APIs, so it is covered by unit tests against captured
 * responses (see `CommonsFixtures`).
 *
 * Commons was chosen over archive.org because its licence field is
 * **mandatory** — the upload workflow rejects a file without one — so
 * [LicencePolicy] can trust it. See `LicencePolicy` for the measurements.
 */
object CommonsMetadataParser {

    private const val HOST = "upload.wikimedia.org"

    /** Commons MIME → our format enum. */
    private fun formatFor(mime: String): AudioFormat = when {
        mime.contains("mpeg") -> AudioFormat.MP3
        mime.contains("ogg") -> AudioFormat.OGG_VORBIS
        mime.contains("flac") -> AudioFormat.FLAC
        mime.contains("wav") -> AudioFormat.WAV
        mime.contains("mp4") || mime.contains("m4a") -> AudioFormat.M4A_AAC
        else -> AudioFormat.UNKNOWN
    }

    private fun extFor(format: AudioFormat): String = when (format) {
        AudioFormat.MP3 -> "mp3"
        AudioFormat.OGG_VORBIS -> "ogg"
        AudioFormat.FLAC -> "flac"
        AudioFormat.WAV -> "wav"
        AudioFormat.M4A_AAC -> "m4a"
        else -> "bin"
    }

    /**
     * @param json   body of a `action=query&prop=imageinfo` response
     * @param show   the show this listing backs
     * @param limit  max programmes to return
     */
    fun parse(
        json: String,
        show: Show,
        limit: Int = 7,
        shareAlikeRequired: Boolean = false,
    ): List<BroadcastProgramme> {
        // A truncated or non-JSON body must degrade to "no programmes", never
        // throw into the worker's per-show failure path.
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val pages = root.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        val out = mutableListOf<BroadcastProgramme>()
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            if (page.optBoolean("missing", false)) continue
            val infoArr = page.optJSONArray("imageinfo") ?: continue
            val ii = infoArr.optJSONObject(0) ?: continue

            val mime = ii.optString("mime")
            if (!mime.startsWith("audio") && !mime.contains("ogg")) continue

            val em = ii.optJSONObject("extmetadata") ?: JSONObject()
            val durationSec = metaOf(em, "Duration").toDoubleOrNull()
                ?: metaOf(em, "PlaytimeSeconds").toDoubleOrNull()
            val author = metaOf(em, "Artist").ifBlank { metaOf(em, "Credit") }
            val trust = LicencePolicy.evaluate(
                host = HOST,
                licenceShort = metaOf(em, "LicenseShortName"),
                usageTerms = metaOf(em, "UsageTerms"),
                shareAlikeReq = shareAlikeRequired,
            )
            if (trust == TrustLevel.REFUSED) continue

            val url = ii.optString("url")
            if (!url.startsWith("http")) continue

            val title = pageTitle(page.optString("title"))
            val shotDate = originalDate(metaOf(em, "DateTimeOriginal").ifBlank { metaOf(em, "DateTime") })

            out += BroadcastProgramme(
                programmeId = "commons:${page.optInt("pageid", 0)}:$title",
                showId = show.id,
                stationId = show.stationId,
                title = title,
                description = metaOf(em, "ImageDescription").take(280).ifBlank { null },
                // Commons exposes an original-date for uploads; fall back to the
                // epoch so retention ordering still functions deterministically.
                broadcastDateEpochMs = shotDate,
                scheduledStartEpochMs = shotDate,
                scheduledEndEpochMs = 0L,
                durationMs = durationSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
                sourceUrl = "https://commons.wikimedia.org/wiki/${page.optString("title").replace(' ', '_')}",
                resolvedAudioUrl = url,
                audioFormat = formatFor(mime),
                catchUpAvailable = true,
            )
        }
        return out.sortedByDescending { it.durationMs ?: 0L }.take(limit)
    }

    /** Reads one `extmetadata` entry's `value` as plain text. */
    private fun metaOf(extmetadata: JSONObject, key: String): String =
        extmetadata.optJSONObject(key)?.optString("value").orEmpty().trim()

    /** `File:Foo Bar.ogg` → `Foo Bar` */
    fun pageTitle(raw: String): String =
        raw.removePrefix("File:")
            .substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
            .ifBlank { "Untitled recording" }

    /**
     * Commons `DateTimeOriginal` / `DateTime` are ISO-ish; take the year.
     *
     * Archival material predates the Unix epoch (1944 → a NEGATIVE epochMs).
     * Retention skips rows whose `broadcastDateEpochMs <= 0`, so a negative
     * value would make such items un-expirable and grow storage without bound.
     * Pre-1970 dates are therefore reported as 0 (undated) — retention then
     * manages them by episode count rather than age.
     */
    private fun originalDate(meta: String): Long {
        val raw = meta
        val y = Regex("(\\d{4})").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
        if (y !in 1970..2030) return 0L
        return java.time.LocalDate.of(y, 1, 1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /** Search URL used to discover candidates in a category. */
    fun categoryQuery(category: String): String =
        "action=query&generator=categorymembers&gcmtitle=" +
            java.net.URLEncoder.encode("Category:$category", "UTF-8") +
            "&gcmtype=file&gcmlimit=50" +
            "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata%7Cmetadata" +
            "&format=json&formatversion=2"

    fun searchQuery(text: String): String =
        "action=query&list=search&srsearch=" +
            java.net.URLEncoder.encode("filetype:audio $text", "UTF-8") +
            "&srnamespace=6&srlimit=20&format=json&formatversion=2"
}
