package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.NetworkTransportType
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.plexoDataStore: DataStore<Preferences> by preferencesDataStore(name = "plexo_settings")

data class PlexoSettings(
    val defaultFolderUri: String = "",
    val defaultFolderLabel: String = "Plexo Downloads",
    val maxSimultaneousDownloads: Int = 3,
    val defaultChunkSizeBytes: Long = 4L * 1024L * 1024L,
    val ethernetDefaultStreams: Int = 16,
    val wifiDefaultStreams: Int = 8,
    val cellularDefaultStreams: Int = 4,
    val globalSpeedLimitBytesPerSec: Long = 0L, // 0 = unlimited
    val slowModeEnabled: Boolean = false,
    val slowModeLimitBytesPerSec: Long = 512L * 1024L, // 512 KB/s default slow mode
    val wifiOnlyGuardrail: Boolean = true,
    val allowVpnBonding: Boolean = false,
    val autoSortByType: Boolean = true,
    val clipboardWatcherEnabled: Boolean = true,
    val speedUnitMbps: Boolean = false,
    val schedulerEnabled: Boolean = false,
    val scheduleStartHour: Int = 1,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 6,
    val scheduleEndMinute: Int = 0,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false
) {
    fun defaultStreamsFor(transportType: NetworkTransportType): Int = when (transportType) {
        NetworkTransportType.ETHERNET -> ethernetDefaultStreams
        NetworkTransportType.WIFI -> wifiDefaultStreams
        NetworkTransportType.CELLULAR -> cellularDefaultStreams
        NetworkTransportType.VPN -> 6
        NetworkTransportType.OTHER -> 4
    }

    fun effectiveSpeedLimitBytesPerSec(): Long {
        return when {
            slowModeEnabled && globalSpeedLimitBytesPerSec > 0L ->
                minOf(slowModeLimitBytesPerSec, globalSpeedLimitBytesPerSec)
            slowModeEnabled -> slowModeLimitBytesPerSec
            globalSpeedLimitBytesPerSec > 0L -> globalSpeedLimitBytesPerSec
            else -> 0L
        }
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val DEFAULT_FOLDER_URI = stringPreferencesKey("default_folder_uri")
        val DEFAULT_FOLDER_LABEL = stringPreferencesKey("default_folder_label")
        val MAX_SIMULTANEOUS = intPreferencesKey("max_simultaneous")
        val DEFAULT_CHUNK_SIZE = longPreferencesKey("default_chunk_size")
        val ETHERNET_STREAMS = intPreferencesKey("ethernet_streams")
        val WIFI_STREAMS = intPreferencesKey("wifi_streams")
        val CELLULAR_STREAMS = intPreferencesKey("cellular_streams")
        val GLOBAL_SPEED_LIMIT = longPreferencesKey("global_speed_limit")
        val SLOW_MODE_ENABLED = booleanPreferencesKey("slow_mode_enabled")
        val SLOW_MODE_LIMIT = longPreferencesKey("slow_mode_limit")
        val WIFI_ONLY_GUARDRAIL = booleanPreferencesKey("wifi_only_guardrail")
        val ALLOW_VPN_BONDING = booleanPreferencesKey("allow_vpn_bonding")
        val AUTO_SORT_BY_TYPE = booleanPreferencesKey("auto_sort_by_type")
        val CLIPBOARD_WATCHER = booleanPreferencesKey("clipboard_watcher")
        val SPEED_UNIT_MBPS = booleanPreferencesKey("speed_unit_mbps")
        val SCHEDULER_ENABLED = booleanPreferencesKey("scheduler_enabled")
        val SCHEDULE_START_HOUR = intPreferencesKey("schedule_start_hour")
        val SCHEDULE_START_MINUTE = intPreferencesKey("schedule_start_minute")
        val SCHEDULE_END_HOUR = intPreferencesKey("schedule_end_hour")
        val SCHEDULE_END_MINUTE = intPreferencesKey("schedule_end_minute")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val settingsFlow: Flow<PlexoSettings> = context.plexoDataStore.data.map { prefs ->
        PlexoSettings(
            defaultFolderUri = prefs[Keys.DEFAULT_FOLDER_URI] ?: "",
            defaultFolderLabel = prefs[Keys.DEFAULT_FOLDER_LABEL] ?: "Plexo Downloads",
            maxSimultaneousDownloads = (prefs[Keys.MAX_SIMULTANEOUS] ?: 3).coerceIn(1, 6),
            defaultChunkSizeBytes = prefs[Keys.DEFAULT_CHUNK_SIZE] ?: (4L * 1024L * 1024L),
            ethernetDefaultStreams = (prefs[Keys.ETHERNET_STREAMS] ?: 16).coerceIn(1, 32),
            wifiDefaultStreams = (prefs[Keys.WIFI_STREAMS] ?: 8).coerceIn(1, 32),
            cellularDefaultStreams = (prefs[Keys.CELLULAR_STREAMS] ?: 4).coerceIn(1, 16),
            globalSpeedLimitBytesPerSec = prefs[Keys.GLOBAL_SPEED_LIMIT] ?: 0L,
            slowModeEnabled = prefs[Keys.SLOW_MODE_ENABLED] ?: false,
            slowModeLimitBytesPerSec = prefs[Keys.SLOW_MODE_LIMIT] ?: (512L * 1024L),
            wifiOnlyGuardrail = prefs[Keys.WIFI_ONLY_GUARDRAIL] ?: true,
            allowVpnBonding = prefs[Keys.ALLOW_VPN_BONDING] ?: false,
            autoSortByType = prefs[Keys.AUTO_SORT_BY_TYPE] ?: true,
            clipboardWatcherEnabled = prefs[Keys.CLIPBOARD_WATCHER] ?: true,
            speedUnitMbps = prefs[Keys.SPEED_UNIT_MBPS] ?: false,
            schedulerEnabled = prefs[Keys.SCHEDULER_ENABLED] ?: false,
            scheduleStartHour = prefs[Keys.SCHEDULE_START_HOUR] ?: 1,
            scheduleStartMinute = prefs[Keys.SCHEDULE_START_MINUTE] ?: 0,
            scheduleEndHour = prefs[Keys.SCHEDULE_END_HOUR] ?: 6,
            scheduleEndMinute = prefs[Keys.SCHEDULE_END_MINUTE] ?: 0,
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name)
            }.getOrDefault(ThemeMode.DARK),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false
        )
    }

    suspend fun getCurrentSettings(): PlexoSettings = settingsFlow.first()

    suspend fun update(transform: (PlexoSettings) -> PlexoSettings) {
        val updated = transform(getCurrentSettings())
        context.plexoDataStore.edit { prefs ->
            prefs[Keys.DEFAULT_FOLDER_URI] = updated.defaultFolderUri
            prefs[Keys.DEFAULT_FOLDER_LABEL] = updated.defaultFolderLabel
            prefs[Keys.MAX_SIMULTANEOUS] = updated.maxSimultaneousDownloads
            prefs[Keys.DEFAULT_CHUNK_SIZE] = updated.defaultChunkSizeBytes
            prefs[Keys.ETHERNET_STREAMS] = updated.ethernetDefaultStreams
            prefs[Keys.WIFI_STREAMS] = updated.wifiDefaultStreams
            prefs[Keys.CELLULAR_STREAMS] = updated.cellularDefaultStreams
            prefs[Keys.GLOBAL_SPEED_LIMIT] = updated.globalSpeedLimitBytesPerSec
            prefs[Keys.SLOW_MODE_ENABLED] = updated.slowModeEnabled
            prefs[Keys.SLOW_MODE_LIMIT] = updated.slowModeLimitBytesPerSec
            prefs[Keys.WIFI_ONLY_GUARDRAIL] = updated.wifiOnlyGuardrail
            prefs[Keys.ALLOW_VPN_BONDING] = updated.allowVpnBonding
            prefs[Keys.AUTO_SORT_BY_TYPE] = updated.autoSortByType
            prefs[Keys.CLIPBOARD_WATCHER] = updated.clipboardWatcherEnabled
            prefs[Keys.SPEED_UNIT_MBPS] = updated.speedUnitMbps
            prefs[Keys.SCHEDULER_ENABLED] = updated.schedulerEnabled
            prefs[Keys.SCHEDULE_START_HOUR] = updated.scheduleStartHour
            prefs[Keys.SCHEDULE_START_MINUTE] = updated.scheduleStartMinute
            prefs[Keys.SCHEDULE_END_HOUR] = updated.scheduleEndHour
            prefs[Keys.SCHEDULE_END_MINUTE] = updated.scheduleEndMinute
            prefs[Keys.THEME_MODE] = updated.themeMode.name
            prefs[Keys.DYNAMIC_COLOR] = updated.dynamicColor
        }
    }
}
