package com.example

import com.example.alarm.AlarmScheduler
import com.example.backup.BackupPayload
import com.example.backup.WebhookSecurityValidator
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
 * Unit-тесты для EscalationEngine (включая Eco-режим и защиту от разряда батареи),
 * WebhookSecurityValidator, MissionValidator (Math, Steps, QR, NFC, Voice),
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

        val snapStage1 = engine.evaluateTick(t0 + 5_000L)
        assertEquals(1, snapStage1.stage)
        assertEquals(45, snapStage1.volumePercent)
        assertEquals(AlarmState.RINGING, snapStage1.state)
        assertFalse(snapStage1.dualToneActive)

        val snapStage2 = engine.evaluateTick(t0 + 35_000L)
        assertEquals(2, snapStage2.stage)
        assertEquals(80, snapStage2.volumePercent)
        assertEquals(AlarmState.ESCALATING, snapStage2.state)
        assertTrue(snapStage2.dualToneActive)

        val snapStage3 = engine.evaluateTick(t0 + 65_000L)
        assertEquals(3, snapStage3.stage)
        assertEquals(100, snapStage3.volumePercent)
        assertTrue(snapStage3.safeScreenPulseActive)

        val snapOverdue = engine.evaluateTick(t0 + 185_000L)
        assertEquals(4, snapOverdue.stage)
        assertEquals(AlarmState.OVERDUE, snapOverdue.state)
        assertTrue(snapOverdue.overdueTriggered)
    }

    @Test
    fun escalationEngine_enforcesLowBatteryEcoModeAndHardCutoff() {
        val engine = EscalationEngine()
        val t0 = 1_700_000_000_000L
        engine.startSession(alarmId = 99L, profile = EscalationProfile.EXTREME_SAFE, nowEpochMs = t0)

        // В режиме низкого заряда (lowBatteryEcoMode = true) на 50-й секунде:
        // вибрация ограничена уровнем 1, пульсация экрана отключена
        val ecoTick = engine.evaluateTick(nowEpochMs = t0 + 50_000L, lowBatteryEcoMode = true)
        assertTrue(ecoTick.ecoModeActive)
        assertEquals(1, ecoTick.vibrationCadenceLevel)
        assertFalse("В Eco-режиме пульсация экрана должна отключаться", ecoTick.safeScreenPulseActive)

        // Через 305 секунд (5+ минут) в режиме низкого заряда срабатывает жёсткий предохранитель батареи
        val cutoffTick = engine.evaluateTick(nowEpochMs = t0 + 305_000L, lowBatteryEcoMode = true)
        assertTrue(cutoffTick.batterySafetyCutoffReached)
        assertEquals(0, cutoffTick.volumePercent)
        assertEquals(0, cutoffTick.vibrationCadenceLevel)
    }

    @Test
    fun webhookSecurityValidator_allowsHttpsAndPrivateLanButBlocksPublicHttp() {
        assertTrue(
            WebhookSecurityValidator.validateWebhookUrl("https://hooks.nabu.casa/api/webhook/123").isAllowed
        )
        assertTrue(
            "HTTPS должен разрешаться для любого локального IP (например 192.168.1.55)",
            WebhookSecurityValidator.validateWebhookUrl("https://192.168.1.55:8123/api/webhook/alarm").isAllowed
        )
        assertTrue(
            WebhookSecurityValidator.validateWebhookUrl("http://192.168.1.100:8123/api/webhook/alarm").isAllowed
        )
        assertTrue(
            WebhookSecurityValidator.validateWebhookUrl("http://homeassistant.local:8123/api/webhook/alarm").isAllowed
        )
        val unlistedLanCheck = WebhookSecurityValidator.validateWebhookUrl("http://192.168.1.55:8123/api/webhook/alarm")
        assertFalse(
            "Приватный IP, не входящий в белый список network_security_config.xml по HTTP, должен отклоняться валидатором с понятной подсказкой",
            unlistedLanCheck.isAllowed
        )
        assertTrue(
            "Сообщение должно объяснять про HTTPS или .local",
            unlistedLanCheck.reasonRu.contains("HTTPS") && unlistedLanCheck.reasonRu.contains(".local")
        )
        assertFalse(
            "172.16.x.x по открытому HTTP не входит в network_security_config.xml и должен отклоняться с подсказкой",
            WebhookSecurityValidator.validateWebhookUrl("http://172.16.0.9:8123/api/webhook/alarm").isAllowed
        )
        assertFalse(
            "Открытый HTTP во внешнюю сеть должен блокироваться",
            WebhookSecurityValidator.validateWebhookUrl("http://example.com/api/webhook").isAllowed
        )
        assertFalse(
            "Обход префикса 192.168.* через внешний домен должен блокироваться",
            WebhookSecurityValidator.validateWebhookUrl("http://192.168.1.100.evil.com/api/webhook").isAllowed
        )
        assertFalse(
            "Обход префикса 10.* через внешний домен должен блокироваться",
            WebhookSecurityValidator.validateWebhookUrl("http://10.0.0.1.attacker.net/api/webhook").isAllowed
        )
        assertFalse(
            "Обход префикса 172.16.* через внешний домен должен блокироваться",
            WebhookSecurityValidator.validateWebhookUrl("http://172.16.0.1.evil.org/api/webhook").isAllowed
        )
        assertFalse(
            "Некорректный IPv4 с октетом > 255 должен блокироваться",
            WebhookSecurityValidator.validateWebhookUrl("http://192.168.999.1/api/webhook").isAllowed
        )
        // Проверка защиты от DNS-ребайндинга для .local/.lan
        assertTrue(
            WebhookSecurityValidator.isPrivateOrLoopbackInetAddress(
                java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 55))
            )
        )
        assertTrue(
            WebhookSecurityValidator.isPrivateOrLoopbackInetAddress(
                java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            )
        )
        assertFalse(
            "Публичный IP 8.8.8.8 должен блокироваться при проверке DNS-ребайндинга .local",
            WebhookSecurityValidator.isPrivateOrLoopbackInetAddress(
                java.net.InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
            )
        )
        assertEquals(
            "my-secret-token-123",
            com.example.backup.SmartHomeWebhookChannel.normalizeBearerToken("  Bearer my-secret-token-123 \r\n ")
        )

        val payload = BackupPayload(
            alarmId = 5L,
            alarmLabel = "Тест \"кавычек\" \\ и спецсимволов",
            eventType = "SUNRISE_T_MINUS_15",
            targetUrlOrAddress = "http://192.168.1.100/hook"
        )
        val json = WebhookSecurityValidator.buildSafeJsonBody(payload, timestampMs = 123456L)
        assertTrue(json.contains("\"alarmId\":5"))
        assertTrue(json.contains("AlarmCommander"))
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
    fun missionChainCoordinator_switchesFromQrToMathAfterFiveFailedAttempts() {
        val qrMission = QrMission(expectedPayload = "BATHROOM-QR", maxFailedAttempts = 5)
        val coordinator = MissionChainCoordinator(
            missions = listOf(qrMission),
            fallbackMathDifficulty = MathDifficulty.EASY
        )

        coordinator.startCurrent()
        assertEquals(com.example.data.MissionType.QR, coordinator.currentMission()?.type)

        // Первые 4 неверных сканирования оставляют миссию в режиме QR
        for (attempt in 1..4) {
            val (finished, res) = coordinator.submitInput(MissionInput.QrPayloadScanned("ANY-OTHER-BARCODE-$attempt"))
            assertFalse(finished)
            assertTrue(res is MissionResult.Failed)
            assertEquals(com.example.data.MissionType.QR, coordinator.currentMission()?.type)
        }

        // На 5-й неверной попытке миссия автоматически переключается на MathMission
        val (finishedOn5th, fifthRes) = coordinator.submitInput(MissionInput.QrPayloadScanned("ANY-OTHER-BARCODE-5"))
        assertFalse(finishedOn5th)
        assertTrue(fifthRes is MissionResult.Progress)
        assertEquals(com.example.data.MissionType.MATH, coordinator.currentMission()?.type)

        // Теперь пользователь решает 3 примера математики и штатно отключает будильник
        for (step in 1..3) {
            val mathMission = coordinator.currentMission() as MathMission
            val problem = mathMission.getCurrentProblem()
            val (done, mathRes) = coordinator.submitInput(MissionInput.MathAnswer(problem.expectedAnswer))
            if (step < 3) {
                assertFalse(done)
                assertTrue(mathRes is MissionResult.Progress)
            } else {
                assertTrue(done)
                assertTrue(mathRes is MissionResult.Completed)
            }
        }
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

    @Test
    fun githubUpdateCheckerAndLocalization_validatesVersionAndLocaleResolution() {
        assertTrue(com.example.settings.GitHubUpdateChecker.isRemoteVersionNewer("v1.1.0", "1.0"))
        assertTrue(com.example.settings.GitHubUpdateChecker.isRemoteVersionNewer("2.0", "1.9.5"))
        assertFalse(com.example.settings.GitHubUpdateChecker.isRemoteVersionNewer("v1.0", "1.0"))
        assertFalse(com.example.settings.GitHubUpdateChecker.isRemoteVersionNewer("0.9", "1.0"))

        assertEquals(
            "Zapad82/Alarm-Commander",
            com.example.settings.GitHubUpdateChecker.normalizeRepoSlug("")
        )
        assertEquals(
            "Zapad82/Alarm-Commander",
            com.example.settings.GitHubUpdateChecker.normalizeRepoSlug("alarm-commander/alarm-commander-android")
        )
        assertEquals(
            "Zapad82/Alarm-Commander",
            com.example.settings.GitHubUpdateChecker.normalizeRepoSlug("https://github.com/Zapad82/Alarm-Commander/releases")
        )
        assertEquals(
            "https://github.com/Zapad82/Alarm-Commander/releases",
            com.example.settings.GitHubUpdateChecker.OFFICIAL_GITHUB_RELEASES_URL
        )
        assertEquals(
            "https://github.com/Zapad82/Alarm-Commander/releases",
            com.example.settings.GitHubUpdateChecker.extractReleaseHtmlUrl("""{"author":{"html_url":"https://github.com/Zapad82"}}""")
        )
        assertEquals(
            "https://github.com/Zapad82/Alarm-Commander/releases/download/v1.0.8/app-release.apk",
            com.example.settings.GitHubUpdateChecker.extractApkDownloadUrl(
                """{"browser_download_url":"https://github.com/Zapad82/Alarm-Commander/releases/download/v1.0.8/app-release.apk"}"""
            )
        )
        assertEquals(
            "",
            com.example.settings.GitHubUpdateChecker.extractApkDownloadUrl(
                """{"browser_download_url":"http://evil.example.com/Alarm-Commander.apk"}"""
            )
        )

        assertTrue(com.example.ui.AppLanguageMode.EN.resolveIsEnglish(java.util.Locale.forLanguageTag("ru")))
        assertFalse(com.example.ui.AppLanguageMode.RU.resolveIsEnglish(java.util.Locale.ENGLISH))
        assertTrue(com.example.ui.AppLanguageMode.SYSTEM.resolveIsEnglish(java.util.Locale.US))
        assertFalse(com.example.ui.AppLanguageMode.SYSTEM.resolveIsEnglish(java.util.Locale.forLanguageTag("ru-RU")))

        assertEquals(com.example.ui.AppThemeMode.LIGHT, com.example.ui.AppThemeMode.fromStorage("LIGHT"))
        assertEquals(com.example.ui.AppThemeMode.DARK, com.example.ui.AppThemeMode.fromStorage("DARK"))

        listOf(
            com.example.data.AlarmSoundSource.SYNTH_TACTICAL_SIREN,
            com.example.data.AlarmSoundSource.SYNTH_RADAR_SONAR,
            com.example.data.AlarmSoundSource.SYNTH_GENTLE_CHORD
        ).forEach { source ->
            val pcm = com.example.alarm.AlarmAudioController.buildSynthesizedPcmSamples(source)
            assertTrue("PCM samples for $source must not be empty", pcm.size >= 44100 * 2)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("Peak amplitude for $source must be audible (> 20000), got $peak", peak > 20000)
        }
    }
}
