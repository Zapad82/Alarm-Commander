package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Состояния конечного автомата (State Machine) будильника:
 * Scheduled -> PreAlarmSunrise -> Ringing -> Escalating -> MissionInProgress -> AntiReturnCheck -> Dismissed / Overdue / Failed
 */
enum class AlarmState(val titleRu: String) {
    SCHEDULED("Запланирован"),
    PRE_ALARM_SUNRISE("Рассвет (T−15)"),
    RINGING("Звонит (T0)"),
    ESCALATING("Эскалация сигнала"),
    MISSION_IN_PROGRESS("Выполнение миссии"),
    ANTI_RETURN_CHECK("Контроль анти-возврата"),
    DISMISSED("Отключён штатно"),
    OVERDUE("Просрочен • Резерв вызван"),
    FAILED("Сбой / Принудительный сброс")
}

/**
 * Профили эскалации звука, вибрации и визуального импульса экрана.
 */
enum class EscalationProfile(
    val titleRu: String,
    val subtitleRu: String,
    val stage2DelaySec: Int,
    val stage3DelaySec: Int,
    val overdueTimeoutSec: Int,
    val initialVolumePercent: Int
) {
    GENTLE(
        titleRu = "Gentle (Мягкий)",
        subtitleRu = "Плавный старт 25% -> 55% (45с) -> 85% (90с), резерв через 4 мин",
        stage2DelaySec = 45,
        stage3DelaySec = 90,
        overdueTimeoutSec = 240,
        initialVolumePercent = 25
    ),
    HARD(
        titleRu = "Hard (Боевой)",
        subtitleRu = "Старт 45% -> 80% сирена (30с) -> 100% + виброимпульс (60с), резерв 3 мин",
        stage2DelaySec = 30,
        stage3DelaySec = 60,
        overdueTimeoutSec = 180,
        initialVolumePercent = 45
    ),
    EXTREME_SAFE(
        titleRu = "ExtremeSafe (Максимум без риска)",
        subtitleRu = "Старт 65% -> 95% (20с) -> 100% двухчастотный сигнал (40с), резерв 2 мин",
        stage2DelaySec = 20,
        stage3DelaySec = 40,
        overdueTimeoutSec = 120,
        initialVolumePercent = 65
    )
}

/**
 * Доступные типы миссий в цепочке разблокировки (все 5 боевых миссий).
 */
enum class MissionType(val titleRu: String, val badgeRu: String, val descriptionRu: String) {
    MATH(
        titleRu = "Математика",
        badgeRu = "MATH",
        descriptionRu = "Решение серии арифметических выражений без калькулятора"
    ),
    STEPS(
        titleRu = "Шаги (50–100)",
        badgeRu = "STEPS",
        descriptionRu = "Физический подъём и прохождение заданного числа шагов по датчику движения"
    ),
    QR(
        titleRu = "QR-код в ванной",
        badgeRu = "QR",
        descriptionRu = "Сканирование контрольного QR-кода через камеру в другой комнате"
    ),
    NFC(
        titleRu = "NFC-метка",
        badgeRu = "NFC",
        descriptionRu = "Прикладывание смартфона к зарегистрированной NFC-метке вдали от кровати"
    ),
    VOICE(
        titleRu = "Голосовая фраза",
        badgeRu = "VOICE",
        descriptionRu = "Чёткое произнесение контрольной фразы вслух через распознавание речи"
    )
}

/**
 * Уровень сложности математической миссии.
 */
enum class MathDifficulty(val titleRu: String, val problemsCount: Int) {
    EASY("Лёгкий (24 + 19)", 3),
    MEDIUM("Средний (16 × 4 − 15)", 3),
    HARD("Тяжёлый ((18 × 7) + 49 − 23)", 4)
}

/**
 * Room-сущность будильника.
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String,
    /** Дни недели 1 (Пн) .. 7 (Вс) через запятую, например "1,2,3,4,5". Пустая строка = однократный */
    val daysOfWeekCsv: String = "1,2,3,4,5",
    val enabled: Boolean = true,
    val escalationProfile: String = EscalationProfile.HARD.name,
    /** Цепочка миссий через запятую, например "MATH,STEPS" или "QR,VOICE" */
    val missionChainCsv: String = "MATH,STEPS",
    val mathDifficulty: String = MathDifficulty.MEDIUM.name,
    val targetSteps: Int = 50,
    val qrExpectedPayload: String = "ALARM-COMMANDER-BATHROOM-01",
    val nfcExpectedTagId: String = "*",
    val voicePhraseExpected: String = "Я полностью проснулся и готов к действию",
    /** По умолчанию Anti-Snooze включён (allowSnooze = false) */
    val allowSnooze: Boolean = false,
    val snoozeDurationMinutes: Int = 5,
    val maxSnoozeCount: Int = 1,
    val currentSnoozeCount: Int = 0,
    /** Предварительный «Рассвет» за T-15 минут */
    val sunrisePreAlarmEnabled: Boolean = true,
    val sunriseLeadMinutes: Int = 15,
    /** Анти-возврат в кровать: проверка через N минут после отключения */
    val antiReturnEnabled: Boolean = true,
    val antiReturnDelayMinutes: Int = 5,
    /** Резервные каналы при просрочке (Overdue) */
    val backupWebhookEnabled: Boolean = true,
    val backupContactEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val profileEnum: EscalationProfile
        get() = runCatching { EscalationProfile.valueOf(escalationProfile) }
            .getOrDefault(EscalationProfile.HARD)

    val mathDifficultyEnum: MathDifficulty
        get() = runCatching { MathDifficulty.valueOf(mathDifficulty) }
            .getOrDefault(MathDifficulty.MEDIUM)

    val missionChain: List<MissionType>
        get() = missionChainCsv.split(",")
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) null
                else runCatching { MissionType.valueOf(trimmed) }.getOrNull()
            }
            .ifEmpty { listOf(MissionType.MATH) }

    val daysOfWeek: Set<Int>
        get() = daysOfWeekCsv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()

    fun formattedTime(): String = "%02d:%02d".format(hour, minute)

    fun formattedDaysShort(): String {
        val days = daysOfWeek
        if (days.isEmpty()) return "Однократно"
        if (days.size == 7) return "Каждый день"
        if (days == setOf(1, 2, 3, 4, 5)) return "Будни (Пн–Пт)"
        if (days == setOf(6, 7)) return "Выходные (Сб–Вс)"
        val names = mapOf(1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс")
        return days.sorted().mapNotNull { names[it] }.joinToString(", ")
    }
}

/**
 * Room-сущность локального журнала событий будильника.
 */
@Entity(tableName = "alarm_logs")
data class AlarmLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val alarmId: Long,
    val alarmLabel: String,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val stateName: String,
    val details: String
)
