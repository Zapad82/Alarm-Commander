package com.example.escalation

import com.example.data.AlarmState
import com.example.data.EscalationProfile

/**
 * Снимок текущего уровня эскалации будильника.
 */
data class EscalationSnapshot(
    val alarmId: Long,
    val state: AlarmState,
    val profile: EscalationProfile,
    val stage: Int, // 0 = Sunrise/Idle, 1 = Stage 1 (T0), 2 = Stage 2, 3 = Stage 3 (Max), 4 = Overdue Backup
    val elapsedSeconds: Int,
    val volumePercent: Int,
    val vibrationCadenceLevel: Int, // 0..3
    val dualToneActive: Boolean,
    val safeScreenPulseActive: Boolean, // Плавная смена фона ≤ 1 Гц (без стробоскопа!)
    val missionAttenuated: Boolean,
    val overdueTriggered: Boolean,
    val statusDescriptionRu: String
)

/**
 * Движок эскалации сигнала и машина состояний будильника.
 * Обеспечивает:
 * - Защиту от двойного запуска одного или конкурирующего будильника.
 * - Расчёт уровня громкости, вибрации и второго тона по времени.
 * - Временное приглушение звука (attenuation до 25%) во время активного ввода миссии.
 * - Автоматический возврат на полную мощность, если пользователь бросил телефон во время миссии.
 * - Переход в состояние OVERDUE с вызовом резервных каналов.
 */
class EscalationEngine {

    private var activeAlarmId: Long? = null
    private var currentState: AlarmState = AlarmState.SCHEDULED
    private var activeProfile: EscalationProfile = EscalationProfile.HARD
    private var startEpochMs: Long = 0L
    private var lastMissionInteractionEpochMs: Long = 0L
    private var overdueBackupDispatched: Boolean = false

    companion object {
        /** Время (в секундах), на которое звук приглушается при активном вводе миссии */
        const val MISSION_ATTENUATION_WINDOW_SEC = 25
        const val ATTENUATED_VOLUME_PERCENT = 25
    }

    /**
     * Защита от повторного/двойного запуска:
     * возвращает false, если этот же будильник уже звенит или проходит миссию.
     */
    @Synchronized
    fun startSession(
        alarmId: Long,
        profile: EscalationProfile,
        nowEpochMs: Long = System.currentTimeMillis(),
        isSunrisePreAlarm: Boolean = false
    ): Boolean {
        val alreadyRunning = activeAlarmId == alarmId &&
            (currentState == AlarmState.RINGING ||
                currentState == AlarmState.ESCALATING ||
                currentState == AlarmState.MISSION_IN_PROGRESS ||
                (isSunrisePreAlarm && currentState == AlarmState.PRE_ALARM_SUNRISE))

        if (alreadyRunning) {
            return false
        }

        activeAlarmId = alarmId
        activeProfile = profile
        startEpochMs = nowEpochMs
        lastMissionInteractionEpochMs = 0L
        overdueBackupDispatched = false
        currentState = if (isSunrisePreAlarm) {
            AlarmState.PRE_ALARM_SUNRISE
        } else {
            AlarmState.RINGING
        }
        return true
    }

    @Synchronized
    fun onMissionStarted(nowEpochMs: Long = System.currentTimeMillis()) {
        if (activeAlarmId == null) return
        currentState = AlarmState.MISSION_IN_PROGRESS
        lastMissionInteractionEpochMs = nowEpochMs
    }

    @Synchronized
    fun onMissionUserInteraction(nowEpochMs: Long = System.currentTimeMillis()) {
        if (activeAlarmId == null) return
        currentState = AlarmState.MISSION_IN_PROGRESS
        lastMissionInteractionEpochMs = nowEpochMs
    }

    @Synchronized
    fun onDismissed(): AlarmState {
        currentState = AlarmState.DISMISSED
        activeAlarmId = null
        return currentState
    }

    @Synchronized
    fun onEnterAntiReturnCheck(alarmId: Long): AlarmState {
        activeAlarmId = alarmId
        currentState = AlarmState.ANTI_RETURN_CHECK
        return currentState
    }

    @Synchronized
    fun onFailed(): AlarmState {
        currentState = AlarmState.FAILED
        activeAlarmId = null
        return currentState
    }

    @Synchronized
    fun markOverdueDispatched() {
        overdueBackupDispatched = true
    }

    @Synchronized
    fun isOverdueBackupDispatched(): Boolean = overdueBackupDispatched

    @Synchronized
    fun getActiveAlarmId(): Long? = activeAlarmId

    @Synchronized
    fun getCurrentState(): AlarmState = currentState

