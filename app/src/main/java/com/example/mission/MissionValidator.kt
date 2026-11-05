package com.example.mission

import com.example.data.MathDifficulty
import com.example.data.MissionType
import kotlin.random.Random

/**
 * Входные данные для проверки миссии.
 */
sealed class MissionInput {
    data class MathAnswer(val answer: Int) : MissionInput()
    data class StepCountUpdate(val currentSteps: Int) : MissionInput()
    data class QrPayloadScanned(val payload: String) : MissionInput()
    data class NfcTagScanned(val tagIdHex: String) : MissionInput()
    data class VoicePhraseSpoken(val recognizedText: String) : MissionInput()
    data class PenaltyPhraseTyped(val phrase: String) : MissionInput()
}

/**
 * Результат шага валидации миссии.
 */
sealed class MissionResult {
    data class Progress(
        val currentStep: Int,
        val totalSteps: Int,
        val promptText: String,
        val feedbackMessage: String? = null
    ) : MissionResult()

    data class Completed(val summaryMessage: String) : MissionResult()
    data class Failed(val reason: String, val currentPrompt: String) : MissionResult()
}

/**
 * Базовый контракт миссии отключения будильника.
 */
interface Mission {
    val type: MissionType
    fun start(): MissionResult.Progress
    fun validate(input: MissionInput): MissionResult
    fun cancel()
}

/**
 * 1. Миссия «Математика» с генератором примеров 3 уровней сложности.
 */
class MathMission(
    private val difficulty: MathDifficulty = MathDifficulty.MEDIUM,
    private val random: Random = Random.Default
) : Mission {

    override val type: MissionType = MissionType.MATH

    data class MathProblem(val expression: String, val expectedAnswer: Int)

    private val totalRequired = difficulty.problemsCount
    private var solvedCount = 0
    private var currentProblem: MathProblem = generateProblem()

    override fun start(): MissionResult.Progress {
        solvedCount = 0
        currentProblem = generateProblem()
        return MissionResult.Progress(
            currentStep = 0,
            totalSteps = totalRequired,
            promptText = currentProblem.expression,
            feedbackMessage = "Решите $totalRequired примера подряд"
        )
    }

    override fun validate(input: MissionInput): MissionResult {
        val mathInput = input as? MissionInput.MathAnswer
            ?: return MissionResult.Failed("Неверный тип ввода", currentProblem.expression)

        return if (mathInput.answer == currentProblem.expectedAnswer) {
            solvedCount++
            if (solvedCount >= totalRequired) {
                MissionResult.Completed("Математический рубеж пройден ($solvedCount/$totalRequired)")
            } else {
                currentProblem = generateProblem()
                MissionResult.Progress(
                    currentStep = solvedCount,
                    totalSteps = totalRequired,
                    promptText = currentProblem.expression,
                    feedbackMessage = "Верно! Осталось: ${totalRequired - solvedCount}"
                )
            }
        } else {
            MissionResult.Failed(
                reason = "Ошибка! Попробуйте ещё раз без автопилота.",
                currentPrompt = currentProblem.expression
            )
        }
    }

    override fun cancel() {
        solvedCount = 0
    }

    fun getCurrentProblem(): MathProblem = currentProblem

    private fun generateProblem(): MathProblem {
        return when (difficulty) {
            MathDifficulty.EASY -> {
                val a = random.nextInt(14, 59)
                val b = random.nextInt(12, 49)
                MathProblem("$a + $b = ?", a + b)
            }
            MathDifficulty.MEDIUM -> {
                val a = random.nextInt(11, 25)
                val b = random.nextInt(4, 9)
                val c = random.nextInt(10, 35)
                MathProblem("($a × $b) − $c = ?", (a * b) - c)
            }
            MathDifficulty.HARD -> {
                val a = random.nextInt(12, 24)
                val b = random.nextInt(6, 12)
                val c = random.nextInt(25, 75)
                val d = random.nextInt(11, 39)
                MathProblem("($a × $b) + $c − $d = ?", (a * b) + c - d)
            }
        }
    }
}

/**
 * 2. Миссия «Шагомер» (50–100 шагов).
 */
