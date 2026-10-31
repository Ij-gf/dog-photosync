package com.autoshot.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autoshot_settings")

const val DEFAULT_INTERVAL_MS: Long = 30 * 60 * 1000L

/** 隐私告知版本；文案有实质变化时 +1，用户需重新确认 */
const val CONSENT_VERSION = 1

data class AppSettings(
    /** 相册增量扫描水位线，DATE_ADDED（秒） */
    val lastScanAt: Long = 0L,
    val avatarPath: String? = null,
    /** 自动拍摄会话间隔，默认 30 分钟 */
    val repeatIntervalMs: Long = DEFAULT_INTERVAL_MS,
    /** 本地最多保留条数 */
    val maxRows: Int = 500,
    /** 已同步记录保留天数 */
    val retentionDays: Int = 30,
    /** 已同意的隐私政策版本；0 表示未同意。政策升级后需重新同意 */
    val consentVersion: Int = 0,
    /** 同意时间（毫秒） */
    val consentAt: Long = 0L,
    val supabaseEmail: String? = null,
    val supabaseToken: String? = null,
    val supabaseUserId: String? = null,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        val AVATAR_PATH = stringPreferencesKey("avatar_path")
        val REPEAT_INTERVAL = longPreferencesKey("repeat_interval_ms")
        val MAX_ROWS = intPreferencesKey("max_rows")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val CONSENT_VERSION = intPreferencesKey("consent_version")
        val CONSENT_AT = longPreferencesKey("consent_at")
        val SUPA_EMAIL = stringPreferencesKey("supabase_email")
        val SUPA_TOKEN = stringPreferencesKey("supabase_token")
        val SUPA_USER = stringPreferencesKey("supabase_user_id")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_ERROR = stringPreferencesKey("last_error")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            lastScanAt = p[Keys.LAST_SCAN_AT] ?: 0L,
            avatarPath = p[Keys.AVATAR_PATH],
            repeatIntervalMs = p[Keys.REPEAT_INTERVAL] ?: DEFAULT_INTERVAL_MS,
            maxRows = p[Keys.MAX_ROWS] ?: 500,
            retentionDays = p[Keys.RETENTION_DAYS] ?: 30,
            consentVersion = p[Keys.CONSENT_VERSION] ?: 0,
            consentAt = p[Keys.CONSENT_AT] ?: 0L,
            supabaseEmail = p[Keys.SUPA_EMAIL],
            supabaseToken = p[Keys.SUPA_TOKEN],
            supabaseUserId = p[Keys.SUPA_USER],
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
            lastError = p[Keys.LAST_ERROR],
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setLastScanAt(value: Long) {
        context.dataStore.edit { it[Keys.LAST_SCAN_AT] = value }
    }

    suspend fun setAvatarPath(value: String?) {
        context.dataStore.edit { p ->
            if (value == null) p.remove(Keys.AVATAR_PATH) else p[Keys.AVATAR_PATH] = value
        }
    }

    suspend fun setRepeatInterval(ms: Long) {
        context.dataStore.edit { it[Keys.REPEAT_INTERVAL] = ms }
    }

    suspend fun setMaxRows(value: Int) {
        context.dataStore.edit { it[Keys.MAX_ROWS] = value }
    }

    suspend fun setRetentionDays(value: Int) {
        context.dataStore.edit { it[Keys.RETENTION_DAYS] = value }
    }

    suspend fun setConsent(version: Int) {
        context.dataStore.edit {
            it[Keys.CONSENT_VERSION] = version
            it[Keys.CONSENT_AT] = System.currentTimeMillis()
        }
    }

    /** 撤回同意：停止采集，并清掉同意记录（下次进入需重新同意） */
    suspend fun clearConsent() {
        context.dataStore.edit {
            it.remove(Keys.CONSENT_VERSION)
            it.remove(Keys.CONSENT_AT)
        }
    }

    suspend fun setAuth(email: String?, token: String?, userId: String?) {
        context.dataStore.edit { p ->
            if (email == null) p.remove(Keys.SUPA_EMAIL) else p[Keys.SUPA_EMAIL] = email
            if (token == null) p.remove(Keys.SUPA_TOKEN) else p[Keys.SUPA_TOKEN] = token
            if (userId == null) p.remove(Keys.SUPA_USER) else p[Keys.SUPA_USER] = userId
        }
    }

    suspend fun clearAuth() = setAuth(null, null, null)

    suspend fun setLastSyncAt(value: Long) {
        context.dataStore.edit { it[Keys.LAST_SYNC_AT] = value }
    }

    suspend fun setLastError(message: String?) {
        context.dataStore.edit { p ->
            if (message == null) p.remove(Keys.LAST_ERROR) else p[Keys.LAST_ERROR] = message
        }
    }
}