    /**
     * Вычисляет текущее состояние эскалации по прошедшему времени.
     * Детерминированный метод (удобен для unit-тестирования).
     */
    @Synchronized
    fun evaluateTick(nowEpochMs: Long = System.currentTimeMillis()): EscalationSnapshot {
        val id = activeAlarmId ?: -1L
        if (currentState == AlarmState.PRE_ALARM_SUNRISE) {
            val elapsed = ((nowEpochMs - startEpochMs).coerceAtLeast(0L) / 1000L).toInt()
            return EscalationSnapshot(
                alarmId = id,
                state = AlarmState.PRE_ALARM_SUNRISE,
                profile = activeProfile,
                stage = 0,
                elapsedSeconds = elapsed,
                volumePercent = 10,
                vibrationCadenceLevel = 0,
                dualToneActive = false,
                safeScreenPulseActive = false,
                missionAttenuated = false,
                overdueTriggered = false,
                statusDescriptionRu = "Фаза «Рассвет» (T−15): мягкая подсветка экрана и webhook умной лампы"
            )
        }

        val elapsedSec = ((nowEpochMs - startEpochMs).coerceAtLeast(0L) / 1000L).toInt()
        val secSinceInteraction = if (lastMissionInteractionEpochMs > 0L) {
            ((nowEpochMs - lastMissionInteractionEpochMs).coerceAtLeast(0L) / 1000L).toInt()
        } else {
            Int.MAX_VALUE
        }

        val isMissionActiveRecently = currentState == AlarmState.MISSION_IN_PROGRESS &&
            secSinceInteraction <= MISSION_ATTENUATION_WINDOW_SEC

        // Если пользователь начал миссию, но бездействует дольше MISSION_ATTENUATION_WINDOW_SEC —
        // возвращаем состояние ESCALATING и полную громкость
        if (currentState == AlarmState.MISSION_IN_PROGRESS && !isMissionActiveRecently) {
            currentState = AlarmState.ESCALATING
        }

        val isOverdue = elapsedSec >= activeProfile.overdueTimeoutSec
        if (isOverdue && currentState != AlarmState.MISSION_IN_PROGRESS) {
            currentState = AlarmState.OVERDUE
        } else if (!isOverdue && currentState == AlarmState.RINGING && elapsedSec >= activeProfile.stage2DelaySec) {
            currentState = AlarmState.ESCALATING
        }

        val stage = when {
            isOverdue -> 4
            elapsedSec >= activeProfile.stage3DelaySec -> 3
            elapsedSec >= activeProfile.stage2DelaySec -> 2
            else -> 1
        }

        val rawVolume = when (stage) {
            1 -> activeProfile.initialVolumePercent
            2 -> when (activeProfile) {
                EscalationProfile.GENTLE -> 55
                EscalationProfile.HARD -> 80
                EscalationProfile.EXTREME_SAFE -> 95
            }
            3, 4 -> when (activeProfile) {
                EscalationProfile.GENTLE -> 85
                EscalationProfile.HARD -> 100
                EscalationProfile.EXTREME_SAFE -> 100
            }
            else -> activeProfile.initialVolumePercent
        }

        val effectiveVolume = if (isMissionActiveRecently) {
            rawVolume.coerceAtMost(ATTENUATED_VOLUME_PERCENT)
        } else {
            rawVolume
        }

        val vibrationLevel = when {
            isMissionActiveRecently -> 0
            stage >= 3 -> 3
            stage == 2 -> 2
            else -> 1
        }

        val statusDesc = when {
            isMissionActiveRecently ->
                "Миссия активна: сигнал временно приглушён (${MISSION_ATTENUATION_WINDOW_SEC - secSinceInteraction}с до возврата мощности)"
            stage == 4 ->
                "КРИТИЧЕСКАЯ ПРОСРОЧКА (Уровень 4): макс. звук + вызов резервных каналов!"
            stage == 3 ->
                "Эскалация Уровень 3: макс. громкость ($effectiveVolume%), двойной тон и тактический виброимпульс"
            stage == 2 ->
                "Эскалация Уровень 2: усиление звука ($effectiveVolume%) и подключение второго генератора"
            else ->
                "Уровень 1 (Старт T0): базовая вибрация и нарастающий тон ($effectiveVolume%)"
        }

        return EscalationSnapshot(
            alarmId = id,
            state = currentState,
            profile = activeProfile,
            stage = stage,
            elapsedSeconds = elapsedSec,
            volumePercent = effectiveVolume,
            vibrationCadenceLevel = vibrationLevel,
            dualToneActive = stage >= 2 && !isMissionActiveRecently,
            safeScreenPulseActive = stage >= 3 && !isMissionActiveRecently,
            missionAttenuated = isMissionActiveRecently,
            overdueTriggered = isOverdue,
            statusDescriptionRu = statusDesc
        )
    }
}
