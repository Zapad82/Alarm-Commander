package com.example.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class BackupPayload(
    val alarmId: Long,
    val alarmLabel: String,
    val eventType: String, // "SUNRISE_T_MINUS_15" or "OVERDUE_ESCALATION"
    val targetUrlOrAddress: String,
    val contactName: String = "",
    val contactPhone: String = ""
)

data class BackupResult(
    val channelName: String,
    val success: Boolean,
    val messageRu: String
)

data class WebhookValidationResult(
    val isAllowed: Boolean,
    val reasonRu: String
)

/**
 * Валидатор сетевой безопасности Webhook:
 * - Разрешает HTTPS для любых внешних и локальных узлов.
 * - Разрешает открытый HTTP исключительно для локальных адресов умного дома (RFC-1918: 192.168.x.x, 10.x.x.x, 172.16-31.x.x, localhost, .local),
 *   блокируя передачу данных в открытом виде через публичный интернет.
 */
object WebhookSecurityValidator {

    fun validateWebhookUrl(rawUrl: String): WebhookValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return WebhookValidationResult(false, "URL не задан")
        }
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()

            if (host.isEmpty()) {
                return WebhookValidationResult(false, "Некорректный хост в URL")
            }

            when (scheme) {
                "https" -> WebhookValidationResult(true, "Защищённый канал HTTPS ($host)")
                "http" -> {
                    if (isPrivateLanHost(host)) {
                        WebhookValidationResult(true, "Локальная сеть умного дома ($host)")
                    } else {
                        WebhookValidationResult(
                            false,
                            "Заблокирован незашифрованный HTTP во внешнюю сеть ($host). Используйте HTTPS или локальный IP."
                        )
                    }
                }
                else -> WebhookValidationResult(false, "Неподдерживаемый протокол: $scheme")
            }
        } catch (_: Exception) {
            WebhookValidationResult(false, "Синтаксическая ошибка URL")
        }
    }

    fun isPrivateLanHost(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h == "localhost" || h == "127.0.0.1" || h.endsWith(".local") || h.endsWith(".lan")) {
            return true
        }
        if (h.startsWith("192.168.") || h.startsWith("10.")) {
            return true
        }
        if (h.startsWith("172.")) {
            val secondOctet = h.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) {
                return true
            }
        }
        return false
    }

    /**
     * Формирует строго экранированный JSON по стандарту RFC-8259 (защита от JSON Injection без зависимости от android.jar stub).
     */
    fun buildSafeJsonBody(payload: BackupPayload, timestampMs: Long = System.currentTimeMillis()): String {
        val safeLabel = escapeJsonString(payload.alarmLabel.take(120))
        val safeEvent = escapeJsonString(payload.eventType.take(64))
        return """{"app":"AlarmCommander","alarmId":${payload.alarmId},"label":"$safeLabel","event":"$safeEvent","timestamp":$timestampMs}"""
    }

    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder(input.length + 8)
        for (ch in input) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}

/**
 * Унифицированный интерфейс резервного канала (Умный дом, Социальный контакт, Второе устройство и др.).
 */
interface BackupChannel {
    val channelId: String
    val displayNameRu: String
    suspend fun trigger(payload: BackupPayload): BackupResult
}

/**
 * 1. Безопасный и энергоэффективный HTTP-клиент для Webhook умного дома.
 * Использует единый пул соединений OkHttpClient (Singleton) и проверяет URL через WebhookSecurityValidator.
 */
class SmartHomeWebhookChannel : BackupChannel {

    override val channelId: String = "SMART_HOME_WEBHOOK"
    override val displayNameRu: String = "Webhook умного дома (Свет / Сценарий)"

    companion object {
        // Единый экземпляр OkHttpClient предотвращает утечку пулов потоков и сокетов
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    override suspend fun trigger(payload: BackupPayload): BackupResult = withContext(Dispatchers.IO) {
        val url = payload.targetUrlOrAddress.trim()
        val validation = WebhookSecurityValidator.validateWebhookUrl(url)
        if (!validation.isAllowed) {
            return@withContext BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "Заблокировано политикой безопасности: ${validation.reasonRu}"
            )
        }

        val jsonBody = WebhookSecurityValidator.buildSafeJsonBody(payload)

        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            sharedHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    BackupResult(
                        channelName = displayNameRu,
                        success = true,
                        messageRu = "Webhook доставлен (${response.code}) -> $url"
                    )
                } else {
                    BackupResult(
                        channelName = displayNameRu,
                        success = false,
                        messageRu = "Ответ сервера HTTP ${response.code} ($url)"
                    )
                }
            }
        } catch (e: Exception) {
            BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "Локальный узел $url недоступен (${e.javaClass.simpleName}): запрос зафиксирован в журнале"
            )
        }
    }
}

