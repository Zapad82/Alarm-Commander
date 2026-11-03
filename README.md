# Alarm Commander — Многослойный тактический будильник / Multi-Layered Tactical Wake-Up System (Android)

> **[RU]** Боевой Android-будильник (Kotlin + Jetpack Compose + Material 3), исключающий выключение на автопилоте за счёт 4-ступенчатой эскалации сигнала, цепочек физических и когнитивных миссий, контроля «Анти-возврат в кровать» и локальных резервных каналов.  
> **[EN]** Production-ready tactical Android alarm system (Kotlin + Jetpack Compose + Material 3) that prevents dismissing alarms on autopilot through 4-stage acoustic/haptic escalation, physical & cognitive mission chains, Anti-Return-to-Bed verification, and local backup channels.

---

## 🇷🇺 ОПИСАНИЕ ПРОЕКТА (РУССКИЙ)

### 1. Продуктовая концепция и ключевые возможности
**Alarm Commander** спроектирован для людей, которые выключают обычные будильники во сне («на автопилоте») или возвращаются в кровать сразу после сигнала:
- **Фаза «Рассвет» (`T−15 мин`)**: мягкий световой градиент экрана и локальный HTTP/HTTPS Webhook на умную лампу (Home Assistant / MQTT-шлюз) за 15 минут до основного подъёма.
- **Точное пробуждение в `T0` (`AlarmManager.setAlarmClock`)**: гарантированный выход процессора из режима глубокого сна Doze, запуск `ForegroundService` (`specialUse`), `PARTIAL_WAKE_LOCK` с жёстким предохранителем 10 минут и полноэкранный вызов (`USE_FULL_SCREEN_INTENT`) поверх экрана блокировки.
- **3 профиля 4-ступенчатой эскалации (`Gentle`, `Hard`, `ExtremeSafe`)**:
  - Нарастание громкости в потоке `USAGE_ALARM`, уплотнение вибро-паттерна и двухтональная сирена на пиковой стадии.
  - Во время выполнения миссии громкость автоматически приглушается до **25%** (Acoustic Safety), чтобы вы могли спокойно решить задачу, но мгновенно возвращается к **100%**, если миссия заброшена.
- **Цепочка миссий (1–5 задач подряд для отключения)**:
  1. **Математика (`MATH`)**: арифметические уравнения (`EASY` / `MEDIUM` / `HARD`) со встроенным крупным тактическим Numpad на экране блокировки.
  2. **Шагомер (`STEPS`)**: 20–100 физических шагов через аппаратный `TYPE_STEP_COUNTER` (или фильтр пиков акселерометра `TYPE_ACCELEROMETER`).
  3. **QR-код в ванной (`QR`)**: потоковое сканирование CameraX + ZXing `MultiFormatReader` в памяти (`ImageProxy` закрывается в `finally` без сохранения фото на диск) + встроенный генератор QR-кода для печати.
  4. **NFC-метка (`NFC`)**: чтение аппаратного `NfcAdapter.enableReaderMode` только в окне активной NFC-миссии + когнитивный резервный код, если модуль NFC недоступен.
  5. **Голосовая фраза (`VOICE`)**: проверка произнесения контрольной фразы вслух через `SpeechRecognizer` (сходство по Левенштейну ≥ 65%).
- **Анти-Snooze и Penalty-Snooze**: перенос сигнала по умолчанию заблокирован (`BackHandler` перехватывает жест «Назад»); если штрафной Snooze разрешён в настройках рубежа, для отсрочки на 5 минут требуется безошибочно набрать длинную контрольную фразу.
- **Контроль «Анти-возврат в кровать»**: через 5 минут после успешного закрытия миссий запускается 60-секундная проверка бодрствования. Если не нажать кнопку подтверждения — будильник перезапускает полную боевую тревогу `T0`.
- **Резервные каналы (`BackupChannel`)**:
  - Локальный `SmartHomeWebhookChannel` (защита от утечек в открытый HTTP: разрешены только `https://` или локальные подсети RFC-1918 `192.168.x.x` / `10.x.x.x` / `.local`, экранирование JSON по RFC-8259).
  - `SocialContactChannel` (экстренное уведомление с быстрым запуском SMS `smsto:` или вызова `tel:` дежурному контакту).
  - Архитектурные контракты-заглушки (`Stubs`) для Post-MVP: `SecondDeviceStubChannel`, `SmartSpeakerStubChannel`, `BedShakerStubChannel`, `FriendVoipCallStubChannel`.
