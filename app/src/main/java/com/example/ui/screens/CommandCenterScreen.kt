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
import androidx.compose.foundation.layout.heightIn
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
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.alarm.AlarmAudioController
import com.example.alarm.AlarmScheduler
import com.example.data.AlarmEntity
import com.example.data.AlarmSignalMode
import com.example.data.AlarmSoundSource
import com.example.data.EscalationProfile
import com.example.data.MathDifficulty
import com.example.data.MissionType
import com.example.ui.AppStrings
import com.example.ui.LocalAppStrings
import com.example.ui.localizeRuntimeText
import com.example.ui.localizedBadge
import com.example.ui.localizedLabel
import com.example.ui.localizedSubtitle
import com.example.ui.localizedTitle
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.TacticalAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun AlarmEntity.localizedDaysSummary(s: AppStrings): String {
    val d = daysOfWeek
    if (d.size == 7) return s.everyDay
    if (d == setOf(1, 2, 3, 4, 5)) return s.weekdays
    if (d == setOf(6, 7)) return s.weekends
    val map = s.dayNamesShort.toMap()
    return d.sorted().mapNotNull { map[it] }.joinToString(", ")
}

@Composable
fun CommandCenterScreen(
    alarms: List<AlarmEntity>,
    disclaimerAccepted: Boolean,
    grantedPermissionsCount: Int,
    totalPermissionsCount: Int,
    defaultQrPayload: String,
    defaultNfcTagId: String,
    defaultSignalMode: String = AlarmSignalMode.SOUND_AND_VIBRO.name,
    defaultSoundSource: String = AlarmSoundSource.SYSTEM_DEFAULT_ALARM.name,
    defaultRingtoneUri: String = "",
    defaultRingtoneTitle: String = "",
    onAcceptDisclaimer: () -> Unit,
    onSaveAlarm: (AlarmEntity) -> Unit,
    onToggleAlarm: (AlarmEntity, Boolean) -> Unit,
    onDeleteAlarm: (AlarmEntity) -> Unit,
    onTriggerTestAlarm: (Long, Boolean) -> Unit,
    onTriggerAntiReturnTest: (Long) -> Unit
) {
    val s = LocalAppStrings.current
    var editingAlarm by remember { mutableStateOf<AlarmEntity?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var showDisclaimerDialog by remember { mutableStateOf(!disclaimerAccepted) }

    if (showDisclaimerDialog) {
        AlertDialog(
            onDismissRequest = { /* требуем осознанного подтверждения */ },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = s.disclaimerTitle,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(s.disclaimerTitle)
            },
            text = {
                Text(s.disclaimerBody)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisclaimerDialog = false
                        onAcceptDisclaimer()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("accept_disclaimer_button")
                ) {
                    Text(s.disclaimerAccept, textAlign = TextAlign.Center)
                }
            }
        )
    }

    if (showEditorDialog) {
        AlarmEditorDialog(
            initialAlarm = editingAlarm,
            defaultQrPayload = defaultQrPayload,
            defaultNfcTagId = defaultNfcTagId,
            defaultSignalMode = defaultSignalMode,
            defaultSoundSource = defaultSoundSource,
            defaultRingtoneUri = defaultRingtoneUri,
            defaultRingtoneTitle = defaultRingtoneTitle,
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
                            contentDescription = s.disclaimerTitle,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = s.disclaimerShortBanner,
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = s.provingGroundTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = s.provingGroundSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onTriggerTestAlarm(primaryAlarmId, false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TacticalAmber,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("combat_test_t0_button")
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = s.testT0Btn,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = s.testT0Btn,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            OutlinedButton(
                                onClick = { onTriggerTestAlarm(primaryAlarmId, true) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("combat_test_sunrise_button")
                            ) {
                                Icon(
                                    Icons.Default.WbSunny,
                                    contentDescription = s.testSunriseBtn,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = s.testSunriseBtn,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onTriggerAntiReturnTest(primaryAlarmId) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("combat_test_anti_return_button")
                        ) {
                            Text(
                                text = s.testAntiReturnBtn,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                contentDescription = s.emptyAlarmsTitle,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = s.emptyAlarmsTitle,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = s.emptyAlarmsHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = s.newAlarmBtn)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = s.newAlarmBtn,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
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
    val s = LocalAppStrings.current
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Анимированный тактический радар (Canvas)
            Box(
                modifier = Modifier.size(72.dp),
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
                        text = s.radarDutyActive,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = s.osPermissionsShort(grantedPermissionsCount, totalPermissionsCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (nextPair != null) {
                    val (alarm, triggerMs) = nextPair
                    val diffMinutes = ((triggerMs - nowMs).coerceAtLeast(60_000L) / 60_000L).toInt()
                    val hours = diffMinutes / 60
                    val mins = diffMinutes % 60
                    Text(
                        text = s.nextT0Format(alarm.formattedTime(), hours, mins),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = s.profileLabelFormat(
                            alarm.label.localizeRuntimeText(s.isEnglish),
                            alarm.profileEnum.name
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = s.allAlarmsDisabled,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = s.enableAlarmOrTestHint,
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
    val s = LocalAppStrings.current
    val profileColor = when (alarm.profileEnum) {
        EscalationProfile.GENTLE -> MaterialTheme.colorScheme.secondary
        EscalationProfile.HARD -> MaterialTheme.colorScheme.primary
        EscalationProfile.EXTREME_SAFE -> MaterialTheme.colorScheme.error
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        text = "${alarm.label.localizeRuntimeText(s.isEnglish)} • ${alarm.localizedDaysSummary(s)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
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
                    text = s.escalationBadge(alarm.profileEnum.name),
                    color = profileColor
                )
                BadgePill(
                    text = s.missionsBadge(
                        alarm.missionChain.joinToString(" → ") { it.localizedBadge(s.isEnglish) }
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                if (!alarm.allowSnooze) {
                    BadgePill(text = s.antiSnoozeBadge, color = MaterialTheme.colorScheme.secondary)
                } else {
                    BadgePill(text = s.penaltySnoozeBadge, color = MaterialTheme.colorScheme.primary)
                }
                if (alarm.sunrisePreAlarmEnabled) {
                    BadgePill(
                        text = s.sunriseBadge(alarm.sunriseLeadMinutes),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (alarm.antiReturnEnabled) {
                    BadgePill(
                        text = s.antiReturnBadge(alarm.antiReturnDelayMinutes),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                val soundLabel = if (alarm.signalModeEnum == AlarmSignalMode.VIBRO_ONLY) {
                    alarm.signalModeEnum.localizedBadge(s.isEnglish)
                } else {
                    val melodyShort = if (alarm.soundSourceEnum == AlarmSoundSource.SYSTEM_CUSTOM_RINGTONE &&
                        alarm.customRingtoneTitle.isNotBlank()
                    ) {
                        alarm.customRingtoneTitle.localizeRuntimeText(s.isEnglish)
                    } else {
                        alarm.soundSourceEnum.localizedShort(s.isEnglish)
                    }
                    "${alarm.signalModeEnum.localizedBadge(s.isEnglish)} • $melodyShort"
                }
                BadgePill(
                    text = soundLabel,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onTestNow,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("test_alarm_${alarm.id}")
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = s.runTestBtn,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s.runTestBtn)
                }

                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_alarm_${alarm.id}")) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_alarm_${alarm.id}")) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
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
        color = color.copy(alpha = 0.14f),
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
    defaultSignalMode: String = AlarmSignalMode.SOUND_AND_VIBRO.name,
    defaultSoundSource: String = AlarmSoundSource.SYSTEM_DEFAULT_ALARM.name,
    defaultRingtoneUri: String = "",
    defaultRingtoneTitle: String = "",
    onDismiss: () -> Unit,
    onSave: (AlarmEntity) -> Unit
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    var hour by remember { mutableIntStateOf(initialAlarm?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initialAlarm?.minute ?: 0) }
    var label by remember {
        mutableStateOf(
            initialAlarm?.label?.localizeRuntimeText(s.isEnglish)
                ?: if (s.isEnglish) "Morning Wake-Up" else "Утренний подъём"
        )
    }

    val selectedDays = remember {
        val initDays = initialAlarm?.daysOfWeek ?: setOf(1, 2, 3, 4, 5)
        mutableStateListOf<Int>().apply { addAll(initDays) }
    }

    var profile by remember {
        mutableStateOf(initialAlarm?.profileEnum ?: EscalationProfile.HARD)
    }

    var signalMode by remember {
        mutableStateOf(
            initialAlarm?.signalModeEnum ?: AlarmSignalMode.fromName(defaultSignalMode)
        )
    }
    var soundSource by remember {
        mutableStateOf(
            initialAlarm?.soundSourceEnum ?: AlarmSoundSource.fromName(defaultSoundSource)
        )
    }
    var customRingtoneUri by remember {
        mutableStateOf(initialAlarm?.customRingtoneUri?.ifBlank { defaultRingtoneUri } ?: defaultRingtoneUri)
    }
    var customRingtoneTitle by remember {
        val initTitle = initialAlarm?.customRingtoneTitle?.ifBlank { defaultRingtoneTitle } ?: defaultRingtoneTitle
        mutableStateOf(
            initTitle.ifBlank {
                AlarmAudioController.resolveRingtoneTitle(context, customRingtoneUri, s.isEnglish)
            }
        )
    }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (pickedUri != null) {
                val uriStr = pickedUri.toString()
                customRingtoneUri = uriStr
                customRingtoneTitle = AlarmAudioController.resolveRingtoneTitle(context, uriStr, s.isEnglish)
                soundSource = AlarmSoundSource.SYSTEM_CUSTOM_RINGTONE
            }
        }
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
            initialAlarm?.voicePhraseExpected?.localizeRuntimeText(s.isEnglish)
                ?: if (s.isEnglish) "I am fully awake and ready for action" else "Я полностью проснулся и готов к действию"
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
            Text(if (initialAlarm == null) s.editorNewTitle else s.editorEditTitle)
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
                            color = MaterialTheme.colorScheme.primary
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
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(onClick = { minute = (minute + 55) % 60 }) { Text("−5") }
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(s.editorLabelField) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alarm_label_input")
                )

                Text(
                    s.editorDaysOfWeek,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    s.dayNamesShort.forEach { (isoDay, shortName) ->
                        val isSelected = isoDay in selectedDays
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    if (isSelected) selectedDays.remove(isoDay) else selectedDays.add(isoDay)
                                }
                        ) {
                            Text(
                                text = shortName,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    s.editorEscalationProfile,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                EscalationProfile.entries.forEach { item ->
                    val selected = profile == item
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { profile = item }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.localizedTitle(s.isEnglish),
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = item.localizedSubtitle(s.isEnglish),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    s.signalModeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlarmSignalMode.entries.forEach { mode ->
                        FilterChip(
                            selected = signalMode == mode,
                            onClick = { signalMode = mode },
                            label = { Text(mode.localizedBadge(s.isEnglish)) }
                        )
                    }
                }

                if (signalMode != AlarmSignalMode.VIBRO_ONLY) {
                    Text(
                        s.soundSourceLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AlarmSoundSource.entries.forEach { src ->
                            FilterChip(
                                selected = soundSource == src,
                                onClick = { soundSource = src },
                                label = { Text(src.localizedShort(s.isEnglish)) }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                                    RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE
                                )
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            }
                            runCatching { ringtonePickerLauncher.launch(pickerIntent) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = "${s.pickSystemRingtoneBtn} ($customRingtoneTitle)",
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Text(
                    s.editorMissionChain,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MissionType.entries.forEach { mType ->
                        val idx = selectedMissions.indexOf(mType)
                        val selected = idx >= 0
                        val title = mType.localizedTitle(s.isEnglish)
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
                                Text(if (selected) "${idx + 1}. $title" else title)
                            }
                        )
                    }
                }

                if (MissionType.MATH in selectedMissions) {
                    Text(s.editorMathDifficulty, style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MathDifficulty.entries.forEach { diff ->
                            FilterChip(
                                selected = mathDifficulty == diff,
                                onClick = { mathDifficulty = diff },
                                label = { Text(diff.localizedLabel(s.isEnglish)) }
                            )
                        }
                    }
                }

                if (MissionType.STEPS in selectedMissions) {
                    Text(s.editorTargetSteps(targetSteps), style = MaterialTheme.typography.labelSmall)
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
                        label = { Text(s.editorExpectedQr) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (MissionType.NFC in selectedMissions) {
                    OutlinedTextField(
                        value = nfcTagId,
                        onValueChange = { nfcTagId = it },
                        label = { Text(s.editorExpectedNfc) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (MissionType.VOICE in selectedMissions) {
                    OutlinedTextField(
                        value = voicePhrase,
                        onValueChange = { voicePhrase = it },
                        label = { Text(s.editorExpectedVoice) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.editorAllowPenaltySnooze, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = allowSnooze, onCheckedChange = { allowSnooze = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.editorSunrisePreAlarm, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = sunriseEnabled, onCheckedChange = { sunriseEnabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.editorAntiReturn, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = antiReturnEnabled, onCheckedChange = { antiReturnEnabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.editorBackupChannels, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
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
                        label = label.ifBlank { if (s.isEnglish) "Tactical Alarm" else "Боевой рубеж" },
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
                        backupContactEnabled = backupContactEnabled,
                        signalMode = signalMode.name,
                        soundSource = soundSource.name,
                        customRingtoneUri = customRingtoneUri,
                        customRingtoneTitle = customRingtoneTitle
                    )
                    onSave(entity)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_alarm_button")
            ) {
                Text(s.saveAlarmBtn)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(s.cancelBtn)
            }
        }
    )
}
