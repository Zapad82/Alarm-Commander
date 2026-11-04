package com.example.ui

import androidx.compose.runtime.compositionLocalOf
import com.example.data.EscalationProfile
import com.example.data.MathDifficulty
import com.example.data.MissionType
import java.util.Locale

enum class AppLanguageMode(val code: String, val labelShort: String) {
    SYSTEM("SYSTEM", "AUTO"),
    RU("RU", "RU"),
    EN("EN", "EN");

    fun resolveIsEnglish(systemLocale: Locale = Locale.getDefault()): Boolean {
        return when (this) {
            EN -> true
            RU -> false
            SYSTEM -> {
                val lang = systemLocale.language.lowercase()
                lang !in setOf("ru", "be", "uk", "kk")
            }
        }
    }

    companion object {
        fun fromStorage(raw: String?): AppLanguageMode {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
        }
    }
}

enum class AppThemeMode(val code: String) {
    DARK("DARK"),
    LIGHT("LIGHT"),
    SYSTEM("SYSTEM");

    companion object {
        fun fromStorage(raw: String?): AppThemeMode {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DARK
        }
    }
}

data class AppStrings(
    val isEnglish: Boolean,
    val appTitle: String,
    val headerSubtitle: String,
    val navCommandCenter: String,
    val navPermissions: String,
    val navLogs: String,
    val navSettings: String,
    // Disclaimer
    val disclaimerTitle: String,
    val disclaimerBody: String,
    val disclaimerAccept: String,
    val disclaimerShortBanner: String,
    // Radar & Test Polygon
    val radarDutyActive: String,
    val osPermissionsShort: (Int, Int) -> String,
    val nextT0Format: (String, Int, Int) -> String,
    val profileLabelFormat: (String, String) -> String,
    val allAlarmsDisabled: String,
    val enableAlarmOrTestHint: String,
    val provingGroundTitle: String,
    val provingGroundSubtitle: String,
    val testT0Btn: String,
    val testSunriseBtn: String,
    val testAntiReturnBtn: String,
    val newAlarmBtn: String,
    val emptyAlarmsTitle: String,
    val emptyAlarmsHint: String,
    val runTestBtn: String,
    val escalationBadge: (String) -> String,
    val missionsBadge: (String) -> String,
    val antiSnoozeBadge: String,
    val penaltySnoozeBadge: String,
    val sunriseBadge: (Int) -> String,
    val antiReturnBadge: (Int) -> String,
    val dayNamesShort: List<Pair<Int, String>>,
    val everyDay: String,
    val weekdays: String,
    val weekends: String,
    // Editor
    val editorNewTitle: String,
    val editorEditTitle: String,
    val editorLabelField: String,
    val editorDaysOfWeek: String,
    val editorEscalationProfile: String,
    val editorMissionChain: String,
    val editorMathDifficulty: String,
    val editorTargetSteps: (Int) -> String,
    val editorExpectedQr: String,
    val editorExpectedNfc: String,
    val editorExpectedVoice: String,
    val editorAllowPenaltySnooze: String,
    val editorSunrisePreAlarm: String,
    val editorAntiReturn: String,
    val editorBackupChannels: String,
    val saveAlarmBtn: String,
    val cancelBtn: String,
    // Active Alarm & Missions
    val activeCombatBanner: String,
    val profileStageFormat: (String, Int) -> String,
    val backupChannelPrefix: String,
    val unlockMissionChainHeader: (Int) -> String,
    val startMissionBtn: String,
    val penaltySnoozeBtn: (Int) -> String,
    val antiSnoozeLockedLabel: String,
    val missionStepProgress: (Int, Int) -> String,
    val mathSolvedProgress: (Int, Int) -> String,
    val mathAnswerLabel: String,
    val submitMathBtn: String,
    val stepsCountFormat: (Int, Int) -> String,
    val stepsSensorSource: (String) -> String,
    val grantStepSensorBtn: String,
    val stepsEmulatorHint: String,
    val leftStepBtn: String,
    val rightStepBtn: String,
    val qrExpectedLabel: (String) -> String,
    val enableCameraQrBtn: String,
    val qrFallbackHint: String,
    val qrInputLabel: (String) -> String,
    val submitQrBtn: String,
    val nfcTitle: String,
    val nfcExpectedIdLabel: (String) -> String,
    val nfcFallbackLabel: (String) -> String,
    val submitNfcBtn: String,
    val voiceInstruction: String,
    val voiceListeningBtn: String,
    val voiceStartBtn: String,
    val voiceFallbackLabel: String,
    val submitVoiceBtn: String,
    val penaltyCardTitle: String,
    val penaltyCardInstruction: String,
    val penaltyInputLabel: String,
    val backBtn: String,
    val confirmBtn: String,
    val sunrisePhaseHeader: String,
    val sunriseDescription: String,
    val dismissSunriseBtn: String,
    val antiReturnHeader: String,
    val antiReturnSecondsFormat: (Int) -> String,
    val antiReturnBody: (String) -> String,
    val confirmAntiReturnBtn: String,
    // Permissions & Audit
    val securityPowerAuditTitle: String,
    val batteryEcoStatusFormat: (Int, Boolean, Boolean) -> String,
    val permissionsChecklistTitle: String,
    val permissionsChecklistSub: String,
    val permActive: String,
    val permOptional: String,
    val permRequired: String,
    // Logs
    val logsHeaderTitle: (Int) -> String,
    val logsHeaderSub: String,
    val exportLogsBtn: String,
    val clearLogsBtn: String,
    val emptyLogsLabel: String,
    // Settings & GitHub Update
    val appearanceAndLangTitle: String,
    val themeSectionLabel: String,
    val themeDark: String,
    val themeLight: String,
    val themeSystem: String,
    val languageSectionLabel: String,
    val langSystem: String,
    val langRussian: String,
    val langEnglish: String,
    val githubUpdateCardTitle: String,
    val githubUpdateSub: String,
    val githubRepoFieldLabel: String,
    val checkGithubUpdateBtn: String,
    val openGithubReleaseBtn: String,
    val signingCertLabel: (String) -> String,
    val currentVersionFormat: (String, Int) -> String,
    val qrGeneratorCardTitle: String,
    val qrGeneratorSub: String,
    val qrPayloadFieldLabel: String,
    val shareQrPayloadBtn: String,
    val nfcFieldLabel: String,
    val backupChannelsCardTitle: String,
    val sunriseWebhookFieldLabel: String,
    val testSunriseWebhookBtn: String,
    val overdueWebhookFieldLabel: String,
    val testOverdueWebhookBtn: String,
    val contactNameFieldLabel: String,
    val contactPhoneFieldLabel: String,
    val testContactBtn: String,
    val saveBackupSettingsBtn: String,
    val stubsCardTitle: String,
    val stubsCardSub: String,
    // Sound, Vibration & System Ringtone Settings
    val soundAndVibroCardTitle: String,
    val soundAndVibroCardSub: String,
    val signalModeLabel: String,
    val soundSourceLabel: String,
    val selectedRingtoneLabel: (String) -> String,
    val pickSystemRingtoneBtn: String,
    val previewSignalBtn: String,
    val stopPreviewSignalBtn: String
)

