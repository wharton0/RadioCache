package com.radiocache.app.engine.providers.archive

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Catch-up provider for public-domain audio hosted on the Internet Archive.
 *
 * An Internet Archive "item" is a container: every playable audio file inside
 * is treated as one programme, so a single item (e.g. a Golden-Age-of-Radio
 * compilation) can back a show for years without any further configuration.
 *
 * ## Why this provider exists in the F-Droid build
 *
 * The engine is source-agnostic; the point of this provider is that its
 * content is unambiguously redistributable. It REFUSES items whose licence is
 * unknown or non-redistributable (see [ArchiveMetadataParser]) — an item that
 * is CC BY-NC-ND, for example, is rejected rather than downloaded, because
 * shipping it would make the app non-free.
 *
 * Show identity is carried in `providerProgrammeKey` as `ia:<identifier>`.
 */
class InternetArchiveProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: AppLogger = AndroidLogger(),
) : CatchUpProvider {

    override val providerId: String = "archive"

    override fun supports(show: Show): Boolean =
        show.providerProgrammeKey?.startsWith(ArchiveMetadataParser.PROVIDER_KEY_PREFIX) == true

    override suspend fun fetchProgrammes(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        withContext(Dispatchers.IO) {
            val key = show.providerProgrammeKey
                ?: return@withContext Result.failure(
                    IllegalArgumentException("show ${show.id} has no archive.org identifier")
                )
            val identifier = key.removePrefix(ArchiveMetadataParser.PROVIDER_KEY_PREFIX)
            if (identifier.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("show ${show.id} has a blank identifier")
                )
            }
            try {
                val url = "https://archive.org/metadata/$identifier"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android) RadioCache/0.1")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("archive.org HTTP ${resp.code} for $identifier")
                        )
                    }
                    val body = resp.body?.string()
                        ?: return@withContext Result.failure(
                            IllegalStateException("empty metadata for $identifier")
                        )
                    val item = ArchiveMetadataParser.parse(
                        json = body,
                        show = show,
                        limit = limit,
                        allow = ALLOWED_EXTRA_LICENCES,
                    )
                    item.licenceRejection?.let {
                        // Deliberate, not a transient error: the item exists
                        // but we must not ship it.
                        logger.w(TAG, "refusing $identifier: $it")
                        return@withContext Result.failure(LicenceNotRedistributable(it))
                    }
                    if (item.programmes.isEmpty()) {
                        logger.w(TAG, "no playable audio in $identifier (licence='${item.licence}')")
                    }
                    logger.i(
                        TAG,
                        "archive.org $identifier: ${item.programmes.size} programmes " +
                            "(licence='${item.licence}')"
                    )
                    Result.success(item.programmes)
                }
            } catch (t: Throwable) {
                logger.e(TAG, "archive.org fetch failed for $identifier", t)
                Result.failure(t)
            }
        }

    companion object {
        private const val TAG = "ArchiveProvider"

        /**
         * Non-PD licences we deliberately accept. CC BY 3.0/4.0 permit
         * redistribution (including commercially) provided attribution is
         * shown, so they are free-software compatible. NoDerivs and
         * NonCommercial are intentionally absent.
         */
        private val ALLOWED_EXTRA_LICENCES = listOf("cc by 3.0", "cc by 4.0", "cc-by")
    }
}

/** Signals a licence we refuse to redistribute (not a transient failure). */
class LicenceNotRedistributable(message: String) : Exception(message)
