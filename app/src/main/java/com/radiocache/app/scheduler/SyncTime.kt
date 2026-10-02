package com.radiocache.app.scheduler

import java.time.Duration
import java.time.ZonedDateTime

/**
 * Daily-clock scheduling math — pure, unit-tested.
 *
 * The periodic worker runs every [intervalDays] days, anchored at the
 * user-chosen clock time: first run waits until the next HH:MM, then the
 * 24h×N period keeps that anchor.
 */
object SyncTime {

    /** Delay from [now] until the next [hour]:[minute]. Always positive. */
    fun delayToNext(hour: Int, minute: Int, now: ZonedDateTime): Duration {
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    fun format(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}