- **Двуязычный интерфейс (`RU` / `EN` / `Авто`) и Темы (`Тёмная` / `Светлая` / `Системная`)**: мгновенное переключение прямо из верхней панели или вкладки **Опции**.
- **100% локально**: Room SQLite + Jetpack DataStore, без рекламы, аналитики и платных SDK.

---

### 2. Подпись собственным сертификатом и обновление поверх старой версии (GitHub Releases)

Чтобы **Google Play Защита (Play Protect)** и антивирусы доверяли вашему APK, а новые версии устанавливались **поверх старых без удаления приложения и без потери будильников/настроек**, соблюдайте 3 правила Android:
1. **Неизменный `applicationId`**: в `app/build.gradle.kts` зафиксирован `com.aistudio.alarmcommander.tqxk`.
2. **Единый сертификат подписи (`.jks`)**: каждая новая сборка должна быть подписана **тем же самым** файлом ключа `my-upload-key.jks`. Android сверяет SHA-256 отпечаток сертификата (его можно увидеть прямо в приложении на вкладке **Опции → Обновление через GitHub и Подпись APK**).
3. **Монотонное увеличение `versionCode`**: при каждом новом релизе в `app/build.gradle.kts` увеличивайте `versionCode` (например, `1` → `2` → `3`) и `versionName` (`"1.0"` → `"1.1"`).

#### Шаг 1. Создание собственного ключа подписи (`my-upload-key.jks`)
Выполните один раз на своём компьютере (требуется JDK `keytool`):
```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Alarm Commander, OU=Mobile Security, O=AlarmCommander, L=Limassol, S=Limassol, C=CY"
```
> Сохраните `my-upload-key.jks` в надёжном месте и **никогда не коммитьте его в публичный Git-репозиторий**.

#### Шаг 2. Локальная сборка подписанного Release APK
Конфигурация `signingConfigs.getByName("release")` в `app/build.gradle.kts` уже настроена на чтение переменных окружения:
```bash
export KEYSTORE_PATH="/полный/путь/к/my-upload-key.jks"
export STORE_PASSWORD="ваш_пароль_хранилища"
export KEY_PASSWORD="ваш_пароль_ключа"

./gradlew assembleRelease
```
Готовый подписанный APK появится в `app/build/outputs/apk/release/app-release.apk`.

#### Шаг 3. Публикация и способы обновления через GitHub Releases
1. **Через интерфейс приложения (рекомендуется, 100% совместимо с Google Play Protect)**:
   - Загрузите подписанный `app-release.apk` в раздел **Releases** вашего репозитория на GitHub (например, тег `v1.1`).
   - В приложении откройте вкладку **Опции (`Config`) → Обновление через GitHub и Подпись APK**.
   - Укажите ваш репозиторий `owner/repo` и нажмите **«Проверить наличие новой версии на GitHub»**.
   - Если найдена более свежая версия, нажмите **«Открыть страницу релизов GitHub / Скачать APK»** — системный браузер скачает подписанный тем же ключом APK и установит его **поверх текущей версии** с сохранением всех будильников и журнала Room.
2. **Через GitHub Actions (автоматический выпуск подписанных APK при создании git-тега)**:
   - Добавьте в `Settings → Secrets and variables → Actions` вашего GitHub-репозитория секреты:
     - `KEYSTORE_BASE64` (вывод команды `base64 -w 0 my-upload-key.jks`)
     - `STORE_PASSWORD`
     - `KEY_PASSWORD`
   - При пуше тега `git tag v1.1 && git push origin v1.1` GitHub Actions декодирует `my-upload-key.jks`, соберёт `assembleRelease` и автоматически прикрепит `alarm-commander-v1.1.apk` к GitHub Release.
3. **Через ADB при разработке**:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   Флаг `-r` (`replace`) обновляет приложение поверх старого с сохранением БД Room и DataStore.

> **Обязательный дисклеймер безопасности**: Приложение является вспомогательным инструментом дисциплины пробуждения, **не гарантирует пробуждение на 100%** и **не является медицинским устройством**. В приложении строго запрещены опасные методы (стробоскопы > 3 Гц, скрытая запись, вредоносные практики).