val RussianStrings = AppStrings(
    isEnglish = false,
    appTitle = "Alarm Commander",
    headerSubtitle = "ТАКТИЧЕСКИЙ КОНТРОЛЬ ПРОБУЖДЕНИЯ",
    navCommandCenter = "Штаб",
    navPermissions = "Защита",
    navLogs = "Журнал",
    navSettings = "Опции",
    disclaimerTitle = "Важное предупреждение о безопасности",
    disclaimerBody = "Alarm Commander — это многослойная система снижения риска проспать, а НЕ медицинское устройство и не средство жизнеобеспечения. Приложение не гарантирует 100% пробуждение при аппаратных сбоях, разряде батареи или принудительной остановке ОС. Опасные методы (стробоскопы >2 Гц, скрытая запись, отправка данных без согласия) строго запрещены.",
    disclaimerAccept = "Понятно, принимаю условия",
    disclaimerShortBanner = "Многослойная система снижения риска проспать • Локальное хранение без телеметрии",
    radarDutyActive = "ДЕЖУРСТВО АКТИВНО",
    osPermissionsShort = { granted, total -> "ОС: $granted/$total" },
    nextT0Format = { time, h, m -> "Следующий T0: $time (через ${h}ч ${m}м)" },
    profileLabelFormat = { label, prof -> "$label • Профиль $prof" },
    allAlarmsDisabled = "Все рубежи отключены",
    enableAlarmOrTestHint = "Включите будильник или запустите боевой тест T0 ниже",
    provingGroundTitle = "ПОЛИГОН ПРОВЕРКИ СИГНАЛА И МИССИЙ",
    provingGroundSubtitle = "Проверьте эскалацию громкости, блокировку кнопки «Назад», цепочку миссий и контроль анти-возврата прямо сейчас.",
    testT0Btn = "Тест T0",
    testSunriseBtn = "Рассвет T−15",
    testAntiReturnBtn = "Тест «Анти-возврат» (таймер 60 сек)",
    newAlarmBtn = "Новый рубеж",
    emptyAlarmsTitle = "Нет активных будильников",
    emptyAlarmsHint = "Создайте первый многослойный будильник или запустите боевой тест выше.",
    runTestBtn = "Запустить тест",
    escalationBadge = { "Эскалация: $it" },
    missionsBadge = { "Миссии: $it" },
    antiSnoozeBadge = "Анти-Snooze",
    penaltySnoozeBadge = "Penalty-Snooze",
    sunriseBadge = { "Рассвет T−${it}м" },
    antiReturnBadge = { "Анти-возврат ${it}м" },
    dayNamesShort = listOf(1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс"),
    everyDay = "Ежедневно",
    weekdays = "Будни (Пн–Пт)",
    weekends = "Выходные (Сб–Вс)",
    editorNewTitle = "Новый тактический рубеж",
    editorEditTitle = "Настройка будильника",
    editorLabelField = "Название рубежа",
    editorDaysOfWeek = "Дни недели:",
    editorEscalationProfile = "Профиль эскалации:",
    editorMissionChain = "Цепочка миссий (выберите 1–5 по порядку):",
    editorMathDifficulty = "Сложность математики:",
    editorTargetSteps = { "Целевое число шагов: $it" },
    editorExpectedQr = "Ожидаемый QR-код в ванной",
    editorExpectedNfc = "NFC Tag ID (* = любая метка)",
    editorExpectedVoice = "Контрольная голосовая фраза",
    editorAllowPenaltySnooze = "Разрешить штрафной Snooze (+5 мин)",
    editorSunrisePreAlarm = "Фаза «Рассвет» за T−15 мин",
    editorAntiReturn = "Контроль «Анти-возврат в кровать» (5 мин)",
    editorBackupChannels = "Резерв: Webhook умного дома и контакт",
    saveAlarmBtn = "Сохранить рубеж",
    cancelBtn = "Отмена",
    activeCombatBanner = "БОЕВАЯ ТРЕВОГА • АВТОПИЛОТ ЗАБЛОКИРОВАН",
    profileStageFormat = { prof, stage -> "ПРОФИЛЬ: $prof • ЭТАП $stage/4" },
    backupChannelPrefix = "Резервный канал:",
    unlockMissionChainHeader = { "ЦЕПОЧКА МИССИЙ РАЗБЛОКИРОВКИ ($it)" },
    startMissionBtn = "ПРИСТУПИТЬ К МИССИИ ОТКЛЮЧЕНИЯ",
    penaltySnoozeBtn = { "Штрафной Snooze (+$it мин через фразу)" },
    antiSnoozeLockedLabel = "Режим Анти-Snooze: перенос будильника заблокирован",
    missionStepProgress = { idx, total -> "МИССИЯ $idx ИЗ $total" },
    mathSolvedProgress = { step, total -> "Решено примеров: $step / $total" },
    mathAnswerLabel = "Ответ",
    submitMathBtn = "ПРОВЕРИТЬ ОТВЕТ",
    stepsCountFormat = { cur, target -> "$cur / $target ШАГОВ" },
    stepsSensorSource = { "Источник: $it" },
    grantStepSensorBtn = "Разрешить доступ к аппаратному шагомеру",
    stepsEmulatorHint = "Идите с телефоном в руках или поочерёдно нажимайте «ЛЕВЫЙ ШАГ» / «ПРАВЫЙ ШАГ» (режим проверки на эмуляторе):",
    leftStepBtn = "ЛЕВЫЙ ШАГ (+5)",
    rightStepBtn = "ПРАВЫЙ ШАГ (+5)",
    qrExpectedLabel = { "Ожидается QR: $it" },
    enableCameraQrBtn = "Включить камеру для сканирования QR",
    qrFallbackHint = "Резервный ввод (если камера недоступна): введите точный код метки вручную",
    qrInputLabel = { "Код метки ($it)" },
    submitQrBtn = "ПОДТВЕРДИТЬ КОД МЕТКИ",
    nfcTitle = "Приложите смартфон к NFC-метке",
    nfcExpectedIdLabel = { if (it == "*") "Ожидаемый ID: любая метка" else "Ожидаемый ID: $it" },
    nfcFallbackLabel = { "Резервный код без NFC: $it" },
    submitNfcBtn = "ПОДТВЕРДИТЬ КАСАНИЕ NFC",
    voiceInstruction = "Произнесите контрольную фразу вслух:",
    voiceListeningBtn = "СЛУШАЮ... ГОВОРИТЕ",
    voiceStartBtn = "НАЧАТЬ РАСПОЗНАВАНИЕ ГОЛОСА",
    voiceFallbackLabel = "Резервный ввод фразы вручную",
    submitVoiceBtn = "ПОДТВЕРДИТЬ ФРАЗУ",
    penaltyCardTitle = "ШТРАФНАЯ ЗАДАЧА ДЛЯ SNOOZE",
    penaltyCardInstruction = "Чтобы отложить сигнал на 5 минут, введите без ошибок фразу:",
    penaltyInputLabel = "Повторите фразу",
    backBtn = "Назад",
    confirmBtn = "Подтвердить",
    sunrisePhaseHeader = "ФАЗА «РАССВЕТ» (T−15 МИН)",
    sunriseDescription = "Мягкий световой поток экрана и команда на умную лампу подготавливают организм к пробуждению до боевого сигнала T0.",
    dismissSunriseBtn = "Я УЖЕ ПРОСНУЛСЯ • ЗАВЕРШИТЬ РАССВЕТ",
    antiReturnHeader = "КОНТРОЛЬ АНТИ-ВОЗВРАТА В КРОВАТЬ",
    antiReturnSecondsFormat = { "$it СЕК" },
    antiReturnBody = { "Будильник «$it»: подтвердите, что вы не легли обратно в кровать. Если таймер истечёт, боевая тревога и миссии запустятся заново!" },
    confirmAntiReturnBtn = "Я БОДРСТВУЮ • НЕ В КРОВАТИ",
    securityPowerAuditTitle = "АУДИТ БЕЗОПАСНОСТИ И ЭНЕРГОЭФФЕКТИВНОСТИ",
    batteryEcoStatusFormat = { pct, charging, eco ->
        "Батарея: $pct% (${if (charging) "Зарядка" else "Автономно"}) • Eco-режим: ${if (eco) "АКТИВЕН" else "В РЕЗЕРВЕ"}"
    },
    permissionsChecklistTitle = "ЧЕК-ЛИСТ РАЗРЕШЕНИЙ ANDROID 8–16",
    permissionsChecklistSub = "Все разрешения запрашиваются прозрачно по шагам. Приложение НЕ использует SYSTEM_ALERT_WINDOW.",
    permActive = "АКТИВНО",
    permOptional = "ОПЦИЯ",
    permRequired = "ТРЕБУЕТСЯ",
    logsHeaderTitle = { "ЛОКАЛЬНЫЙ ЖУРНАЛ СОБЫТИЙ ($it)" },
    logsHeaderSub = "Все срабатывания, стадии эскалации и миссии хранятся только на устройстве.",
    exportLogsBtn = "Экспорт",
    clearLogsBtn = "Очистить",
    emptyLogsLabel = "Записей в журнале пока нет",
    appearanceAndLangTitle = "ЯЗЫК ИНТЕРФЕЙСА И ТЕМА (THEME & LANGUAGE)",
    themeSectionLabel = "Тема оформления:",
    themeDark = "Тёмная",
    themeLight = "Светлая",
    themeSystem = "Авто (ОС)",
    languageSectionLabel = "Язык интерфейса:",
    langSystem = "Авто (ОС)",
    langRussian = "Русский",
    langEnglish = "English",
    githubUpdateCardTitle = "ОБНОВЛЕНИЕ ЧЕРЕЗ GITHUB И ПОДПИСЬ APK",
    githubUpdateSub = "Проверка новых релизов на GitHub. При установке поверх старой версии (подписанной тем же сертификатом) все будильники и настройки сохраняются.",
    githubRepoFieldLabel = "Репозиторий GitHub (owner/repo)",
    checkGithubUpdateBtn = "Проверить обновления на GitHub",
    openGithubReleaseBtn = "Открыть страницу релиза GitHub",
    signingCertLabel = { "SHA-256 сертификата APK: $it" },
    currentVersionFormat = { verName, verCode -> "Установлена версия: v$verName (код $verCode)" },
    qrGeneratorCardTitle = "КОНТРОЛЬНЫЙ QR-КОД ДЛЯ ВАННОЙ / КУХНИ",
    qrGeneratorSub = "Распечатайте или сфотографируйте этот QR-код и разместите его вдали от кровати.",
    qrPayloadFieldLabel = "Строка контрольного QR-кода",
    shareQrPayloadBtn = "Отправить строку QR-кода для печати",
    nfcFieldLabel = "Ожидаемый NFC Tag ID (* = любая метка)",
    backupChannelsCardTitle = "РЕЗЕРВНЫЕ КАНАЛЫ MVP (УМНЫЙ ДОМ И КОНТАКТ)",
    sunriseWebhookFieldLabel = "Webhook «Рассвет» T−15 (Home Assistant / лампа)",
    testSunriseWebhookBtn = "Проверить Webhook «Рассвет»",
    overdueWebhookFieldLabel = "Webhook при просрочке (Overdue Escalation)",
    testOverdueWebhookBtn = "Проверить Webhook просрочки",
    contactNameFieldLabel = "Имя резервного контакта",
    contactPhoneFieldLabel = "Телефон резервного контакта",
    testContactBtn = "Проверить оповещение резервного контакта",
    saveBackupSettingsBtn = "СОХРАНИТЬ НАСТРОЙКИ РЕЗЕРВА",
    stubsCardTitle = "ИНТЕРФЕЙСЫ POST-MVP КАНАЛОВ (STUBS)",
    stubsCardSub = "Архитектурные адаптеры BackupChannel с чистыми контрактами и записью в локальный журнал:",
    soundAndVibroCardTitle = "СИГНАЛ БУДИЛЬНИКА: ЗВУК, ВИБРАЦИЯ И МЕЛОДИИ",
    soundAndVibroCardSub = "Выберите режим подачи сигнала (Звук + Вибро / Только звук / Только вибро) и мелодию: стандартный будильник Android, любую системную мелодию телефона или встроенный синтезатор.",
    signalModeLabel = "Режим подачи сигнала:",
    soundSourceLabel = "Мелодия / Источник звука:",
    selectedRingtoneLabel = { "Выбранная мелодия телефона: $it" },
    pickSystemRingtoneBtn = "Выбрать системную мелодию телефона",
    previewSignalBtn = "Прослушать выбранный сигнал (4 сек)",
    stopPreviewSignalBtn = "Остановить прослушивание"
)

val EnglishStrings = AppStrings(
    isEnglish = true,
    appTitle = "Alarm Commander",
    headerSubtitle = "TACTICAL WAKE-UP CONTROL",
    navCommandCenter = "HQ",
    navPermissions = "Guard",
    navLogs = "Logs",
    navSettings = "Config",
    disclaimerTitle = "Important Safety Disclaimer",
    disclaimerBody = "Alarm Commander is a multi-layered risk-reduction system against oversleeping, NOT a medical or life-support device. It does not guarantee 100% wake-up under hardware failure, battery depletion, or OS termination. Hazardous methods (strobes >2 Hz, covert recording, or unauthorized data transfer) are strictly prohibited.",
    disclaimerAccept = "I Understand & Accept",
    disclaimerShortBanner = "Multi-layered anti-oversleep system • 100% local storage, zero telemetry",
    radarDutyActive = "ACTIVE STANDBY",
    osPermissionsShort = { granted, total -> "OS: $granted/$total" },
    nextT0Format = { time, h, m -> "Next T0: $time (in ${h}h ${m}m)" },
    profileLabelFormat = { label, prof -> "$label • Profile $prof" },
    allAlarmsDisabled = "All Alarms Disabled",
    enableAlarmOrTestHint = "Enable an alarm or run a combat T0 test below",
    provingGroundTitle = "SIGNAL & MISSION PROVING GROUND",
    provingGroundSubtitle = "Test volume escalation, Back-button lock, mission chains, and anti-return check right now.",
    testT0Btn = "Test T0",
    testSunriseBtn = "Sunrise T−15",
    testAntiReturnBtn = "Test Anti-Return (60s timer)",
    newAlarmBtn = "New Alarm",
    emptyAlarmsTitle = "No Active Alarms",
    emptyAlarmsHint = "Create your first multi-layered alarm or run a combat test above.",
    runTestBtn = "Run Test",
    escalationBadge = { "Escalation: $it" },
    missionsBadge = { "Missions: $it" },
    antiSnoozeBadge = "Anti-Snooze",
    penaltySnoozeBadge = "Penalty-Snooze",
    sunriseBadge = { "Sunrise T−${it}m" },
    antiReturnBadge = { "Anti-Return ${it}m" },
    dayNamesShort = listOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 7 to "Su"),
    everyDay = "Every day",
    weekdays = "Weekdays (Mon–Fri)",
    weekends = "Weekends (Sat–Sun)",
    editorNewTitle = "New Tactical Alarm",
    editorEditTitle = "Edit Alarm Configuration",
    editorLabelField = "Alarm Label",
    editorDaysOfWeek = "Days of week:",
    editorEscalationProfile = "Escalation profile:",
    editorMissionChain = "Mission chain (pick 1–5 in order):",
    editorMathDifficulty = "Math difficulty:",
    editorTargetSteps = { "Target step count: $it" },
    editorExpectedQr = "Expected bathroom QR code",
    editorExpectedNfc = "NFC Tag ID (* = any tag)",
    editorExpectedVoice = "Verification voice phrase",
    editorAllowPenaltySnooze = "Allow penalty Snooze (+5 min)",
    editorSunrisePreAlarm = "Pre-alarm Sunrise at T−15 min",
    editorAntiReturn = "Anti-Return to bed check (5 min)",
    editorBackupChannels = "Backup: Smart Home Webhook & Contact",
    saveAlarmBtn = "Save Alarm",
    cancelBtn = "Cancel",
    activeCombatBanner = "COMBAT ALARM • AUTOPILOT LOCKED",
    profileStageFormat = { prof, stage -> "PROFILE: $prof • STAGE $stage/4" },
    backupChannelPrefix = "Backup channel:",
    unlockMissionChainHeader = { "UNLOCK MISSION CHAIN ($it)" },
    startMissionBtn = "START DISMISSAL MISSION",
    penaltySnoozeBtn = { "Penalty Snooze (+$it min via phrase)" },
    antiSnoozeLockedLabel = "Anti-Snooze mode: alarm snoozing is locked",
    missionStepProgress = { idx, total -> "MISSION $idx OF $total" },
    mathSolvedProgress = { step, total -> "Solved equations: $step / $total" },
    mathAnswerLabel = "Answer",
    submitMathBtn = "VERIFY ANSWER",
    stepsCountFormat = { cur, target -> "$cur / $target STEPS" },
    stepsSensorSource = { "Sensor: $it" },
    grantStepSensorBtn = "Grant Hardware Step Sensor Permission",
    stepsEmulatorHint = "Walk holding your phone or alternate tapping LEFT STEP / RIGHT STEP (emulator test mode):",
    leftStepBtn = "LEFT STEP (+5)",
    rightStepBtn = "RIGHT STEP (+5)",
    qrExpectedLabel = { "Expected QR: $it" },
    enableCameraQrBtn = "Enable Camera for QR Scanning",
    qrFallbackHint = "Fallback input (if camera is unavailable): enter the exact QR code string manually",
    qrInputLabel = { "QR Code ($it)" },
    submitQrBtn = "CONFIRM QR CODE",
    nfcTitle = "Hold your phone against the NFC tag",
    nfcExpectedIdLabel = { if (it == "*") "Expected ID: Any registered tag" else "Expected ID: $it" },
    nfcFallbackLabel = { "Fallback code without NFC: $it" },
    submitNfcBtn = "CONFIRM NFC TAG",
    voiceInstruction = "Speak the verification phrase aloud:",
    voiceListeningBtn = "LISTENING... SPEAK NOW",
    voiceStartBtn = "START VOICE RECOGNITION",
    voiceFallbackLabel = "Fallback manual phrase input",
    submitVoiceBtn = "CONFIRM PHRASE",
    penaltyCardTitle = "PENALTY SNOOZE CHALLENGE",
    penaltyCardInstruction = "To snooze the alarm for 5 minutes, type this exact phrase without errors:",
    penaltyInputLabel = "Repeat phrase",
    backBtn = "Back",
    confirmBtn = "Confirm",
    sunrisePhaseHeader = "SUNRISE PHASE (T−15 MIN)",
    sunriseDescription = "Soft screen illumination and smart lamp webhook prepare your body for waking up before the T0 alarm.",
    dismissSunriseBtn = "I AM AWAKE • END SUNRISE",
    antiReturnHeader = "ANTI-RETURN TO BED CHECK",
    antiReturnSecondsFormat = { "$it SEC" },
    antiReturnBody = { "Alarm \"$it\": confirm that you have not returned to bed. If the timer expires, the full alarm and missions will restart!" },
    confirmAntiReturnBtn = "I AM AWAKE • OUT OF BED",
    securityPowerAuditTitle = "SECURITY & POWER EFFICIENCY AUDIT",
    batteryEcoStatusFormat = { pct, charging, eco ->
        "Battery: $pct% (${if (charging) "Charging" else "Unplugged"}) • Eco-mode: ${if (eco) "ACTIVE" else "STANDBY"}"
    },
    permissionsChecklistTitle = "ANDROID 8–16 PERMISSIONS CHECKLIST",
    permissionsChecklistSub = "All permissions are requested step-by-step. The app NEVER uses SYSTEM_ALERT_WINDOW.",
    permActive = "GRANTED",
    permOptional = "OPTIONAL",
    permRequired = "REQUIRED",
    logsHeaderTitle = { "LOCAL EVENT JOURNAL ($it)" },
    logsHeaderSub = "All triggers, escalation stages, and missions are stored strictly on-device.",
    exportLogsBtn = "Export",
    clearLogsBtn = "Clear",
    emptyLogsLabel = "No journal entries yet",
    appearanceAndLangTitle = "INTERFACE LANGUAGE & THEME",
    themeSectionLabel = "App Theme:",
    themeDark = "Dark",
    themeLight = "Light",
    themeSystem = "System (OS)",
    languageSectionLabel = "Interface Language:",
    langSystem = "Auto (OS)",
    langRussian = "Русский",
    langEnglish = "English",
    githubUpdateCardTitle = "GITHUB RELEASES UPDATE & APK SIGNATURE",
    githubUpdateSub = "Check for new releases on GitHub. Installing a new version signed with the same certificate updates the app in-place while keeping all alarms.",
    githubRepoFieldLabel = "GitHub Repository (owner/repo)",
    checkGithubUpdateBtn = "Check for Updates on GitHub",
    openGithubReleaseBtn = "Open GitHub Releases Page",
    signingCertLabel = { "APK Certificate SHA-256: $it" },
    currentVersionFormat = { verName, verCode -> "Installed version: v$verName (code $verCode)" },
    qrGeneratorCardTitle = "CONTROL QR CODE FOR BATHROOM / KITCHEN",
    qrGeneratorSub = "Print or photograph this QR code and place it away from your bed.",
    qrPayloadFieldLabel = "Control QR Code Payload",
    shareQrPayloadBtn = "Share QR Code String for Printing",
    nfcFieldLabel = "Expected NFC Tag ID (* = any tag)",
    backupChannelsCardTitle = "BACKUP CHANNELS (SMART HOME & CONTACT)",
    sunriseWebhookFieldLabel = "Sunrise T−15 Webhook (Home Assistant / Lamp)",
    testSunriseWebhookBtn = "Test Sunrise Webhook",
    overdueWebhookFieldLabel = "Overdue Escalation Webhook",
    testOverdueWebhookBtn = "Test Overdue Webhook",
    contactNameFieldLabel = "Backup Contact Name",
    contactPhoneFieldLabel = "Backup Contact Phone",
    testContactBtn = "Test Backup Contact Alert",
    saveBackupSettingsBtn = "SAVE BACKUP SETTINGS",
    stubsCardTitle = "POST-MVP CHANNEL INTERFACES (STUBS)",
    stubsCardSub = "Clean BackupChannel adapters logging test invocations locally:",
    soundAndVibroCardTitle = "ALARM SIGNAL: SOUND, VIBRATION & RINGTONES",
    soundAndVibroCardSub = "Choose the signal mode (Sound + Vibro / Sound Only / Vibro Only) and sound source: default Android alarm, any system phone melody, or built-in tactical synthesizer.",
    signalModeLabel = "Signal Mode:",
    soundSourceLabel = "Alarm Melody / Sound Source:",
    selectedRingtoneLabel = { "Selected phone melody: $it" },
    pickSystemRingtoneBtn = "Pick System Phone Melody / Ringtone",
    previewSignalBtn = "Preview Selected Signal (4 sec)",
    stopPreviewSignalBtn = "Stop Signal Preview"
)