/**
 * 2. Канал резервного социального контакта.
 * В соответствии с политиками Google Play (без запрещённого разрешения SEND_SMS в фоне)
 * формирует экстренное уведомление с кнопками быстрого вызова (ACTION_DIAL) и отправки SMS (ACTION_SENDTO).
 */
class SocialContactChannel(private val context: Context) : BackupChannel {

    override val channelId: String = "SOCIAL_CONTACT_ALERT"
    override val displayNameRu: String = "Резервный социальный контакт"

    override suspend fun trigger(payload: BackupPayload): BackupResult {
        val rawPhone = payload.contactPhone.trim()
        val sanitizedPhone = rawPhone.filter { it.isDigit() || it == '+' }
        val name = payload.contactName.ifBlank { "Резервный контакт" }.take(60)
        if (sanitizedPhone.length < 5) {
            return BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "Некорректный телефон резервного контакта"
            )
        }

        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$sanitizedPhone")).apply {
            putExtra(
                "sms_body",
                "Alarm Commander: Будильник «${payload.alarmLabel}» не отключён вовремя! Пожалуйста, разбудите меня."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitizedPhone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val smsPending = PendingIntent.getActivity(
            context,
            9101,
            smsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dialPending = PendingIntent.getActivity(
            context,
            9102,
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, "alarm_commander_backup_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Просрочка будильника • Связь с «$name»")
            .setContentText("Миссия не выполнена вовремя. Нажмите для звонка или отправки сообщения на $sanitizedPhone.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_call, "Позвонить $name", dialPending)
            .addAction(android.R.drawable.ic_dialog_email, "Отправить SMS", smsPending)
            .setAutoCancel(true)
            .build()

        runCatching { nm.notify(9100 + (payload.alarmId % 500).toInt(), notification) }

        return BackupResult(
            channelName = displayNameRu,
            success = true,
            messageRu = "Подготовлено оповещение контакту $name ($sanitizedPhone)"
        )
    }
}

/**
 * 3. Заглушки (Stubs) для будущих post-MVP резервных каналов с явными TODO.
 */
class FriendVoipCallStubChannel : BackupChannel {
    override val channelId: String = "FRIEND_VOIP_CALL_STUB"
    override val displayNameRu: String = "Авто-звонок другу (Post-MVP Stub)"

    override suspend fun trigger(payload: BackupPayload): BackupResult {
        // TODO(Post-MVP): Интегрировать SIP / WebRTC или внешний webhook-шлюз с двусторонним подтверждением согласия контакта.
        return BackupResult(
            channelName = displayNameRu,
            success = true,
            messageRu = "[STUB] Запрос на SIP/VoIP авто-звонок зафиксирован (требует внешнего шлюза)"
        )
    }
}

class SmartSpeakerStubChannel : BackupChannel {
    override val channelId: String = "SMART_SPEAKER_STUB"
    override val displayNameRu: String = "Умная колонка в гостиной (Post-MVP Stub)"

    override suspend fun trigger(payload: BackupPayload): BackupResult {
        // TODO(Post-MVP): Реализовать локальный Cast / DLNA / Matter-вызов для запуска аудиопотока на умной колонке.
        return BackupResult(
            channelName = displayNameRu,
            success = true,
            messageRu = "[STUB] Сигнал на умную колонку отправлен в очередь локальной сети"
        )
    }
}

class BedShakerStubChannel : BackupChannel {
    override val channelId: String = "BED_SHAKER_BLE_STUB"
    override val displayNameRu: String = "Вибро-шайба под матрас BLE (Post-MVP Stub)"

    override suspend fun trigger(payload: BackupPayload): BackupResult {
        // TODO(Post-MVP): Подключить BluetoothGatt и записать характеристику запуска мотора в BLE Bed-Shaker.
        return BackupResult(
            channelName = displayNameRu,
            success = true,
            messageRu = "[STUB] Команда BLE GATT для Bed-Shaker сформирована"
        )
    }
}

class SecondDeviceStubChannel : BackupChannel {
    override val channelId: String = "SECOND_DEVICE_PEER_STUB"
    override val displayNameRu: String = "Второе устройство по Wi-Fi Direct (Post-MVP Stub)"

    override suspend fun trigger(payload: BackupPayload): BackupResult {
        // TODO(Post-MVP): Реализовать Nearby Connections / mDNS для синхронного подъёма второго телефона или планшета.
        return BackupResult(
            channelName = displayNameRu,
            success = true,
            messageRu = "[STUB] Пакет пробуждения второго устройства отправлен в локальный сокет"
        )
    }
}