---

## 🇬🇧 PROJECT DOCUMENTATION (ENGLISH)

### 1. Product Overview & Core Capabilities
**Alarm Commander** is a multi-layered tactical Android alarm app designed for heavy sleepers who unconsciously dismiss standard alarms on autopilot or fall back asleep right after turning them off:
- **Sunrise Pre-Alarm (`T−15 min`)**: Gentle warm screen illumination and a local Smart Home Webhook trigger (Home Assistant / MQTT bridge) 15 minutes before `T0`.
- **Exact Wake-Up at `T0` (`AlarmManager.setAlarmClock`)**: Wakes the device from deep Doze mode, launches a high-priority `ForegroundService` (`specialUse`), acquires a non-reference-counted `PARTIAL_WAKE_LOCK` with a hard 10-minute safety cap, and presents the combat alarm UI over the lockscreen via `USE_FULL_SCREEN_INTENT`.
- **3 Escalation Profiles (`Gentle`, `Hard`, `ExtremeSafe`)**:
  - 4-stage progressive volume ramp on `USAGE_ALARM`, escalating vibration cadence, and dual-tone siren fallback.
  - **Acoustic Safety**: Automatically attenuates alarm volume to **25%** while you are actively solving a mission, and restores **100%** volume if no input is detected within the mission timeout.
- **Unlock Mission Chains (1–5 sequential missions)**:
  1. **Math Equations (`MATH`)**: Multi-step arithmetic (`EASY` / `MEDIUM` / `HARD`) with a built-in lockscreen tactical Numpad.
  2. **Step Counter (`STEPS`)**: Requires 20–100 physical steps using hardware `TYPE_STEP_COUNTER` (with `TYPE_ACCELEROMETER` step-peak fallback).
  3. **Bathroom QR Code (`QR`)**: Real-time on-device CameraX + ZXing `MultiFormatReader` scanning (`ImageProxy` closed in-memory inside `finally`, zero disk storage) + built-in printable QR generator.
  4. **NFC Tag (`NFC`)**: Hardware `NfcAdapter.enableReaderMode` active strictly during the NFC mission window + fallback cognitive code entry.
  5. **Voice Phrase (`VOICE`)**: On-device `SpeechRecognizer` verification with Levenshtein similarity ≥ 65%.
- **Anti-Snooze & Penalty-Snooze**: System back gesture is intercepted (`BackHandler`); if Penalty-Snooze is enabled, postponing the alarm by 5 minutes requires typing an exact verification phrase.
- **Anti-Return-to-Bed Verification**: 5 minutes after completing all missions, a 60-second readiness check triggers. If unconfirmed, the full `T0` alarm and mission chain restart automatically.
- **Bilingual UI (`Auto` / `RU` / `EN`) & Theme Selector (`Dark` / `Light` / `System`)**: Switchable anytime from the top app bar or the **Config** tab.

---

### 2. Custom Certificate Signing & In-Place Updates via GitHub Releases

To ensure **Google Play Protect** and mobile antivirus scanners trust your APK—and to allow installing **new versions over existing ones without losing alarms, logs, or settings**—follow these 3 Android rules:
1. **Consistent `applicationId`**: Keep `com.aistudio.alarmcommander.tqxk` unchanged in `app/build.gradle.kts`.
2. **Consistent Release Keystore (`my-upload-key.jks`)**: Sign every release with the exact same developer certificate. You can verify the active APK SHA-256 certificate fingerprint inside the app under **Config → GitHub Releases Update & APK Signature**.
3. **Increment `versionCode`**: Increase `versionCode` (`1` → `2` → `3`) and `versionName` (`"1.0"` → `"1.1"`) in `app/build.gradle.kts` before building a new release.

#### Step 1: Generate Your Personal Release Keystore (`my-upload-key.jks`)
Run once on your workstation using JDK `keytool`:
```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Alarm Commander, OU=Mobile Security, O=AlarmCommander, L=Limassol, S=Limassol, C=CY"
```
> Keep `my-upload-key.jks` backed up securely and **never commit it to a public Git repository**.

