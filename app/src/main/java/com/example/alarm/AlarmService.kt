package com.example.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.backup.BackupPayload
import com.example.backup.SmartHomeWebhookChannel
import com.example.backup.SocialContactChannel
import com.example.data.AlarmEntity
import com.example.data.AlarmRepository
import com.example.data.AlarmState
import com.example.data.AppDatabase
import com.example.data.EscalationProfile
import com.example.data.MathDifficulty
import com.example.data.MissionType
import com.example.escalation.EscalationEngine
import com.example.escalation.EscalationSnapshot
import com.example.mission.MathMission
import com.example.mission.Mission
import com.example.mission.MissionChainCoordinator
import com.example.mission.MissionInput
import com.example.mission.MissionResult
import com.example.mission.NfcMission
import com.example.mission.QrMission
import com.example.mission.StepsMission
import com.example.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Единое реактивное состояние активной тревоги/рассвета/миссии/анти-возврата,
 * публикуемое Foreground-сервисом для UI.
 */
data class ActiveAlarmUiState(
    val isActive: Boolean = false,
    val alarm: AlarmEntity? = null,
    val escalation: EscalationSnapshot? = null,
    val currentMissionType: MissionType? = null,
    val currentMissionIndex: Int = 0,
    val totalMissionsInChain: Int = 1,
    val missionPrompt: String = "",
    val missionFeedback: String? = null,
    val missionProgressStep: Int = 0,
    val missionTotalSteps: Int = 1,
    val isPenaltySnoozeMode: Boolean = false,
    val penaltyPhraseRequired: String = "ПОДТВЕРЖДАЮ ПЕРЕНОС 5 МИН",
    val isAntiReturnPromptActive: Boolean = false,
    val antiReturnSecondsRemaining: Int = 0,
    val lastBackupEventMessage: String? = null
)

/**
 * Foreground-сервис удержания звонка, расчёта эскалации, выполнения миссии и защиты от Process Death.
 */
