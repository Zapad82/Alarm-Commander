package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AlarmLogEntity
import com.example.mission.QrCodeUtils
import com.example.permissions.PermissionItemStatus
import com.example.permissions.PermissionManager
import com.example.settings.CommanderSettingsState
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TacticalAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран пошаговой проверки и объяснения системных разрешений Android 8–16.
 */
@Composable
fun PermissionsScreen(
    permissions: List<PermissionItemStatus>,
    permissionManager: PermissionManager,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefresh()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("permissions_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ЧЕК-ЛИСТ БОЕВОЙ ГОТОВНОСТИ ОС",
                            style = MaterialTheme.typography.labelLarge,
                            color = TacticalAmber
                        )
                        Text(
                            text = "Все разрешения запрашиваются прозрачно по шагам. Приложение НЕ использует SYSTEM_ALERT_WINDOW.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            }
        }

        items(permissions, key = { it.id }) { item ->
            val statusColor = if (item.isGranted) RadarEmerald else TacticalAmber
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = item.titleRu,
                                tint = statusColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.titleRu,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (item.isGranted) "АКТИВНО" else if (item.isOptional) "ОПЦИЯ" else "ТРЕБУЕТСЯ",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.whyNeededRu,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!item.isGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                when (item.id) {
                                    "EXACT_ALARM" -> runCatching {
                                        context.startActivity(permissionManager.buildExactAlarmSettingsIntent())
                                    }
                                    "FULL_SCREEN_INTENT" -> runCatching {
                                        context.startActivity(permissionManager.buildFullScreenIntentSettings())
                                    }
                                    "POST_NOTIFICATIONS" -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            runtimeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    "BATTERY_OPTIMIZATION" -> runCatching {
                                        context.startActivity(permissionManager.buildBatteryOptimizationSettingsIntent())
                                    }
                                    "CAMERA" -> {
                                        runtimeLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                    "ACTIVITY_RECOGNITION" -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            runtimeLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                        }
                                    }
                                    "RECORD_AUDIO" -> {
                                        runtimeLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    "DND_BYPASS" -> runCatching {
                                        context.startActivity(permissionManager.buildDndPolicySettingsIntent())
                                    }
                                    "NFC_ADAPTER" -> runCatching {
                                        context.startActivity(permissionManager.buildNfcSettingsIntent())
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(item.actionLabelRu)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Экран локального журнала событий будильника (Room) с возможностью экспорта отчёта.
 */
@Composable
fun LogsScreen(
    logs: List<AlarmLogEntity>,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("logs_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "ЛОКАЛЬНЫЙ ЖУРНАЛ СОБЫТИЙ (${logs.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = TacticalAmber
                    )
                    Text(
                        text = "Все срабатывания, стадии эскалации и миссии хранятся только на устройстве.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val report = buildString {
                                    appendLine("=== ALARM COMMANDER LOG REPORT ===")
                                    logs.take(50).forEach { e ->
                                        appendLine("${formatter.format(Date(e.timestamp))} | ${e.eventType} [${e.stateName}] | ${e.details}")
                                    }
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Alarm Commander — Журнал событий")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(sendIntent, "Экспорт журнала"))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Экспорт")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Экспорт")
                        }
                        OutlinedButton(
                            onClick = onClearLogs,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clear_logs_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить журнал")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Очистить")
                        }
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "Журнал пуст",
                            tint = TacticalAmber,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Записей в журнале пока нет")
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${entry.eventType} • [${entry.stateName}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = TacticalAmber
                            )
                            Text(
                                text = formatter.format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = entry.details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Экран настроек резервных каналов, генератора контрольного QR-кода для ванной и Post-MVP заглушек.
 */
@Composable
fun SettingsScreen(
    settings: CommanderSettingsState,
    onSaveSettings: (
        sunriseUrl: String,
        escalationUrl: String,
        contactName: String,
        contactPhone: String,
        qrPayload: String,
        nfcTagId: String
    ) -> Unit,
    onTestWebhook: (url: String, eventType: String) -> Unit,
    onTestSocialContact: (name: String, phone: String) -> Unit,
    onTestFutureStub: (String) -> Unit
) {
    val context = LocalContext.current
    var sunriseUrl by remember(settings.sunriseWebhookUrl) { mutableStateOf(settings.sunriseWebhookUrl) }
    var escalationUrl by remember(settings.escalationWebhookUrl) { mutableStateOf(settings.escalationWebhookUrl) }
    var contactName by remember(settings.backupContactName) { mutableStateOf(settings.backupContactName) }
    var contactPhone by remember(settings.backupContactPhone) { mutableStateOf(settings.backupContactPhone) }
    var qrPayload by remember(settings.defaultQrPayload) { mutableStateOf(settings.defaultQrPayload) }
    var nfcTagId by remember(settings.defaultNfcTagId) { mutableStateOf(settings.defaultNfcTagId) }

    val qrBitmap = remember(qrPayload) {
        QrCodeUtils.generateQrBitmap(qrPayload.ifBlank { "ALARM-COMMANDER-BATHROOM-01" }, 420)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Контрольный QR-код для печати/размещения в ванной комнате
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = "Генератор QR",
                            tint = TacticalAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "КОНТРОЛЬНЫЙ QR-КОД ДЛЯ ВАННОЙ / КУХНИ",
                            style = MaterialTheme.typography.labelLarge,
                            color = TacticalAmber
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Распечатайте или сфотографируйте этот QR-код и разместите его вдали от кровати.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR-код разблокировки будильника",
                            modifier = Modifier
                                .size(170.dp)
                                .padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = qrPayload,
                        onValueChange = { qrPayload = it },
                        label = { Text("Строка контрольного QR-кода") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Alarm Commander — Контрольный код QR-метки для ванной: $qrPayload"
                                )
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "Отправить код метки"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Отправить QR-код")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Отправить строку QR-кода для печати")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nfcTagId,
                        onValueChange = { nfcTagId = it },
                        label = { Text("Ожидаемый NFC Tag ID (* = любая метка)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. Резервные каналы MVP: Webhook умного дома и Социальный контакт
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "РЕЗЕРВНЫЕ КАНАЛЫ MVP (УМНЫЙ ДОМ И КОНТАКТ)",
                        style = MaterialTheme.typography.labelLarge,
                        color = TacticalAmber
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sunriseUrl,
                        onValueChange = { sunriseUrl = it },
                        label = { Text("Webhook «Рассвет» T−15 (Home Assistant / лампа)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onTestWebhook(sunriseUrl, "SUNRISE_T_MINUS_15") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Тест Webhook Рассвета")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Проверить Webhook «Рассвет»")
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = escalationUrl,
                        onValueChange = { escalationUrl = it },
                        label = { Text("Webhook при просрочке (Overdue Escalation)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onTestWebhook(escalationUrl, "OVERDUE_ESCALATION") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Тест Webhook тревоги")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Проверить Webhook просрочки")
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Имя резервного контакта") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text("Телефон резервного контакта") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onTestSocialContact(contactName, contactPhone) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Проверить оповещение резервного контакта")
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            onSaveSettings(
                                sunriseUrl,
                                escalationUrl,
                                contactName,
                                contactPhone,
                                qrPayload,
                                nfcTagId
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarEmerald,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("save_settings_button")
                    ) {
                        Text("СОХРАНИТЬ НАСТРОЙКИ РЕЗЕРВА", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3. Интерфейсы-заглушки (Stubs) для Post-MVP каналов
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ИНТЕРФЕЙСЫ POST-MVP КАНАЛОВ (STUBS)",
                        style = MaterialTheme.typography.labelLarge,
                        color = TacticalAmber
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Архитектурные адаптеры BackupChannel с чистыми контрактами и записью в локальный журнал:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    listOf(
                        "FRIEND_CALL" to "Авто-звонок другу (VoIP/SIP шлюз)",
                        "SMART_SPEAKER" to "Умная колонка в гостиной (Cast/Matter)",
                        "BED_SHAKER" to "Вибро-шайба под матрас (BLE GATT)",
                        "SECOND_DEVICE" to "Второе устройство по Wi-Fi Direct"
                    ).forEach { (key, title) ->
                        OutlinedButton(
                            onClick = { onTestFutureStub(key) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text("Тест Stub: $title")
                        }
                    }
                }
            }
        }
    }
}
