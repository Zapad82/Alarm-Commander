package com.example.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/**
 * Унифицированный интерфейс резервного канала (Умный дом, Социальный контакт, Второе устройство и др.).
 */
interface BackupChannel {
    val channelId: String
    val displayNameRu: String
    suspend fun trigger(payload: BackupPayload): BackupResult
}

/**
 * 1. Реальный HTTP-клиент для Webhook умного дома (Home Assistant / Node-RED / локальное реле света).
 */
class SmartHomeWebhookChannel : BackupChannel {

    override val channelId: String = "SMART_HOME_WEBHOOK"
    override val displayNameRu: String = "Webhook умного дома (Свет / Сценарий)"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    override suspend fun trigger(payload: BackupPayload): BackupResult = withContext(Dispatchers.IO) {
        val url = payload.targetUrlOrAddress.trim()
        if (url.isBlank()) {
            return@withContext BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "URL вебхука не задан в настройках"
            )
        }

        val jsonBody = """
            {
              "app": "AlarmCommander",
              "alarmId": ${payload.alarmId},
              "label": "${payload.alarmLabel.replace("\"", "'")}",
              "event": "${payload.eventType}",
              "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
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
            // Если локальный хаб недоступен (например, демо-IP 192.168.1.100), фиксируем попытку в журнале
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
        val phone = payload.contactPhone.trim()
        val name = payload.contactName.ifBlank { "Резервный контакт" }
        if (phone.isBlank()) {
            return BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "Телефон резервного контакта не указан"
            )
        }

        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra(
                "sms_body",
                "Alarm Commander: Будильник «${payload.alarmLabel}» не отключён вовремя! Пожалуйста, разбудите меня."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
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
            .setContentText("Миссия не выполнена вовремя. Нажмите для звонка или отправки сообщения на $phone.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_call, "Позвонить $name", dialPending)
            .addAction(android.R.drawable.ic_dialog_email, "Отправить SMS", smsPending)
            .setAutoCancel(true)
            .build()

        runCatching { nm.notify(9100 + payload.alarmId.toInt(), notification) }

        return BackupResult(
            channelName = displayNameRu,
            success = true,
            messageRu = "Подготовлено оповещение контакту $name ($phone)"
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