class AlarmService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: AlarmRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var audioController: AlarmAudioController
    private val escalationEngine = EscalationEngine()
    private var missionCoordinator: MissionChainCoordinator? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var tickerJob: Job? = null
    private var antiReturnCountdownJob: Job? = null

    companion object {
        const val CHANNEL_ID_ALARM = "alarm_commander_critical_channel"
        const val CHANNEL_ID_BACKUP = "alarm_commander_backup_channel"
        const val NOTIFICATION_ID_ALARM = 7001

        const val ACTION_START_ALARM = "com.example.service.START_ALARM"
        const val ACTION_START_SUNRISE = "com.example.service.START_SUNRISE"
        const val ACTION_START_ANTI_RETURN = "com.example.service.START_ANTI_RETURN"
        const val ACTION_CONFIRM_ANTI_RETURN = "com.example.service.CONFIRM_ANTI_RETURN"

        private val _activeUiState = MutableStateFlow(ActiveAlarmUiState())
        val activeUiState: StateFlow<ActiveAlarmUiState> = _activeUiState.asStateFlow()

        @Volatile
        private var instance: AlarmService? = null

        fun notifyMissionStarted() {
            instance?.handleMissionStarted()
        }

        fun submitMissionInput(input: MissionInput) {
            instance?.handleMissionInput(input)
        }

        fun requestPenaltySnoozeMode(enable: Boolean) {
            instance?.handlePenaltySnoozeMode(enable)
        }

        fun executePenaltySnooze(typedPhrase: String): Boolean {
            return instance?.handlePenaltySnoozeSubmit(typedPhrase) ?: false
        }

        fun dismissSunrisePreAlarm() {
            instance?.stopCurrentSession("Рассвет T−15 завершён пользователем", AlarmState.SCHEDULED)
        }

        fun confirmAntiReturnAwake() {
            instance?.handleAntiReturnConfirmed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase.getInstance(applicationContext)
        repository = AlarmRepository(db.alarmDao())
        settingsRepository = SettingsRepository(applicationContext)
        scheduler = AlarmScheduler(applicationContext)
        audioController = AlarmAudioController(applicationContext)
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_ALARM
        val alarmId = intent?.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L) ?: -1L

        serviceScope.launch {
            when (action) {
                ACTION_CONFIRM_ANTI_RETURN -> {
                    handleAntiReturnConfirmed()
                }
                ACTION_START_ANTI_RETURN -> {
                    startAntiReturnVerification(alarmId)
                }
                ACTION_START_SUNRISE -> {
                    startAlarmOrSunriseSession(alarmId = alarmId, isSunrise = true)
                }
                else -> {
                    // Если сервис перезапущен системой после Process Death (intent == null),
                    // восстанавливаем активный alarmId из DataStore
                    val effectiveId = if (alarmId > 0L) {
                        alarmId
                    } else {
                        settingsRepository.getSnapshot().activeAlarmId
                    }
                    startAlarmOrSunriseSession(alarmId = effectiveId, isSunrise = false)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startAlarmOrSunriseSession(alarmId: Long, isSunrise: Boolean) {
        val alarm = (if (alarmId > 0L) repository.getAlarmById(alarmId) else null)
            ?: AlarmEntity(
                id = 999L,
                hour = 7,
                minute = 0,
                label = "Боевой тест Alarm Commander",
                escalationProfile = EscalationProfile.HARD.name,
                missionChainCsv = "MATH,STEPS",
                mathDifficulty = MathDifficulty.MEDIUM.name,
                targetSteps = 30,
                allowSnooze = true,
                sunrisePreAlarmEnabled = true,
                antiReturnEnabled = true
            )

        val started = escalationEngine.startSession(
            alarmId = alarm.id,
            profile = alarm.profileEnum,
            isSunrisePreAlarm = isSunrise
        )
        if (!started) return

        acquireWakeLock()
        startForegroundWithFullScreenNotification(alarm, isSunrise)

        // Перепланируем следующий цикл, если будильник периодический
        if (!isSunrise && alarm.id != 999L && alarm.daysOfWeek.isNotEmpty()) {
            scheduler.scheduleAlarm(alarm)
        }

        val missions = buildMissionList(alarm)
        val coordinator = MissionChainCoordinator(missions)
        val savedSettings = settingsRepository.getSnapshot()
        val initialProgress = if (savedSettings.activeAlarmId == alarm.id && savedSettings.activeMissionIndex > 0) {
            coordinator.setMissionIndex(savedSettings.activeMissionIndex)
        } else {
            coordinator.startCurrent()
        }
        missionCoordinator = coordinator

        val initialSnap = escalationEngine.evaluateTick()
        settingsRepository.persistActiveAlarmSession(
            alarmId = alarm.id,
            state = initialSnap.state,
            escalationStage = initialSnap.stage,
            missionIndex = coordinator.currentMissionIndex(),
            sessionStartEpochMs = System.currentTimeMillis()
        )

        repository.logEvent(
            alarmId = alarm.id,
            alarmLabel = alarm.label,
            eventType = if (isSunrise) "SUNRISE_STARTED" else "ALARM_RINGING_T0",
            state = initialSnap.state,
            details = if (isSunrise) {
                "Запущена фаза «Рассвет» (T−15 мин)"
            } else {
                "Сигнал T0 запущен (Профиль: ${alarm.profileEnum.name}, миссии: ${alarm.missionChainCsv})"
            }
        )

        // Если это фаза Рассвета и включён Webhook — вызываем умную лампу
        var backupMsg: String? = null
        if (isSunrise && alarm.backupWebhookEnabled) {
            val res = SmartHomeWebhookChannel().trigger(
                BackupPayload(
                    alarmId = alarm.id,
                    alarmLabel = alarm.label,
                    eventType = "SUNRISE_T_MINUS_15",
                    targetUrlOrAddress = savedSettings.sunriseWebhookUrl
                )
            )
            backupMsg = res.messageRu
            repository.logEvent(
                alarmId = alarm.id,
                alarmLabel = alarm.label,
                eventType = "SUNRISE_WEBHOOK",
                state = AlarmState.PRE_ALARM_SUNRISE,
                details = res.messageRu
            )
        }

        _activeUiState.value = ActiveAlarmUiState(
            isActive = true,
            alarm = alarm,
            escalation = initialSnap,
            currentMissionType = coordinator.currentMission().type,
            currentMissionIndex = coordinator.currentMissionIndex(),
            totalMissionsInChain = coordinator.totalMissionsCount(),
            missionPrompt = initialProgress.promptText,
            missionFeedback = initialProgress.feedbackMessage,
            missionProgressStep = initialProgress.currentStep,
            missionTotalSteps = initialProgress.totalSteps,
            isPenaltySnoozeMode = false,
            isAntiReturnPromptActive = false,
            lastBackupEventMessage = backupMsg
        )

        // Открываем Activity поверх экрана блокировки
        launchFullScreenActivity(alarm.id)

        startEscalationLoop(alarm)
    }

    private fun startEscalationLoop(alarm: AlarmEntity) {
        tickerJob?.cancel()
        var lastLoggedStage = 1
        var cutoffLogged = false
        val safetyAuditor = com.example.permissions.SafetyAndPowerAuditor(applicationContext)
        tickerJob = serviceScope.launch {
            while (isActive) {
                val isLowBattery = safetyAuditor.isCriticallyLowBattery()
                val snap = escalationEngine.evaluateTick(lowBatteryEcoMode = isLowBattery)

                if (snap.batterySafetyCutoffReached) {
                    audioController.stopAll()
                    releaseWakeLock()
                    if (!cutoffLogged) {
                        cutoffLogged = true
                        repository.logEvent(
                            alarmId = alarm.id,
                            alarmLabel = alarm.label,
                            eventType = "BATTERY_SAFE_AUTO_CUTOFF",
                            state = AlarmState.OVERDUE,
                            details = snap.statusDescriptionRu
                        )
                    }
                } else {
                    audioController.startOrUpdate(
                        stage = snap.stage,
                        volumePercent = snap.volumePercent,
                        dualTone = snap.dualToneActive,
                        vibrationCadenceLevel = snap.vibrationCadenceLevel
                    )
                }

                if (snap.stage > lastLoggedStage && snap.stage in 2..4) {
                    lastLoggedStage = snap.stage
                    repository.logEvent(
                        alarmId = alarm.id,
                        alarmLabel = alarm.label,
                        eventType = "ESCALATION_STAGE_${snap.stage}",
                        state = snap.state,
                        details = snap.statusDescriptionRu
                    )
                    settingsRepository.persistActiveAlarmSession(
                        alarmId = alarm.id,
                        state = snap.state,
                        escalationStage = snap.stage,
                        missionIndex = missionCoordinator?.currentMissionIndex() ?: 0,
                        sessionStartEpochMs = System.currentTimeMillis()
                    )
                }

                // Если наступила просрочка (OVERDUE) — вызываем резервные каналы один раз
                if (snap.overdueTriggered && !escalationEngine.isOverdueBackupDispatched()) {
                    escalationEngine.markOverdueDispatched()
                    dispatchOverdueBackupChannels(alarm)
                }

                _activeUiState.value = _activeUiState.value.copy(
                    escalation = snap
                )

                delay(1000L)
            }
        }
    }

    private suspend fun dispatchOverdueBackupChannels(alarm: AlarmEntity) {
        val settings = settingsRepository.getSnapshot()
        val messages = mutableListOf<String>()

        if (alarm.backupWebhookEnabled) {
            val webRes = SmartHomeWebhookChannel().trigger(
                BackupPayload(
                    alarmId = alarm.id,
                    alarmLabel = alarm.label,
                    eventType = "OVERDUE_ESCALATION",
                    targetUrlOrAddress = settings.escalationWebhookUrl
                )
            )
            messages.add(webRes.messageRu)
        }

        if (alarm.backupContactEnabled) {
            val socialRes = SocialContactChannel(applicationContext).trigger(
                BackupPayload(
                    alarmId = alarm.id,
                    alarmLabel = alarm.label,
                    eventType = "OVERDUE_ESCALATION",
                    targetUrlOrAddress = "",
                    contactName = settings.backupContactName,
                    contactPhone = settings.backupContactPhone
                )
            )
            messages.add(socialRes.messageRu)
        }

        val combinedMsg = messages.joinToString(" | ").ifBlank { "Резервная тревога активирована" }
        repository.logEvent(
            alarmId = alarm.id,
            alarmLabel = alarm.label,
            eventType = "OVERDUE_BACKUP_TRIGGERED",
            state = AlarmState.OVERDUE,
            details = combinedMsg
        )
        _activeUiState.value = _activeUiState.value.copy(
            lastBackupEventMessage = combinedMsg
        )
    }

    private fun handleMissionStarted() {
        val current = _activeUiState.value
        val alarm = current.alarm ?: return
        escalationEngine.onMissionStarted()
        val snap = escalationEngine.evaluateTick()
        _activeUiState.value = current.copy(escalation = snap)
        serviceScope.launch {
            repository.logEvent(
                alarmId = alarm.id,
                alarmLabel = alarm.label,
                eventType = "MISSION_STARTED",
                state = AlarmState.MISSION_IN_PROGRESS,
                details = "Начато выполнение миссии: ${current.currentMissionType?.titleRu}"
            )
        }
    }

    private fun handleMissionInput(input: MissionInput) {
        val coordinator = missionCoordinator ?: return
        val current = _activeUiState.value
        val alarm = current.alarm ?: return

        escalationEngine.onMissionUserInteraction()
        val (allFinished, result) = coordinator.submitInput(input)
        val snap = escalationEngine.evaluateTick()

        when (result) {
            is MissionResult.Progress -> {
                _activeUiState.value = current.copy(
                    escalation = snap,
                    currentMissionType = coordinator.currentMission().type,
                    currentMissionIndex = coordinator.currentMissionIndex(),
                    missionPrompt = result.promptText,
                    missionFeedback = result.feedbackMessage,
                    missionProgressStep = result.currentStep,
                    missionTotalSteps = result.totalSteps
                )
            }
            is MissionResult.Failed -> {
                _activeUiState.value = current.copy(
                    escalation = snap,
                    missionPrompt = result.currentPrompt,
                    missionFeedback = result.reason
                )
            }
            is MissionResult.Completed -> {
                if (allFinished) {
                    serviceScope.launch {
                        onAllMissionsCompleted(alarm, result.summaryMessage)
                    }
                }
            }
        }
    }

    private suspend fun onAllMissionsCompleted(alarm: AlarmEntity, summary: String) {
        // Если включён анти-возврат в кровать, планируем проверку через antiReturnDelayMinutes
        if (alarm.antiReturnEnabled) {
            val checkAtMs = scheduler.scheduleAntiReturnCheck(alarm.id, alarm.antiReturnDelayMinutes)
            settingsRepository.persistAntiReturnSession(alarm.id, checkAtMs)
            repository.logEvent(
                alarmId = alarm.id,
                alarmLabel = alarm.label,
                eventType = "ANTI_RETURN_SCHEDULED",
                state = AlarmState.DISMISSED,
                details = "Запланирован контроль анти-возврата через ${alarm.antiReturnDelayMinutes} мин"
            )
        }

        // Если будильник однократный (дни не выбраны), выключаем переключатель enabled
        if (alarm.id != 999L && alarm.daysOfWeek.isEmpty()) {
            repository.saveAlarm(alarm.copy(enabled = false, currentSnoozeCount = 0))
        }

        stopCurrentSession(
            reason = "Все миссии выполнены ($summary). Будильник штатно отключён.",
            finalState = AlarmState.DISMISSED
        )
    }

    private fun handlePenaltySnoozeMode(enable: Boolean) {
        escalationEngine.onMissionUserInteraction()
        _activeUiState.value = _activeUiState.value.copy(
            isPenaltySnoozeMode = enable,
            missionFeedback = if (enable) {
                "Для Snooze введите контрольную фразу без ошибок"
            } else {
                null
            }
        )
    }

    private fun handlePenaltySnoozeSubmit(typedPhrase: String): Boolean {
        val current = _activeUiState.value
        val alarm = current.alarm ?: return false
        if (!alarm.allowSnooze) return false

        val expectedRu = current.penaltyPhraseRequired.trim()
        val expectedEn = "I CONFIRM 5 MIN SNOOZE"
        val normalizedTyped = typedPhrase.trim()
        if (!normalizedTyped.equals(expectedRu, ignoreCase = true) &&
            !normalizedTyped.equals(expectedEn, ignoreCase = true)
        ) {
            _activeUiState.value = current.copy(
                missionFeedback = "Фраза введена неверно! Автопилот не пройдёт."
            )
            return false
        }

        serviceScope.launch {
            val snoozeTriggerMs = System.currentTimeMillis() + (alarm.snoozeDurationMinutes * 60_000L)
            scheduler.scheduleAlarm(alarm, customTriggerEpochMs = snoozeTriggerMs)
            stopCurrentSession(
                reason = "Выполнен штрафной Snooze на ${alarm.snoozeDurationMinutes} мин",
                finalState = AlarmState.SCHEDULED
            )
        }
        return true
    }

    /**
     * Запускает 60-секундное окно подтверждения «Анти-возврата в кровать».
     * Если пользователь не подтвердит бодрствование за 60 секунд — запускается боевая тревога T0!
     */
    private suspend fun startAntiReturnVerification(alarmId: Long) {
        val alarm = repository.getAlarmById(alarmId) ?: AlarmEntity(
            id = alarmId.coerceAtLeast(1L),
            hour = 7,
            minute = 0,
            label = "Контроль анти-возврата"
        )
        acquireWakeLock()
        escalationEngine.onEnterAntiReturnCheck(alarm.id)
        startForegroundWithFullScreenNotification(alarm, isSunrise = false)

        repository.logEvent(
            alarmId = alarm.id,
            alarmLabel = alarm.label,
            eventType = "ANTI_RETURN_CHECK_TRIGGERED",
            state = AlarmState.ANTI_RETURN_CHECK,
            details = "Запущена 60-секундная проверка бодрствования (Анти-возврат в кровать)"
        )

        audioController.startOrUpdate(
            stage = 1,
            volumePercent = 35,
            dualTone = false,
            vibrationCadenceLevel = 1
        )

        _activeUiState.value = ActiveAlarmUiState(
            isActive = true,
            alarm = alarm,
            isAntiReturnPromptActive = true,
            antiReturnSecondsRemaining = 60
        )

        launchFullScreenActivity(alarm.id)

        antiReturnCountdownJob?.cancel()
        antiReturnCountdownJob = serviceScope.launch {
            for (remaining in 60 downTo 1) {
                _activeUiState.value = _activeUiState.value.copy(
                    antiReturnSecondsRemaining = remaining
                )
                delay(1000L)
            }
            // Пользователь не ответил за 60 секунд — вернулся в кровать! Перезапускаем полную тревогу T0!
            repository.logEvent(
                alarmId = alarm.id,
                alarmLabel = alarm.label,
                eventType = "ANTI_RETURN_FAILED_REALARM",
                state = AlarmState.RINGING,
                details = "Нет подтверждения анти-возврата за 60 сек — повторный запуск боевой тревоги!"
            )
            startAlarmOrSunriseSession(alarm.id, isSunrise = false)
        }
    }

    private fun handleAntiReturnConfirmed() {
        antiReturnCountdownJob?.cancel()
        val alarm = _activeUiState.value.alarm
        serviceScope.launch {
            if (alarm != null) {
                scheduler.cancelAntiReturnCheck(alarm.id)
            }
            settingsRepository.clearAntiReturnSession()
            stopCurrentSession(
                reason = "Проверка анти-возврата успешно подтверждена: пользователь бодрствует.",
                finalState = AlarmState.DISMISSED
            )
        }
    }

    private fun stopCurrentSession(reason: String, finalState: AlarmState) {
        tickerJob?.cancel()
        antiReturnCountdownJob?.cancel()
        audioController.stopAll()
        escalationEngine.onDismissed()

        val alarm = _activeUiState.value.alarm
        _activeUiState.value = ActiveAlarmUiState(isActive = false)

        serviceScope.launch {
            settingsRepository.clearActiveAlarmSession()
            if (alarm != null) {
                repository.logEvent(
                    alarmId = alarm.id,
                    alarmLabel = alarm.label,
                    eventType = "SESSION_ENDED",
                    state = finalState,
                    details = reason
                )
            }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildMissionList(alarm: AlarmEntity): List<Mission> {
        return alarm.missionChain.map { type ->
            when (type) {
                MissionType.MATH -> MathMission(alarm.mathDifficultyEnum)
                MissionType.STEPS -> StepsMission(alarm.targetSteps)
                MissionType.QR -> QrMission(alarm.qrExpectedPayload)
                MissionType.NFC -> NfcMission(alarm.nfcExpectedTagId)
                MissionType.VOICE -> com.example.mission.VoicePhraseMission(alarm.voicePhraseExpected)
            }
        }.ifEmpty { listOf(MathMission(alarm.mathDifficultyEnum)) }
    }

    private fun startForegroundWithFullScreenNotification(alarm: AlarmEntity, isSunrise: Boolean) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            alarm.id.toInt(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isSunrise) {
            "Рассвет T−15 • ${alarm.label}"
        } else {
            "БОЕВАЯ ТРЕВОГА • ${alarm.label}"
        }
        val body = if (isSunrise) {
            "Мягкая подготовка к пробуждению"
        } else {
            "Выполните миссию (${alarm.missionChainCsv}), чтобы отключить сигнал"
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_ALARM,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID_ALARM, notification)
        }
    }

    private fun launchFullScreenActivity(alarmId: Long) {
        runCatching {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            startActivity(activityIntent)
        }
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alarmChannel = NotificationChannel(
                CHANNEL_ID_ALARM,
                getString(R.string.notif_channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_alarm_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                enableVibration(true)
            }

            val backupChannel = NotificationChannel(
                CHANNEL_ID_BACKUP,
                getString(R.string.notif_channel_backup_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_backup_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            nm.createNotificationChannel(alarmChannel)
            nm.createNotificationChannel(backupChannel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmCommander::AlarmRingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // Строгий лимит 10 минут удержания CPU для защиты батареи
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        instance = null
        tickerJob?.cancel()
        antiReturnCountdownJob?.cancel()
        audioController.stopAll()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
