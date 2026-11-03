package com.example

import com.example.alarm.AlarmScheduler
import com.example.data.AlarmState
import com.example.data.EscalationProfile
import com.example.data.MathDifficulty
import com.example.escalation.EscalationEngine
import com.example.mission.MathMission
import com.example.mission.MissionChainCoordinator
import com.example.mission.MissionInput
import com.example.mission.MissionResult
import com.example.mission.NfcMission
import com.example.mission.QrMission
import com.example.mission.StepsMission
import com.example.mission.VoicePhraseMission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit-тесты для EscalationEngine, MissionValidator (Math, Steps, QR, NFC, Voice),
 * MissionChainCoordinator и расчёта времени будильника.
 */
class ExampleUnitTest {

    @Test
    fun escalationEngine_preventsDuplicateTriggerForSameAlarm() {
        val engine = EscalationEngine()
        val t0 = 1_700_000_000_000L

        val firstStart = engine.startSession(alarmId = 42L, profile = EscalationProfile.HARD, nowEpochMs = t0)
        val duplicateStart = engine.startSession(alarmId = 42L, profile = EscalationProfile.HARD, nowEpochMs = t0 + 1000L)

        assertTrue("Первый запуск должен быть успешным", firstStart)
        assertFalse("Повторный запуск активного будильника должен блокироваться", duplicateStart)
    }

    @Test
    fun escalationEngine_progressesThroughStagesAndOverdueCorrectly() {
        val engine = EscalationEngine()
        val t0 = 1_700_000_000_000L
        engine.startSession(alarmId = 7L, profile = EscalationProfile.HARD, nowEpochMs = t0)

        // Stage 1 (0 сек)
        val snapStage1 = engine.evaluateTick(t0 + 5_000L)
        assertEquals(1, snapStage1.stage)
        assertEquals(45, snapStage1.volumePercent)
        assertEquals(AlarmState.RINGING, snapStage1.state)
        assertFalse(snapStage1.dualToneActive)

        // Stage 2 (30 сек для профиля HARD)
        val snapStage2 = engine.evaluateTick(t0 + 35_000L)
        assertEquals(2, snapStage2.stage)
        assertEquals(80, snapStage2.volumePercent)
        assertEquals(AlarmState.ESCALATING, snapStage2.state)
        assertTrue(snapStage2.dualToneActive)

        // Stage 3 (60 сек для профиля HARD)
        val snapStage3 = engine.evaluateTick(t0 + 65_000L)
        assertEquals(3, snapStage3.stage)
        assertEquals(100, snapStage3.volumePercent)
        assertTrue(snapStage3.safeScreenPulseActive)

        // Stage 4 Overdue (180 сек для профиля HARD)
        val snapOverdue = engine.evaluateTick(t0 + 185_000L)
        assertEquals(4, snapOverdue.stage)
        assertEquals(AlarmState.OVERDUE, snapOverdue.state)
        assertTrue(snapOverdue.overdueTriggered)
    }

    @Test
    fun escalationEngine_attenuatesDuringActiveMissionAndRestoresVolumeWhenIdle() {
        val engine = EscalationEngine()
        val t0 = 1_700_000_000_000L
        engine.startSession(alarmId = 10L, profile = EscalationProfile.EXTREME_SAFE, nowEpochMs = t0)

        val beforeMission = engine.evaluateTick(t0 + 45_000L)
        assertEquals(100, beforeMission.volumePercent)

        engine.onMissionStarted(t0 + 46_000L)
        val duringMission = engine.evaluateTick(t0 + 55_000L)
        assertEquals(AlarmState.MISSION_IN_PROGRESS, duringMission.state)
        assertTrue(duringMission.missionAttenuated)
        assertEquals(EscalationEngine.ATTENUATED_VOLUME_PERCENT, duringMission.volumePercent)

        val afterIdleTimeout = engine.evaluateTick(t0 + 46_000L + 30_000L)
        assertFalse(afterIdleTimeout.missionAttenuated)
        assertEquals(100, afterIdleTimeout.volumePercent)
        assertEquals(AlarmState.ESCALATING, afterIdleTimeout.state)
    }

