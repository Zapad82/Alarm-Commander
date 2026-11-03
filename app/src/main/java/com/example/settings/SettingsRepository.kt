package com.example.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.AlarmState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alarm_commander_settings")

/**
 * Снимок настроек и сохранённого состояния активной тревоги (переживает process death).
 */
data class CommanderSettingsState(
    val disclaimerAccepted: Boolean = false,
    val sunriseWebhookUrl: String = "http://192.168.1.100:8123/api/webhook/alarm_sunrise",
    val escalationWebhookUrl: String = "http://192.168.1.100:8123/api/webhook/alarm_overdue",
    val backupContactName: String = "Дежурный контакт",
    val backupContactPhone: String = "+79001234567",
    val bypassDndPreferred: Boolean = true,
    val defaultQrPayload: String = "ALARM-COMMANDER-BATHROOM-01",
    val defaultNfcTagId: String = "*",
    // Язык ("SYSTEM", "RU", "EN") и тема ("DARK", "LIGHT", "SYSTEM") + репозиторий обновлений GitHub
    val languageMode: String = "SYSTEM",
    val themeMode: String = "DARK",
    val githubRepoSlug: String = "alarm-commander/alarm-commander-android",
    // Поля сохранения активной сессии для восстановления после убийства процесса (process death)
    val activeAlarmId: Long = -1L,
    val activeAlarmState: AlarmState = AlarmState.DISMISSED,
    val activeEscalationStage: Int = 0,
    val activeMissionIndex: Int = 0,
    val activeSessionStartEpochMs: Long = 0L,
    val antiReturnAlarmId: Long = -1L,
    val antiReturnDeadlineEpochMs: Long = 0L
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val SUNRISE_WEBHOOK_URL = stringPreferencesKey("sunrise_webhook_url")
        val ESCALATION_WEBHOOK_URL = stringPreferencesKey("escalation_webhook_url")
        val BACKUP_CONTACT_NAME = stringPreferencesKey("backup_contact_name")
        val BACKUP_CONTACT_PHONE = stringPreferencesKey("backup_contact_phone")
        val BYPASS_DND_PREFERRED = booleanPreferencesKey("bypass_dnd_preferred")
        val DEFAULT_QR_PAYLOAD = stringPreferencesKey("default_qr_payload")
        val DEFAULT_NFC_TAG_ID = stringPreferencesKey("default_nfc_tag_id")
        val LANGUAGE_MODE = stringPreferencesKey("language_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GITHUB_REPO_SLUG = stringPreferencesKey("github_repo_slug")

        // Process-death recovery keys
        val ACTIVE_ALARM_ID = longPreferencesKey("active_alarm_id")
        val ACTIVE_ALARM_STATE = stringPreferencesKey("active_alarm_state")
        val ACTIVE_ESCALATION_STAGE = intPreferencesKey("active_escalation_stage")
        val ACTIVE_MISSION_INDEX = intPreferencesKey("active_mission_index")
        val ACTIVE_SESSION_START_MS = longPreferencesKey("active_session_start_ms")
        val ANTI_RETURN_ALARM_ID = longPreferencesKey("anti_return_alarm_id")
        val ANTI_RETURN_DEADLINE_MS = longPreferencesKey("anti_return_deadline_ms")
    }

    val settingsFlow: Flow<CommanderSettingsState> = context.dataStore.data.map { prefs ->
        val stateStr = prefs[Keys.ACTIVE_ALARM_STATE] ?: AlarmState.DISMISSED.name
        val parsedState = runCatching { AlarmState.valueOf(stateStr) }.getOrDefault(AlarmState.DISMISSED)
        CommanderSettingsState(
            disclaimerAccepted = prefs[Keys.DISCLAIMER_ACCEPTED] ?: false,
            sunriseWebhookUrl = prefs[Keys.SUNRISE_WEBHOOK_URL]
                ?: "http://192.168.1.100:8123/api/webhook/alarm_sunrise",
            escalationWebhookUrl = prefs[Keys.ESCALATION_WEBHOOK_URL]
                ?: "http://192.168.1.100:8123/api/webhook/alarm_overdue",
            backupContactName = prefs[Keys.BACKUP_CONTACT_NAME] ?: "Дежурный контакт",
            backupContactPhone = prefs[Keys.BACKUP_CONTACT_PHONE] ?: "+79001234567",
            bypassDndPreferred = prefs[Keys.BYPASS_DND_PREFERRED] ?: true,
            defaultQrPayload = prefs[Keys.DEFAULT_QR_PAYLOAD] ?: "ALARM-COMMANDER-BATHROOM-01",
            defaultNfcTagId = prefs[Keys.DEFAULT_NFC_TAG_ID] ?: "*",
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "SYSTEM",
            themeMode = prefs[Keys.THEME_MODE] ?: "DARK",
            githubRepoSlug = prefs[Keys.GITHUB_REPO_SLUG] ?: "alarm-commander/alarm-commander-android",
            activeAlarmId = prefs[Keys.ACTIVE_ALARM_ID] ?: -1L,
            activeAlarmState = parsedState,
            activeEscalationStage = prefs[Keys.ACTIVE_ESCALATION_STAGE] ?: 0,
            activeMissionIndex = prefs[Keys.ACTIVE_MISSION_INDEX] ?: 0,
            activeSessionStartEpochMs = prefs[Keys.ACTIVE_SESSION_START_MS] ?: 0L,
            antiReturnAlarmId = prefs[Keys.ANTI_RETURN_ALARM_ID] ?: -1L,
            antiReturnDeadlineEpochMs = prefs[Keys.ANTI_RETURN_DEADLINE_MS] ?: 0L
        )
    }

    suspend fun getSnapshot(): CommanderSettingsState = settingsFlow.first()

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = accepted }
    }

    suspend fun setLanguageMode(mode: String) {
        context.dataStore.edit { it[Keys.LANGUAGE_MODE] = mode }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setGithubRepoSlug(slug: String) {
        context.dataStore.edit {
            it[Keys.GITHUB_REPO_SLUG] = slug.trim().ifEmpty { "alarm-commander/alarm-commander-android" }
        }
    }

    suspend fun updateBackupSettings(
        sunriseWebhookUrl: String,
        escalationWebhookUrl: String,
        contactName: String,
        contactPhone: String,
        defaultQrPayload: String,
        defaultNfcTagId: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SUNRISE_WEBHOOK_URL] = sunriseWebhookUrl.trim()
            prefs[Keys.ESCALATION_WEBHOOK_URL] = escalationWebhookUrl.trim()
            prefs[Keys.BACKUP_CONTACT_NAME] = contactName.trim()
            prefs[Keys.BACKUP_CONTACT_PHONE] = contactPhone.trim()
            prefs[Keys.DEFAULT_QR_PAYLOAD] = defaultQrPayload.trim().ifEmpty { "ALARM-COMMANDER-BATHROOM-01" }
            prefs[Keys.DEFAULT_NFC_TAG_ID] = defaultNfcTagId.trim().ifEmpty { "*" }
        }
    }

    /**
     * Сохраняет состояние активной тревоги в DataStore, чтобы при убийстве процесса системой
     * (Process Death) сервис и Activity восстановили текущую стадию эскалации и индекс миссии.
     */
    suspend fun persistActiveAlarmSession(
        alarmId: Long,
        state: AlarmState,
        escalationStage: Int,
        missionIndex: Int,
        sessionStartEpochMs: Long
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_ALARM_ID] = alarmId
            prefs[Keys.ACTIVE_ALARM_STATE] = state.name
            prefs[Keys.ACTIVE_ESCALATION_STAGE] = escalationStage
            prefs[Keys.ACTIVE_MISSION_INDEX] = missionIndex
            prefs[Keys.ACTIVE_SESSION_START_MS] = sessionStartEpochMs
        }
    }

    suspend fun clearActiveAlarmSession() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_ALARM_ID] = -1L
            prefs[Keys.ACTIVE_ALARM_STATE] = AlarmState.DISMISSED.name
            prefs[Keys.ACTIVE_ESCALATION_STAGE] = 0
            prefs[Keys.ACTIVE_MISSION_INDEX] = 0
            prefs[Keys.ACTIVE_SESSION_START_MS] = 0L
        }
    }

    suspend fun persistAntiReturnSession(alarmId: Long, deadlineEpochMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ANTI_RETURN_ALARM_ID] = alarmId
            prefs[Keys.ANTI_RETURN_DEADLINE_MS] = deadlineEpochMs
        }
    }

    suspend fun clearAntiReturnSession() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ANTI_RETURN_ALARM_ID] = -1L
            prefs[Keys.ANTI_RETURN_DEADLINE_MS] = 0L
        }
    }
}
