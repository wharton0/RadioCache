package com.radiocache.app.download

import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streaming audio downloader shared by the sync worker (Stage 4) and any
 * future manual-download path. Retries transient I/O failures with
 * exponential backoff and resumes partial `.part` files via HTTP Range
 * when the server supports it (206); callers report progress to Room.
 */
class AudioDownloader(
    private val http: OkHttpClient,
    private val logger: AppLogger = AndroidLogger(),
    private val maxAttempts: Int = 3,
) {

    /**
     * Downloads [url] to [destFile], creating parent dirs. Progress is
     * reported as (bytesWritten, totalBytes or -1) with bytesWritten
     * counting resumed bytes. Failed attempts keep the `.part` file so
     * the next attempt (or next sync) continues where it stopped.
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: suspend (written: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return Result.success(downloadOnce(url, destFile, onProgress))
            } catch (t: IOException) {
                lastError = t
                logger.w(TAG, "download attempt ${attempt + 1}/$maxAttempts failed: $url", t)
                runCatching { if (destFile.exists()) destFile.delete() }
                if (attempt < maxAttempts - 1) delay(BACKOFF_MS shl attempt)
            } catch (t: Throwable) {
                // Non-transient (bad URL, OOM, …): don't retry.
                runCatching { if (destFile.exists()) destFile.delete() }
                return Result.failure(t)
            }
        }
        return Result.failure(lastError ?: IOException("download failed: $url"))
    }

    private suspend fun downloadOnce(
        url: String,
        destFile: File,
        onProgress: suspend (Long, Long) -> Unit,
    ): File {
        destFile.parentFile?.mkdirs()
        // Partial data from a previous attempt: resume instead of restarting.
        val tmp = File(destFile.parentFile, destFile.name + ".part")
        val resumeFrom = if (tmp.exists()) tmp.length() else 0L
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
        if (resumeFrom > 0) req.header("Range", "bytes=$resumeFrom-")
        http.newCall(req.build()).execute().use { resp ->
            // 206 = server honored the Range; 200 = it ignored it (restart
            // from zero). Anything else is an error.
            val append = resumeFrom > 0 && resp.code == 206
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty body for $url")
            val remaining = body.contentLength() // -1 when unknown
            val total = when {
                append && remaining >= 0 -> resumeFrom + remaining
                append -> parseContentRangeTotal(resp.header("Content-Range")) ?: -1L
                else -> remaining
            }
            if (append && total in 0 until resumeFrom) {
                // Server sent less than we already have — corrupt resume.
                throw IOException("invalid resume range for $url")
            }
            body.byteStream().use { input ->
                val output = FileOutputStream(tmp, append) // append on resume
                output.use {
                    val buf = ByteArray(64 * 1024)
                    var written = resumeFrom
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("zero bytes for $url")
            // Complete file may already exist (rare retry race): replace it.
            if (destFile.exists()) runCatching { destFile.delete() }
            if (!tmp.renameTo(destFile)) throw IOException("rename failed for $url")
            logger.i(TAG, "downloaded ${destFile.length()} bytes -> ${destFile.name}")
            return destFile
        }
    }

    companion object {
        private const val TAG = "AudioDownloader"
        private const val BACKOFF_MS = 2000L

        /** "bytes 100-999/1234" → 1234; null when absent/unparseable. */
        internal fun parseContentRangeTotal(header: String?): Long? {
            val h = header ?: return null
            val slash = h.indexOf('/')
            if (slash < 0 || slash == h.lastIndex) return null
            return h.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0 }
        }
    }
}