val LocalAppStrings = compositionLocalOf { RussianStrings }

fun MissionType.localizedTitle(isEnglish: Boolean): String = if (!isEnglish) titleRu else when (this) {
    MissionType.MATH -> "Math"
    MissionType.STEPS -> "Steps (50–100)"
    MissionType.QR -> "Bathroom QR"
    MissionType.NFC -> "NFC Tag"
    MissionType.VOICE -> "Voice Phrase"
}

fun MissionType.localizedBadge(isEnglish: Boolean): String = if (!isEnglish) badgeRu else when (this) {
    MissionType.MATH -> "MATH"
    MissionType.STEPS -> "STEPS"
    MissionType.QR -> "QR"
    MissionType.NFC -> "NFC"
    MissionType.VOICE -> "VOICE"
}

fun EscalationProfile.localizedTitle(isEnglish: Boolean): String = if (!isEnglish) titleRu else when (this) {
    EscalationProfile.GENTLE -> "Gentle (Soft Start)"
    EscalationProfile.HARD -> "Hard (Tactical)"
    EscalationProfile.EXTREME_SAFE -> "ExtremeSafe (Max Safe)"
}

fun EscalationProfile.localizedSubtitle(isEnglish: Boolean): String = if (!isEnglish) subtitleRu else when (this) {
    EscalationProfile.GENTLE -> "Smooth start 25% -> 55% (45s) -> 85% (90s), backup at 4 min"
    EscalationProfile.HARD -> "Start 45% -> 80% siren (30s) -> 100% + vibro pulse (60s), backup 3 min"
    EscalationProfile.EXTREME_SAFE -> "Start 65% -> 95% (20s) -> 100% dual-tone siren (40s), backup 2 min"
}

