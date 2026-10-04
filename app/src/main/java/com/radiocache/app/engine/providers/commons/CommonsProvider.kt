package com.radiocache.app.engine.providers.commons

import com.radiocache.app.data.model.BroadcastProgramme
import com.radiocache.app.data.model.Show
import com.radiocache.app.engine.catchup.CatchUpProvider
import com.radiocache.app.engine.providers.common.LicencePolicy
import com.radiocache.app.engine.providers.common.TrustLevel
import com.radiocache.app.util.AndroidLogger
import com.radiocache.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Catch-up provider for freely-licensed audio on Wikimedia Commons.
 *
 * This is the app's primary source. Commons is used in preference to
 * archive.org because **licence metadata is mandatory there** — the upload
 * workflow rejects a file that lacks one — so [LicencePolicy] can gate on it
 * with confidence. Every candidate is checked by [LicencePolicy.evaluate]
 * before it is offered; NC and NoDerivs files are dropped, not downloaded.
 *
 * Show identity is `commons:<query>`, where the query is either a category
 * name or a search phrase (see [CommonsMetadataParser.categoryQuery] /
 * [searchQuery]).
 */
class CommonsProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: AppLogger = AndroidLogger(),
    /** Set true when the project ships under a copyleft licence requiring alike. */
    private val shareAlikeRequired: Boolean = true,
) : CatchUpProvider {

    override val providerId: String = "commons"

    override fun supports(show: Show): Boolean =
        show.providerProgrammeKey?.startsWith(KEY_PREFIX) == true

    override suspend fun fetchProgrammes(show: Show, limit: Int): Result<List<BroadcastProgramme>> =
        withContext(Dispatchers.IO) {
            val key = show.providerProgrammeKey
                ?: return@withContext Result.failure(
                    IllegalArgumentException("show ${show.id} has no Commons query")
                )
            val query = key.removePrefix(KEY_PREFIX).trim()
            if (query.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("show ${show.id} has a blank Commons query")
                )
            }
            try {
                val body = fetchListing(query)
                val programmes = CommonsMetadataParser.parse(
                    json = body,
                    show = show,
                    limit = limit,
                    shareAlikeRequired = shareAlikeRequired,
                )
                if (programmes.isEmpty()) {
                    logger.w(TAG, "no redistributable audio for '$query' (nc/nd/unknown filtered out)")
                } else {
                    logger.i(TAG, "commons '$query': ${programmes.size} programmes")
                }
                Result.success(programmes)
            } catch (t: Throwable) {
                logger.e(TAG, "commons fetch failed for '$query'", t)
                Result.failure(t)
            }
        }

    /**
     * Tries the query as a category first, then as a full-text search. Commons
     * categories are inconsistent (many audio categories do not exist), so
     * falling back keeps a show alive rather than failing the sync.
     */
    private fun fetchListing(query: String): String {
        val byCategory = get(CommonsMetadataParser.categoryQuery(query))
        if (hasAudio(byCategory)) return byCategory
        val bySearch = get(CommonsMetadataParser.searchQuery(query))
        return if (hasAudio(bySearch)) bySearch else byCategory
    }

    private fun hasAudio(json: String): Boolean =
        json.contains("\"mime\":\"audio") || json.contains("\"mime\": \"audio")

    private fun get(query: String): String {
        val req = Request.Builder()
            .url("https://commons.wikimedia.org/w/api.php?$query")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Commons HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw IllegalStateException("empty Commons body")
        }
    }

    companion object {
        private const val TAG = "CommonsProvider"
        const val KEY_PREFIX = "commons:"

        /** Wikimedia's policy asks for a descriptive UA with contact info. */
        private const val USER_AGENT =
            "RadioCache/0.9 (Android; offline public-domain radio cache)"
    }
}
