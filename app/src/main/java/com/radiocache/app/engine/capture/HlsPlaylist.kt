package com.radiocache.app.engine.capture

import java.net.URL

/**
 * Pure HLS playlist parsing — unit-tested, no I/O.
 *
 * Supports master playlists (variant selection by bandwidth) and media
 * playlists (sliding live windows tracked by segment URI, VOD via ENDLIST).
 */
object HlsPlaylist {

    data class Variant(val bandwidth: Long, val uri: String)

    data class Segment(val durationMs: Long, val uri: String)

    data class Media(
        val targetDurationSec: Long,
        val endList: Boolean,
        val segments: List<Segment>,
    )

    fun resolve(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URL(URL(baseUrl), relative).toString() }.getOrDefault(relative)
    }

    /** Highest-bandwidth variant, URIs resolved against the master URL. */
    fun pickVariant(masterBody: String, masterUrl: String): String? {
        val lines = masterBody.lineSequence().map { it.trim() }.toList()
        var best: Variant? = null
        var pendingBandwidth: Long? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(line)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val bw = pendingBandwidth
                    if (bw != null) {
                        if (best == null || bw > best.bandwidth) {
                            best = Variant(bw, resolve(masterUrl, line))
                        }
                    }
                    pendingBandwidth = null
                }
            }
        }
        return best?.uri
    }

    fun parseMedia(body: String, playlistUrl: String): Media {
        val lines = body.lineSequence().map { it.trim() }.toList()
        var target = 6L
        var endList = false
        var pendingMs: Long? = null
        val segs = mutableListOf<Segment>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION") ->
                    target = line.substringAfter(":").trim().toLongOrNull() ?: target
                line == "#EXT-X-ENDLIST" -> endList = true
                line.startsWith("#EXTINF") ->
                    pendingMs = line.substringAfter(":").substringBefore(",").trim()
                        .toDoubleOrNull()?.let { (it * 1000).toLong() }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val dur = pendingMs
                    if (dur != null) segs += Segment(dur, resolve(playlistUrl, line))
                    pendingMs = null
                }
            }
        }
        return Media(target, endList, segs)
    }
}
