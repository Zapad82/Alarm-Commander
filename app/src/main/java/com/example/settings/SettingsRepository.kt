package com.example.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alarm_commander_settings")

/**
 * Шифрование и расшифровка Bearer-токена Webhook через аппаратный Android Keystore (AES/GCM/NoPadding)
 * перед записью в DataStore.
 */
object WebhookTokenCrypto {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "alarm_commander_webhook_token_aes_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val ENCRYPTED_PREFIX = "ENCv1:"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encryptForStorage(plainToken: String): String {
        val trimmed = plainToken.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith(ENCRYPTED_PREFIX)) return trimmed
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$ENCRYPTED_PREFIX$ivBase64:$cipherBase64"
        }.getOrDefault(trimmed)
    }

    fun decryptFromStorage(storedValue: String?): String {
        val raw = storedValue?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (!raw.startsWith(ENCRYPTED_PREFIX)) return raw
        return runCatching {
            val payload = raw.removePrefix(ENCRYPTED_PREFIX)
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

/**
 * Снимок настроек и сохранённого состояния активной тревоги (переживает process death).
 */
data class CommanderSettingsState(
    val disclaimerAccepted: Boolean = false,
    val sunriseWebhookUrl: String = "http://192.168.1.100:8123/api/webhook/alarm_sunrise",
    val escalationWebhookUrl: String = "http://192.168.1.100:8123/api/webhook/alarm_overdue",
    val webhookAuthToken: String = "",
    val backupContactName: String = "",
    val backupContactPhone: String = "",
    val bypassDndPreferred: Boolean = true,
    val defaultQrPayload: String = "ALARM-COMMANDER-BATHROOM-01",
    val defaultNfcTagId: String = "*",
    // Глобальные настройки звука, вибрации и системной мелодии по умолчанию
    val defaultSignalMode: String = "SOUND_AND_VIBRO",
    val defaultSoundSource: String = "SYSTEM_DEFAULT_ALARM",
    val customRingtoneUri: String = "",
    val customRingtoneTitle: String = "",
    // Язык ("SYSTEM", "RU", "EN") и тема ("DARK", "LIGHT", "SYSTEM") + репозиторий обновлений GitHub
    val languageMode: String = "SYSTEM",
    val themeMode: String = "DARK",
    val githubRepoSlug: String = GitHubUpdateChecker.OFFICIAL_GITHUB_REPO_SLUG,
    // Поля сохранения активной сессии для восстановления после убийства процесса (process death)
    val activeAlarmId: Long = -1L,
    val activeAlarmState: AlarmState = AlarmState.DISMISSED,
    val activeEscalationStage: Int = 0,
    val activeMissionIndex: Int = 0,
    val activeSessionStartEpochMs: Long = 0L,
    val activeIsTestRun: Boolean = false,
    val antiReturnAlarmId: Long = -1L,
    val antiReturnDeadlineEpochMs: Long = 0L
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val SUNRISE_WEBHOOK_URL = stringPreferencesKey("sunrise_webhook_url")
        val ESCALATION_WEBHOOK_URL = stringPreferencesKey("escalation_webhook_url")
        val WEBHOOK_AUTH_TOKEN = stringPreferencesKey("webhook_auth_token")
        val BACKUP_CONTACT_NAME = stringPreferencesKey("backup_contact_name")
        val BACKUP_CONTACT_PHONE = stringPreferencesKey("backup_contact_phone")
        val BYPASS_DND_PREFERRED = booleanPreferencesKey("bypass_dnd_preferred")
        val DEFAULT_QR_PAYLOAD = stringPreferencesKey("default_qr_payload")
        val DEFAULT_NFC_TAG_ID = stringPreferencesKey("default_nfc_tag_id")
        val DEFAULT_SIGNAL_MODE = stringPreferencesKey("default_signal_mode")
        val DEFAULT_SOUND_SOURCE = stringPreferencesKey("default_sound_source")
        val CUSTOM_RINGTONE_URI = stringPreferencesKey("custom_ringtone_uri")
        val CUSTOM_RINGTONE_TITLE = stringPreferencesKey("custom_ringtone_title")
        val LANGUAGE_MODE = stringPreferencesKey("language_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GITHUB_REPO_SLUG = stringPreferencesKey("github_repo_slug")

        // Process-death recovery keys
        val ACTIVE_ALARM_ID = longPreferencesKey("active_alarm_id")
        val ACTIVE_ALARM_STATE = stringPreferencesKey("active_alarm_state")
        val ACTIVE_ESCALATION_STAGE = intPreferencesKey("active_escalation_stage")
        val ACTIVE_MISSION_INDEX = intPreferencesKey("active_mission_index")
        val ACTIVE_SESSION_START_MS = longPreferencesKey("active_session_start_ms")
        val ACTIVE_IS_TEST_RUN = booleanPreferencesKey("active_is_test_run")
        val ANTI_RETURN_ALARM_ID = longPreferencesKey("anti_return_alarm_id")
        val ANTI_RETURN_DEADLINE_MS = longPreferencesKey("anti_return_deadline_ms")
    }

    val settingsFlow: Flow<CommanderSettingsState> = context.dataStore.data.map { prefs ->
        val stateStr = prefs[Keys.ACTIVE_ALARM_STATE] ?: AlarmState.DISMISSED.name
        val parsedState = runCatching { AlarmState.valueOf(stateStr) }.getOrDefault(AlarmState.DISMISSED)
        val rawSavedPhone = prefs[Keys.BACKUP_CONTACT_PHONE].orEmpty().trim()
        val sanitizedSavedPhone = if (rawSavedPhone == "+79001234567") "" else rawSavedPhone
        val rawSavedName = prefs[Keys.BACKUP_CONTACT_NAME].orEmpty().trim()
        val sanitizedSavedName = if (rawSavedName == "Дежурный контакт" && sanitizedSavedPhone.isEmpty()) "" else rawSavedName
        CommanderSettingsState(
            disclaimerAccepted = prefs[Keys.DISCLAIMER_ACCEPTED] ?: false,
            sunriseWebhookUrl = prefs[Keys.SUNRISE_WEBHOOK_URL]
                ?: "http://192.168.1.100:8123/api/webhook/alarm_sunrise",
            escalationWebhookUrl = prefs[Keys.ESCALATION_WEBHOOK_URL]
                ?: "http://192.168.1.100:8123/api/webhook/alarm_overdue",
            webhookAuthToken = WebhookTokenCrypto.decryptFromStorage(prefs[Keys.WEBHOOK_AUTH_TOKEN]),
            backupContactName = sanitizedSavedName,
            backupContactPhone = sanitizedSavedPhone,
            bypassDndPreferred = prefs[Keys.BYPASS_DND_PREFERRED] ?: true,
            defaultQrPayload = prefs[Keys.DEFAULT_QR_PAYLOAD] ?: "ALARM-COMMANDER-BATHROOM-01",
            defaultNfcTagId = prefs[Keys.DEFAULT_NFC_TAG_ID] ?: "*",
            defaultSignalMode = prefs[Keys.DEFAULT_SIGNAL_MODE] ?: "SOUND_AND_VIBRO",
            defaultSoundSource = prefs[Keys.DEFAULT_SOUND_SOURCE] ?: "SYSTEM_DEFAULT_ALARM",
            customRingtoneUri = prefs[Keys.CUSTOM_RINGTONE_URI] ?: "",
            customRingtoneTitle = prefs[Keys.CUSTOM_RINGTONE_TITLE] ?: "",
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "SYSTEM",
            themeMode = prefs[Keys.THEME_MODE] ?: "DARK",
            githubRepoSlug = GitHubUpdateChecker.normalizeRepoSlug(prefs[Keys.GITHUB_REPO_SLUG]),
            activeAlarmId = prefs[Keys.ACTIVE_ALARM_ID] ?: -1L,
            activeAlarmState = parsedState,
            activeEscalationStage = prefs[Keys.ACTIVE_ESCALATION_STAGE] ?: 0,
            activeMissionIndex = prefs[Keys.ACTIVE_MISSION_INDEX] ?: 0,
            activeSessionStartEpochMs = prefs[Keys.ACTIVE_SESSION_START_MS] ?: 0L,
            activeIsTestRun = prefs[Keys.ACTIVE_IS_TEST_RUN] ?: false,
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
            it[Keys.GITHUB_REPO_SLUG] = GitHubUpdateChecker.normalizeRepoSlug(slug)
        }
    }

    suspend fun updateSignalSettings(
        signalMode: String,
        soundSource: String,
        customRingtoneUri: String,
        customRingtoneTitle: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_SIGNAL_MODE] = signalMode
            prefs[Keys.DEFAULT_SOUND_SOURCE] = soundSource
            prefs[Keys.CUSTOM_RINGTONE_URI] = customRingtoneUri
            prefs[Keys.CUSTOM_RINGTONE_TITLE] = customRingtoneTitle
        }
    }

    suspend fun updateBackupSettings(
        sunriseWebhookUrl: String,
        escalationWebhookUrl: String,
        contactName: String,
        contactPhone: String,
        defaultQrPayload: String,
        defaultNfcTagId: String,
        webhookAuthToken: String = ""
    ) {
        val encryptedToken = WebhookTokenCrypto.encryptForStorage(webhookAuthToken)
        context.dataStore.edit { prefs ->
            prefs[Keys.SUNRISE_WEBHOOK_URL] = sunriseWebhookUrl.trim()
            prefs[Keys.ESCALATION_WEBHOOK_URL] = escalationWebhookUrl.trim()
            prefs[Keys.WEBHOOK_AUTH_TOKEN] = encryptedToken
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
        sessionStartEpochMs: Long,
        isTestRun: Boolean = false
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_ALARM_ID] = alarmId
            prefs[Keys.ACTIVE_ALARM_STATE] = state.name
            prefs[Keys.ACTIVE_ESCALATION_STAGE] = escalationStage
            prefs[Keys.ACTIVE_MISSION_INDEX] = missionIndex
            prefs[Keys.ACTIVE_SESSION_START_MS] = sessionStartEpochMs
            prefs[Keys.ACTIVE_IS_TEST_RUN] = isTestRun
        }
    }

    suspend fun clearActiveAlarmSession() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_ALARM_ID] = -1L
            prefs[Keys.ACTIVE_ALARM_STATE] = AlarmState.DISMISSED.name
            prefs[Keys.ACTIVE_ESCALATION_STAGE] = 0
            prefs[Keys.ACTIVE_MISSION_INDEX] = 0
            prefs[Keys.ACTIVE_SESSION_START_MS] = 0L
            prefs[Keys.ACTIVE_IS_TEST_RUN] = false
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
