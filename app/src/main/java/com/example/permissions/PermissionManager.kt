package com.example.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionItemStatus(
    val id: String,
    val titleRu: String,
    val whyNeededRu: String,
    val isGranted: Boolean,
    val isOptional: Boolean = false,
    val actionLabelRu: String
)

/**
 * Централизованный менеджер проверки и пошагового объяснения разрешений Android 8–16.
 * Не использует SYSTEM_ALERT_WINDOW — вместо него используется USE_FULL_SCREEN_INTENT.
 */
class PermissionManager(private val context: Context) {

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        } else {
            true
        }
    }

    fun isPostNotificationsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isDndPolicyGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun isCameraGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isActivityRecognitionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isRecordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNfcAvailableAndEnabled(): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null && adapter.isEnabled
    }

    fun evaluateAllPermissions(): List<PermissionItemStatus> {
        return listOf(
            PermissionItemStatus(
                id = "EXACT_ALARM",
                titleRu = "Точные будильники (Exact Alarm)",
                whyNeededRu = "Гарантирует пробуждение процессора ровно в T0 через AlarmManager.setAlarmClock даже в режиме глубокого сна Doze.",
                isGranted = canScheduleExactAlarms(),
                actionLabelRu = "Открыть настройки точных будильников"
            ),
            PermissionItemStatus(
                id = "FULL_SCREEN_INTENT",
                titleRu = "Полноэкранный вызов (Full-Screen Intent)",
                whyNeededRu = "Позволяет развернуть экран будильника поверх заблокированного экрана без использования опасного SYSTEM_ALERT_WINDOW.",
                isGranted = canUseFullScreenIntent(),
                actionLabelRu = "Разрешить полноэкранные уведомления"
            ),
            PermissionItemStatus(
                id = "POST_NOTIFICATIONS",
                titleRu = "Экстренные уведомления",
                whyNeededRu = "Необходимо для работы Foreground-сервиса звонка, отображения миссии и проверки анти-возврата.",
                isGranted = isPostNotificationsGranted(),
                actionLabelRu = "Выдать разрешение уведомлений"
            ),
            PermissionItemStatus(
                id = "BATTERY_OPTIMIZATION",
                titleRu = "Исключение из экономии заряда (Doze / OEM)",
                whyNeededRu = "Предотвращает принудительную выгрузку фонового сервиса будильника агрессивными оболочками (MIUI, OneUI, HyperOS).",
                isGranted = isIgnoringBatteryOptimizations(),
                isOptional = true,
                actionLabelRu = "Открыть настройки экономии заряда"
            ),
            PermissionItemStatus(
                id = "CAMERA",
                titleRu = "Камера (только для миссии QR-код)",
                whyNeededRu = "Используется исключительно локально в момент сканирования QR-кода в ванной. Никакие фото или видео не сохраняются.",
                isGranted = isCameraGranted(),
                actionLabelRu = "Разрешить доступ к камере"
            ),
            PermissionItemStatus(
                id = "ACTIVITY_RECOGNITION",
                titleRu = "Датчик шагов (только для миссии Шагомер)",
                whyNeededRu = "Позволяет считать 50–100 физических шагов для подтверждения того, что вы встали с кровати.",
                isGranted = isActivityRecognitionGranted(),
                actionLabelRu = "Разрешить датчик физической активности"
            ),
            PermissionItemStatus(
                id = "RECORD_AUDIO",
                titleRu = "Микрофон (только для миссии Голосовая фраза)",
                whyNeededRu = "Используется только во время голосовой миссии для распознавания контрольной фразы через SpeechRecognizer без сохранения записи.",
                isGranted = isRecordAudioGranted(),
                isOptional = true,
                actionLabelRu = "Разрешить микрофон для голосовой миссии"
            ),
            PermissionItemStatus(
                id = "DND_BYPASS",
                titleRu = "Обход режима «Не беспокоить» (Опционально)",
                whyNeededRu = "Позволяет будильнику звучать на полной громкости, даже если на телефоне случайно оставлен фильтр DND.",
                isGranted = isDndPolicyGranted(),
                isOptional = true,
                actionLabelRu = "Настроить доступ к «Не беспокоить»"
            ),
            PermissionItemStatus(
                id = "NFC_ADAPTER",
                titleRu = "Аппаратный модуль NFC (Опционально)",
                whyNeededRu = "Нужен для миссии касания NFC-метки. Если NFC отсутствует, миссия предложит резервный когнитивный ввод.",
                isGranted = isNfcAvailableAndEnabled(),
                isOptional = true,
                actionLabelRu = "Открыть настройки NFC"
            )
        )
    }

    fun buildExactAlarmSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            buildAppDetailsIntent()
        }
    }

    fun buildFullScreenIntentSettings(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            buildAppDetailsIntent()
        }
    }

    fun buildBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildDndPolicySettingsIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildNfcSettingsIntent(): Intent {
        return Intent(Settings.ACTION_NFC_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildAppDetailsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