class StepsMission(
    private val targetSteps: Int = 50
) : Mission {

    override val type: MissionType = MissionType.STEPS
    private var currentSteps = 0

    override fun start(): MissionResult.Progress {
        currentSteps = 0
        return MissionResult.Progress(
            currentStep = 0,
            totalSteps = targetSteps.coerceAtLeast(10),
            promptText = "Встаньте с кровати и пройдите $targetSteps шагов",
            feedbackMessage = "Датчик движения активен"
        )
    }

    override fun validate(input: MissionInput): MissionResult {
        val stepInput = input as? MissionInput.StepCountUpdate
            ?: return MissionResult.Failed("Ожидаются данные шагомера", "0 / $targetSteps")

        currentSteps = stepInput.currentSteps.coerceAtLeast(0)
        val goal = targetSteps.coerceAtLeast(10)
        return if (currentSteps >= goal) {
            MissionResult.Completed("Пройдено $currentSteps из $goal шагов!")
        } else {
            MissionResult.Progress(
                currentStep = currentSteps,
                totalSteps = goal,
                promptText = "Шагов пройдено: $currentSteps / $goal",
                feedbackMessage = "Осталось пройти: ${goal - currentSteps} шагов"
            )
        }
    }

    override fun cancel() {
        currentSteps = 0
    }
}

/**
 * 3. Миссия «QR-код в ванной» с автоматическим резервным переключением на Математику
 *    после 5 неверных сканирований/попыток (если целевой QR-код не подготовлен или утерян).
 */
class QrMission(
    private val expectedPayload: String = "ALARM-COMMANDER-BATHROOM-01",
    private val maxFailedAttempts: Int = 5
) : Mission {

    override val type: MissionType = MissionType.QR
    private var failedAttempts: Int = 0

    fun getFailedAttempts(): Int = failedAttempts

    fun isFallbackToMathTriggered(): Boolean = failedAttempts >= maxFailedAttempts

    override fun start(): MissionResult.Progress {
        failedAttempts = 0
        return MissionResult.Progress(
            currentStep = 0,
            totalSteps = 1,
            promptText = "Наведите камеру на контрольный QR-код: $expectedPayload",
            feedbackMessage = "Ожидание сканирования QR-кода..."
        )
    }

    override fun validate(input: MissionInput): MissionResult {
        val qrInput = input as? MissionInput.QrPayloadScanned
            ?: return MissionResult.Failed("Ожидается QR-код", expectedPayload)

        val normalizedActual = qrInput.payload.trim()
        val normalizedExpected = expectedPayload.trim()

        val matches = normalizedExpected == "*" ||
            normalizedActual.equals(normalizedExpected, ignoreCase = true)

        return if (matches && normalizedActual.isNotEmpty()) {
            failedAttempts = 0
            MissionResult.Completed("Контрольный QR-код подтверждён: $normalizedActual")
        } else {
            failedAttempts++
            val displayCode = normalizedActual.ifBlank { "без кода" }
            MissionResult.Failed(
                reason = "Неверный QR-код («$displayCode»): попытка $failedAttempts из $maxFailedAttempts. При $maxFailedAttempts попытках включится Математика.",
                currentPrompt = expectedPayload
            )
        }
    }

    override fun cancel() {
        failedAttempts = 0
    }
}

/**
 * 4. Миссия «NFC-метка».
 */
class NfcMission(
    private val expectedTagId: String = "*"
) : Mission {

    override val type: MissionType = MissionType.NFC

    override fun start(): MissionResult.Progress {
        val targetLabel = if (expectedTagId == "*" || expectedTagId.isBlank()) {
            "любая NFC-метка"
        } else {
            "ID: $expectedTagId"
        }
        return MissionResult.Progress(
            currentStep = 0,
            totalSteps = 1,
            promptText = "Приложите смартфон к NFC-метке ($targetLabel)",
            feedbackMessage = "NFC-ридер ожидает касания метки"
        )
    }

    override fun validate(input: MissionInput): MissionResult {
        val nfcInput = input as? MissionInput.NfcTagScanned
            ?: return MissionResult.Failed("Ожидается сканирование NFC-метки", expectedTagId)

        val cleanedActual = nfcInput.tagIdHex.trim().uppercase()
        val cleanedExpected = expectedTagId.trim().uppercase()

        val valid = cleanedActual.isNotEmpty() &&
            (cleanedExpected == "*" || cleanedExpected.isEmpty() || cleanedActual == cleanedExpected)

        return if (valid) {
            MissionResult.Completed("NFC-метка считана ($cleanedActual)")
        } else {
            MissionResult.Failed(
                reason = "Чужая NFC-метка ($cleanedActual). Ожидалась: $cleanedExpected",
                currentPrompt = expectedTagId
            )
        }
    }

    override fun cancel() = Unit
}

/**
 * 5. Миссия «Голосовая фраза» (проверка произнесённой вслух контрольной фразы).
 */
