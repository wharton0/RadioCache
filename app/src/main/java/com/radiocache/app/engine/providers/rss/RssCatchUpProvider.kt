package com.radiocache.app.engine.providers.rss

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.engine.catchup.guessAudioFormat
import com.radiocache.app.engine.providers.common.LicencePolicy
import com.radiocache.app.engine.providers.common.TrustLevel
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Generic RSS catch-up adapter — ONE provider among many, not the core.
 *
 * Handles standard podcast/RSS feeds (BBC podcast feeds, station MP3 feeds,
 * …): resolves each `<item>` with an `<enclosure>` to a downloadable
 * programme. Items WITHOUT an enclosure are returned metadata-only
 * (catch-up unavailable) so the planner can fall back to live capture
 * instead of failing.
 *
 * Parsing uses JDK DOM (no new dependencies) and is pure JVM — fully
 * covered by [RssFeedParserTest].
 */
class RssCatchUpProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: AppLogger = AndroidLogger(),
) : CatchUpProvider {

    override val providerId: String = "rss"

    override fun supports(show: Show): Boolean =
        show.feedUrl?.isNotBlank() == true

    override suspend fun fetchProgrammes(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        withContext(Dispatchers.IO) {
            try {
                val feedUrl = show.feedUrl
                    ?: return@withContext Result.failure(IllegalArgumentException("show ${show.id} has no feedUrl"))
                val req = Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("RSS HTTP ${resp.code} for $feedUrl")
                        )
                    }
                    val xml = resp.body?.string()
                        ?: return@withContext Result.failure(IllegalStateException("empty RSS for $feedUrl"))
                    val programmes = RssFeedParser.parse(xml, show).take(limit)
                    // Diagnosability: raw <item> count vs parsed count tells
                    // fetch problems apart from parse problems in worker logs.
                    val rawItems = "<item".toRegex().findAll(xml).count()
                    if (programmes.isEmpty() && rawItems > 0) {
                        logger.w(TAG, "parsed 0/${rawItems} items for ${show.id} — parser rejected the feed")
                    }
                    Result.success(programmes)
                }
            } catch (t: Throwable) {
                logger.e(TAG, "RSS fetch failed for ${show.id}", t)
                Result.failure(t)
            }
        }

    companion object {
        private const val TAG = "RssProvider"
    }
}

/** Pure RSS → programmes mapping. No I/O, no Android APIs. */
object RssFeedParser {

    private val PUBDATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )

    fun parse(xml: String, show: Show): List<BroadcastProgramme> {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            // Best-effort XXE hardening: Android's XML stack does NOT
            // recognize all Xerces feature strings — a single hard
            // setFeature() throws and would empty the whole parse
            // (this exact bug shipped in v0.5.0 and broke BBC/Absolute
            // on-device while JVM unit tests stayed green).
            fun safeFeature(name: String, value: Boolean) =
                runCatching { factory.setFeature(name, value) }
            safeFeature("http://xml.org/sax/features/external-general-entities", false)
            safeFeature("http://xml.org/sax/features/external-parameter-entities", false)
            safeFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.isExpandEntityReferences = false }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return emptyList()

        val items = doc.getElementsByTagName("item")
        val out = mutableListOf<BroadcastProgramme>()
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            out += parseItem(el, show) ?: continue
        }
        return out.sortedByDescending { it.scheduledStartEpochMs }
    }

    private fun parseItem(el: Element, show: Show): BroadcastProgramme? {
        fun text(tag: String): String? {
            val nodes = el.getElementsByTagName(tag)
            if (nodes.length == 0) return null
            return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
        }
        val title = text("title") ?: return null
        val guid = text("guid")
        val link = text("link")
        val programmeId = guid ?: link ?: title
        val startMs = text("pubDate")?.let(::parseDate) ?: 0L
        val dayMs = if (startMs > 0) startMs - (startMs % 86_400_000L) else 0L

        var audioUrl: String? = null
        val enclosures = el.getElementsByTagName("enclosure")
        if (enclosures.length > 0) {
            audioUrl = (enclosures.item(0) as? Element)
                ?.getAttribute("url")?.trim()?.takeIf { it.isNotBlank() }
        }

        return BroadcastProgramme(
            programmeId = programmeId,
            showId = show.id,
            stationId = show.stationId,
            title = title,
            description = text("description") ?: text("itunes:summary"),
            broadcastDateEpochMs = dayMs,
            scheduledStartEpochMs = startMs,
            scheduledEndEpochMs = if (startMs > 0) startMs + show.durationMinutes * 60_000L else 0L,
            durationMs = text("itunes:duration")?.let(::parseDuration),
            sourceUrl = link,
            // Licence gate. A generic feed may point anywhere, so the enclosure
            // is only offered when the host is allow-listed AND nothing in the
            // feed's own licence fields forbids redistribution. Without this
            // check a user-supplied feed could pull down NC/ND audio and make
            // the app non-free.
            resolvedAudioUrl = audioUrl?.takeIf { isRedistributable(el, audioHost) },
            audioFormat = guessAudioFormat(audioUrl),
            catchUpAvailable = audioUrl != null && isRedistributable(el, audioHost),
        )
    }

    private fun audioHost(audioUrl: String?): String? =
        runCatching { android.net.Uri.parse(audioUrl ?: return null).host }.getOrNull()

    /**
     * True only when the enclosure's host is trusted and the feed carries no
     * non-commercial / no-derivatives statement.
     */
    private fun isRedistributable(item: Element, audioHost: String?): Boolean {
        val host = audioHost?.lowercase() ?: return false
        if (!LicencePolicy.TRUSTED_HOSTS.containsKey(host)) return false
        val text = buildString {
            for (tag in LICENCE_TAGS) {
                val n = item.getElementsByTagName(tag)
                if (n.length > 0) append(n.item(0)?.textContent.orEmpty()).append(' ')
            }
        }
        val shareAlike = item.getElementsByTagName("link")
            .let { n -> if (n.length > 0) n.item(0)?.textContent.orEmpty() else "" }
            .contains("creativecommons.org/licenses/by-sa", ignoreCase = true)
        return LicencePolicy.evaluate(host, text, shareAlikeReq = shareAlike) != TrustLevel.REFUSED
    }

    private companion object {
        val LICENCE_TAGS = listOf(
            "copyright", "license", "licence",
            "creativecommons:license", "itunes:copyright",
        )
    }

    fun parseDate(raw: String): Long {
        for (fmt in PUBDATE_FORMATS) {
            runCatching { ZonedDateTime.parse(raw.trim(), fmt).toInstant().toEpochMilli() }
                .getOrNull()?.let { return it }
        }
        return 0L
    }

    /** Accepts seconds ("3720") or H:MM:SS / MM:SS. Returns ms or null. */
    fun parseDuration(raw: String): Long? {
        val t = raw.trim()
        t.toLongOrNull()?.let { return it * 1000 }
        val parts = t.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return null
        var seconds = 0L
        for (p in parts) seconds = seconds * 60 + p
        return seconds * 1000
    }
}
