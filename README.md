# Alarm Commander — Многослойная система снижения риска проспать (Android MVP)

> **Дисклеймер безопасности:** `Alarm Commander` — это многослойный инструмент снижения риска проспать, а **не** медицинское устройство и не средство жизнеобеспечения. Приложение не гарантирует 100% пробуждение при аппаратных сбоях, полном разряде аккумулятора или принудительной остановке со стороны OEM-оболочки. В приложении строго исключены опасные практики (стробоскопы >2 Гц, электрошок, экстремальные частоты, скрытая аудиозапись и отправка данных без согласия).

---

## 1. Архитектура и структура пакетов (`com.example`)

Проект построен по принципам **MVVM + Clean-ish Architecture** (`UI → ViewModel → Domain Engines / Coordinators → Repository → Room / DataStore / Android System Services`):

```text
app/src/main/java/com/example/
├── MainActivity.kt                  # Точка входа, флаги экрана блокировки, NFC ReaderMode, навигация
├── alarm/
│   ├── AlarmScheduler.kt            # Точное планирование T0 (setAlarmClock), Рассвета T−15 и Анти-возврата
│   ├── AlarmReceiver.kt             # BroadcastReceiver сигналов AlarmManager + кратковременный WakeLock
│   ├── AlarmService.kt              # Foreground Service звонка, эскалации, миссий и защиты от Process Death
│   ├── AlarmAudioController.kt      # Двухгенераторный PCM-синтезатор (USAGE_ALARM) + управление вибрацией
│   ├── BootReceiver.kt              # Перепланирование активных будильников после BOOT_COMPLETED
│   └── TimeChangeReceiver.kt        # Перепланирование при TIME_SET и TIMEZONE_CHANGED
├── data/
│   ├── Entities.kt                  # Room-сущности (AlarmEntity, AlarmLogEntity) и доменные enum-модели
│   ├── AlarmDao.kt                  # Реактивные Flow-запросы и suspend-операции
│   ├── AppDatabase.kt               # Singleton Room Database (alarm_commander.db)
│   └── AlarmRepository.kt           # Репозиторий будильников, стартовых шаблонов и локального журнала
├── escalation/
│   └── EscalationEngine.kt          # Машина состояний, профили Gentle/Hard/ExtremeSafe, затухание на время миссии
├── mission/
│   ├── MissionValidator.kt          # Контракт Mission, MathMission, StepsMission, QrMission, NfcMission, ChainCoordinator
│   ├── QrCodeUtils.kt               # Генератор QR-кода (ZXing) и потоковый анализатор CameraX ImageAnalysis
│   └── StepSensorTracker.kt         # Шагомер (STEP_DETECTOR / STEP_COUNTER + резервный фильтр акселерометра)
├── backup/
│   └── BackupChannel.kt             # Контракт BackupChannel, SmartHomeWebhook (OkHttp), SocialContact, Post-MVP Stubs
├── permissions/
│   └── PermissionManager.kt         # Проверка и пошаговое объяснение разрешений Android 12–16
├── settings/
│   └── SettingsRepository.kt        # Preferences DataStore (настройки резерва + снимок сессии для Process Death)
└── ui/
    ├── AlarmCommanderViewModel.kt   # Координация UI-состояния и боевых тестов
    ├── screens/
    │   ├── CommandCenterScreen.kt   # Главный штаб, полигон тестов T0/Рассвет/Анти-возврат, редактор рубежей
    │   ├── ActiveAlarmScreen.kt     # Незакрываемый экран тревоги (BackHandler), HUD эскалации и раннеры миссий
    │   └── PermissionsAndSettingsScreens.kt # Чек-лист разрешений, локальный журнал, генератор QR и настройки резерва
    └── theme/
        ├── Color.kt                 # Тактическая палитра (Obsidian, Slate, Tactical Amber, Radar Emerald)
        ├── Type.kt                  # Локальные шрифты JetBrains Mono и Rubik
        └── Theme.kt                 # Тема Material 3
```

---

## 2. Как собрать и запустить проект

1. **Сборка Debug APK из командной строки:**
   ```bash
   gradle :app:assembleDebug
   ```
2. **Запуск Unit и Robolectric тестов:**
   ```bash
   gradle :app:testDebugUnitTest
   ```
3. **Запуск в Android Studio:**
   - Откройте корневую папку проекта в Android Studio (Ladybug / Meerkat или новее).
   - Дождитесь синхронизации Gradle и нажмите **Run (`app`)** на устройстве или эмуляторе с Android 8.0+ (API 26–36).

---

## 3. Необходимые разрешения и как их выдать

Откройте вкладку **«Разрешения»** внутри приложения:
1. **Точные будильники (`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`)**: обеспечивает пробуждение процессора точно в секунду `T0` через `AlarmManager.setAlarmClock`.
2. **Полноэкранный вызов (`USE_FULL_SCREEN_INTENT`)**: на Android 14+ (API 34+) проверяется через `NotificationManager.canUseFullScreenIntent()`. Позволяет включать экран и показывать окно миссии поверх блокировки **без** использования опасного разрешения `SYSTEM_ALERT_WINDOW`.
3. **Уведомления (`POST_NOTIFICATIONS`)**: требуется на Android 13+ для отображения несмахиваемого уведомления Foreground-сервиса.
4. **Камера (`CAMERA`)** и **Физическая активность (`ACTIVITY_RECOGNITION`)**: запрашиваются только для миссий QR-кода и Шагомера.
5. **Обход «Не беспокоить» (`ACCESS_NOTIFICATION_POLICY`)**: опциональное разрешение, позволяющее каналу будильника пробивать режим DND.

---

## 4. Как протестировать все сценарии будильника прямо сейчас

На главном экране (**«Штаб»**) расположена карточка **«Полигон проверки сигнала и миссий»**:
- **Кнопка «Тест T0»**: мгновенно запускает `AlarmService` в режиме боевой тревоги. Вы увидите нарастание уровней эскалации (`Stage 1 → 2 → 3 → 4`), блокировку кнопки «Назад», приглушение звука на 25 секунд при переходе к миссии и последовательное выполнение цепочки миссий (`Математика → Шаги`).
- **Кнопка «Рассвет T−15»**: включает экран мягкого янтарного свечения и отправляет JSON-запрос на настроенный Webhook умного дома.
- **Кнопка «Тест Анти-возврат в кровать»**: открывает 60-секундный контрольный таймер. Если не подтвердить бодрствование за 60 секунд, автоматически запускается полная боевая тревога T0.
- **Печать/сохранение QR-кода**: откройте вкладку **«Резерв и QR»** — приложение сгенерирует готовый QR-код (`ALARM-COMMANDER-BATHROOM-01`), который можно сфотографировать или распечатать.

---

## 5. Особенности и ограничения Android 12–16

- **Android 12 (API 31+)**: запуск Foreground-сервисов из фона ограничен, но будильники, использующие `AlarmManager.setAlarmClock()`, получают системное исключение для старта `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`.
- **Android 13 (API 33+)**: уведомления требуют явного runtime-разрешения `POST_NOTIFICATIONS`.
- **Android 14–16 (API 34–36)**: полноэкранные интенты (`USE_FULL_SCREEN_INTENT`) по умолчанию разрешены только приложениям категории будильников/звонков; для остальных требуется подтверждение через `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (реализовано в `PermissionManager`).
- **OEM Battery Killers (Xiaomi MIUI/HyperOS, Samsung OneUI, Huawei, Realme)**: агрессивные оболочки могут блокировать автозапуск после перезагрузки; пользователям рекомендуется отключить оптимизацию батареи для приложения в системных настройках.