fun MathDifficulty.localizedLabel(isEnglish: Boolean): String = if (!isEnglish) titleRu else when (this) {
    MathDifficulty.EASY -> "Easy (24 + 19)"
    MathDifficulty.MEDIUM -> "Medium (16 × 4 − 15)"
    MathDifficulty.HARD -> "Hard ((18 × 7) + 49 − 23)"
}

/**
 * Переводит динамические строки сервисов, датчиков, миссий, предзаполненных будильников и логов
 * на английский язык, когда выбран английский интерфейс (isEnglish == true).
 */
fun String.localizeRuntimeText(isEnglish: Boolean): String {
    if (!isEnglish || this.isBlank()) return this
    val text = this

    // 1. Точные совпадения (предзаполненные названия, статусы датчиков, дефолтные фразы)
    when (text) {
        "Подъём • Рабочий рубеж" -> return "Wake-Up • Workday"
        "Субботний марш • Ванная QR" -> return "Saturday March • Bathroom QR"
        "Боевой тест Alarm Commander" -> return "Alarm Commander Combat Test"
        "Контроль анти-возврата" -> return "Anti-Return Check"
        "Утренний подъём" -> return "Morning Wake-Up"
        "Боевой рубеж" -> return "Tactical Alarm"
        "Дежурный контакт" -> return "Emergency Contact"
        "Я полностью проснулся и готов к действию" -> return "I am fully awake and ready for action"
        "ПОДТВЕРЖДАЮ ПЕРЕНОС 5 МИН" -> return "I CONFIRM 5 MIN SNOOZE"
        "Датчик движения активен" -> return "Motion sensor active"
        "Ожидание сканирования QR-кода..." -> return "Waiting for QR code scan..."
        "NFC-ридер ожидает касания метки" -> return "NFC reader waiting for tag tap"
        "Произнесите контрольную фразу чётко вслух" -> return "Speak the verification phrase clearly aloud"
        "Ошибка! Попробуйте ещё раз без автопилота." -> return "Incorrect! Try again without autopilot."
        "Для Snooze введите контрольную фразу без ошибок" -> return "Type the verification phrase without typos to Snooze"
        "Фраза введена неверно! Автопилот не пройдёт." -> return "Phrase mismatch! Autopilot blocked."
        "Аппаратный STEP_DETECTOR" -> return "Hardware STEP_DETECTOR"
        "Аппаратный STEP_COUNTER" -> return "Hardware STEP_COUNTER"
        "Акселерометр (импульсный шагомер)" -> return "Accelerometer (step detector)"
        "Датчик шагов не обнаружен" -> return "Step sensor not found"
        "Ожидание датчика" -> return "Waiting for sensor"
        "Инициализация датчика..." -> return "Initializing step sensor..."
        "Нажмите кнопку микрофона и произнесите фразу вслух" -> return "Tap the microphone button and speak the phrase aloud"
        "Системный сервис распознавания речи недоступен на этом эмуляторе — используйте контрольный ввод ниже." ->
            return "System speech recognition is unavailable on this device — use the fallback input below."
        "Микрофон активен: чётко произнесите фразу..." -> return "Microphone active: speak the phrase clearly..."
        "Фиксация голоса..." -> return "Capturing voice..."
        "Обработка произнесённой фразы..." -> return "Processing spoken phrase..."
        "Пустой результат" -> return "Empty speech result"
        "Речь не распознана. Повторите фразу громче." -> return "Speech not recognized. Repeat the phrase louder."
        "Резервная тревога активирована" -> return "Backup alert triggered"
        "Настройки сохранены / Settings saved" -> return "Settings saved"
        "Журнал очищен / Logs cleared" -> return "Logs cleared"
        "Запущена фаза «Рассвет» (T−15 мин)" -> return "Sunrise phase started (T−15 min)"
        "Рассвет T−15 завершён пользователем" -> return "Sunrise T−15 dismissed by user"
        "Запущена 60-секундная проверка бодрствования (Анти-возврат в кровать)" ->
            return "60-second wakefulness verification started (Anti-Return to Bed)"
        "Нет подтверждения анти-возврата за 60 сек — повторный запуск боевой тревоги!" ->
            return "No Anti-Return confirmation within 60s — restarting full combat alarm!"
        "Проверка анти-возврата успешно подтверждена: пользователь бодрствует." ->
            return "Anti-Return check confirmed: user is awake."
        "Пользователь ознакомился с условиями безопасности и ограничением ответственности" ->
            return "User accepted the safety disclaimer and liability terms"
        "Initial Seed: Подъём • Рабочий рубеж (07:00) & Субботний марш (08:30)" ->
            return "Initial Seed: Wake-Up • Workday (07:00) & Saturday March (08:30)"
    }

    // 2. Шаблонные и составные сообщения (миссии, датчики, логи, резервные каналы)
    var translated = text
        .replace("Подъём • Рабочий рубеж", "Wake-Up • Workday")
        .replace("Субботний марш • Ванная QR", "Saturday March • Bathroom QR")
        .replace("Боевой тест Alarm Commander", "Alarm Commander Combat Test")
        .replace("Контроль анти-возврата", "Anti-Return Check")
        .replace("Удалён / Deleted:", "Deleted:")
        .replace("Математика", "Math")
        .replace("Шагомер (50–100)", "Steps (50–100)")
        .replace("QR-код в ванной", "Bathroom QR")
        .replace("NFC-метка", "NFC Tag")
        .replace("Голосовая фраза", "Voice Phrase")
        .replace("любая NFC-метка", "any NFC tag")

    Regex("""Решите (\d+) примера подряд""").find(translated)?.let { m ->
        return "Solve ${m.groupValues[1]} problems in a row"
    }
    Regex("""Верно! Осталось: (\d+)""").find(translated)?.let { m ->
        return "Correct! Remaining: ${m.groupValues[1]}"
    }
    Regex("""Осталось пройти: (\d+) шагов""").find(translated)?.let { m ->
        return "Remaining steps: ${m.groupValues[1]}"
    }
    Regex("""Шагов пройдено: (\d+) / (\d+)""").find(translated)?.let { m ->
        return "Steps walked: ${m.groupValues[1]} / ${m.groupValues[2]}"
    }
    Regex("""Встаньте с кровати и пройдите (\d+) шагов""").find(translated)?.let { m ->
        return "Get out of bed and walk ${m.groupValues[1]} steps"
    }
    Regex("""Неверный QR-код \(«(.*)»\)\. Нужен код: «(.*)»""").find(translated)?.let { m ->
        return "Wrong QR code (\"${m.groupValues[1]}\"). Expected: \"${m.groupValues[2]}\""
    }
    Regex("""Чужая NFC-метка \((.*)\)\. Ожидалась: (.*)""").find(translated)?.let { m ->
        return "Unrecognized NFC tag (${m.groupValues[1]}). Expected: ${m.groupValues[2]}"
    }
    Regex("""Распознано: «(.*)»\. Произнесите полную фразу чётко!""").find(translated)?.let { m ->
        return "Recognized: \"${m.groupValues[1]}\". Speak the full phrase clearly!"
    }
    Regex("""Распознано: «(.*)»""").find(translated)?.let { m ->
        return "Recognized: \"${m.groupValues[1]}\""
    }
    Regex("""Слышу: «(.*)»\.\.\.""").find(translated)?.let { m ->
        return "Hearing: \"${m.groupValues[1]}\"..."
    }
    Regex("""Голос не распознан \(код (\d+)\)\..*""").find(translated)?.let { m ->
        return "Speech not recognized (code ${m.groupValues[1]}). Tap the mic again or type the phrase below."
    }
    Regex("""Не удалось запустить SpeechRecognizer: (.*)""").find(translated)?.let { m ->
        return "Failed to start SpeechRecognizer: ${m.groupValues[1]}"
    }
    Regex("""Этап (\d+)/(\d+) пройден! Следующая миссия: (.*)""").find(translated)?.let { m ->
        return "Stage ${m.groupValues[1]}/${m.groupValues[2]} completed! Next mission: ${m.groupValues[3]}"
    }
    Regex("""Сигнал T0 запущен \(Профиль: (.*), миссии: (.*)\)""").find(translated)?.let { m ->
        return "T0 alarm started (Profile: ${m.groupValues[1]}, missions: ${m.groupValues[2]})"
    }
    Regex("""Начато выполнение миссии: (.*)""").find(translated)?.let { m ->
        return "Mission started: ${m.groupValues[1]}"
    }
    Regex("""Запланирован контроль анти-возврата через (\d+) мин""").find(translated)?.let { m ->
        return "Anti-Return check scheduled in ${m.groupValues[1]} min"
    }
    Regex("""Все миссии выполнены \((.*)\)\. Будильник штатно отключён\.""").find(translated)?.let { m ->
        return "All missions completed (${m.groupValues[1]}). Alarm dismissed."
    }
    Regex("""Выполнен штрафной Snooze на (\d+) мин""").find(translated)?.let { m ->
        return "Penalty Snooze activated for ${m.groupValues[1]} min"
    }

    translated = translated
        .replace("Не удалось отправить Webhook", "Webhook request failed")
        .replace("Webhook успешно доставлен", "Webhook delivered")
        .replace("Локальная симуляция Webhook", "Local Webhook simulation")
        .replace("Создано экстренное уведомление для связи с контактом", "Emergency contact alert created for")
        .replace("Заглушка канала вызвана", "Channel stub invoked")
        .replace("Авто-звонок другу (Stub)", "Auto-Call Friend (Stub)")
        .replace("Умная колонка в другой комнате (Stub)", "Living Room Smart Speaker (Stub)")
        .replace("Вибро-шайба под матрас (Stub)", "Under-Mattress Bed Shaker (Stub)")
        .replace("Второе устройство в локальной сети (Stub)", "Second Device on LAN (Stub)")

    return translated
}

