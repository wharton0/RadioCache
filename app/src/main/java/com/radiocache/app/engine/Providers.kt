package com.radiocache.app.engine

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.data.model.Station

/**
 * Top-level provider contracts.
 *
 * ```
 *                    RadioCache
 *                        │
 *              ┌─────────┴─────────┐
 *              │                   │
 *       Catch-up Engine      Live Capture Engine
 *              │                   │
 *      ┌───────┼────────┐          │
 *      │       │        │          │
 *   Capital   BBC    Absolute    Live Radio
 *      │       │        │          │
 *  GlobalPlayer BBC API Provider  AAC/HLS
 *      │       │        │          │
 *      └───────┴────────┴──────────┘
 *                    │
 *             Local Recording
 * ```
 */

/**
 * Resolves "what aired and when" for a show — schedule + programme metadata —
 * WITHOUT downloading audio. Implemented per provider family.
 */
interface ProgrammeResolver {
    val providerId: String
    fun supports(show: Show): Boolean
    suspend fun resolveRecent(show: Show, limit: Int = 7): Result<List<BroadcastProgramme>>
}

/**
 * Resolves a station's public live-stream details for the capture fallback.
 * Only exposes streams the station publishes openly (website/player links).
 */
interface LiveStreamProvider {
    val providerId: String
    fun supports(station: Station): Boolean
    suspend fun resolveLiveStream(station: Station): Result<LiveStreamInfo>
}

data class LiveStreamInfo(
    val stationId: String,
    val streamUrl: String,
    /** e.g. "HLS", "AAC", "MP3". */
    val format: String,
    val bitrateKbps: Int? = null,
)

/**
 * Tells the scheduler WHEN a show airs so capture can be timed.
 * Stage 4 uses this; Stage 1 ships a schedule-hint parser only.
 */
interface ScheduleResolver {
    /** Next scheduled start after [afterEpochMs], or null if unknown. */
    fun nextStartAfter(show: Show, afterEpochMs: Long): Long?
}
