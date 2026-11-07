package com.shadowbox.x.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shadowbox_settings")

/** Non-secret settings only. (backupPwBlob is Keystore-sealed.) */
data class AppSettings(
    val autoLockSeconds: Int = 30,
    val lockOnBackground: Boolean = true,
    val biometricUnlock: Boolean = false,
    val requireBioReveal: Boolean = true,
    val clipboardClearSeconds: Int = 30,
    val accent: String = "ELECTRIC",
    val backupDays: Int = 0,
    val backupTree: String? = null,
    val backupPwBlob: String? = null,
    val lastBackupAt: Long = 0,
    val lastBackupStatus: String = "No backup yet",
    val backupDue: Boolean = false
)

class SettingsRepository(ctx: Context, private val scope: CoroutineScope) {
    private val ds = ctx.dataStore
    private val kAuto = intPreferencesKey("autoLock")
    private val kBg = booleanPreferencesKey("lockBg")
    private val kBio = booleanPreferencesKey("bio")
    private val kReveal = booleanPreferencesKey("bioReveal")
    private val kClip = intPreferencesKey("clip")
    private val kAccent = stringPreferencesKey("accent")
    private val kDays = intPreferencesKey("backupDays")
    private val kTree = stringPreferencesKey("backupTree")
    private val kPw = stringPreferencesKey("backupPw")
    private val kLast = longPreferencesKey("lastBackup")
    private val kStatus = stringPreferencesKey("lastStatus")
    private val kDue = booleanPreferencesKey("backupDue")

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            p[kAuto] ?: d.autoLockSeconds, p[kBg] ?: d.lockOnBackground, p[kBio] ?: d.biometricUnlock,
            p[kReveal] ?: d.requireBioReveal, p[kClip] ?: d.clipboardClearSeconds, p[kAccent] ?: d.accent,
            p[kDays] ?: d.backupDays, p[kTree], p[kPw], p[kLast] ?: 0, p[kStatus] ?: d.lastBackupStatus, p[kDue] ?: false
        )
    }

    val settings: StateFlow<AppSettings> = ds.data.map { read(it) }.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = ds.data.map { read(it) }.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        ds.edit { p ->
            val n = block(read(p))
            p[kAuto] = n.autoLockSeconds; p[kBg] = n.lockOnBackground; p[kBio] = n.biometricUnlock
            p[kReveal] = n.requireBioReveal; p[kClip] = n.clipboardClearSeconds; p[kAccent] = n.accent
            p[kDays] = n.backupDays; p[kLast] = n.lastBackupAt; p[kStatus] = n.lastBackupStatus; p[kDue] = n.backupDue
            if (n.backupTree != null) p[kTree] = n.backupTree else p.remove(kTree)
            if (n.backupPwBlob != null) p[kPw] = n.backupPwBlob else p.remove(kPw)
        }
    }

    fun set(block: (AppSettings) -> AppSettings) { scope.launch { update(block) } }
}
