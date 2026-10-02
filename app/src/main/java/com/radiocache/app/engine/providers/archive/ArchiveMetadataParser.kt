package com.radiocache.app.engine.providers.archive

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Pure mapping from an Internet Archive item's metadata JSON to
 * [BroadcastProgramme]s — one programme per audio file. No I/O, no Android
 * APIs, so it is fully covered by unit tests against a captured fixture.
 *
 * Object URLs use the stable `archive.org/download/<id>/<file>` form rather
 * than a specific `iaXXXXXX.us.archive.org` mirror host, because mirror
 * assignment changes server-side over time.
 */
object ArchiveMetadataParser {

    /** Licences we accept. Everything else is refused by design. */
    val ACCEPTED_LICENCE_MARKERS = listOf(
        "publicdomain",
        "public domain",
        "pd",
    )

    private val AUDIO_EXT = listOf(".mp3", ".ogg", ".m4a", ".flac", ".wav")

    /** Files that are derivatives/metadata, never a playable programme. */
    private val EXCLUDE_NAME_MARKERS = listOf(
        "_64kb", "_128kb", "_source.json", "_files.xml", "_meta.xml",
        "_chapters.json", "_thumb", "_spectrogram", "__ia_thumb",
    )

    data class Item(
        val identifier: String,
        val title: String,
        val licence: String,
        val programmes: List<BroadcastProgramme>,
        /** Set when the item is licensed in a way we refuse to ship. */
        val licenceRejection: String? = null,
    )

    /**
     * @param json   response body of `archive.org/metadata/<identifier>`
     * @param show   the show this item backs (supplied by the caller)
     * @param limit  maximum programmes to return
     * @param allow  extra licence markers to accept (e.g. "cc by 3.0")
     */
    fun parse(
        json: String,
        show: Show,
        limit: Int = 7,
        allow: List<String> = emptyList(),
    ): Item {
        val root = JSONObject(json)
        val meta = root.optJSONObject("metadata") ?: JSONObject()
        val identifier = meta.optString("identifier").ifBlank {
            show.providerProgrammeKey?.removePrefix(PROVIDER_KEY_PREFIX).orEmpty()
        }
        val title = meta.optString("title").ifBlank { show.name }
        val licence = readLicence(meta)

        if (!isAcceptable(licence, allow)) {
            return Item(
                identifier = identifier,
                title = title,
                licence = licence,
                programmes = emptyList(),
                licenceRejection = "licence not redistributable: '$licence'",
            )
        }

        val files = root.optJSONArray("files") ?: JSONArray()
        val out = mutableListOf<BroadcastProgramme>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            if (!isPlayable(name)) continue
            // archive.org reports length in seconds as a decimal string.
            val seconds = f.optString("length").toDoubleOrNull()
            val durationMs = if (seconds != null && seconds > 0) (seconds * 1000).toLong() else null
            val url = downloadUrl(identifier, name)
            out += BroadcastProgramme(
                programmeId = "$identifier/${f.optString("sha1").ifBlank { name }}",
                showId = show.id,
                stationId = show.stationId,
                title = titleFromFile(name, title),
                description = meta.optString("description").take(280).ifBlank { null },
                // Archive items have no reliable broadcast date; use the
                // item's own year so retention ordering still behaves.
                broadcastDateEpochMs = broadcastDate(meta),
                scheduledStartEpochMs = broadcastDate(meta),
                scheduledEndEpochMs = 0L,
                durationMs = durationMs,
                sourceUrl = "https://archive.org/details/$identifier",
                resolvedAudioUrl = url,
                audioFormat = formatFor(name),
                catchUpAvailable = true,
            )
        }
        // Longest first: a radio app wants substantial programmes, and the
        // 60-second "sample" clips archive.org ships must sort last.
        val picked = out.sortedByDescending { it.durationMs ?: 0L }.take(limit)
        return Item(identifier, title, licence, picked)
    }

    private fun readLicence(meta: JSONObject): String {
        val usage = meta.optString("usage").trim()
        if (usage.isNotEmpty()) return usage
        val rights = meta.optString("rights").trim()
        if (rights.isNotEmpty()) return rights
        return meta.optString("licenseurl").trim()
    }

    private fun isAcceptable(licence: String, allow: List<String>): Boolean {
        val l = licence.lowercase()
        if (l.isBlank()) return false // unknown licence => refuse
        if (ACCEPTED_LICENCE_MARKERS.any { l.contains(it) }) return true
        val normalised = normaliseLicence(licence)
        return allow.any { normalised.contains(it.lowercase()) }
    }

    /**
     * archive.org renders Creative Commons in several spellings: "Attribution
     * 3.0", "CC BY 3.0", "Attribution 4.0 International". Normalise so a
     * caller's allow-list entry ("cc by 3.0") matches every form.
     */
    fun normaliseLicence(licence: String): String {
        val l = licence.lowercase()
        if (!l.contains("attribution") && !l.contains("cc by")) return l
        // Strip non-commercial / no-derivatives BEFORE anything else: those
        // must never be silently upgraded into an acceptable licence.
        if (l.contains("noncommercial") || l.contains("non-commercial") || l.contains("-nc")) return l
        if (l.contains("noderiv") || l.contains("no-deriv")) return l
        // Match a full "3.0"/"4.0" first, then a bare major digit. Capturing
        // only the major would yield "cc by 3", which fails an allow-list
        // entry of "cc by 3.0".
        val version = Regex("(\\d+\\.\\d)").find(l)?.groupValues?.get(1)
            ?: Regex("\\b(\\d)\\b").find(l)?.groupValues?.get(1)?.plus(".0")
            ?: "4.0"
        return "cc by $version"
    }


    fun isPlayable(name: String): Boolean {
        val lower = name.lowercase()
        if (!AUDIO_EXT.any { lower.endsWith(it) }) return false
        if (EXCLUDE_NAME_MARKERS.any { lower.contains(it) }) return false
        return true
    }

    /**
     * `My Show ep 01.mp3` -> `My Show — ep 01`. Only tidies the extension and
     * separators; never invents a title.
     */
    fun titleFromFile(name: String, fallback: String): String {
        val base = AUDIO_EXT.firstOrNull { name.lowercase().endsWith(it) }
            ?.let { name.dropLast(it.length) } ?: name
        return base.replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { fallback }
    }

    fun downloadUrl(identifier: String, fileName: String): String {
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "https://archive.org/download/$identifier/$encoded"
    }

    private fun formatFor(name: String): AudioFormat = when {
        name.lowercase().endsWith(".mp3") -> AudioFormat.MP3
        name.lowercase().endsWith(".m4a") -> AudioFormat.M4A_AAC
        name.lowercase().endsWith(".ogg") -> AudioFormat.OGG_VORBIS
        name.lowercase().endsWith(".flac") -> AudioFormat.FLAC
        name.lowercase().endsWith(".wav") -> AudioFormat.WAV
        else -> AudioFormat.UNKNOWN
    }

    /** Best-effort broadcast date from the item's declared year. */
    private fun broadcastDate(meta: JSONObject): Long {
        val year = meta.optString("year").toIntOrNull()
            ?: meta.optString("date").take(4).toIntOrNull()
            ?: return 0L
        return java.time.LocalDate.of(year.coerceIn(1920, 2030), 1, 1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    const val PROVIDER_KEY_PREFIX = "ia:"
}
