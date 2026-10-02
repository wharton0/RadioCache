package com.radiocache.app.engine.providers.globalplayer

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.engine.LiveStreamInfo
import com.radiocache.app.engine.LiveStreamProvider
import com.radiocache.app.engine.ProgrammeResolver
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant

/**
 * Global Player provider — FIRST PRIORITY, generic across Global stations.
 *
 * Covers via configuration (no code duplication):
 * - Capital London → Capital Breakfast (show id 46w7Auo) ← core test source
 * - Heart (any station) → Heart Breakfast (resolved by title if id unknown)
 * - Future Global stations by adding a [GlobalStationConfig].
 *
 * Data chain:
 *   Station → Show → broadcast date → Catch-up Episode → Audio Source
 *
 * Legal/public mechanism (verified Sep 2026, re-verified after page redesign):
 * - Station page `.../catchup/{brand}/{station}/` embeds Next.js
 *   `__NEXT_DATA__` JSON with `pageProps.catchupInfo[]` (show id + title).
 * - Show page embeds `pageProps.catchupShow.blocks[]` → `{type: Listing,
 *   items[]}` with per-episode id, day label, `content.published`,
 *   `content.durationSeconds` (e.g. 4h breakfast = 14400s).
 * - Episode audio: public BFF JSON
 *   `https://bff-web-guacamole.musicradio.com/playables/{episodeId}`
 *   (same endpoint the web player itself uses — no auth); pick the
 *   rendition with `canUse == "true"` (free GlobalAdSupported .m4a,
 *   7-day window). Premium/license renditions are skipped, never bypassed.
 * - Live: live page `station.id` → same playables endpoint → free rendition.
 * - Audio URLs are resolved DYNAMICALLY per episode — never hardcoded.
 * - If the episode list is empty/absent → catch-up unavailable →
 *   [com.radiocache.app.engine.download.DownloadPlanner] falls back to live.
 */
class GlobalPlayerProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: AppLogger = AndroidLogger(),
) : CatchUpProvider, ProgrammeResolver, LiveStreamProvider {

    override val providerId: String = "global"

    // -- CatchUpProvider / ProgrammeResolver --

    override fun supports(show: Show): Boolean =
        GlobalStations.configForShow(show) != null || show.feedUrl == null && show.stationId in GlobalStations.allStationIds()

    override suspend fun fetchProgrammes(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        resolveRecent(show, limit)

    override suspend fun resolveRecent(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        withContext(Dispatchers.IO) {
            try {
                val cfg = GlobalStations.configForShow(show)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("No Global config for show ${show.id}")
                    )
                val showId = cfg.showId ?: discoverShowId(cfg, show.name)
                    ?: return@withContext Result.success(emptyList()) // honest: no listing
                val episodes = fetchEpisodes(cfg.brand, cfg.station, showId, show.name)
                Result.success(episodes.take(limit).map { ep ->
                    ep.toProgramme(show)
                })
            } catch (t: Throwable) {
                logger.e(TAG, "Global resolve failed for ${show.id}", t)
                Result.failure(t)
            }
        }

    // -- LiveStreamProvider --

    override fun supports(station: com.radiocache.app.data.model.Station): Boolean =
        GlobalStations.configForStation(station.id) != null

    override suspend fun resolveLiveStream(
        station: com.radiocache.app.data.model.Station,
    ): Result<LiveStreamInfo> =
        withContext(Dispatchers.IO) {
            val cfg = GlobalStations.configForStation(station.id)
                ?: return@withContext Result.failure(IllegalArgumentException("No Global config for ${station.id}"))
            // Same public path as catch-up: live page -> station content id
            // -> playables JSON -> free rendition. Resolved dynamically.
            val url = runCatching { resolveLiveUrl(cfg) }.getOrNull()
            if (url != null) {
                Result.success(LiveStreamInfo(station.id, url, "AAC", null))
            } else {
                Result.success(
                    LiveStreamInfo(
                        stationId = station.id,
                        streamUrl = "https://www.globalplayer.com/live/${cfg.brand}/${cfg.station}/",
                        format = "WEB_PLAYER",
                    )
                )
            }
        }

    private fun resolveLiveUrl(cfg: GlobalStationConfig): String? {
        val props = fetchPageProps("https://www.globalplayer.com/live/${cfg.brand}/${cfg.station}/")
            ?: return null
        val stationId = props.optJSONObject("station")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: return null
        return resolvePlayableUrl(stationId)
    }

    // -- internals (public-page JSON parsing, no auth bypass) --

    private fun discoverShowId(cfg: GlobalStationConfig, showName: String): String? {
        val props = fetchPageProps("https://www.globalplayer.com/catchup/${cfg.brand}/${cfg.station}/")
            ?: return null
        val list = props.optJSONArray("catchupInfo") ?: return null
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val title = item.optString("title")
            if (title.contains(showName, ignoreCase = true)) return item.optString("id").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun fetchEpisodes(
        brand: String,
        station: String,
        showId: String,
        showName: String,
    ): List<GlobalEpisode> {
        val props = fetchPageProps("https://www.globalplayer.com/catchup/$brand/$station/$showId/")
            ?: return emptyList()
        val items = listingItems(props) ?: legacyEpisodes(props)
        if (items == null) {
            logger.w(TAG, "no episode listing found for show $showId")
            return emptyList()
        }
        val out = mutableListOf<GlobalEpisode>()
        for (i in 0 until items.length()) {
            val e = items.optJSONObject(i) ?: continue
            val id = e.optString("id")
            if (id.isBlank()) continue
            val content = e.optJSONObject("content")
            val dayLabel = e.optString("title")
            val startIso = content?.optString("published")?.takeIf { it.isNotBlank() }
            val durationMs = if (content != null && content.has("durationSeconds")) {
                content.optLong("durationSeconds", 0L).takeIf { it > 0 }?.times(1000L)
            } else null
            val episodePage = "https://www.globalplayer.com/catchup/$brand/$station/episodes/$id/"
            out += GlobalEpisode(
                id = id,
                // "Capital Breakfast — Tuesday, 29 September": useful in player UI.
                title = if (dayLabel.isNotBlank()) "$showName — $dayLabel" else showName,
                description = e.optString("description").takeIf { it.isNotBlank() },
                streamUrl = resolvePlayableUrl(id),
                startIso = startIso,
                durationMs = durationMs,
                pageUrl = episodePage,
            )
        }
        return out
    }

    /**
     * New page layout (verified Sep 2026):
     * `catchupShow.blocks[]` → `{type: Listing, items[]}`.
     */
    private fun listingItems(props: JSONObject): org.json.JSONArray? {
        val show = props.optJSONObject("catchupShow") ?: return null
        val blocks = show.optJSONArray("blocks") ?: return null
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "Listing") {
                return b.optJSONArray("items")
            }
        }
        return null
    }

    /** Old layout fallback (`catchupInfo.episodes[]`), kept defensively. */
    private fun legacyEpisodes(props: JSONObject): org.json.JSONArray? =
        props.optJSONObject("catchupInfo")?.optJSONArray("episodes")

    /**
     * Resolves an episode's playable audio URL via Global's public BFF
     * endpoint (the same JSON the website player fetches — no auth, no
     * signing). Picks the `canUse == "true"` rendition (free,
     * GlobalAdSupported, direct .m4a). Any other rendition (premium/license
     * gated) is skipped, never bypassed.
     */
    private fun resolvePlayableUrl(episodeId: String): String? {
        val req = Request.Builder()
            .url("https://bff-web-guacamole.musicradio.com/playables/$episodeId")
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.w(TAG, "playables ${resp.code} for episode $episodeId")
                return null
            }
            val body = resp.body?.string() ?: return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val playback = json.optJSONArray("playback") ?: return null
            for (i in 0 until playback.length()) {
                val p = playback.optJSONObject(i) ?: continue
                if (p.optString("canUse") == "true") {
                    return p.optString("url").takeIf { it.isNotBlank() }
                }
            }
            logger.i(TAG, "no free rendition for episode $episodeId")
            return null
        }
    }

    private fun fetchPageProps(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.w(TAG, "Global page ${resp.code} for $url")
                return null
            }
            val html = resp.body?.string() ?: return null
            val idx = html.indexOf("__NEXT_DATA__")
            if (idx == -1) return null
            val start = html.indexOf('{', idx)
            val end = html.indexOf("</script>", start)
            if (start == -1 || end == -1) return null
            val json = JSONObject(html.substring(start, end))
            return json.optJSONObject("props")?.optJSONObject("pageProps")
        }
    }

    companion object {
        private const val TAG = "GlobalPlayer"
    }
}

