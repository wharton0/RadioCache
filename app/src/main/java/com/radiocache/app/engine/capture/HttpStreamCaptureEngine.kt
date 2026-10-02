package com.radiocache.app.engine.capture

import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Stage 6 — Live Stream Capture Engine (real implementation).
 *
 * Records an authorized, openly-served live audio stream to a local file:
 * - Direct icecast/AAC/MP3 streams: timed byte copy until [maxDurationMs].
 * - HLS (.m3u8): master→variant resolution, then segment download loop
 *   (works for both sliding live windows and VOD/event playlists).
 *
 * Only streams the station publishes openly (canUse-free renditions,
 * public manifests). Login/DRM/token-gated URLs are never touched —
 * callers must resolve via [com.radiocache.app.engine.LiveStreamProvider].
 */
class HttpStreamCaptureEngine(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val logger: AppLogger = AndroidLogger(),
) : StreamCaptureEngine {

    override suspend fun capture(
        streamUrl: String,
        outputFile: File,
        maxDurationMs: Long,
        onProgress: (bytesWritten: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (maxDurationMs <= 0) return@withContext Result.failure(
            IllegalArgumentException("maxDurationMs must be positive")
        )
        try {
            outputFile.parentFile?.mkdirs()
            val tmp = File(outputFile.parentFile, outputFile.name + ".part")
            if (isHlsUrl(streamUrl)) captureHls(streamUrl, tmp, maxDurationMs, onProgress)
            else captureDirect(streamUrl, tmp, maxDurationMs, onProgress)
            if (tmp.length() == 0L) throw IOException("captured zero bytes from $streamUrl")
            if (!tmp.renameTo(outputFile)) throw IOException("rename failed: $streamUrl")
            logger.i(TAG, "captured ${outputFile.length()} bytes -> ${outputFile.name}")
            Result.success(outputFile)
        } catch (t: Throwable) {
            logger.e(TAG, "capture failed: $streamUrl", t)
            Result.failure(t)
        }
    }

    private fun isHlsUrl(url: String): Boolean =
        ".m3u8" in url.substringBefore("?").lowercase()

    private suspend fun captureDirect(
        url: String,
        tmp: File,
        maxDurationMs: Long,
        onProgress: (Long) -> Unit,
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
            .header("Icy-MetaData", "0")
            .build()
        val startMs = System.currentTimeMillis()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty body for $url")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var written = 0L
                    while (System.currentTimeMillis() - startMs < maxDurationMs) {
                        val n = input.read(buf)
                        if (n == -1) break // stream ended early
                        output.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                    output.flush()
                }
            }
        }
    }

    private suspend fun captureHls(
        masterUrl: String,
        tmp: File,
        maxDurationMs: Long,
        onProgress: (Long) -> Unit,
    ) {
        val master = getString(masterUrl)
        val variant = HlsPlaylist.pickVariant(master, masterUrl) ?: masterUrl
        val deadlineMs = System.currentTimeMillis() + maxDurationMs
        // Persisted next-in-line marker: a resumed/retried capture continues
        // after the last segment instead of re-downloading the live window
        // (which would duplicate audio at the head of the file).
        val seenFile = File(tmp.parentFile, tmp.name + ".seen")
        val seen = readSeen(seenFile)
        var written = 0L
        var capturedMs = 0L
        try {
            // Resume: keep the bytes already on disk and only append the
            // segments after the marker. Otherwise truncate and start over.
            val resume = seen.isNotEmpty() && tmp.exists() && tmp.length() > 0
            if (resume) capturedMs = readCapturedMs(tmp)
            java.io.FileOutputStream(tmp, resume).use { output ->
                while (System.currentTimeMillis() < deadlineMs && capturedMs < maxDurationMs) {
                    val media = HlsPlaylist.parseMedia(getString(variant), variant)
                    var fresh = 0
                    for (seg in media.segments) {
                        if (!seen.add(seg.uri)) continue
                        fresh++
                        downloadTo(seg.uri, output)
                        seen.add(seg.uri)
                        runCatching { seenFile.writeText(seen.joinToString("\n")) }
                        written = tmp.length()
                        capturedMs += seg.durationMs
                        onProgress(written)
                        if (capturedMs >= maxDurationMs) break
                    }
                    if (media.endList) break // VOD fully consumed (or trimmed above)
                    if (fresh == 0) {
                        // Live window hasn't advanced; wait half a segment.
                        delay((media.targetDurationSec * 1000 / 2).coerceIn(2000L, 10000L))
                    }
                }
                output.flush()
            }
        } finally {
            runCatching { seenFile.delete() }
        }
    }

    /** Restores the persisted segment list; ignores a corrupt/oversized file. */
    private fun readSeen(file: File): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (!file.exists()) return out
        runCatching {
            file.readLines().forEach { line ->
                val t = line.trim()
                if (t.isNotEmpty()) {
                    if (out.size >= MAX_SEEN) return@runCatching
                    out.add(t)
                }
            }
        }.onFailure { logger.w(TAG, "seen-list unreadable, restarting capture", it) }
        return out
    }

    private fun getString(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw IOException("empty body for $url")
        }
    }

    private fun downloadTo(url: String, output: java.io.OutputStream) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for segment $url")
            val body = resp.body ?: throw IOException("empty segment $url")
            body.byteStream().use { it.copyTo(output) }
        }
    }

    /** Rough elapsed-media time of a resumed TS capture, from file size at ~160kbps. */
    private fun readCapturedMs(file: File): Long =
        (file.length() * 8L / 160_000L * 1000L).coerceAtLeast(0L)

    companion object {
        private const val TAG = "StreamCapture"

        /** Upper bound on the persisted segment list (4h / 6s segments). */
        private const val MAX_SEEN = 4000
    }
}
