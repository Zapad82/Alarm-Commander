package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.data.AlarmSignalMode
import com.example.data.AlarmSoundSource
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
 * Энергоэффективный контроллер многослойного звука, системных мелодий телефона и тактической вибрации.
 * Поддерживает:
 * - Режимы: Звук + Вибро (SOUND_AND_VIBRO), Только звук (SOUND_ONLY), Только вибрация (VIBRO_ONLY).
 * - Источники звука: Стандартный будильник телефона (SYSTEM_DEFAULT_ALARM), выбранная системная мелодия
 *   телефона (SYSTEM_CUSTOM_RINGTONE) через MediaPlayer с динамической регулировкой громкости эскалации,
 *   а также 3 встроенных синтезатора (Сирена, Сонар, Мягкий тон 432 Гц) с автоматическим резервом.
 */
class AlarmAudioController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthJob: Job? = null
    private var previewStopJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeMediaUriString: String? = null

    @Volatile
    private var targetVolumePercent: Int = 40

    @Volatile
    private var currentStage: Int = 1

    @Volatile
    private var dualToneEnabled: Boolean = false

    @Volatile
    private var vibrationLevel: Int = 1

    @Volatile
    private var currentSignalMode: AlarmSignalMode = AlarmSignalMode.SOUND_AND_VIBRO

    @Volatile
    private var currentSoundSource: AlarmSoundSource = AlarmSoundSource.SYSTEM_DEFAULT_ALARM

    @Volatile
    private var currentCustomUri: String = ""

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

    companion object {
        fun resolveDefaultAlarmUri(): Uri? {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        fun resolveRingtoneTitle(context: Context, uriString: String, isEnglish: Boolean): String {
            if (uriString.isBlank()) {
                return if (isEnglish) "Default Phone Alarm" else "Стандартный будильник телефона"
            }
            return runCatching {
                val uri = Uri.parse(uriString)
                val rt = RingtoneManager.getRingtone(context, uri)
                rt?.getTitle(context)?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: if (isEnglish) "Selected Phone Melody" else "Выбранная мелодия телефона"
        }
    }

    fun startOrUpdate(
        stage: Int,
        volumePercent: Int,
        dualTone: Boolean,
        vibrationCadenceLevel: Int,
        signalMode: AlarmSignalMode = AlarmSignalMode.SOUND_AND_VIBRO,
        soundSource: AlarmSoundSource = AlarmSoundSource.SYSTEM_DEFAULT_ALARM,
        customRingtoneUri: String = ""
    ) {
        val playSoundAllowed = signalMode != AlarmSignalMode.VIBRO_ONLY
        val playVibroAllowed = signalMode != AlarmSignalMode.SOUND_ONLY

        val effectiveVolume = if (playSoundAllowed) volumePercent.coerceIn(0, 100) else 0
        val effectiveVibro = if (playVibroAllowed) {
            if (signalMode == AlarmSignalMode.VIBRO_ONLY) {
                vibrationCadenceLevel.coerceAtLeast(2)
            } else {
                vibrationCadenceLevel
            }
        } else {
            0
        }

        if (effectiveVolume <= 0 && effectiveVibro <= 0) {
            stopAll()
            return
        }

        val sourceChanged = currentSoundSource != soundSource ||
            currentCustomUri != customRingtoneUri ||
            currentSignalMode != signalMode

        currentStage = stage
        targetVolumePercent = effectiveVolume
        dualToneEnabled = dualTone
        vibrationLevel = effectiveVibro
        currentSignalMode = signalMode
        currentSoundSource = soundSource
        currentCustomUri = customRingtoneUri

        if (sourceChanged && synthJob?.isActive == true) {
            stopAll()
        }

        // Обновляем громкость MediaPlayer на лету, если играет системная мелодия
        updateMediaPlayerVolume(effectiveVolume)

        if (synthJob?.isActive == true) return

        synthJob = scope.launch {
            var useSynthAudio = playSoundAllowed && soundSource in setOf(
                AlarmSoundSource.SYNTH_TACTICAL_SIREN,
                AlarmSoundSource.SYNTH_RADAR_SONAR,
                AlarmSoundSource.SYNTH_GENTLE_CHORD
            )

            if (playSoundAllowed && !useSynthAudio) {
                val startedSystem = startSystemRingtonePlayer(
                    soundSource = soundSource,
                    customUriString = customRingtoneUri,
                    volumePercent = effectiveVolume
                )
                if (!startedSystem) {
                    // Фолбэк на встроенный синтезатор, если системная мелодия недоступна (например, в эмуляторе без рингтонов)
                    useSynthAudio = true
                }
            }

            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2)

            val audioTrack = if (useSynthAudio) {
                runCatching {
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
            } else {
                null
            }

            runCatching { audioTrack?.play() }

            val chunkSamples = sampleRate / 5 // 200ms блок
            val pcmBuffer = ShortArray(chunkSamples)
            var phase1 = 0.0
            var phase2 = 0.0
            var tickCounter = 0

            try {
                while (isActive) {
                    if (useSynthAudio && targetVolumePercent > 0) {
                        val playPulse = when {
                            currentSoundSource == AlarmSoundSource.SYNTH_RADAR_SONAR -> tickCounter % 2 == 0
                            currentStage == 0 -> tickCounter % 6 == 0
                            currentStage == 1 -> tickCounter % 3 != 2
                            else -> true
                        }

                        if (!playPulse) {
                            delay(190L)
                        } else {
                            val volScalar = (targetVolumePercent / 100.0).coerceIn(0.05, 1.0)
                            val baseFreq = when (currentSoundSource) {
                                AlarmSoundSource.SYNTH_GENTLE_CHORD -> when (currentStage) {
                                    0, 1 -> 432.0
                                    2 -> 528.0
                                    else -> 660.0
                                }
                                AlarmSoundSource.SYNTH_RADAR_SONAR -> if (tickCounter % 4 < 2) 880.0 else 1320.0
                                else -> when (currentStage) {
                                    0 -> 432.0
                                    1 -> 528.0
                                    2 -> 740.0
                                    else -> if (tickCounter % 4 < 2) 880.0 else 1174.6
                                }
                            }
                            val secondFreq = when {
                                currentSoundSource == AlarmSoundSource.SYNTH_GENTLE_CHORD -> baseFreq * 1.25
                                dualToneEnabled -> baseFreq * 1.498
                                else -> 0.0
                            }

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
                    } else {
                        // Если играет системный MediaPlayer или режим «Только вибрация» — ждём 200 мс
                        updateMediaPlayerVolume(targetVolumePercent)
                        delay(190L)
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
                stopSystemRingtonePlayer()
                if (wasVibrating) {
                    runCatching { vibrator?.cancel() }
                    wasVibrating = false
                }
            }
        }
    }

    /**
     * Запускает 4-секундное прослушивание выбранного сигнала и вибрации из экрана настроек или редактора.
     */
    fun startPreview(
        signalMode: AlarmSignalMode,
        soundSource: AlarmSoundSource,
        customRingtoneUri: String,
        onPreviewEnded: () -> Unit = {}
    ) {
        stopAll()
        startOrUpdate(
            stage = 2,
            volumePercent = 75,
            dualTone = true,
            vibrationCadenceLevel = 2,
            signalMode = signalMode,
            soundSource = soundSource,
            customRingtoneUri = customRingtoneUri
        )
        previewStopJob = scope.launch {
            delay(4000L)
            stopAll()
            onPreviewEnded()
        }
    }

    private fun startSystemRingtonePlayer(
        soundSource: AlarmSoundSource,
        customUriString: String,
        volumePercent: Int
    ): Boolean {
        stopSystemRingtonePlayer()
        val targetUri: Uri = when {
            soundSource == AlarmSoundSource.SYSTEM_CUSTOM_RINGTONE && customUriString.isNotBlank() ->
                runCatching { Uri.parse(customUriString) }.getOrNull() ?: resolveDefaultAlarmUri()
            else -> resolveDefaultAlarmUri()
        } ?: return false

        return runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, targetUri)
            mp.isLooping = true
            mp.prepare()
            val vol = (volumePercent / 100f).coerceIn(0f, 1f)
            mp.setVolume(vol, vol)
            mp.start()
            mediaPlayer = mp
            activeMediaUriString = targetUri.toString()
            true
        }.getOrElse {
            stopSystemRingtonePlayer()
            false
        }
    }

    private fun updateMediaPlayerVolume(volumePercent: Int) {
        val mp = mediaPlayer ?: return
        val vol = (volumePercent / 100f).coerceIn(0f, 1f)
        runCatching {
            if (mp.isPlaying) {
                mp.setVolume(vol, vol)
            }
        }
    }

    private fun stopSystemRingtonePlayer() {
        val mp = mediaPlayer
        mediaPlayer = null
        activeMediaUriString = null
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
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
        previewStopJob?.cancel()
        previewStopJob = null
        synthJob?.cancel()
        synthJob = null
        stopSystemRingtonePlayer()
        if (wasVibrating) {
            runCatching { vibrator?.cancel() }
            wasVibrating = false
        }
    }
}
