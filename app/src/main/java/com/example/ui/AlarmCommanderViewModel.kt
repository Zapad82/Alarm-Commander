package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.ActiveAlarmUiState
import com.example.alarm.AlarmScheduler
import com.example.alarm.AlarmService
import com.example.backup.BackupPayload
import com.example.backup.BedShakerStubChannel
import com.example.backup.FriendVoipCallStubChannel
import com.example.backup.SecondDeviceStubChannel
import com.example.backup.SmartHomeWebhookChannel
import com.example.backup.SmartSpeakerStubChannel
import com.example.backup.SocialContactChannel
import com.example.data.AlarmEntity
import com.example.data.AlarmLogEntity
import com.example.data.AlarmRepository
import com.example.data.AlarmState
import com.example.data.AppDatabase
import com.example.mission.MissionInput
import com.example.permissions.PermissionItemStatus
import com.example.permissions.PermissionManager
import com.example.settings.CommanderSettingsState
import com.example.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlarmCommanderViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val repository = AlarmRepository(database.alarmDao())
    private val settingsRepository = SettingsRepository(appContext)
    private val scheduler = AlarmScheduler(appContext)
    val permissionManager = PermissionManager(appContext)
    val safetyAuditor = com.example.permissions.SafetyAndPowerAuditor(appContext)

    val alarmsFlow: StateFlow<List<AlarmEntity>> = repository.alarmsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logsFlow: StateFlow<List<AlarmLogEntity>> = repository.logsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settingsFlow: StateFlow<CommanderSettingsState> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CommanderSettingsState())

    val activeAlarmUiState: StateFlow<ActiveAlarmUiState> = AlarmService.activeUiState

    private val _permissionsState = MutableStateFlow<List<PermissionItemStatus>>(emptyList())
    val permissionsState: StateFlow<List<PermissionItemStatus>> = _permissionsState.asStateFlow()

    private val _auditReportState = MutableStateFlow(safetyAuditor.runFullAudit(CommanderSettingsState()))
    val auditReportState: StateFlow<com.example.permissions.SafetyAndPowerReport> = _auditReportState.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _githubReleaseState = MutableStateFlow(com.example.settings.GitHubReleaseCheckResult())
    val githubReleaseState: StateFlow<com.example.settings.GitHubReleaseCheckResult> = _githubReleaseState.asStateFlow()

    val apkSignatureSha256: String by lazy {
        com.example.settings.GitHubUpdateChecker.getSigningCertificateSha256(appContext)
    }

    init {
        refreshPermissions()
        viewModelScope.launch {
            settingsFlow.collect { currentSettings ->
                _auditReportState.value = safetyAuditor.runFullAudit(currentSettings)
            }
        }
        viewModelScope.launch {
            val seeded = repository.ensureSeededDefaults()
            seeded.filter { it.enabled }.forEach { scheduler.scheduleAlarm(it) }

            // Проверяем, не была ли прервана недавняя (не старше 10 минут) реальная тревога из-за Process Death
            val snap = settingsRepository.getSnapshot()
            val nowMs = System.currentTimeMillis()
            if (snap.antiReturnAlarmId > 0L &&
                (snap.antiReturnAlarmId == 999L || nowMs > snap.antiReturnDeadlineEpochMs + 60_000L)
            ) {
                scheduler.cancelAntiReturnCheck(snap.antiReturnAlarmId)
                settingsRepository.clearAntiReturnSession()
            }

            val sessionAgeMs = nowMs - snap.activeSessionStartEpochMs
            if (snap.activeAlarmId > 0L &&
                snap.activeAlarmId != 999L &&
                sessionAgeMs in 1..600_000L &&
                snap.activeAlarmState in setOf(
                    AlarmState.RINGING,
                    AlarmState.ESCALATING,
                    AlarmState.MISSION_IN_PROGRESS,
                    AlarmState.OVERDUE
                ) &&
                !AlarmService.activeUiState.value.isActive
            ) {
                val recoverIntent = Intent(appContext, AlarmService::class.java).apply {
                    action = AlarmService.ACTION_START_ALARM
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, snap.activeAlarmId)
                    putExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, false)
                }
                ContextCompat.startForegroundService(appContext, recoverIntent)
            } else if (snap.activeAlarmId > 0L && !AlarmService.activeUiState.value.isActive) {
                settingsRepository.clearActiveAlarmSession()
            }
        }
    }

    fun refreshPermissions() {
        _permissionsState.value = permissionManager.evaluateAllPermissions()
        _auditReportState.value = safetyAuditor.runFullAudit(settingsFlow.value)
    }

    fun clearBannerMessage() {
        _statusBannerMessage.value = null
    }

    fun setLanguageMode(mode: AppLanguageMode) {
        viewModelScope.launch {
            settingsRepository.setLanguageMode(mode.name)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode.name)
        }
    }

    fun checkGithubUpdates(repoSlug: String) {
        viewModelScope.launch {
            settingsRepository.setGithubRepoSlug(repoSlug)
            _githubReleaseState.value = _githubReleaseState.value.copy(isChecking = true)
            val currentVer = com.example.BuildConfig.VERSION_NAME
            val res = com.example.settings.GitHubUpdateChecker.checkLatestRelease(
                repoSlug = repoSlug,
                currentVersionName = currentVer
            )
            _githubReleaseState.value = res
        }
    }

    fun acceptDisclaimer() {
        viewModelScope.launch {
            settingsRepository.setDisclaimerAccepted(true)
            repository.logEvent(
                alarmId = 0L,
                alarmLabel = "Система",
                eventType = "DISCLAIMER_ACCEPTED",
                state = AlarmState.SCHEDULED,
                details = "Пользователь ознакомился с условиями безопасности и ограничением ответственности"
            )
        }
    }

    fun saveAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            val savedId = repository.saveAlarm(alarm)
            val savedEntity = alarm.copy(id = savedId)
            if (savedEntity.enabled) {
                scheduler.scheduleAlarm(savedEntity)
                _statusBannerMessage.value =
                    "«${savedEntity.label}» → ${savedEntity.formattedTime()}"
            } else {
                scheduler.cancelAlarm(savedId)
                _statusBannerMessage.value = "«${savedEntity.label}» OFF"
            }
        }
    }

    fun toggleAlarmEnabled(alarm: AlarmEntity, enabled: Boolean) {
        saveAlarm(alarm.copy(enabled = enabled))
    }

    fun deleteAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            scheduler.cancelAlarm(alarm.id)
            repository.deleteAlarm(alarm)
            _statusBannerMessage.value = "Удалён / Deleted: «${alarm.label}»"
        }
    }

    /**
     * Немедленный запуск боевого теста T0 или фазы «Рассвет» (T−15) через Foreground-сервис.
     */
    fun triggerCombatTestAlarm(alarmId: Long, isSunrise: Boolean = false) {
        val intent = Intent(appContext, AlarmService::class.java).apply {
            action = if (isSunrise) {
                AlarmService.ACTION_START_SUNRISE
            } else {
                AlarmService.ACTION_START_ALARM
            }
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, true)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    /**
     * Немедленный запуск 60-секундной проверки «Анти-возврата в кровать» для тестирования.
     */
    fun triggerAntiReturnTest(alarmId: Long) {
        val intent = Intent(appContext, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ANTI_RETURN
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun onStartMissionClicked() {
        AlarmService.notifyMissionStarted()
    }

    fun submitMathAnswer(answer: Int) {
        AlarmService.submitMissionInput(MissionInput.MathAnswer(answer))
    }

    fun submitStepProgress(steps: Int) {
        AlarmService.submitMissionInput(MissionInput.StepCountUpdate(steps))
    }

    fun submitQrScanned(payload: String) {
        AlarmService.submitMissionInput(MissionInput.QrPayloadScanned(payload))
    }

    fun submitNfcScanned(tagHex: String) {
        AlarmService.submitMissionInput(MissionInput.NfcTagScanned(tagHex))
    }

    fun submitVoicePhrase(spokenText: String) {
        AlarmService.submitMissionInput(MissionInput.VoicePhraseSpoken(spokenText))
    }

    fun setPenaltySnoozeMode(enabled: Boolean) {
        AlarmService.requestPenaltySnoozeMode(enabled)
    }

    fun submitPenaltySnoozePhrase(phrase: String): Boolean {
        return AlarmService.executePenaltySnooze(phrase)
    }

    fun dismissSunriseSession() {
        AlarmService.dismissSunrisePreAlarm()
    }

    fun confirmAntiReturnAwake() {
        AlarmService.confirmAntiReturnAwake()
    }

    fun updateGlobalSignalSettings(
        signalMode: String,
        soundSource: String,
        customRingtoneUri: String,
        customRingtoneTitle: String
    ) {
        viewModelScope.launch {
            settingsRepository.updateSignalSettings(
                signalMode = signalMode,
                soundSource = soundSource,
                customRingtoneUri = customRingtoneUri,
                customRingtoneTitle = customRingtoneTitle
            )
            // Применяем глобальный выбор сигнала и мелодии ко всем существующим будильникам
            alarmsFlow.value.forEach { alarm ->
                repository.saveAlarm(
                    alarm.copy(
                        signalMode = signalMode,
                        soundSource = soundSource,
                        customRingtoneUri = customRingtoneUri,
                        customRingtoneTitle = customRingtoneTitle
                    )
                )
            }
        }
    }

    fun saveBackupAndSensorSettings(
        sunriseWebhookUrl: String,
        escalationWebhookUrl: String,
        contactName: String,
        contactPhone: String,
        defaultQrPayload: String,
        defaultNfcTagId: String
    ) {
        viewModelScope.launch {
            settingsRepository.updateBackupSettings(
                sunriseWebhookUrl = sunriseWebhookUrl,
                escalationWebhookUrl = escalationWebhookUrl,
                contactName = contactName,
                contactPhone = contactPhone,
                defaultQrPayload = defaultQrPayload,
                defaultNfcTagId = defaultNfcTagId
            )
            _statusBannerMessage.value = "Настройки сохранены / Settings saved"
        }
    }

    fun testSmartHomeWebhook(url: String, eventType: String) {
        viewModelScope.launch {
            val result = SmartHomeWebhookChannel().trigger(
                BackupPayload(
                    alarmId = 0L,
                    alarmLabel = "Webhook Test",
                    eventType = eventType,
                    targetUrlOrAddress = url
                )
            )
            repository.logEvent(
                alarmId = 0L,
                alarmLabel = "Webhook Test",
                eventType = "WEBHOOK_TEST",
                state = AlarmState.SCHEDULED,
                details = result.messageRu
            )
            _statusBannerMessage.value = result.messageRu
        }
    }

    fun testSocialContactChannel(name: String, phone: String) {
        viewModelScope.launch {
            val result = SocialContactChannel(appContext).trigger(
                BackupPayload(
                    alarmId = 0L,
                    alarmLabel = "Contact Test",
                    eventType = "MANUAL_CONTACT_TEST",
                    targetUrlOrAddress = "",
                    contactName = name,
                    contactPhone = phone
                )
            )
            repository.logEvent(
                alarmId = 0L,
                alarmLabel = "Contact Test",
                eventType = "CONTACT_TEST",
                state = AlarmState.SCHEDULED,
                details = result.messageRu
            )
            _statusBannerMessage.value = result.messageRu
        }
    }

    fun testFutureStubChannel(stubType: String) {
        viewModelScope.launch {
            val channel = when (stubType) {
                "FRIEND_CALL" -> FriendVoipCallStubChannel()
                "SMART_SPEAKER" -> SmartSpeakerStubChannel()
                "BED_SHAKER" -> BedShakerStubChannel()
                else -> SecondDeviceStubChannel()
            }
            val res = channel.trigger(
                BackupPayload(
                    alarmId = 0L,
                    alarmLabel = "Stub Test",
                    eventType = "STUB_PING",
                    targetUrlOrAddress = "local://stub"
                )
            )
            repository.logEvent(
                alarmId = 0L,
                alarmLabel = channel.displayNameRu,
                eventType = "STUB_CHANNEL_TEST",
                state = AlarmState.SCHEDULED,
                details = res.messageRu
            )
            _statusBannerMessage.value = res.messageRu
        }
    }

    fun clearEventLogs() {
        viewModelScope.launch {
            repository.clearLogs()
            _statusBannerMessage.value = "Журнал очищен / Logs cleared"
        }
    }
}
