package com.radiocache.app.engine.providers.librivox

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.engine.providers.common.LicencePolicy
import com.radiocache.app.engine.providers.common.TrustLevel
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader

/**
 * Catch-up provider for LibriVox — volunteer-read recordings of public-domain
 * books, dedicated to the public domain by the project (see
 * https://librivox.org/pages/public-domain/).
 *
 * ## Licence basis
 *
 * LibriVox items carry **no per-file licence field**, so [LicencePolicy]
 * classifies them as [TrustLevel.ATTESTED_HOST]: acceptable because the host
 * itself is reviewed and its commitment is documented, not because we verified
 * each file. Any explicit restriction on a record still vetoes it.
 *
 * The catalogue is exposed as RSS (stable, no API key) and each item carries
 * MP3 enclosures.
 */
class LibrivoxProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: AppLogger = AndroidLogger(),
) : CatchUpProvider {

    override val providerId: String = "librivox"

    override fun supports(show: Show): Boolean =
        show.providerProgrammeKey?.startsWith(KEY_PREFIX) == true

    override suspend fun fetchProgrammes(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        withContext(Dispatchers.IO) {
            val id = show.providerProgrammeKey?.removePrefix(KEY_PREFIX).orEmpty()
                .ifBlank { DEFAULT_BOOK_ID }
            val url = "$RSS_BASE/$id"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("LibriVox HTTP ${resp.code} for $url")
                        )
                    }
                    val xml = resp.body?.string()
                        ?: return@withContext Result.failure(
                            IllegalStateException("empty LibriVox feed for $url")
                        )
                    val programmes = LibrivoxFeedParser.parse(xml, show).take(limit)
                    if (programmes.isEmpty()) {
                        logger.w(TAG, "no playable audio in LibriVox book $id")
                    } else {
                        logger.i(TAG, "librivox book $id: ${programmes.size} sections")
                    }
                    Result.success(programmes)
                }
            } catch (t: Throwable) {
                logger.e(TAG, "librivox fetch failed for book $id", t)
                Result.failure(t)
            }
        }

    companion object {
        private const val TAG = "LibrivoxProvider"
        const val KEY_PREFIX = "librivox:"
        private const val RSS_BASE = "https://librivox.org/rss"
        private const val USER_AGENT = "RadioCache/0.9 (Android; offline public-domain radio cache)"

        /** A known-good public-domain collection ("Poems by William Blake"). */
        const val DEFAULT_BOOK_ID = "2"
    }
}

/**
 * Pure RSS → programmes mapping for a LibriVox book feed. Sections are audio
 * files; the enclosure carries the MP3 URL.
 */
object LibrivoxFeedParser {

    private val PUBDATE = listOf(
        java.time.format.DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH),
        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )

    fun parse(xml: String, show: Show): List<BroadcastProgramme> {
        val doc: org.w3c.dom.Document = runCatching {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            // Android's XML stack throws on some setFeature strings; each call
            // is isolated so one failure can't empty the whole parse.
            fun safe(name: String, v: Boolean) = runCatching { f.setFeature(name, v) }
            safe("http://xml.org/sax/features/external-general-entities", false)
            safe("http://xml.org/sax/features/external-parameter-entities", false)
            safe("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { f.isXIncludeAware = false }
            runCatching { f.isExpandEntityReferences = false }
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        }.getOrNull() ?: return emptyList()

        val items = doc.getElementsByTagName("item")
        val out = mutableListOf<BroadcastProgramme>()
        for (i in 0 until items.length) {
            val el = items.item(i) as? org.w3c.dom.Element ?: continue
            out += parseItem(el, show) ?: continue
        }
        return out.sortedByDescending { it.scheduledStartEpochMs }
    }

    private fun parseItem(el: org.w3c.dom.Element, show: Show): BroadcastProgramme? {
        fun text(tag: String): String? {
            val n = el.getElementsByTagName(tag)
            if (n.length == 0) return null
            return n.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
        }
        val title = text("title") ?: return null
        val audio = (el.getElementsByTagName("enclosure").item(0) as? org.w3c.dom.Element)
            ?.getAttribute("url")?.trim()?.takeIf { it.startsWith("http") } ?: return null

        // LibriVox's RSS has no enclosure for some sections; only accept audio.
        if (!audio.endsWith(".mp3", ignoreCase = true) && !audio.endsWith(".ogg", ignoreCase = true)) {
            return null
        }
        val startMs = text("pubDate")?.let(::parseDate) ?: 0L
        return BroadcastProgramme(
            programmeId = text("guid") ?: audio,
            showId = show.id,
            stationId = show.stationId,
            title = title,
            description = text("itunes:summary") ?: text("description"),
            broadcastDateEpochMs = if (startMs > 0) startMs - (startMs % 86_400_000L) else 0L,
            scheduledStartEpochMs = startMs,
            scheduledEndEpochMs = if (startMs > 0) startMs + show.durationMinutes * 60_000L else 0L,
            durationMs = text("itunes:duration")?.let(::parseDuration),
            sourceUrl = text("link"),
            resolvedAudioUrl = audio,
            audioFormat = if (audio.endsWith(".ogg", true)) AudioFormat.OGG_VORBIS else AudioFormat.MP3,
            catchUpAvailable = true,
        )
    }

    private fun parseDate(raw: String): Long {
        for (fmt in PUBDATE) {
            runCatching {
                java.time.ZonedDateTime.parse(raw.trim(), fmt).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun parseDuration(raw: String): Long? {
        val t = raw.trim()
        t.toLongOrNull()?.let { return it * 1000 }
        val parts = t.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return null
        var sec = 0L
        for (p in parts) sec = sec * 60 + p
        return sec * 1000
    }
}
