package com.radiocache.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings_prefs")

/**
 * User-tunable storage/sync settings. Persisted in DataStore so Stage 4's
 * WorkManager can read them without touching Room.
 */
class SettingsRepository(private val appContext: Context) {

    val keepDays: Flow<Int> = appContext.settingsStore.data.map { it[KEY_KEEP_DAYS] ?: DEFAULT_KEEP_DAYS }
    val wifiOnly: Flow<Boolean> = appContext.settingsStore.data.map { it[KEY_WIFI_ONLY] ?: true }
    /** Max recordings kept per show (newest first). */
    val keepCount: Flow<Int> = appContext.settingsStore.data.map { it[KEY_KEEP_COUNT] ?: DEFAULT_KEEP_COUNT }
    /** Master switch for the periodic auto-sync (manual sync always works). */
    val autoSync: Flow<Boolean> = appContext.settingsStore.data.map { it[KEY_AUTO_SYNC] ?: true }
    /** Auto-sync interval in days. */
    val syncIntervalDays: Flow<Int> = appContext.settingsStore.data.map { it[KEY_INTERVAL_DAYS] ?: DEFAULT_INTERVAL_DAYS }
    /** Daily sync clock time (used when interval is 1 day; otherwise start-of-window). */
    val syncHour: Flow<Int> = appContext.settingsStore.data.map { it[KEY_SYNC_HOUR] ?: DEFAULT_SYNC_HOUR }
    val syncMinute: Flow<Int> = appContext.settingsStore.data.map { it[KEY_SYNC_MINUTE] ?: DEFAULT_SYNC_MINUTE }

    suspend fun setKeepDays(days: Int) {
        appContext.settingsStore.edit { it[KEY_KEEP_DAYS] = days.coerceIn(1, 90) }
    }

    suspend fun setKeepCount(count: Int) {
        appContext.settingsStore.edit { it[KEY_KEEP_COUNT] = count.coerceIn(1, 20) }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        appContext.settingsStore.edit { it[KEY_AUTO_SYNC] = enabled }
    }

    suspend fun setSyncIntervalDays(days: Int) {
        appContext.settingsStore.edit { it[KEY_INTERVAL_DAYS] = days.coerceIn(1, 30) }
    }

    suspend fun setSyncTime(hour: Int, minute: Int) {
        appContext.settingsStore.edit {
            it[KEY_SYNC_HOUR] = hour.coerceIn(0, 23)
            it[KEY_SYNC_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        appContext.settingsStore.edit { it[KEY_WIFI_ONLY] = enabled }
    }

    companion object {
        const val DEFAULT_KEEP_DAYS = 7
        const val DEFAULT_KEEP_COUNT = 3
        const val DEFAULT_INTERVAL_DAYS = 1
        const val DEFAULT_SYNC_HOUR = 6
        const val DEFAULT_SYNC_MINUTE = 0
        private val KEY_KEEP_DAYS = intPreferencesKey("keep_days")
        private val KEY_KEEP_COUNT = intPreferencesKey("keep_count")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        private val KEY_INTERVAL_DAYS = intPreferencesKey("sync_interval_days")
        private val KEY_SYNC_HOUR = intPreferencesKey("sync_hour")
        private val KEY_SYNC_MINUTE = intPreferencesKey("sync_minute")
    }
}
