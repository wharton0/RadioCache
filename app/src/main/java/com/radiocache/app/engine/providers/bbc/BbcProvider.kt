package com.radiocache.app.engine.providers.bbc

import com.radiocache.app.data.model.AudioFormat
import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
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
 * BBC provider — SECOND PRIORITY, generic across BBC stations/shows.
 *
 * NOT bound to Radio 1: everything keys off
 * `stationId + programme brand PID`, so Radio 2 works without touching
 * core logic:
 * - BBC Radio 1 → Breakfast: pid `b0080x5m`
 * - BBC Radio 2 → music programme: configured PID (see [BbcStations])
 *
 * Public metadata mechanism (verified Sep 2026, no key required):
 * - `https://www.bbc.co.uk/programmes/{pid}.json` → brand metadata,
 *   `ownership.service.id` (e.g. `bbc_radio_one`).
 * - `https://www.bbc.co.uk/programmes/{pid}/episodes/player.json` →
 *   recent episodes with pids + first_broadcast_date.
 * - RMS segments API gives now-playing metadata (no audio).
 *
 * HONEST LIMITATION (do not "fix" by bypassing DRM):
 * BBC Sounds on-demand audio is delivered via DRM/DASH for the official
 * app only. There is NO public plain-file catch-up URL. This provider
 * therefore returns metadata with `resolvedAudioUrl = null`
 * (catch-up unavailable), and the [DownloadPlanner] routes to the
 * live-capture fallback using the BBC's PUBLIC live HLS manifests below.
 * Per-episode availability text comes from the Sounds episode page.
 */
class BbcProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: AppLogger = AndroidLogger(),
) : CatchUpProvider, ProgrammeResolver, LiveStreamProvider {

    override val providerId: String = "bbc"

    override fun supports(show: Show): Boolean =
        BbcStations.configForShow(show) != null

    fun supportsStation(station: Station): Boolean =
        BbcStations.configForStation(station.id) != null

    override fun supports(station: Station): Boolean = supportsStation(station)

    override suspend fun fetchProgrammes(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        resolveRecent(show, limit)

    override suspend fun resolveRecent(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        withContext(Dispatchers.IO) {
            try {
                val cfg = BbcStations.configForShow(show)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("No BBC config for show ${show.id}")
                    )
                val episodes = fetchEpisodeList(cfg.brandPid, limit)
                Result.success(episodes.map { it.toProgramme(show) })
            } catch (t: Throwable) {
                logger.e(TAG, "BBC resolve failed for ${show.id}", t)
                Result.failure(t)
            }
        }

    override suspend fun resolveLiveStream(station: Station): Result<LiveStreamInfo> {
        val cfg = BbcStations.configForStation(station.id)
            ?: return Result.failure(IllegalArgumentException("No BBC config for ${station.id}"))
        // Public live HLS manifests published by the BBC for open playback
        // (documented by receiver community; BBC-controlled, no auth).
        // UK 320kbps variant preferred; worldwide 96k fallback noted.
        return Result.success(
            LiveStreamInfo(
                stationId = station.id,
                streamUrl = cfg.liveHlsUk,
                format = "HLS",
                bitrateKbps = 320,
            )
        )
    }

    // -- internals --

    private fun fetchEpisodeList(brandPid: String, limit: Int): List<BbcEpisodeMeta> {
        val url = "https://www.bbc.co.uk/programmes/$brandPid/episodes/player.json?limit=$limit"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.w(TAG, "BBC episodes ${resp.code} for $brandPid")
                return emptyList()
            }
            val body = resp.body?.string() ?: return emptyList()
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val episodes = root.optJSONArray("episodes") ?: return emptyList()
            val out = mutableListOf<BbcEpisodeMeta>()
            for (i in 0 until episodes.length()) {
                val e = episodes.optJSONObject(i) ?: continue
                val pid = e.optJSONObject("programme")?.optString("pid")
                    ?: e.optString("pid").takeIf { it.isNotBlank() } ?: continue
                val title = e.optJSONObject("programme")?.optString("title")
                    ?: e.optString("title", pid)
                val firstBroadcast = e.optString("first_broadcast_date")
                    .takeIf { it.isNotBlank() }
                    ?: e.optJSONObject("programme")?.optString("first_broadcast_date")
                out += BbcEpisodeMeta(pid, title, firstBroadcast)
            }
            return out
        }
    }

    companion object {
        private const val TAG = "BbcProvider"
    }
}

data class BbcStationConfig(
    val stationId: String,
    val serviceId: String,
    /** Sounds brand PID of the test/default show. */
    val brandPid: String,
    val brandName: String,
    val liveHlsUk: String,
    val liveHlsWorldwide: String,
)

/** Registry: add Radio 2's music programme PID here — no engine change. */
object BbcStations {
    private val stations = listOf(
        BbcStationConfig(
            stationId = "bbc-r1",
            serviceId = "bbc_radio_one",
            brandPid = "b0080x5m",
            brandName = "Radio 1 Breakfast with Greg James",
            liveHlsUk = "http://a.files.bbci.co.uk/media/live/manifesto/audio/simulcast/hls/uk/sbr_high/ak/bbc_radio_one.m3u8",
            liveHlsWorldwide = "http://as-hls-ww-live.akamaized.net/pool_904/live/ww/bbc_radio_one/bbc_radio_one.isml/bbc_radio_one-audio=96000.norewind.m3u8",
        ),
        BbcStationConfig(
            stationId = "bbc-r2",
            serviceId = "bbc_radio_two",
            // Placeholder: wire the chosen R2 music programme brand PID here
            // (e.g. Sounds brand page → programmes/{pid}.json → pid).
            brandPid = "b0080c9l",
            brandName = "BBC Radio 2 music programme (configure PID)",
            liveHlsUk = "http://a.files.bbci.co.uk/media/live/manifesto/audio/simulcast/hls/uk/sbr_high/ak/bbc_radio_two.m3u8",
            liveHlsWorldwide = "http://as-hls-ww-live.akamaized.net/pool_904/live/ww/bbc_radio_two/bbc_radio_two.isml/bbc_radio_two-audio=96000.norewind.m3u8",
        ),
    )

    fun configForShow(show: Show): BbcStationConfig? {
        show.providerProgrammeKey?.let { key ->
            stations.find { it.brandPid == key }?.let { return it }
        }
        return stations.find { it.stationId == show.stationId }
    }

    fun configForStation(stationId: String): BbcStationConfig? =
        stations.find { it.stationId == stationId }
}

private data class BbcEpisodeMeta(
    val pid: String,
    val title: String,
    val firstBroadcastIso: String?,
) {
    fun toProgramme(show: Show): BroadcastProgramme {
        val startMs = firstBroadcastIso?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: 0L
        val dayMs = if (startMs > 0) startMs - (startMs % 86_400_000L) else 0L
        return BroadcastProgramme(
            programmeId = pid,
            showId = show.id,
            stationId = show.stationId,
            title = title,
            description = "BBC Sounds episode $pid — on-demand audio is app/DRM only; use live capture.",
            broadcastDateEpochMs = dayMs,
            scheduledStartEpochMs = startMs,
            scheduledEndEpochMs = if (startMs > 0) startMs + show.durationMinutes * 60_000L else 0L,
            durationMs = null,
            sourceUrl = "https://www.bbc.co.uk/programmes/$pid",
            resolvedAudioUrl = null, // deliberate: no public plain-file URL
            audioFormat = AudioFormat.UNKNOWN,
            catchUpAvailable = false,
        )
    }
}
