package com.radiocache.app.engine.providers.absolute

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station
import com.radiocache.app.engine.LiveStreamInfo
import com.radiocache.app.engine.LiveStreamProvider
import com.radiocache.app.engine.ProgrammeResolver
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger

/**
 * Absolute Radio provider — STAGE-2 TEST SOURCE, independent file.
 *
 * Research status (Sep 2026, honest):
 * - Owner: Bauer (Planet Radio). Official listening via hellorayo.co.uk /
 *   Planet Radio app / RadioPlayer. Bauer moved third-party streams behind
 *   direct access in 2022; aggregator AAC/MP3 URLs found online are stale or
 *   unofficial — DO NOT hardcode them.
 * - No public plain-file catch-up feed found (unlike Global's __NEXT_DATA__).
 *   Schedule + show metadata are readable on the station site, but episode
 *   audio requires the official player.
 * - Therefore: catch-up = metadata-only (unavailable) until a public feed
 *   is confirmed; live = canonical listening page, direct manifest resolved
 *   at capture time from the official player.
 *
 * This validates the architecture point: music radio ≠ podcast, and a
 * station with no public catch-up must still flow cleanly into the
 * live-capture fallback instead of breaking the app.
 */
class AbsoluteRadioProvider(
    private val logger: AppLogger = AndroidLogger(),
) : CatchUpProvider, ProgrammeResolver, LiveStreamProvider {

    override val providerId: String = "absolute"

    override fun supports(show: Show): Boolean =
        show.stationId == "absolute" || show.stationId.startsWith("absolute-")

    override fun supports(station: Station): Boolean =
        station.id == "absolute" || station.id.startsWith("absolute-")

    override suspend fun fetchProgrammes(
        show: Show,
        limit: Int,
    ): Result<List<BroadcastProgramme>> = resolveRecent(show, limit)

    override suspend fun resolveRecent(show: Show, limit: Int): Result<List<BroadcastProgramme>> {
        logger.i(TAG, "Absolute catch-up has no public file feed; returning unavailable for ${show.id}")
        return Result.success(emptyList())
    }

    override suspend fun resolveLiveStream(station: Station): Result<LiveStreamInfo> =
        Result.success(
            LiveStreamInfo(
                stationId = station.id,
                streamUrl = "https://www.hellorayo.co.uk/absolute-radio/",
                format = "WEB_PLAYER",
            )
        )

    companion object {
        private const val TAG = "AbsoluteRadio"
    }
}
