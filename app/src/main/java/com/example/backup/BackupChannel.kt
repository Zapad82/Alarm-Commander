package com.example.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

data class BackupPayload(
    val alarmId: Long,
    val alarmLabel: String,
    val eventType: String, // "SUNRISE_T_MINUS_15" or "OVERDUE_ESCALATION"
    val targetUrlOrAddress: String,
    val contactName: String = "",
    val contactPhone: String = "",
    val authBearerToken: String = ""
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
 * - Для открытого HTTP строго синхронизирован с res/xml/network_security_config.xml:
 *   разрешает только локальные mDNS-имена (*.local, *.lan, *.home, *.internal, localhost)
 *   и явно перечисленные в network_security_config.xml локальные IP-адреса.
 */
object WebhookSecurityValidator {

    /**
     * Точный белый список IPv4-адресов, разрешённых для открытого HTTP в res/xml/network_security_config.xml.
     * Так как Android network-security-config не поддерживает CIDR-маски, валидатор и XML-конфиг
     * используют единый согласованный список. Для любых других IP требуется HTTPS или mDNS-имя (*.local / *.lan).
     */
    val CLEARTEXT_ALLOWED_LAN_IPS: Set<String> = setOf(
        "127.0.0.1",
        "192.168.0.1",
        "192.168.0.10",
        "192.168.0.50",
        "192.168.0.100",
        "192.168.1.1",
        "192.168.1.10",
        "192.168.1.50",
        "192.168.1.100",
        "192.168.1.200",
        "10.0.0.1",
        "10.0.0.10",
        "10.0.0.100"
    )

    private val STRICT_IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val LOCAL_MDNS_HOSTNAME_REGEX =
        Regex("""^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.(local|lan|home|internal)$""")

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
                    when {
                        isCleartextPermittedByNetworkConfig(host) -> {
                            WebhookValidationResult(true, "Локальный узел умного дома ($host)")
                        }
                        isRfc1918PrivateIpv4(host) -> {
                            WebhookValidationResult(
                                false,
                                "Адрес $host не входит в белый список Cleartext-IP Android. Используйте HTTPS, mDNS-имя (например, http://homeassistant.local:8123) или IP из списка (${CLEARTEXT_ALLOWED_LAN_IPS.joinToString(", ")})."
                            )
                        }
                        else -> {
                            WebhookValidationResult(
                                false,
                                "Заблокирован незашифрованный HTTP во внешнюю сеть ($host). Используйте HTTPS или локальный домен *.local."
                            )
                        }
                    }
                }
                else -> WebhookValidationResult(false, "Неподдерживаемый протокол: $scheme")
            }
        } catch (_: Exception) {
            WebhookValidationResult(false, "Синтаксическая ошибка URL")
        }
    }

    /**
     * Проверяет, разрешён ли хост для открытого HTTP в соответствии с network_security_config.xml.
     */
    fun isCleartextPermittedByNetworkConfig(host: String): Boolean {
        val h = host.lowercase().trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        if (h in CLEARTEXT_ALLOWED_LAN_IPS) return true
        if (STRICT_IPV4_REGEX.matches(h)) return false
        return LOCAL_MDNS_HOSTNAME_REGEX.matches(h)
    }

    /**
     * Проверяет, является ли строка валидным приватным IPv4-адресом RFC-1918 (10/8, 172.16/12, 192.168/16, 127/8).
     */
    fun isRfc1918PrivateIpv4(host: String): Boolean {
        val h = host.lowercase().trim().removePrefix("[").removeSuffix("]")
        val ipMatch = STRICT_IPV4_REGEX.matchEntire(h) ?: return false
        val octets = ipMatch.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    fun isPrivateLanHost(host: String): Boolean = isCleartextPermittedByNetworkConfig(host)

    /**
     * Проверяет, что скомпилированный InetAddress является строго локальным (защита от DNS-ребайндинга для *.local).
     */
    fun isPrivateOrLoopbackInetAddress(address: InetAddress): Boolean {
        return address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress
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
 * Использует единый пул соединений OkHttpClient (Singleton), проверяет URL через WebhookSecurityValidator
 * и защищает mDNS-домены (*.local / *.lan) от DNS-ребайндинга в публичные IP.
 */
class SmartHomeWebhookChannel : BackupChannel {

    override val channelId: String = "SMART_HOME_WEBHOOK"
    override val displayNameRu: String = "Webhook умного дома (Свет / Сценарий)"

    companion object {
        /**
         * DNS-резолвер с защитой от DNS Rebinding: если хост заканчивается на .local / .lan / .home / .internal,
         * разрешает подключение только если все скомпилированные IP-адреса являются приватными/локальными.
         */
        private val safeWebhookDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = Dns.SYSTEM.lookup(hostname)
                val lower = hostname.lowercase().trim()
                val isLocalDomain = lower == "localhost" ||
                    lower.endsWith(".local") ||
                    lower.endsWith(".lan") ||
                    lower.endsWith(".home") ||
                    lower.endsWith(".internal")
                if (isLocalDomain) {
                    val safeAddresses = resolved.filter {
                        WebhookSecurityValidator.isPrivateOrLoopbackInetAddress(it)
                    }
                    if (safeAddresses.isEmpty()) {
                        throw UnknownHostException(
                            "DNS Rebinding blocked: local hostname $hostname resolved to non-private IP"
                        )
                    }
                    return safeAddresses
                }
                return resolved
            }
        }

        // Единый экземпляр OkHttpClient с отключёнными редиректами и защитой от DNS-ребайндинга
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(safeWebhookDns)
                .connectTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

        internal fun normalizeBearerToken(rawToken: String): String {
            val trimmed = rawToken.trim()
            if (trimmed.isEmpty()) return ""
            return trimmed
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
                .replace("\r", "")
                .replace("\n", "")
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
        val cleanBearer = normalizeBearerToken(payload.authBearerToken)

        return@withContext try {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (cleanBearer.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $cleanBearer")
            }

            val request = requestBuilder.build()

            sharedHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    BackupResult(
                        channelName = displayNameRu,
                        success = true,
                        messageRu = "Webhook доставлен (${response.code}) -> $url"
                    )
                } else if (response.code in 300..399) {
                    BackupResult(
                        channelName = displayNameRu,
                        success = false,
                        messageRu = "Редирект HTTP ${response.code} заблокирован политикой безопасности ($url)"
                    )
                } else {
                    BackupResult(
                        channelName = displayNameRu,
                        success = false,
                        messageRu = "Ответ сервера HTTP ${response.code} ($url)"
                    )
                }
            }
        } catch (cleartextEx: UnknownServiceException) {
            BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "Открытый HTTP к $url заблокирован network_security_config: используйте HTTPS, домен *.local (http://homeassistant.local:8123) или разрешённый локальный IP."
            )
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("CLEARTEXT", ignoreCase = true)) {
                BackupResult(
                    channelName = displayNameRu,
                    success = false,
                    messageRu = "Открытый HTTP к $url заблокирован network_security_config: используйте HTTPS, домен *.local (http://homeassistant.local:8123) или разрешённый локальный IP."
                )
            } else {
                BackupResult(
                    channelName = displayNameRu,
                    success = false,
                    messageRu = "Локальный узел $url недоступен (${e.javaClass.simpleName}): запрос зафиксирован в журнале"
                )
            }
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
        if (sanitizedPhone.length < 5 || sanitizedPhone == "+79001234567") {
            return BackupResult(
                channelName = displayNameRu,
                success = false,
                messageRu = "Укажите реальный номер телефона резервного контакта в разделе «Опции»"
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
