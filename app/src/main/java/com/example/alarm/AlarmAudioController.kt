package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
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
 * Энергоэффективный контроллер многослойного звука и тактической вибрации будильника.
 * Оптимизации энергопотребления:
 * - Во время пауз скважности (!playPulse) не гоняет пустые буферы в AudioTrack, а уходит в suspend delay.
 * - Не вызывает IPC-метод vibrator.cancel() на каждом тике, если вибрация уже остановлена.
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

    @Volatile
    private var wasVibrating: Boolean = false

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
        if (volumePercent <= 0 && vibrationCadenceLevel <= 0) {
            stopAll()
            return
        }

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
                    val playPulse = when (currentStage) {
                        0 -> tickCounter % 6 == 0
                        1 -> tickCounter % 3 != 2
                        else -> true
                    }

                    if (!playPulse) {
                        // Экономия CPU во время паузы между импульсами на ранних стадиях
                        delay(190L)
                    } else {
                        val volScalar = (targetVolumePercent / 100.0).coerceIn(0.05, 1.0)
                        val baseFreq = when (currentStage) {
                            0 -> 432.0
                            1 -> 528.0
                            2 -> 740.0
                            else -> if (tickCounter % 4 < 2) 880.0 else 1174.6
                        }
                        val secondFreq = if (dualToneEnabled) baseFreq * 1.498 else 0.0

                        for (i in 0 until chunkSamples) {
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

                        runCatching {
                            audioTrack?.write(pcmBuffer, 0, chunkSamples)
                        }
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
                if (wasVibrating) {
                    runCatching { vibrator?.cancel() }
                    wasVibrating = false
                }
            }
        }
    }

    private fun triggerVibrationPulse(level: Int) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator() || level <= 0) {
            if (wasVibrating) {
                runCatching { vib.cancel() }
                wasVibrating = false
            }
            return
        }
        wasVibrating = true
        val durationMs = when (level) {
            1 -> 200L
            2 -> 420L
            else -> 700L
        }
        val amplitude = when (level) {
            1 -> 110
            2 -> 185
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
        if (wasVibrating) {
            runCatching { vibrator?.cancel() }
            wasVibrating = false
        }
    }
}