class VoicePhraseMission(
    private val expectedPhrase: String = "Я полностью проснулся и готов к действию"
) : Mission {

    override val type: MissionType = MissionType.VOICE

    override fun start(): MissionResult.Progress {
        return MissionResult.Progress(
            currentStep = 0,
            totalSteps = 1,
            promptText = expectedPhrase,
            feedbackMessage = "Произнесите контрольную фразу чётко вслух"
        )
    }

    override fun validate(input: MissionInput): MissionResult {
        val voiceInput = input as? MissionInput.VoicePhraseSpoken
            ?: return MissionResult.Failed("Ожидается голосовая фраза", expectedPhrase)

        val actualWords = normalizeWords(voiceInput.recognizedText)
        val expectedWords = normalizeWords(expectedPhrase)
        val englishDefaultWords = if (expectedPhrase == "Я полностью проснулся и готов к действию") {
            normalizeWords("I am fully awake and ready for action")
        } else {
            emptyList()
        }

        if (actualWords.isEmpty()) {
            return MissionResult.Failed("Речь не распознана. Повторите фразу громче.", expectedPhrase)
        }

        val matchedPrimary = expectedWords.count { exp -> actualWords.contains(exp) }
        val ratioPrimary = if (expectedWords.isNotEmpty()) {
            matchedPrimary.toDouble() / expectedWords.size.toDouble()
        } else {
            1.0
        }

        val matchedAlt = englishDefaultWords.count { exp -> actualWords.contains(exp) }
        val ratioAlt = if (englishDefaultWords.isNotEmpty()) {
            matchedAlt.toDouble() / englishDefaultWords.size.toDouble()
        } else {
            0.0
        }

        val matchRatio = maxOf(ratioPrimary, ratioAlt)

        return if (matchRatio >= 0.75) {
            MissionResult.Completed("Голосовая фраза подтверждена: «${voiceInput.recognizedText.trim()}»")
        } else {
            MissionResult.Failed(
                reason = "Распознано: «${voiceInput.recognizedText.trim()}». Произнесите полную фразу чётко!",
                currentPrompt = expectedPhrase
            )
        }
    }

    private fun normalizeWords(text: String): List<String> {
        return text.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
    }

    override fun cancel() = Unit
}

/**
 * Координатор цепочки миссий (Mission Chain Validator).
 * Последовательно проводит пользователя через все назначенные миссии (например: QR -> Математика -> Голос).
 * Если в миссии QR-кода совершено 5 неверных попыток (например, QR-код не подготовлен),
 * автоматически переводит текущий этап в режим Математики для резервного отключения.
 */
class MissionChainCoordinator(
    missions: List<Mission>,
    private val fallbackMathDifficulty: MathDifficulty = MathDifficulty.MEDIUM
) {
    private val mutableMissions: MutableList<Mission> = missions.toMutableList()
    private var currentIndex: Int = 0

    init {
        require(mutableMissions.isNotEmpty()) { "Цепочка миссий не может быть пустой" }
    }

    fun currentMission(): Mission = mutableMissions[currentIndex.coerceIn(0, mutableMissions.lastIndex)]

    fun currentMissionIndex(): Int = currentIndex

    fun totalMissionsCount(): Int = mutableMissions.size

    fun startCurrent(): MissionResult.Progress = currentMission().start()

    fun setMissionIndex(index: Int): MissionResult.Progress {
        currentIndex = index.coerceIn(0, mutableMissions.lastIndex)
        return currentMission().start()
    }

    /**
     * Проверяет ввод для текущей миссии. Если текущая миссия завершена и в цепочке есть следующая —
     * автоматически переключает на следующая миссию.
     * Если в миссии QR достигнут лимит 5 неверных попыток — заменяет текущую миссию на Математику.
     */
    fun submitInput(input: MissionInput): Pair<Boolean, MissionResult> {
        val activeMission = currentMission()
        val result = activeMission.validate(input)
        return when {
            result is MissionResult.Completed -> {
                if (currentIndex + 1 < mutableMissions.size) {
                    currentIndex++
                    val nextProgress = currentMission().start()
                    false to nextProgress.copy(
                        feedbackMessage = "Этап ${currentIndex}/${mutableMissions.size} пройден! Следующая миссия: ${currentMission().type.titleRu}"
                    )
                } else {
                    true to result
                }
            }
            result is MissionResult.Failed &&
                activeMission is QrMission &&
                activeMission.isFallbackToMathTriggered() -> {
                val mathFallback = MathMission(difficulty = fallbackMathDifficulty)
                mutableMissions[currentIndex.coerceIn(0, mutableMissions.lastIndex)] = mathFallback
                val mathProgress = mathFallback.start()
                false to mathProgress.copy(
                    feedbackMessage = "5 неверных попыток QR-кода: активировано резервное отключение через Математику!"
                )
            }
            else -> {
                false to result
            }
        }
    }
}
