package com.radiocache.app.data.repository

import java.util.concurrent.TimeUnit

/**
 * Pure retention math — unit-tested, no Android dependencies.
 *
 * Rule: keep recordings whose broadcast day is within the last [keepDays]
 * days. Day boundaries use UTC midnights so cleanup is timezone-stable.
 */
object RetentionPolicy {

    const val DAY_MS: Long = 86_400_000L

    /** Rows with broadcastDate <= cutoff are deleted (0/negative = keep). */
    fun cutoffEpochMs(nowEpochMs: Long, keepDays: Int): Long {
        val todayMidnightUtc = nowEpochMs - (nowEpochMs % DAY_MS)
        return todayMidnightUtc - TimeUnit.DAYS.toMillis(keepDays.toLong())
    }

    fun isExpired(broadcastDateEpochMs: Long, nowEpochMs: Long, keepDays: Int): Boolean {
        if (broadcastDateEpochMs <= 0) return false // undated imports are kept
        return broadcastDateEpochMs <= cutoffEpochMs(nowEpochMs, keepDays)
    }

    /**
     * IDs beyond the newest [keepCount] in a list already sorted newest-first.
     * Pure — unit-tested.
     */
    fun idsBeyondLatest(sortedIdsNewestFirst: List<String>, keepCount: Int): List<String> {
        if (keepCount < 0) return emptyList()
        if (sortedIdsNewestFirst.size <= keepCount) return emptyList()
        return sortedIdsNewestFirst.drop(keepCount)
    }
}
