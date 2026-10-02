package com.radiocache.app.engine.capture

import java.io.File

/**
 * Live Stream Capture Engine contract (Stage 6, optional fallback).
 *
 * Records an authorized live stream (AAC/HLS) to a local file. Fully
 * independent from the catch-up engine: callers pick an engine based on
 * [com.radiocache.app.data.model.DownloadStrategy], never mixing them.
 */
interface StreamCaptureEngine {
    /** Capture [streamUrl] into [outputFile] for at most [maxDurationMs]. */
    suspend fun capture(
        streamUrl: String,
        outputFile: File,
        maxDurationMs: Long,
        onProgress: (bytesWritten: Long) -> Unit = {},
    ): Result<File>
}

/** Stage-1 stub: fails fast so callers handle "capture unavailable" cleanly. */
class NoOpStreamCaptureEngine : StreamCaptureEngine {
    override suspend fun capture(
        streamUrl: String,
        outputFile: File,
        maxDurationMs: Long,
        onProgress: (Long) -> Unit,
    ): Result<File> =
        Result.failure(UnsupportedOperationException("Stream capture lands in Stage 6"))
}
