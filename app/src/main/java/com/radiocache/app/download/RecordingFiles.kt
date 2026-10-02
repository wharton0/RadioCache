package com.radiocache.app.download

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure local-file layout for cached audio — unit-tested, no Android APIs.
 *
 * Layout: `<filesDir>/recordings/{stationId}/{showId}/{yyyy-MM-dd}_{programmeId}.{ext}`
 * Extension is guessed from the resolved URL (Global serves .m4a); the
 * downloader never trusts URL suffixes for anything but the file name.
 */
object RecordingFiles {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val UNSAFE = Regex("[^a-zA-Z0-9._-]")

    fun sanitize(name: String): String {
        val clean = UNSAFE.replace(name, "_").trim('_')
        return clean.ifBlank { "episode" }.take(80)
    }

    fun extensionFor(urlHint: String?): String = when {
        urlHint == null -> "m4a"
        ".mp3" in urlHint.substringAfterLast('/') -> "mp3"
        ".m4a" in urlHint.substringAfterLast('/') ||
            ".mp4" in urlHint.substringAfterLast('/') -> "m4a"
        else -> "m4a"
    }

    fun destination(
        filesDir: File,
        stationId: String,
        showId: String,
        broadcastDateEpochMs: Long,
        programmeId: String,
        urlHint: String?,
        extOverride: String? = null,
    ): File {
        val day = if (broadcastDateEpochMs > 0) {
            DATE.format(Instant.ofEpochMilli(broadcastDateEpochMs))
        } else {
            DATE.format(Instant.now())
        }
        val dir = File(filesDir, "recordings/${sanitize(stationId)}/${sanitize(showId)}")
        val ext = extOverride ?: extensionFor(urlHint)
        return File(dir, "${day}_${sanitize(programmeId)}.$ext")
    }
}