#### Step 2: Build a Signed Release APK
`app/build.gradle.kts` (`signingConfigs.getByName("release")`) is pre-configured to read your keystore credentials from environment variables:
```bash
export KEYSTORE_PATH="/absolute/path/to/my-upload-key.jks"
export STORE_PASSWORD="your_keystore_password"
export KEY_PASSWORD="your_key_password"

./gradlew assembleRelease
```
The signed release APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

#### Step 3: Updating Over Existing Installations via GitHub Releases
1. **In-App GitHub Release Checker (Play Protect & Play Policy Compliant)**:
   - Upload your signed `app-release.apk` to your repository's **GitHub Releases** page (e.g., tag `v1.1`).
   - In the app, open **Config → GitHub Releases Update & APK Signature**, enter your `owner/repo`, and tap **"Check for Updates on GitHub"**.
   - When a newer release tag is detected, tap **"Open GitHub Releases Page / Download APK"**. Android's system browser downloads the APK and installs it **in-place over the existing version**, preserving all Room alarms and DataStore settings because the SHA-256 certificate matches.
2. **Via ADB during local testing**:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

---

### 3. Архитектура и запуск тестов / Architecture & Running Tests

```text
app/src/main/java/com/example/
├── MainActivity.kt                  # Edge-to-edge UI host, NFC reader lifecycle, Theme & Language provider
├── alarm/
│   ├── AlarmScheduler.kt            # Exact AlarmManager.setAlarmClock + Sunrise T-15 scheduling
│   ├── AlarmAudioController.kt      # Progressive volume ramp, vibration cadence & dual-tone synthesis
│   ├── AlarmService.kt              # ForegroundService (specialUse), 10m WakeLock cap, Eco battery guard
│   ├── AlarmReceiver.kt             # BroadcastReceiver for T0, Sunrise & Anti-Return triggers
│   ├── BootReceiver.kt              # Reschedules enabled alarms on BOOT_COMPLETED / MY_PACKAGE_REPLACED
│   └── TimeChangeReceiver.kt        # Recalculates schedules on TIME_SET / TIMEZONE_CHANGED
├── data/
│   ├── Entities.kt                  # AlarmEntity, AlarmLogEntity, MissionType, EscalationProfile
│   ├── AlarmDao.kt                  # Room DAO with 500-row log rotation
│   ├── AppDatabase.kt               # Room SQLite singleton
│   └── AlarmRepository.kt           # Repository + initial tactical presets
├── escalation/
│   └── EscalationEngine.kt          # Pure state machine for 4-stage escalation & 25% mission attenuation
├── mission/
│   ├── MissionValidator.kt          # Pure validators for MATH, STEPS, QR, NFC, VOICE & Penalty-Snooze
│   ├── QrCodeUtils.kt               # ZXing QR generator + safe CameraX analyzer
│   ├── StepSensorTracker.kt         # Hardware TYPE_STEP_COUNTER + accelerometer fallback
│   └── VoiceSpeechHelper.kt         # SpeechRecognizer wrapper
├── backup/
│   └── BackupChannel.kt             # RFC-1918/HTTPS Webhook validator, Social Contact & Post-MVP Stubs
├── permissions/
│   ├── PermissionManager.kt         # Android 8–16 permission evaluator & step-by-step intents
│   └── SafetyAndPowerAuditor.kt     # Real-time battery, WakeLock, sensor & webhook security auditor
├── settings/
│   ├── SettingsRepository.kt        # Jetpack DataStore preferences & process-death session recovery
│   └── GitHubUpdateChecker.kt       # GitHub Releases API version checker & APK SHA-256 cert inspector
└── ui/
    ├── AppLocalization.kt           # Bilingual RU/EN strings & Theme/Language enums
    ├── AlarmCommanderViewModel.kt   # MVVM state holder
    └── screens/
        ├── CommandCenterScreen.kt   # Tactical Radar, Proving Ground, Alarm cards & Editor Dialog
        ├── ActiveAlarmScreen.kt     # Lockscreen Combat UI, Mission Chain, Sunrise & Anti-Return views
        └── PermissionsAndSettingsScreens.kt # Security Audit, Permissions, Logs, Theme/Lang & GitHub Updates
```

Запуск модульных и Robolectric-тестов / Run unit & Robolectric tests:
```bash
gradle :app:testDebugUnitTest
```
