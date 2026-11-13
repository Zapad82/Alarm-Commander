package com.example.alarm

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var tickerJob: Job? = null
    private var antiReturnCountdownJob: Job? = null
    private var screenUnlockReceiverRegistered: Boolean = false
    @Volatile
    private var isVoiceListeningMuted: Boolean = false
    @Volatile
    private var isCurrentSessionTestRun: Boolean = false
    @Volatile
    private var isSessionActive: Boolean = false
    @Volatile
    private var activeAlarmIdInMemory: Long = -1L
    @Volatile
    private var activeSessionStartMs: Long = 0L

    /**
     * Приёмник включения и разблокировки экрана во время активной тревоги:
     * если пользователь разблокировал экран или нажал кнопку питания, а окно будильника
     * было свёрнуто/перекрыто, немедленно выводит окно будильника на передний план
     * и обновляет уведомление в шторке.
     */
    private val screenAndUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if ((act == Intent.ACTION_USER_PRESENT || act == Intent.ACTION_SCREEN_ON) &&
                (isSessionActive || _activeUiState.value.isActive)
            ) {
                val currentAlarm = _activeUiState.value.alarm
                if (currentAlarm != null) {
                    val isSunrise = _activeUiState.value.escalation?.state == AlarmState.PRE_ALARM_SUNRISE
                    val stage = _activeUiState.value.escalation?.stage ?: 1
                    val vol = _activeUiState.value.escalation?.volumePercent ?: 50
                    postNotificationToShade(currentAlarm, isSunrise, stage, vol)
                    launchFullScreenActivity(currentAlarm.id)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID_ALARM = "alarm_commander_urgent_v2"
        const val CHANNEL_ID_BACKUP = "alarm_commander_backup_channel"
        const val NOTIFICATION_ID_ALARM = 7001

        const val ACTION_START_ALARM = "com.example.service.START_ALARM"
        const val ACTION_WATCHDOG_REVIVE = "com.example.service.WATCHDOG_REVIVE"
        const val ACTION_RESTORE_NOTIFICATION = "com.example.service.RESTORE_NOTIFICATION"
        const val ACTION_START_SUNRISE = "com.example.service.START_SUNRISE"
        const val ACTION_START_ANTI_RETURN = "com.example.service.START_ANTI_RETURN"
        const val ACTION_CONFIRM_ANTI_RETURN = "com.example.service.CONFIRM_ANTI_RETURN"

        private val _activeUiState = MutableStateFlow(ActiveAlarmUiState())
        val activeUiState: StateFlow<ActiveAlarmUiState> = _activeUiState.asStateFlow()

        @Volatile
        private var lastLaunchSignalEpochMs: Long = 0L

        @Volatile
        private var instance: AlarmService? = null

        fun isSessionCurrentlyActive(): Boolean {
            val now = System.currentTimeMillis()
            return _activeUiState.value.isActive ||
                instance?.isSessionActive == true ||
                (now - lastLaunchSignalEpochMs) in 0..15_000L
        }

        /**
         * Вызывается синхронно из AlarmReceiver или ViewModel до запуска корутин БД,
         * чтобы MainActivity при старте гарантированно знала, что будильник активен,
         * и не сбрасывала флаги показа поверх экрана блокировки.
         */
        fun markAlarmLaunchingFromReceiver(
            alarmId: Long,
            isSunrise: Boolean = false,
            isAntiReturn: Boolean = false
        ) {
            lastLaunchSignalEpochMs = System.currentTimeMillis()
            val cur = _activeUiState.value
            if (!cur.isActive) {
                val placeholder = AlarmEntity(
                    id = if (alarmId > 0L) alarmId else 999L,
                    hour = 7,
                    minute = 0,
                    label = if (isAntiReturn) "Контроль анти-возврата" else "Будильник Alarm Commander"
                )
                _activeUiState.value = ActiveAlarmUiState(
                    isActive = true,
                    alarm = placeholder,
                    isAntiReturnPromptActive = isAntiReturn,
                    antiReturnSecondsRemaining = if (isAntiReturn) 60 else 0
                )
            }
        }

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

        fun setVoiceListeningMute(muted: Boolean) {
            val svc = instance ?: return
            svc.isVoiceListeningMuted = muted
            if (muted) {
                svc.audioController.stopAll()
            }
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
        registerScreenUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_ALARM
        val alarmId = intent?.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L) ?: -1L
        val isTestRun = intent?.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, false) ?: false

        if (action == ACTION_RESTORE_NOTIFICATION) {
            val activeAlarm = _activeUiState.value.alarm
            if ((isSessionActive || _activeUiState.value.isActive) && activeAlarm != null) {
                val isSunrise = _activeUiState.value.escalation?.state == AlarmState.PRE_ALARM_SUNRISE
                val stage = _activeUiState.value.escalation?.stage ?: 1
                val vol = _activeUiState.value.escalation?.volumePercent ?: 50
                startForegroundWithFullScreenNotification(activeAlarm, isSunrise, stage, vol)
            }
            return START_STICKY
        }

        if (action != ACTION_CONFIRM_ANTI_RETURN) {
            lastLaunchSignalEpochMs = System.currentTimeMillis()
            acquireWakeLock()
            val immediateAlarm = _activeUiState.value.alarm ?: AlarmEntity(
                id = if (alarmId > 0L) alarmId else 999L,
                hour = 7,
                minute = 0,
                label = "Alarm Commander"
            )
            if (!_activeUiState.value.isActive) {
                _activeUiState.value = ActiveAlarmUiState(
                    isActive = true,
                    alarm = immediateAlarm,
                    isAntiReturnPromptActive = action == ACTION_START_ANTI_RETURN,
                    antiReturnSecondsRemaining = if (action == ACTION_START_ANTI_RETURN) 60 else 0
                )
            }
            // Синхронно поднимаем Foreground-уведомление в шторке в первую миллисекунду onStartCommand
            startForegroundWithFullScreenNotification(
                alarm = immediateAlarm,
                isSunrise = action == ACTION_START_SUNRISE,
                stage = _activeUiState.value.escalation?.stage ?: 1,
                volumePercent = _activeUiState.value.escalation?.volumePercent ?: 50
            )
        }

        serviceScope.launch {
            when (action) {
                ACTION_CONFIRM_ANTI_RETURN -> {
                    handleAntiReturnConfirmed()
                }
                ACTION_START_ANTI_RETURN -> {
                    startAntiReturnVerification(alarmId)
                }
                ACTION_START_SUNRISE -> {
                    startAlarmOrSunriseSession(
                        alarmId = alarmId,
                        isSunrise = true,
                        isTestRun = isTestRun
                    )
                }
                else -> {
                    val isWatchdog = action == ACTION_WATCHDOG_REVIVE
                    if (alarmId > 0L && !isWatchdog) {
                        startAlarmOrSunriseSession(
                            alarmId = alarmId,
                            isSunrise = false,
                            isTestRun = isTestRun,
                            isWatchdogRevive = false
                        )
                    } else {
                        // Восстановление после выгрузки приложения («Закрыть все приложения») или по сигналу Watchdog
                        val snap = settingsRepository.getSnapshot()
                        val targetId = if (alarmId > 0L) alarmId else snap.activeAlarmId
                        val ageMs = System.currentTimeMillis() - snap.activeSessionStartEpochMs
                        if (targetId > 0L &&
                            ageMs in 0..600_000L &&
                            snap.activeAlarmState in setOf(
                                AlarmState.RINGING,
                                AlarmState.ESCALATING,
                                AlarmState.MISSION_IN_PROGRESS,
                                AlarmState.OVERDUE
                            )
                        ) {
                            startAlarmOrSunriseSession(
                                alarmId = targetId,
                                isSunrise = false,
                                isTestRun = snap.activeIsTestRun || isTestRun,
                                isWatchdogRevive = true
                            )
                        } else if (alarmId > 0L && !isWatchdog) {
                            startAlarmOrSunriseSession(
                                alarmId = alarmId,
                                isSunrise = false,
                                isTestRun = isTestRun,
                                isWatchdogRevive = false
                            )
                        } else {
                            scheduler.cancelWatchdogRevive()
                            settingsRepository.clearActiveAlarmSession()
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startAlarmOrSunriseSession(
        alarmId: Long,
        isSunrise: Boolean,
        isTestRun: Boolean = false,
        isWatchdogRevive: Boolean = false
    ) {
        val savedSettings = settingsRepository.getSnapshot()
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
                antiReturnEnabled = true,
                signalMode = savedSettings.defaultSignalMode,
                soundSource = savedSettings.defaultSoundSource,
                customRingtoneUri = savedSettings.customRingtoneUri,
                customRingtoneTitle = savedSettings.customRingtoneTitle
            )

        val nowMs = System.currentTimeMillis()
        val isContinuingAfterKill = isWatchdogRevive &&
            savedSettings.activeAlarmId == alarm.id &&
            savedSettings.activeSessionStartEpochMs > 0L &&
            (nowMs - savedSettings.activeSessionStartEpochMs) in 0..600_000L

        val effectiveStartMs = if (isContinuingAfterKill) {
            savedSettings.activeSessionStartEpochMs
        } else {
            nowMs
        }

        val started = escalationEngine.startSession(
            alarmId = alarm.id,
            profile = alarm.profileEnum,
            nowEpochMs = effectiveStartMs,
            isSunrisePreAlarm = isSunrise
        )
        if (!started) {
            // Если сервис уже работает в памяти, а пользователь закрыл окно будильника через «Недавние» —
            // немедленно возвращаем уведомление и разворачиваем экран будильника обратно!
            if (isSessionActive) {
                startForegroundWithFullScreenNotification(alarm, isSunrise)
                launchFullScreenActivity(alarm.id)
                if (!isSunrise) {
                    scheduler.scheduleWatchdogRevive(alarm.id, isCurrentSessionTestRun, delayMs = 4_000L)
                }
            }
            return
        }

        isCurrentSessionTestRun = isTestRun || alarm.id == 999L
        isSessionActive = true
        activeAlarmIdInMemory = alarm.id
        activeSessionStartMs = effectiveStartMs

        scheduler.cancelAntiReturnCheck(alarm.id)
        settingsRepository.clearAntiReturnSession()

        acquireWakeLock()
        startForegroundWithFullScreenNotification(alarm, isSunrise)

        // Перепланируем следующий цикл, если это реальный периодический будильник (не ручной тест и не перезапуск Watchdog)
        if (!isSunrise && !isCurrentSessionTestRun && !isContinuingAfterKill && alarm.id != 999L && alarm.daysOfWeek.isNotEmpty()) {
            scheduler.scheduleAlarm(alarm)
        }

        val missions = buildMissionList(alarm)
        val coordinator = MissionChainCoordinator(
            missions = missions,
            fallbackMathDifficulty = alarm.mathDifficultyEnum
        )
        val initialProgress = if (isContinuingAfterKill && savedSettings.activeMissionIndex > 0) {
            coordinator.setMissionIndex(savedSettings.activeMissionIndex)
        } else {
            coordinator.startCurrent()
        }
        missionCoordinator = coordinator

        val initialSnap = escalationEngine.evaluateTick()
        if (!isSunrise) {
            settingsRepository.persistActiveAlarmSession(
                alarmId = alarm.id,
                state = initialSnap.state,
                escalationStage = initialSnap.stage,
                missionIndex = coordinator.currentMissionIndex(),
                sessionStartEpochMs = activeSessionStartMs,
                isTestRun = isCurrentSessionTestRun
            )
            // Взводим сторожевой таймер (Watchdog) в AlarmManager для автозапуска при «Закрыть все приложения»
            scheduler.scheduleWatchdogRevive(alarm.id, isCurrentSessionTestRun, delayMs = 4_000L)
        }

        repository.logEvent(
            alarmId = alarm.id,
            alarmLabel = alarm.label,
            eventType = when {
                isSunrise -> "SUNRISE_STARTED"
                isContinuingAfterKill -> "ALARM_WATCHDOG_REVIVED"
                else -> "ALARM_RINGING_T0"
            },
            state = initialSnap.state,
            details = when {
                isSunrise -> "Запущена фаза «Рассвет» (T−15 мин)"
                isContinuingAfterKill -> "Автозапуск будильника после выгрузки из памяти (стадия ${initialSnap.stage})"
                else -> "Сигнал T0 запущен (Профиль: ${alarm.profileEnum.name}, миссии: ${alarm.missionChainCsv})"
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
                    targetUrlOrAddress = savedSettings.sunriseWebhookUrl,
                    authBearerToken = savedSettings.webhookAuthToken
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
        var watchdogTick = 0
        val safetyAuditor = com.example.permissions.SafetyAndPowerAuditor(applicationContext)
        tickerJob = serviceScope.launch {
            while (isActive) {
                val isLowBattery = safetyAuditor.isCriticallyLowBattery()
                val snap = escalationEngine.evaluateTick(lowBatteryEcoMode = isLowBattery)

                if (snap.batterySafetyCutoffReached) {
                    isSessionActive = false
                    scheduler.cancelWatchdogRevive()
                    settingsRepository.clearActiveAlarmSession()
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
                    if (isSessionActive && snap.state != AlarmState.PRE_ALARM_SUNRISE && watchdogTick % 3 == 0) {
                        // Каждые 3 секунды продлеваем сторожевой таймер в AlarmManager,
                        // чтобы при закрытии приложения через «Закрыть все» оно перезапустилось через ~3–4 сек
                        scheduler.scheduleWatchdogRevive(alarm.id, isCurrentSessionTestRun, delayMs = 4_500L)
                    }
                    if (isSessionActive && watchdogTick % 2 == 0) {
                        // Поддерживаем постоянное уведомление в шторке Android с актуальной стадией и кнопкой возврата в окно миссии
                        postNotificationToShade(
                            alarm = alarm,
                            isSunrise = snap.state == AlarmState.PRE_ALARM_SUNRISE,
                            stage = snap.stage,
                            volumePercent = snap.volumePercent
                        )
                    }
                    watchdogTick++

                    if (isVoiceListeningMuted) {
                        // Временно приглушаем динамик и вибро, пока активен микрофон распознавания речи
                        audioController.stopAll()
                    } else {
                        val settingsSnap = settingsRepository.getSnapshot()
                        val effectiveUri = alarm.customRingtoneUri.ifBlank { settingsSnap.customRingtoneUri }
                        audioController.startOrUpdate(
                            stage = snap.stage,
                            volumePercent = snap.volumePercent,
                            dualTone = snap.dualToneActive,
                            vibrationCadenceLevel = snap.vibrationCadenceLevel,
                            signalMode = alarm.signalModeEnum,
                            soundSource = alarm.soundSourceEnum,
                            customRingtoneUri = effectiveUri
                        )
                    }
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
                    if (isSessionActive && snap.state != AlarmState.PRE_ALARM_SUNRISE) {
                        settingsRepository.persistActiveAlarmSession(
                            alarmId = alarm.id,
                            state = snap.state,
                            escalationStage = snap.stage,
                            missionIndex = missionCoordinator?.currentMissionIndex() ?: 0,
                            sessionStartEpochMs = activeSessionStartMs,
                            isTestRun = isCurrentSessionTestRun
                        )
                    }
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
                    targetUrlOrAddress = settings.escalationWebhookUrl,
                    authBearerToken = settings.webhookAuthToken
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
                serviceScope.launch {
                    settingsRepository.persistActiveAlarmSession(
                        alarmId = alarm.id,
                        state = snap.state,
                        escalationStage = snap.stage,
                        missionIndex = coordinator.currentMissionIndex(),
                        sessionStartEpochMs = activeSessionStartMs,
                        isTestRun = isCurrentSessionTestRun
                    )
                }
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
        val wasTestRun = isCurrentSessionTestRun || alarm.id == 999L
        // Если включён анти-возврат в кровать и это НЕ ручной тестовый запуск,
        // планируем проверку через antiReturnDelayMinutes
        if (alarm.antiReturnEnabled && !wasTestRun) {
            val checkAtMs = scheduler.scheduleAntiReturnCheck(alarm.id, alarm.antiReturnDelayMinutes)
            settingsRepository.persistAntiReturnSession(alarm.id, checkAtMs)
            repository.logEvent(
                alarmId = alarm.id,
                alarmLabel = alarm.label,
                eventType = "ANTI_RETURN_SCHEDULED",
                state = AlarmState.DISMISSED,
                details = "Запланирован контроль анти-возврата через ${alarm.antiReturnDelayMinutes} мин"
            )
        } else {
            scheduler.cancelAntiReturnCheck(alarm.id)
            settingsRepository.clearAntiReturnSession()
        }

        // Если реальный будильник однократный (дни не выбраны), выключаем переключатель enabled
        if (!wasTestRun && alarm.daysOfWeek.isEmpty()) {
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

        val settingsSnap = settingsRepository.getSnapshot()
        settingsRepository.clearAntiReturnSession()
        val effectiveUri = alarm.customRingtoneUri.ifBlank { settingsSnap.customRingtoneUri }
        audioController.startOrUpdate(
            stage = 1,
            volumePercent = 35,
            dualTone = false,
            vibrationCadenceLevel = 1,
            signalMode = alarm.signalModeEnum,
            soundSource = alarm.soundSourceEnum,
            customRingtoneUri = effectiveUri
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
        isSessionActive = false
        activeAlarmIdInMemory = -1L
        lastLaunchSignalEpochMs = 0L
        isVoiceListeningMuted = false
        scheduler.cancelWatchdogRevive()
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
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID_ALARM)
            }
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

    private fun buildAlarmNotification(
        alarm: AlarmEntity,
        isSunrise: Boolean,
        stage: Int = 1,
        volumePercent: Int = 50
    ): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmScheduler.EXTRA_LAUNCH_FROM_ALARM, true)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            alarm.id.toInt().coerceAtLeast(1),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_RESTORE_ALARM_NOTIFICATION
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
        }
        val restorePending = PendingIntent.getBroadcast(
            this,
            55099,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isSunrise) {
            "Рассвет T−15 • ${alarm.label}"
        } else {
            "БОЕВАЯ ТРЕВОГА (ЭТАП $stage/4) • ${alarm.label}"
        }
        val body = if (isSunrise) {
            "Мягкая подготовка к пробуждению • Нажмите, чтобы открыть"
        } else {
            "Нажмите сюда, чтобы открыть окно будильника и пройти миссию (${alarm.missionChainCsv}) • Громкость $volumePercent%"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(restorePending)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .addAction(
                android.R.drawable.ic_lock_idle_alarm,
                "ОТКРЫТЬ ОКНО БУДИЛЬНИКА",
                fullScreenPending
            )
            .build()
    }

    private fun startForegroundWithFullScreenNotification(
        alarm: AlarmEntity,
        isSunrise: Boolean,
        stage: Int = 1,
        volumePercent: Int = 50
    ) {
        val notification = buildAlarmNotification(alarm, isSunrise, stage, volumePercent)
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

    private fun postNotificationToShade(
        alarm: AlarmEntity,
        isSunrise: Boolean,
        stage: Int = 1,
        volumePercent: Int = 50
    ) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = buildAlarmNotification(alarm, isSunrise, stage, volumePercent)
            nm.notify(NOTIFICATION_ID_ALARM, notification)
        }
    }

    private fun launchFullScreenActivity(alarmId: Long) {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_LAUNCH_FROM_ALARM, true)
        }

        // 1. Попытка запуска через PendingIntent с явным разрешением фонового старта для Android 14–16 (API 34+)
        runCatching {
            val pending = PendingIntent.getActivity(
                this,
                (60000 + (alarmId % 1000)).toInt(),
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pending.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                pending.send()
            }
        }

        // 2. Прямой вызов startActivity для мгновенного вывода окна поверх блокировки
        runCatching {
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
                enableVibration(false)
                setSound(null, null)
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

    private fun registerScreenUnlockReceiver() {
        if (screenUnlockReceiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this,
                screenAndUnlockReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            screenUnlockReceiverRegistered = true
        }
    }

    private fun unregisterScreenUnlockReceiver() {
        if (!screenUnlockReceiverRegistered) return
        runCatching {
            unregisterReceiver(screenAndUnlockReceiver)
        }
        screenUnlockReceiverRegistered = false
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AlarmCommander::AlarmRingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // Строгий лимит 10 минут удержания CPU для защиты батареи
            }
        }
        if (screenWakeLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "AlarmCommander::AlarmScreenWakeLock"
            ).apply {
                setReferenceCounted(false)
                runCatching { acquire(20_000L) }
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
        runCatching {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
        }
        screenWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Если пользователь смахнул приложение или нажал «Закрыть все приложения» во время звонка:
        // взводим немедленный перезапуск через AlarmManager.setAlarmClock (1.5 сек) и возвращаем экран тревоги!
        if (isSessionActive && activeAlarmIdInMemory > 0L) {
            runCatching {
                scheduler.scheduleWatchdogRevive(
                    alarmId = activeAlarmIdInMemory,
                    isTestRun = isCurrentSessionTestRun,
                    delayMs = 1_500L
                )
                _activeUiState.value.alarm?.let { activeAlarm ->
                    startForegroundWithFullScreenNotification(activeAlarm, isSunrise = false)
                }
                launchFullScreenActivity(activeAlarmIdInMemory)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val shouldRevive = isSessionActive && activeAlarmIdInMemory > 0L
        val reviveAlarmId = activeAlarmIdInMemory
        val reviveTestFlag = isCurrentSessionTestRun

        unregisterScreenUnlockReceiver()
        instance = null
        tickerJob?.cancel()
        antiReturnCountdownJob?.cancel()
        audioController.stopAll()
        releaseWakeLock()
        serviceScope.cancel()

        if (shouldRevive) {
            runCatching {
                scheduler.scheduleWatchdogRevive(
                    alarmId = reviveAlarmId,
                    isTestRun = reviveTestFlag,
                    delayMs = 1_500L
                )
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
