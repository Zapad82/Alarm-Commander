package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Контроллер многослойного звука и тактической вибрации будильника.
 * Генерирует синтезированный сигнал через AudioTrack на канале USAGE_ALARM,
 * плавно повышает громкость по стадиям EscalationEngine и подключает второй генератор тона.
 */
class AlarmAudioController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthJob: Job? = null

    @Volatile
    private var targetVolumePercent: Int = 40

    @Volatile
    private var currentStage: Int = 1

    @Volatile
    private var dualToneEnabled: Boolean = false

    @Volatile
    private var vibrationLevel: Int = 1

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun startOrUpdate(
        stage: Int,
        volumePercent: Int,
        dualTone: Boolean,
        vibrationCadenceLevel: Int
    ) {
        currentStage = stage
        targetVolumePercent = volumePercent.coerceIn(5, 100)
        dualToneEnabled = dualTone
        vibrationLevel = vibrationCadenceLevel

        if (synthJob?.isActive == true) return

        synthJob = scope.launch {
            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2)

            val audioTrack = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()

            runCatching { audioTrack?.play() }

            val chunkSamples = sampleRate / 5 // 200ms блок
            val pcmBuffer = ShortArray(chunkSamples)
            var phase1 = 0.0
            var phase2 = 0.0
            var tickCounter = 0

            try {
                while (isActive) {
                    val volScalar = (targetVolumePercent / 100.0).coerceIn(0.05, 1.0)
                    val baseFreq = when (currentStage) {
                        0 -> 432.0 // Мягкий тон рассвета
                        1 -> 528.0 // Уровень 1
                        2 -> 740.0 // Уровень 2
                        else -> if (tickCounter % 4 < 2) 880.0 else 1174.6 // Уровень 3-4: двухчастотная сирена
                    }
                    val secondFreq = if (dualToneEnabled) baseFreq * 1.498 else 0.0

                    // Скважность сигнала (паузы между импульсами на ранних стадиях)
                    val playPulse = when (currentStage) {
                        0 -> tickCounter % 6 == 0
                        1 -> tickCounter % 3 != 2
                        else -> true
                    }

                    for (i in 0 until chunkSamples) {
                        if (!playPulse) {
                            pcmBuffer[i] = 0
                        } else {
                            phase1 += (2.0 * PI * baseFreq) / sampleRate
                            val s1 = sin(phase1)
                            val s2 = if (secondFreq > 0.0) {
                                phase2 += (2.0 * PI * secondFreq) / sampleRate
                                sin(phase2) * 0.65
                            } else {
                                0.0
                            }
                            val combined = ((s1 + s2) / (if (secondFreq > 0.0) 1.65 else 1.0)) * volScalar
                            pcmBuffer[i] = (combined * Short.MAX_VALUE * 0.85).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                        }
                    }

                    runCatching {
                        audioTrack?.write(pcmBuffer, 0, chunkSamples)
                    }

                    if (tickCounter % 5 == 0) {
                        triggerVibrationPulse(vibrationLevel)
                    }

                    tickCounter++
                    delay(10L)
                }
            } finally {
                runCatching {
                    audioTrack?.stop()
                    audioTrack?.release()
                }
                runCatching { vibrator?.cancel() }
            }
        }
    }

    private fun triggerVibrationPulse(level: Int) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator() || level <= 0) {
            runCatching { vib.cancel() }
            return
        }
        val durationMs = when (level) {
            1 -> 220L
            2 -> 450L
            else -> 750L
        }
        val amplitude = when (level) {
            1 -> 110
            2 -> 190
            else -> 255
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        }
    }

    fun stopAll() {
        synthJob?.cancel()
        synthJob = null
        runCatching { vibrator?.cancel() }
    }
}
