package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.alarm.AlarmScheduler
import com.example.data.AlarmEntity
import com.example.data.EscalationProfile
import com.example.data.MathDifficulty
import com.example.data.MissionType
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.TacticalAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CommandCenterScreen(
    alarms: List<AlarmEntity>,
    disclaimerAccepted: Boolean,
    grantedPermissionsCount: Int,
    totalPermissionsCount: Int,
    defaultQrPayload: String,
    defaultNfcTagId: String,
    onAcceptDisclaimer: () -> Unit,
    onSaveAlarm: (AlarmEntity) -> Unit,
    onToggleAlarm: (AlarmEntity, Boolean) -> Unit,
    onDeleteAlarm: (AlarmEntity) -> Unit,
    onTriggerTestAlarm: (Long, Boolean) -> Unit,
    onTriggerAntiReturnTest: (Long) -> Unit
) {
    var editingAlarm by remember { mutableStateOf<AlarmEntity?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var showDisclaimerDialog by remember { mutableStateOf(!disclaimerAccepted) }

    if (showDisclaimerDialog) {
        AlertDialog(
            onDismissRequest = { /* требуем осознанного подтверждения */ },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Безопасность",
                    tint = TacticalAmber
                )
            },
            title = {
                Text(stringResource(R.string.disclaimer_title))
            },
            text = {
                Text(stringResource(R.string.disclaimer_body))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisclaimerDialog = false
                        onAcceptDisclaimer()
                    },
                    modifier = Modifier.testTag("accept_disclaimer_button")
                ) {
                    Text(stringResource(R.string.disclaimer_accept))
                }
            }
        )
    }

    if (showEditorDialog) {
        AlarmEditorDialog(
            initialAlarm = editingAlarm,
            defaultQrPayload = defaultQrPayload,
            defaultNfcTagId = defaultNfcTagId,
            onDismiss = { showEditorDialog = false },
            onSave = { updated ->
                showEditorDialog = false
                onSaveAlarm(updated)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("alarms_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Тактический радар и телеметрия ближайшего пробуждения
            item {
                TacticalRadarHeroBanner(
                    alarms = alarms,
                    grantedPermissionsCount = grantedPermissionsCount,
                    totalPermissionsCount = totalPermissionsCount
                )
            }

            // 2. Обязательный дисклеймер безопасности
            item {
                Surface(
                    color = SlateSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDisclaimerDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Дисклеймер",
                            tint = TacticalAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.disclaimer_banner_short),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Панель мгновенного боевого тестирования (T0, Рассвет T−15, Анти-возврат)
            item {
                val primaryAlarmId = alarms.firstOrNull()?.id ?: 999L
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TacticalAmber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ПОЛИГОН ПРОВЕРКИ СИГНАЛА И МИССИЙ",
                            style = MaterialTheme.typography.labelLarge,
                            color = TacticalAmber
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Проверьте эскалацию громкости, блокировку кнопки «Назад», цепочку миссий и контроль анти-возврата прямо сейчас.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onTriggerTestAlarm(primaryAlarmId, false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TacticalAmber,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("combat_test_t0_button")
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Боевой тест T0",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Тест T0", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onTriggerTestAlarm(primaryAlarmId, true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("combat_test_sunrise_button")
                            ) {
                                Icon(
                                    Icons.Default.WbSunny,
                                    contentDescription = "Тест Рассвета",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Рассвет T−15")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onTriggerAntiReturnTest(primaryAlarmId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("combat_test_anti_return_button")
                        ) {
                            Text("Тест «Анти-возврат в кровать» (таймер 60 сек)")
                        }
                    }
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Alarm,
                                contentDescription = "Нет будильников",
                                tint = TacticalAmber,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.empty_alarms_title),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.empty_alarms_Hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmCardItem(
                        alarm = alarm,
                        onToggle = { enabled -> onToggleAlarm(alarm, enabled) },
                        onEdit = {
                            editingAlarm = alarm
                            showEditorDialog = true
                        },
                        onDelete = { onDeleteAlarm(alarm) },
                        onTestNow = { onTriggerTestAlarm(alarm.id, false) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                editingAlarm = null
                showEditorDialog = true
            },
            containerColor = TacticalAmber,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .testTag("add_alarm_fab")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = "Создать будильник")
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.btn_new_alarm),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun TacticalRadarHeroBanner(
    alarms: List<AlarmEntity>,
    grantedPermissionsCount: Int,
    totalPermissionsCount: Int
) {
    val nowMs = System.currentTimeMillis()
    val nextPair = remember(alarms) {
        alarms.filter { it.enabled }
            .map { alarm ->
                alarm to AlarmScheduler.computeNextTriggerEpochMs(
                    hour = alarm.hour,
                    minute = alarm.minute,
                    daysOfWeek = alarm.daysOfWeek,
                    nowEpochMs = nowMs
                )
            }
            .minByOrNull { it.second }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RadarEmerald.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Анимированный тактический радар (Canvas)
            Box(
                modifier = Modifier.size(76.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension / 2f

                    drawCircle(
                        color = ObsidianBg,
                        radius = maxRadius,
                        center = center
                    )
                    listOf(0.35f, 0.68f, 1.0f).forEach { ratio ->
                        drawCircle(
                            color = RadarEmerald.copy(alpha = 0.35f),
                            radius = maxRadius * ratio,
                            center = center,
                            style = Stroke(width = 1.5f)
                        )
                    }
                    val rad = (sweepAngle * PI / 180.0)
                    val endX = center.x + (maxRadius * cos(rad)).toFloat()
                    val endY = center.y + (maxRadius * sin(rad)).toFloat()
                    drawLine(
                        color = TacticalAmber,
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = RadarEmerald,
                        radius = 4.5f,
                        center = center
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ДЕЖУРСТВО АКТИВНО",
                        style = MaterialTheme.typography.labelSmall,
                        color = RadarEmerald
                    )
                    Text(
                        text = "ОС: $grantedPermissionsCount/$totalPermissionsCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanTelemetry
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (nextPair != null) {
                    val (alarm, triggerMs) = nextPair
                    val diffMinutes = ((triggerMs - nowMs).coerceAtLeast(60_000L) / 60_000L).toInt()
                    val hours = diffMinutes / 60
                    val mins = diffMinutes % 60
                    Text(
                        text = "Следующий T0: ${alarm.formattedTime()} (через ${hours}ч ${mins}м)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${alarm.label} • Профиль ${alarm.profileEnum.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TacticalAmber
                    )
                } else {
                    Text(
                        text = "Все рубежи отключены",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Включите будильник или запустите боевой тест T0 ниже",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmCardItem(
    alarm: AlarmEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestNow: () -> Unit
) {
    val profileColor = when (alarm.profileEnum) {
        EscalationProfile.GENTLE -> RadarEmerald
        EscalationProfile.HARD -> TacticalAmber
        EscalationProfile.EXTREME_SAFE -> AlertCrimson
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (alarm.enabled) profileColor.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("alarm_card_${alarm.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alarm.formattedTime(),
                        style = MaterialTheme.typography.displayMedium,
                        color = if (alarm.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = "${alarm.label} • ${alarm.formattedDaysShort()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("alarm_switch_${alarm.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BadgePill(
                    text = "Эскалация: ${alarm.profileEnum.name}",
                    color = profileColor
                )
                BadgePill(
                    text = "Миссии: ${alarm.missionChain.joinToString(" → ") { it.badgeRu }}",
                    color = TacticalAmber
                )
                if (!alarm.allowSnooze) {
                    BadgePill(text = "Анти-Snooze", color = RadarEmerald)
                } else {
                    BadgePill(text = "Penalty-Snooze", color = TacticalAmber)
                }
                if (alarm.sunrisePreAlarmEnabled) {
                    BadgePill(text = "Рассвет T−${alarm.sunriseLeadMinutes}м", color = TacticalAmber)
                }
                if (alarm.antiReturnEnabled) {
                    BadgePill(text = "Анти-возврат ${alarm.antiReturnDelayMinutes}м", color = RadarEmerald)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onTestNow,
                    modifier = Modifier.testTag("test_alarm_${alarm.id}")
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Проверить этот будильник",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Запустить тест")
                }

                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_alarm_${alarm.id}")) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Редактировать будильник",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_alarm_${alarm.id}")) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить будильник",
                            tint = AlertCrimson
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(0.8.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditorDialog(
    initialAlarm: AlarmEntity?,
    defaultQrPayload: String,
    defaultNfcTagId: String,
    onDismiss: () -> Unit,
    onSave: (AlarmEntity) -> Unit
) {
    var hour by remember { mutableIntStateOf(initialAlarm?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initialAlarm?.minute ?: 0) }
    var label by remember { mutableStateOf(initialAlarm?.label ?: "Утренний подъём") }

    val selectedDays = remember {
        val initDays = initialAlarm?.daysOfWeek ?: setOf(1, 2, 3, 4, 5)
        mutableStateListOf<Int>().apply { addAll(initDays) }
    }

    var profile by remember {
        mutableStateOf(initialAlarm?.profileEnum ?: EscalationProfile.HARD)
    }

    val selectedMissions = remember {
        val initMissions = initialAlarm?.missionChain ?: listOf(MissionType.MATH, MissionType.STEPS)
        mutableStateListOf<MissionType>().apply { addAll(initMissions) }
    }

    var mathDifficulty by remember {
        mutableStateOf(initialAlarm?.mathDifficultyEnum ?: MathDifficulty.MEDIUM)
    }
    var targetSteps by remember { mutableIntStateOf(initialAlarm?.targetSteps ?: 50) }
    var qrPayload by remember {
        mutableStateOf(initialAlarm?.qrExpectedPayload ?: defaultQrPayload)
    }
    var nfcTagId by remember {
        mutableStateOf(initialAlarm?.nfcExpectedTagId ?: defaultNfcTagId)
    }
    var voicePhrase by remember {
        mutableStateOf(
            initialAlarm?.voicePhraseExpected ?: "Я полностью проснулся и готов к действию"
        )
    }
    var allowSnooze by remember { mutableStateOf(initialAlarm?.allowSnooze ?: false) }
    var sunriseEnabled by remember { mutableStateOf(initialAlarm?.sunrisePreAlarmEnabled ?: true) }
    var antiReturnEnabled by remember { mutableStateOf(initialAlarm?.antiReturnEnabled ?: true) }
    var backupWebhookEnabled by remember { mutableStateOf(initialAlarm?.backupWebhookEnabled ?: true) }
    var backupContactEnabled by remember { mutableStateOf(initialAlarm?.backupContactEnabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialAlarm == null) "Новый тактический рубеж" else "Настройка будильника")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { hour = (hour + 1) % 24 }) { Text("+") }
                        Text(
                            text = "%02d".format(hour),
                            style = MaterialTheme.typography.displayMedium,
                            color = TacticalAmber
                        )
                        OutlinedButton(onClick = { hour = (hour + 23) % 24 }) { Text("−") }
                    }
                    Text(
                        text = " : ",
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { minute = (minute + 5) % 60 }) { Text("+5") }
                        Text(
                            text = "%02d".format(minute),
                            style = MaterialTheme.typography.displayMedium,
                            color = TacticalAmber
                        )
                        OutlinedButton(onClick = { minute = (minute + 55) % 60 }) { Text("−5") }
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Название рубежа") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alarm_label_input")
                )

                Text("Дни недели:", style = MaterialTheme.typography.labelLarge, color = TacticalAmber)
                val dayLabels = listOf(1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayLabels.forEach { (isoDay, shortName) ->
                        val isSelected = isoDay in selectedDays
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) TacticalAmber else ObsidianBg)
                                .clickable {
                                    if (isSelected) selectedDays.remove(isoDay) else selectedDays.add(isoDay)
                                }
                        ) {
                            Text(
                                text = shortName,
                                color = if (isSelected) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Text("Профиль эскалации:", style = MaterialTheme.typography.labelLarge, color = TacticalAmber)
                EscalationProfile.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (profile == item) SlateSurfaceVariant else ObsidianBg)
                            .clickable { profile = item }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.titleRu, fontWeight = FontWeight.Bold)
                            Text(item.subtitleRu, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Text(
                    "Цепочка миссий (выберите 1–5 по порядку):",
                    style = MaterialTheme.typography.labelLarge,
                    color = TacticalAmber
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MissionType.entries.forEach { mType ->
                        val idx = selectedMissions.indexOf(mType)
                        val selected = idx >= 0
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected && selectedMissions.size > 1) {
                                    selectedMissions.remove(mType)
                                } else if (!selected) {
                                    selectedMissions.add(mType)
                                }
                            },
                            label = {
                                Text(if (selected) "${idx + 1}. ${mType.titleRu}" else mType.titleRu)
                            }
                        )
                    }
                }

                if (MissionType.MATH in selectedMissions) {
                    Text("Сложность математики:", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MathDifficulty.entries.forEach { diff ->
                            FilterChip(
                                selected = mathDifficulty == diff,
                                onClick = { mathDifficulty = diff },
                                label = { Text(diff.name) }
                            )
                        }
                    }
                }

                if (MissionType.STEPS in selectedMissions) {
                    Text("Целевое число шагов: $targetSteps", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = targetSteps.toFloat(),
                        onValueChange = { targetSteps = it.toInt() },
                        valueRange = 20f..100f,
                        steps = 7
                    )
                }

                if (MissionType.QR in selectedMissions) {
                    OutlinedTextField(
                        value = qrPayload,
                        onValueChange = { qrPayload = it },
                        label = { Text("Ожидаемый QR-код в ванной") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (MissionType.NFC in selectedMissions) {
                    OutlinedTextField(
                        value = nfcTagId,
                        onValueChange = { nfcTagId = it },
                        label = { Text("NFC Tag ID (* = любая метка)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (MissionType.VOICE in selectedMissions) {
                    OutlinedTextField(
                        value = voicePhrase,
                        onValueChange = { voicePhrase = it },
                        label = { Text("Контрольная голосовая фраза") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Разрешить штрафной Snooze (+5 мин)", modifier = Modifier.weight(1f))
                    Switch(checked = allowSnooze, onCheckedChange = { allowSnooze = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Фаза «Рассвет» за T−15 мин", modifier = Modifier.weight(1f))
                    Switch(checked = sunriseEnabled, onCheckedChange = { sunriseEnabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Контроль «Анти-возврат в кровать» (5 мин)", modifier = Modifier.weight(1f))
                    Switch(checked = antiReturnEnabled, onCheckedChange = { antiReturnEnabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Резерв: Webhook умного дома и контакт", modifier = Modifier.weight(1f))
                    Switch(
                        checked = backupWebhookEnabled && backupContactEnabled,
                        onCheckedChange = {
                            backupWebhookEnabled = it
                            backupContactEnabled = it
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entity = (initialAlarm ?: AlarmEntity(hour = hour, minute = minute, label = label)).copy(
                        hour = hour,
                        minute = minute,
                        label = label.ifBlank { "Боевой рубеж" },
                        daysOfWeekCsv = selectedDays.sorted().joinToString(","),
                        enabled = true,
                        escalationProfile = profile.name,
                        missionChainCsv = selectedMissions.joinToString(",") { it.name },
                        mathDifficulty = mathDifficulty.name,
                        targetSteps = targetSteps,
                        qrExpectedPayload = qrPayload.ifBlank { "ALARM-COMMANDER-BATHROOM-01" },
                        nfcExpectedTagId = nfcTagId.ifBlank { "*" },
                        voicePhraseExpected = voicePhrase.ifBlank { "Я полностью проснулся и готов к действию" },
                        allowSnooze = allowSnooze,
                        sunrisePreAlarmEnabled = sunriseEnabled,
                        antiReturnEnabled = antiReturnEnabled,
                        backupWebhookEnabled = backupWebhookEnabled,
                        backupContactEnabled = backupContactEnabled
                    )
                    onSave(entity)
                },
                modifier = Modifier.testTag("save_alarm_button")
            ) {
                Text("Сохранить рубеж")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