data class GlobalStationConfig(
    val stationId: String,
    val brand: String,
    val station: String,
    /** Catch-up show id from the station page; null = discover by title. */
    val showId: String?,
    val showName: String,
)

/** Station registry — Heart/others added here, never in engine logic. */
object GlobalStations {
    private val configs = listOf(
        // Core test source: Capital London → Capital Breakfast.
        GlobalStationConfig("capital", "capital", "london", "46w7Auo", "Capital Breakfast"),
        // Heart: show id resolved by title match at runtime.
        GlobalStationConfig("heart", "heart", "london", null, "Heart Breakfast"),
        // Classic FM → Breakfast with Dan Walker (verified free catch-up).
        GlobalStationConfig("classicfm", "classicfm", "uk", "46wcDcx", "Classic FM Breakfast"),
        // Smooth → Kate Garraway weekdays (verified free catch-up).
        GlobalStationConfig("smooth", "smooth", "uk", "b8F8VgS", "Kate Garraway"),
    )

    fun configForShow(show: Show): GlobalStationConfig? {
        show.providerProgrammeKey?.let { key ->
            configs.find { it.showId == key }?.let { return it }
        }
        return configs.find { it.stationId == show.stationId }
    }

    fun configForStation(stationId: String): GlobalStationConfig? =
        configs.find { it.stationId == stationId }

    fun allStationIds(): Set<String> = configs.map { it.stationId }.toSet()
}

private data class GlobalEpisode(
    val id: String,
    val title: String,
    val description: String?,
    val streamUrl: String?,
    val startIso: String?,
    val durationMs: Long?,
    val pageUrl: String,
) {
    fun toProgramme(show: Show): BroadcastProgramme {
        val startMs = startIso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        val dayMs = if (startMs > 0) startMs - (startMs % 86_400_000L) else 0L
        // Prefer the platform-published duration; fall back to schedule.
        val endMs = when {
            startMs <= 0 -> 0L
            durationMs != null && durationMs > 0 -> startMs + durationMs
            else -> startMs + show.durationMinutes * 60_000L
        }
        return BroadcastProgramme(
            programmeId = id,
            showId = show.id,
            stationId = show.stationId,
            title = title,
            description = description,
            broadcastDateEpochMs = dayMs,
            scheduledStartEpochMs = startMs,
            scheduledEndEpochMs = endMs,
            durationMs = durationMs,
            sourceUrl = pageUrl,
            resolvedAudioUrl = streamUrl,
            audioFormat = if (streamUrl != null) AudioFormat.M4A_AAC else AudioFormat.UNKNOWN,
            catchUpAvailable = streamUrl != null,
        )
    }
}