    @Test
    fun mathMission_validatesCorrectAndRejectsWrongAnswers() {
        val mission = MathMission(difficulty = MathDifficulty.EASY, random = Random(12345))
        mission.start()

        for (step in 1..3) {
            val problem = mission.getCurrentProblem()
            val wrongRes = mission.validate(MissionInput.MathAnswer(problem.expectedAnswer + 999))
            assertTrue(wrongRes is MissionResult.Failed)

            val rightRes = mission.validate(MissionInput.MathAnswer(problem.expectedAnswer))
            if (step < 3) {
                assertTrue(rightRes is MissionResult.Progress)
            } else {
                assertTrue(rightRes is MissionResult.Completed)
            }
        }
    }

    @Test
    fun stepsQrNfcAndVoiceMissions_validateCorrectly() {
        val stepsMission = StepsMission(targetSteps = 50)
        stepsMission.start()
        val stepProgress = stepsMission.validate(MissionInput.StepCountUpdate(25))
        assertTrue(stepProgress is MissionResult.Progress)
        val stepDone = stepsMission.validate(MissionInput.StepCountUpdate(50))
        assertTrue(stepDone is MissionResult.Completed)

        val qrMission = QrMission(expectedPayload = "ALARM-COMMANDER-BATHROOM-01")
        qrMission.start()
        assertTrue(qrMission.validate(MissionInput.QrPayloadScanned("WRONG-CODE")) is MissionResult.Failed)
        assertTrue(
            qrMission.validate(MissionInput.QrPayloadScanned("alarm-commander-bathroom-01")) is MissionResult.Completed
        )

        val nfcMission = NfcMission(expectedTagId = "04A1B2C3")
        nfcMission.start()
        assertTrue(nfcMission.validate(MissionInput.NfcTagScanned("0000")) is MissionResult.Failed)
        assertTrue(nfcMission.validate(MissionInput.NfcTagScanned("04a1b2c3")) is MissionResult.Completed)

        val voiceMission = VoicePhraseMission(expectedPhrase = "Я полностью проснулся и готов к действию")
        voiceMission.start()
        assertTrue(voiceMission.validate(MissionInput.VoicePhraseSpoken("ещё пять минут")) is MissionResult.Failed)
        assertTrue(
            voiceMission.validate(
                MissionInput.VoicePhraseSpoken("Я полностью проснулся и готов к действию!")
            ) is MissionResult.Completed
        )
    }

    @Test
    fun missionChainCoordinator_advancesAcrossMultipleMissionsSequentially() {
        val qrMission = QrMission(expectedPayload = "BATHROOM-QR")
        val stepsMission = StepsMission(targetSteps = 30)
        val coordinator = MissionChainCoordinator(listOf(qrMission, stepsMission))

        coordinator.startCurrent()
        assertEquals(0, coordinator.currentMissionIndex())

        val (finishedAfterFirst, firstResult) = coordinator.submitInput(
            MissionInput.QrPayloadScanned("BATHROOM-QR")
        )
        assertFalse(finishedAfterFirst)
        assertTrue(firstResult is MissionResult.Progress)
        assertEquals(1, coordinator.currentMissionIndex())

        val (finishedAfterSecond, secondResult) = coordinator.submitInput(
            MissionInput.StepCountUpdate(30)
        )
        assertTrue(finishedAfterSecond)
        assertTrue(secondResult is MissionResult.Completed)
    }

    @Test
    fun alarmScheduler_computesFutureTriggerEpochMs() {
        val now = 1_700_000_000_000L
        val next = AlarmScheduler.computeNextTriggerEpochMs(
            hour = 7,
            minute = 30,
            daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7),
            nowEpochMs = now
        )
        assertTrue("Следующее срабатывание должно быть строго в будущем", next > now)
    }
}
