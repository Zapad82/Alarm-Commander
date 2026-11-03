package com.example.ui.screens

import android.Manifest
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.alarm.ActiveAlarmUiState
import com.example.data.AlarmState
import com.example.data.MissionType
import com.example.mission.StepSensorTracker
import com.example.mission.VoiceSpeechHelper
import com.example.mission.ZxingQrCodeAnalyzer
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TacticalAmber
import java.util.concurrent.Executors

/**
 * Полноэкранный боевой экран будильника и выполнения цепочки миссий.
 * Блокирует системный жест «Назад» (BackHandler), исключая отключение на автопилоте.
 */
@Composable
fun ActiveAlarmScreen(
    uiState: ActiveAlarmUiState,
    isCameraPermissionGranted: Boolean,
    isActivityPermissionGranted: Boolean,
    isRecordAudioPermissionGranted: Boolean,
    onPermissionRefresh: () -> Unit,
    onStartMissionClick: () -> Unit,
    onSubmitMathAnswer: (Int) -> Unit,
    onSubmitSteps: (Int) -> Unit,
    onSubmitQr: (String) -> Unit,
    onSubmitNfc: (String) -> Unit,
    onSubmitVoicePhrase: (String) -> Unit,
    onTogglePenaltySnooze: (Boolean) -> Unit,
    onSubmitPenaltySnooze: (String) -> Unit,
    onDismissSunrise: () -> Unit,
    onConfirmAntiReturnAwake: () -> Unit
) {
    // Блокируем кнопку/свайп «Назад», пока звенит будильник или идёт миссия
    BackHandler(enabled = true) {
        // Намеренно заблокировано: выход возможен только через выполнение цепочки миссий
    }

    val escalation = uiState.escalation
    val alarm = uiState.alarm

    // Безопасная низкочастотная пульсация фона (период 1400 мс ≈ 0.7 Гц, без опасного стробоскопа)
    val infiniteTransition = rememberInfiniteTransition(label = "safe_escalation_pulse")
    val pulseColor by infiniteTransition.animateColor(
        initialValue = ObsidianBg,
        targetValue = if (escalation?.safeScreenPulseActive == true) {
            Color(0xFF3B0D11)
        } else {
            ObsidianBg
        },
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg_pulse"
    )

    // 1. Режим контроля «Анти-возврат в кровать»
    if (uiState.isAntiReturnPromptActive) {
        AntiReturnVerificationView(
            alarmLabel = alarm?.label ?: "Контроль пробуждения",
            secondsRemaining = uiState.antiReturnSecondsRemaining,
            onConfirmAwake = onConfirmAntiReturnAwake
        )
        return
    }

    // 2. Режим предварительного «Рассвета» (T−15 минут)
    if (escalation?.state == AlarmState.PRE_ALARM_SUNRISE) {
        SunrisePreAlarmView(
            alarmLabel = alarm?.label ?: "Утренний рубеж",
            alarmTime = alarm?.formattedTime() ?: "07:00",
            webhookStatus = uiState.lastBackupEventMessage,
            onWakeUpEarly = onDismissSunrise
        )
        return
    }

    // 3. Основной боевой режим T0 / Эскалация / Цепочка миссий
    val isMissionOpened = escalation?.state == AlarmState.MISSION_IN_PROGRESS

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pulseColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Верхний тактический бейдж состояния
            Surface(
                color = AlertCrimson.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AlertCrimson, RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Боевая тревога",
                        tint = AlertCrimson,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "БОЕВАЯ ТРЕВОГА • АВТОПИЛОТ ЗАБЛОКИРОВАН",
                        style = MaterialTheme.typography.labelLarge,
                        color = AlertCrimson
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = alarm?.formattedTime() ?: "07:00",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = alarm?.label ?: "Боевой подъём",
                style = MaterialTheme.typography.titleLarge,
                color = TacticalAmber
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Карточка телеметрии эскалации
            if (escalation != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ПРОФИЛЬ: ${escalation.profile.name} • ЭТАП ${escalation.stage}/4",
                                style = MaterialTheme.typography.labelLarge,
                                color = TacticalAmber
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Громкость",
                                    tint = if (escalation.missionAttenuated) RadarEmerald else AlertCrimson,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${escalation.volumePercent}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (escalation.missionAttenuated) RadarEmerald else AlertCrimson
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { (escalation.stage / 4f).coerceIn(0.15f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (escalation.missionAttenuated) RadarEmerald else AlertCrimson
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = escalation.statusDescriptionRu,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!uiState.lastBackupEventMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Резервный канал: ${uiState.lastBackupEventMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TacticalAmber
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isMissionOpened && !uiState.isPenaltySnoozeMode) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TacticalAmber.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ЦЕПОЧКА МИССИЙ РАЗБЛОКИРОВКИ (${uiState.totalMissionsInChain})",
                            style = MaterialTheme.typography.labelLarge,
                            color = TacticalAmber
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = alarm?.missionChain?.joinToString("  →  ") { it.titleRu } ?: "Математика",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onStartMissionClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TacticalAmber,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .testTag("start_mission_button")
                        ) {
                            Text(
                                text = "ПРИСТУПИТЬ К МИССИИ ОТКЛЮЧЕНИЯ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (alarm?.allowSnooze == true) {
                    OutlinedButton(
                        onClick = { onTogglePenaltySnooze(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("penalty_snooze_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Snooze,
                            contentDescription = "Штрафной Snooze"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Штрафной Snooze (+${alarm.snoozeDurationMinutes} мин через фразу)")
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Анти-Snooze",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Режим Анти-Snooze: перенос будильника заблокирован",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (uiState.isPenaltySnoozeMode) {
                PenaltySnoozeCard(
                    requiredPhrase = uiState.penaltyPhraseRequired,
                    feedback = uiState.missionFeedback,
                    onSubmitPhrase = onSubmitPenaltySnooze,
                    onCancel = { onTogglePenaltySnooze(false) }
                )
            } else {
                MissionExecutionContainer(
                    uiState = uiState,
                    isCameraPermissionGranted = isCameraPermissionGranted,
                    isActivityPermissionGranted = isActivityPermissionGranted,
                    isRecordAudioPermissionGranted = isRecordAudioPermissionGranted,
                    onPermissionRefresh = onPermissionRefresh,
                    onSubmitMathAnswer = onSubmitMathAnswer,
                    onSubmitSteps = onSubmitSteps,
                    onSubmitQr = onSubmitQr,
                    onSubmitNfc = onSubmitNfc,
                    onSubmitVoicePhrase = onSubmitVoicePhrase
                )
            }
        }
    }
}

@Composable
private fun MissionExecutionContainer(
    uiState: ActiveAlarmUiState,
    isCameraPermissionGranted: Boolean,
    isActivityPermissionGranted: Boolean,
    isRecordAudioPermissionGranted: Boolean,
    onPermissionRefresh: () -> Unit,
    onSubmitMathAnswer: (Int) -> Unit,
    onSubmitSteps: (Int) -> Unit,
    onSubmitQr: (String) -> Unit,
    onSubmitNfc: (String) -> Unit,
    onSubmitVoicePhrase: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalAmber, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "МИССИЯ ${uiState.currentMissionIndex + 1} ИЗ ${uiState.totalMissionsInChain}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TacticalAmber
                )
                Text(
                    text = uiState.currentMissionType?.titleRu ?: "Задача",
                    style = MaterialTheme.typography.titleMedium,
                    color = RadarEmerald
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!uiState.missionFeedback.isNullOrBlank()) {
                Text(
                    text = uiState.missionFeedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TacticalAmber
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            when (uiState.currentMissionType) {
                MissionType.MATH, null -> MathMissionView(
                    prompt = uiState.missionPrompt,
                    step = uiState.missionProgressStep,
                    total = uiState.missionTotalSteps,
                    onSubmitAnswer = onSubmitMathAnswer
                )
                MissionType.STEPS -> StepsMissionView(
                    targetSteps = uiState.missionTotalSteps,
                    currentSteps = uiState.missionProgressStep,
                    isPermissionGranted = isActivityPermissionGranted,
                    onPermissionRefresh = onPermissionRefresh,
                    onStepDetected = onSubmitSteps
                )
                MissionType.QR -> QrMissionView(
                    expectedPayload = uiState.alarm?.qrExpectedPayload ?: "ALARM-COMMANDER-BATHROOM-01",
                    isCameraPermissionGranted = isCameraPermissionGranted,
                    onPermissionRefresh = onPermissionRefresh,
                    onQrScanned = onSubmitQr
                )
                MissionType.NFC -> NfcMissionView(
                    expectedTagId = uiState.alarm?.nfcExpectedTagId ?: "*",
                    onNfcSimulatedOrOverride = onSubmitNfc
                )
                MissionType.VOICE -> VoiceMissionView(
                    expectedPhrase = uiState.alarm?.voicePhraseExpected
                        ?: "Я полностью проснулся и готов к действию",
                    isRecordAudioPermissionGranted = isRecordAudioPermissionGranted,
                    onPermissionRefresh = onPermissionRefresh,
                    onVoiceRecognized = onSubmitVoicePhrase
                )
            }
        }
    }
}

@Composable
private fun MathMissionView(
    prompt: String,
    step: Int,
    total: Int,
    onSubmitAnswer: (Int) -> Unit
) {
    var answerText by remember(prompt) { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Решено примеров: $step / $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = ObsidianBg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = prompt,
                style = MaterialTheme.typography.displayMedium,
                color = TacticalAmber,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(vertical = 16.dp, horizontal = 12.dp)
                    .testTag("math_prompt_text")
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = answerText,
            onValueChange = { answerText = it.filter { ch -> ch.isDigit() || ch == '-' } },
            label = { Text("Ответ") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("math_answer_input")
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Встроенный крупный тактический цифровой блок (Numpad) для мгновенного ввода на экране блокировки
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("±", "0", "⌫")
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            rows.forEach { rowKeys ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowKeys.forEach { key ->
                        FilledTonalButton(
                            onClick = {
                                when (key) {
                                    "⌫" -> if (answerText.isNotEmpty()) {
                                        answerText = answerText.dropLast(1)
                                    }
                                    "±" -> {
                                        answerText = if (answerText.startsWith("-")) {
                                            answerText.removePrefix("-")
                                        } else {
                                            "-$answerText"
                                        }
                                    }
                                    else -> if (answerText.length < 7) {
                                        answerText += key
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                val parsed = answerText.trim().toIntOrNull()
                if (parsed != null) {
                    onSubmitAnswer(parsed)
                    answerText = ""
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = RadarEmerald,
                contentColor = Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("submit_math_button")
        ) {
            Text("ПРОВЕРИТЬ ОТВЕТ", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StepsMissionView(
    targetSteps: Int,
    currentSteps: Int,
    isPermissionGranted: Boolean,
    onPermissionRefresh: () -> Unit,
    onStepDetected: (Int) -> Unit
) {
    val context = LocalContext.current
    var sensorSource by remember { mutableStateOf("Инициализация датчика...") }
    var localSteps by remember { mutableIntStateOf(currentSteps) }
    var expectedNextFoot by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onPermissionRefresh()
    }

    DisposableEffect(Unit) {
        val tracker = StepSensorTracker(context) { steps, sourceLabel ->
            sensorSource = sourceLabel
            if (steps > localSteps) {
                localSteps = steps
                onStepDetected(localSteps)
            }
        }
        tracker.startTracking()
        onDispose {
            tracker.stopTracking()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Default.DirectionsWalk,
            contentDescription = "Шагомер",
            tint = TacticalAmber,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${currentSteps.coerceAtLeast(localSteps)} / $targetSteps ШАГОВ",
            style = MaterialTheme.typography.displayMedium,
            color = RadarEmerald
        )
        Text(
            text = "Источник: $sensorSource",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = {
                (currentSteps.coerceAtLeast(localSteps).toFloat() / targetSteps.coerceAtLeast(1)).coerceIn(0f, 1f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = RadarEmerald
        )

        if (!isPermissionGranted) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Разрешить доступ к аппаратному шагомеру")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Идите с телефоном в руках или поочерёдно нажимайте «ЛЕВЫЙ ШАГ» / «ПРАВЫЙ ШАГ» (режим проверки на эмуляторе):",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (expectedNextFoot == 0) {
                        expectedNextFoot = 1
                        localSteps = currentSteps.coerceAtLeast(localSteps) + 5
                        onStepDetected(localSteps)
                    }
                },
                enabled = expectedNextFoot == 0,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("step_left_button")
            ) {
                Text("ЛЕВЫЙ ШАГ (+5)")
            }
            Button(
                onClick = {
                    if (expectedNextFoot == 1) {
                        expectedNextFoot = 0
                        localSteps = currentSteps.coerceAtLeast(localSteps) + 5
                        onStepDetected(localSteps)
                    }
                },
                enabled = expectedNextFoot == 1,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("step_right_button")
            ) {
                Text("ПРАВЫЙ ШАГ (+5)")
            }
        }
    }
}

@Composable
private fun QrMissionView(
    expectedPayload: String,
    isCameraPermissionGranted: Boolean,
    onPermissionRefresh: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var manualCodeInput by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onPermissionRefresh()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = "Сканер QR",
                tint = TacticalAmber
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Ожидается QR: $expectedPayload",
                style = MaterialTheme.typography.labelLarge,
                color = TacticalAmber
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isCameraPermissionGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, RadarEmerald, RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        cameraProviderFuture.addListener({
                            runCatching {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(640, 480))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            executor,
                                            ZxingQrCodeAnalyzer { decoded ->
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    onQrScanned(decoded)
                                                }
                                            }
                                        )
                                    }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Камера")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Включить камеру для сканирования QR")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Резервный ввод (если камера недоступна в эмуляторе): введите точный код метки вручную",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = manualCodeInput,
            onValueChange = { manualCodeInput = it },
            label = { Text("Код метки ($expectedPayload)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("qr_manual_input")
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onQrScanned(manualCodeInput) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("submit_qr_button")
        ) {
            Text("ПОДТВЕРДИТЬ КОД МЕТКИ")
        }
    }
}

@Composable
private fun NfcMissionView(
    expectedTagId: String,
    onNfcSimulatedOrOverride: (String) -> Unit
) {
    var manualOverride by remember { mutableStateOf("") }
    val requiredCode = if (expectedTagId == "*" || expectedTagId.isBlank()) "NFC-WAKE-CONFIRMED" else expectedTagId

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Default.Nfc,
            contentDescription = "NFC-ридер",
            tint = TacticalAmber,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Приложите смартфон к NFC-метке",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Ожидаемый ID: ${if (expectedTagId == "*") "Любая зарегистрированная метка" else expectedTagId}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = manualOverride,
            onValueChange = { manualOverride = it },
            label = { Text("Резервный код без NFC: $requiredCode") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nfc_manual_input")
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (manualOverride.trim().equals(requiredCode, ignoreCase = true)) {
                    onNfcSimulatedOrOverride(expectedTagId.ifBlank { "NFC-TAG-01" })
                } else {
                    onNfcSimulatedOrOverride(manualOverride)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("submit_nfc_button")
        ) {
            Text("ПОДТВЕРДИТЬ КАСАНИЕ NFC")
        }
    }
}

@Composable
private fun VoiceMissionView(
    expectedPhrase: String,
    isRecordAudioPermissionGranted: Boolean,
    onPermissionRefresh: () -> Unit,
    onVoiceRecognized: (String) -> Unit
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("Нажмите кнопку микрофона и произнесите фразу вслух") }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var manualPhraseInput by remember { mutableStateOf("") }

    val speechHelper = remember {
        VoiceSpeechHelper(
            context = context,
            onStatusChanged = { listening, status ->
                isListening = listening
                voiceStatus = status
            },
            onRmsChanged = { rms -> rmsLevel = rms },
            onSpeechResult = { text -> onVoiceRecognized(text) }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            speechHelper.stopListening()
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionRefresh()
        if (granted) {
            speechHelper.startListening()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Голосовая миссия",
            tint = if (isListening) RadarEmerald else TacticalAmber,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Произнесите контрольную фразу вслух:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = ObsidianBg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "«$expectedPhrase»",
                style = MaterialTheme.typography.titleLarge,
                color = TacticalAmber,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(14.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = voiceStatus,
            style = MaterialTheme.typography.labelSmall,
            color = if (isListening) RadarEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (isListening) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (rmsLevel / 12f).coerceIn(0.1f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = RadarEmerald
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                if (isRecordAudioPermissionGranted) {
                    speechHelper.startListening()
                } else {
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = TacticalAmber,
                contentColor = Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("start_voice_recognition_button")
        ) {
            Icon(Icons.Default.Mic, contentDescription = "Говорить")
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isListening) "СЛУШАЮ... ГОВОРИТЕ" else "НАЧАТЬ РАСПОЗНАВАНИЕ ГОЛОСА",
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = manualPhraseInput,
            onValueChange = { manualPhraseInput = it },
            label = { Text("Резервный ввод фразы вручную") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("voice_manual_input")
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onVoiceRecognized(manualPhraseInput) },
            colors = ButtonDefaults.buttonColors(
                containerColor = RadarEmerald,
                contentColor = Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("submit_voice_button")
        ) {
            Text("ПОДТВЕРДИТЬ ФРАЗУ", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PenaltySnoozeCard(
    requiredPhrase: String,
    feedback: String?,
    onSubmitPhrase: (String) -> Unit,
    onCancel: () -> Unit
) {
    var typed by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalAmber, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "ШТРАФНАЯ ЗАДАЧА ДЛЯ SNOOZE",
                style = MaterialTheme.typography.labelLarge,
                color = TacticalAmber
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Чтобы отложить сигнал на 5 минут, введите без ошибок фразу:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = ObsidianBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = requiredPhrase,
                    style = MaterialTheme.typography.titleMedium,
                    color = TacticalAmber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }
            if (!feedback.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = feedback, color = AlertCrimson, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Повторите фразу") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Назад")
                }
                Button(
                    onClick = { onSubmitPhrase(typed) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Подтвердить")
                }
            }
        }
    }
}

@Composable
private fun SunrisePreAlarmView(
    alarmLabel: String,
    alarmTime: String,
    webhookStatus: String?,
    onWakeUpEarly: () -> Unit
) {
    val dawnGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF3A1C00),
            Color(0xFFD97706),
            Color(0xFFFDE68A)
        )
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(dawnGradient)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.WbSunny,
                contentDescription = "Рассвет",
                tint = Color(0xFFFFFBEB),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "ФАЗА «РАССВЕТ» (T−15 МИН)",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFFFFBEB)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = alarmTime,
                style = MaterialTheme.typography.displayLarge,
                color = Color.White
            )
            Text(
                text = alarmLabel,
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFFEF3C7)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Мягкий световой поток экрана и команда на умную лампу подготавливают организм к пробуждению до боевого сигнала T0.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF1E293B),
                textAlign = TextAlign.Center
            )
            if (!webhookStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = webhookStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF0F172A),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onWakeUpEarly,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F172A),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("dismiss_sunrise_button")
            ) {
                Text("Я УЖЕ ПРОСНУЛСЯ • ЗАВЕРШИТЬ РАССВЕТ", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AntiReturnVerificationView(
    alarmLabel: String,
    secondsRemaining: Int,
    onConfirmAwake: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, TacticalAmber, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Контроль анти-возврата",
                    tint = TacticalAmber,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "КОНТРОЛЬ АНТИ-ВОЗВРАТА В КРОВАТЬ",
                    style = MaterialTheme.typography.labelLarge,
                    color = TacticalAmber,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$secondsRemaining СЕК",
                    style = MaterialTheme.typography.displayLarge,
                    color = AlertCrimson
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Будильник «$alarmLabel»: подтвердите, что вы не легли обратно в кровать. Если таймер истечёт, боевая тревога и миссии запустятся заново!",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(22.dp))
                Button(
                    onClick = onConfirmAwake,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RadarEmerald,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("confirm_anti_return_button")
                ) {
                    Text(
                        text = "Я БОДРСТВУЮ • НЕ В КРОВАТИ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
