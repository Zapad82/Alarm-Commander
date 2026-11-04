package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Энергоэффективный контроллер многослойного звука, системных мелодий телефона и тактической вибрации.
 * Поддерживает:
 * - Режимы: Звук + Вибро (SOUND_AND_VIBRO), Только звук (SOUND_ONLY), Только вибрация (VIBRO_ONLY).
 * - Источники звука:
 *   1) Стандартный будильник телефона (SYSTEM_DEFAULT_ALARM),
 *   2) Выбранная системная мелодия телефона (SYSTEM_CUSTOM_RINGTONE),
 *   3) 3 встроенных синтезируемых сигнала (SYNTH_TACTICAL_SIREN, SYNTH_RADAR_SONAR, SYNTH_GENTLE_CHORD),
 *      которые генерируются в честный 44.1 кГц 16-бит PCM WAV и воспроизводятся через аппаратный MediaPlayer
 *      (с резервным непрерывным потоком AudioTrack), что гарантирует 100% слышимость на любых прошивках Android.
 */
class AlarmAudioController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var synthJob: Job? = null
    private var previewStopJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

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
        private const val SAMPLE_RATE = 44100

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

        /**
         * Генерирует 16-битный PCM массив сэмплов (44.1 кГц) для выбранного встроенного синтезатора.
         */
        internal fun buildSynthesizedPcmSamples(soundSource: AlarmSoundSource): ShortArray {
            return when (soundSource) {
                AlarmSoundSource.SYNTH_TACTICAL_SIREN -> generateTacticalSirenPcm()
                AlarmSoundSource.SYNTH_RADAR_SONAR -> generateRadarSonarPcm()
                AlarmSoundSource.SYNTH_GENTLE_CHORD -> generateGentleChordPcm()
                else -> generateTacticalSirenPcm()
            }
        }

        private fun generateTacticalSirenPcm(): ShortArray {
            // 2.0 секунды: 1.0 сек плавный подъём/спад сирены (660..1180 Гц) + 1.0 сек быстрые двухтональные импульсы
            val totalSamples = SAMPLE_RATE * 2
            val samples = ShortArray(totalSamples)
            var phase1 = 0.0
            var phase2 = 0.0
            var phase3 = 0.0

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val freq = if (t < 1.0) {
                    // Плавная сирена вверх-вниз
                    val sweep = sin(PI * t)
                    660.0 + 520.0 * sweep
                } else {
                    // Чёткие тактические импульсы 980 Гц / 740 Гц каждые 0.25 сек
                    val step = ((t - 1.0) / 0.25).toInt()
                    if (step % 2 == 0) 980.0 else 740.0
                }

                // Мягкая огибающая на краях цикла для отсутствия щелчков
                val edgeEnv = when {
                    i < 600 -> i / 600.0
                    i > totalSamples - 600 -> (totalSamples - i) / 600.0
                    else -> 1.0
                }

                phase1 += (2.0 * PI * freq) / SAMPLE_RATE
                phase2 += (2.0 * PI * (freq * 1.5)) / SAMPLE_RATE
                phase3 += (2.0 * PI * (freq * 2.0)) / SAMPLE_RATE

                val wave = (sin(phase1) * 0.65 + sin(phase2) * 0.22 + sin(phase3) * 0.13) * edgeEnv
                samples[i] = (wave * Short.MAX_VALUE * 0.92).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return samples
        }

        private fun generateRadarSonarPcm(): ShortArray {
            // 2.0 секунды: резонансный пинг сонара (1046.5 Гц) -> высокий ответный пинг (1318.5 Гц) -> двойной импульс радара (1568 Гц)
            val totalSamples = SAMPLE_RATE * 2
            val samples = ShortArray(totalSamples)
            var phase1 = 0.0
            var phase2 = 0.0

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                var amp = 0.0
                var f1 = 1046.5
                var f2 = 1568.0

                when {
                    t < 0.48 -> {
                        val localT = t
                        val attack = (localT / 0.008).coerceAtMost(1.0)
                        val decay = exp(-localT * 5.2)
                        amp = attack * decay
                        f1 = 1046.5
                        f2 = 2093.0
                    }
                    t in 0.52..0.98 -> {
                        val localT = t - 0.52
                        val attack = (localT / 0.008).coerceAtMost(1.0)
                        val decay = exp(-localT * 5.8)
                        amp = attack * decay
                        f1 = 1318.5
                        f2 = 1977.7
                    }
                    t in 1.08..1.26 -> {
                        val localT = t - 1.08
                        val attack = (localT / 0.005).coerceAtMost(1.0)
                        val decay = exp(-localT * 8.0)
                        amp = attack * decay * 0.95
                        f1 = 1568.0
                        f2 = 2352.0
                    }
                    t in 1.32..1.58 -> {
                        val localT = t - 1.32
                        val attack = (localT / 0.005).coerceAtMost(1.0)
                        val decay = exp(-localT * 6.5)
                        amp = attack * decay
                        f1 = 1568.0
                        f2 = 2352.0
                    }
                }

                phase1 += (2.0 * PI * f1) / SAMPLE_RATE
                phase2 += (2.0 * PI * f2) / SAMPLE_RATE

                val wave = (sin(phase1) * 0.72 + sin(phase2) * 0.28) * amp
                samples[i] = (wave * Short.MAX_VALUE * 0.95).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return samples
        }

        private fun generateGentleChordPcm(): ShortArray {
            // 2.4 секунды: тёплый гармонический аккорд 432 Гц + 540 Гц (мажорная терция) + 648 Гц (квинта) + 864 Гц (октава)
            val durationSec = 2.4
            val totalSamples = (SAMPLE_RATE * durationSec).toInt()
            val samples = ShortArray(totalSamples)
            var p1 = 0.0
            var p2 = 0.0
            var p3 = 0.0
            var p4 = 0.0

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                // Плавное дыхание аккорда с двумя мягкими волнами
                val swell = 0.35 + 0.65 * sin(PI * (t / durationSec))
                val shimmer = 0.90 + 0.10 * sin(2.0 * PI * 3.0 * t)
                val edgeEnv = when {
                    i < 1200 -> i / 1200.0
                    i > totalSamples - 1200 -> (totalSamples - i) / 1200.0
                    else -> 1.0
                }

                p1 += (2.0 * PI * 432.0) / SAMPLE_RATE
                p2 += (2.0 * PI * 540.0) / SAMPLE_RATE
                p3 += (2.0 * PI * 648.0) / SAMPLE_RATE
                p4 += (2.0 * PI * 864.0) / SAMPLE_RATE

                val chord = (sin(p1) * 0.44 + sin(p2) * 0.26 + sin(p3) * 0.18 + sin(p4) * 0.12)
                val wave = chord * swell * shimmer * edgeEnv
                samples[i] = (wave * Short.MAX_VALUE * 0.90).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return samples
        }

        /**
         * Записывает стандартный RIFF WAVE заголовок (44 байта) + 16-бит PCM данные в файл кэша.
         */
        internal fun ensureSynthesizedWavFile(context: Context, soundSource: AlarmSoundSource): File? {
            return runCatching {
                val fileName = "alarm_commander_${soundSource.name.lowercase()}_v3.wav"
                val outFile = File(context.cacheDir, fileName)
                if (outFile.exists() && outFile.length() > 4096L) {
                    return outFile
                }

                val pcmSamples = buildSynthesizedPcmSamples(soundSource)
                val dataSize = pcmSamples.size * 2
                val totalDataLen = dataSize + 36
                val byteRate = SAMPLE_RATE * 2

                val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
                // RIFF header
                buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
                buffer.putInt(totalDataLen)
                buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
                // fmt subchunk
                buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
                buffer.putInt(16) // PCM chunk size
                buffer.putShort(1) // AudioFormat = 1 (PCM)
                buffer.putShort(1) // NumChannels = 1 (Mono)
                buffer.putInt(SAMPLE_RATE)
                buffer.putInt(byteRate)
                buffer.putShort(2) // BlockAlign = 2 bytes
                buffer.putShort(16) // BitsPerSample = 16
                // data subchunk
                buffer.put("data".toByteArray(Charsets.US_ASCII))
                buffer.putInt(dataSize)
                for (sample in pcmSamples) {
                    buffer.putShort(sample)
                }

                FileOutputStream(outFile).use { fos ->
                    fos.write(buffer.array())
                    fos.flush()
                }
                outFile
            }.getOrNull()
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
            synthJob?.cancel()
            synthJob = null
            stopMediaPlayer()
        }

        // Обновляем громкость MediaPlayer на лету
        updateMediaPlayerVolume(effectiveVolume)

        if (synthJob?.isActive == true) return

        synthJob = scope.launch {
            var playerStarted = false
            if (playSoundAllowed) {
                playerStarted = startAudioPlayer(
                    soundSource = soundSource,
                    customUriString = customRingtoneUri,
                    volumePercent = effectiveVolume
                )
            }

            // Если по какой-то причине MediaPlayer не запустился (например, в headless-окружении без кодеков),
            // запускаем непрерывный блокирующий AudioTrack 44.1 кГц без разрывов буфера.
            val useFallbackAudioTrack = playSoundAllowed && !playerStarted
            val fallbackSamples = if (useFallbackAudioTrack) {
                buildSynthesizedPcmSamples(soundSource)
            } else {
                ShortArray(0)
            }

            val audioTrack = if (useFallbackAudioTrack && fallbackSamples.isNotEmpty()) {
                runCatching {
                    val minBuf = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(SAMPLE_RATE)
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
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }.getOrNull()
            } else {
                null
            }

            val chunkSize = SAMPLE_RATE / 5 // 200 мс
            val scaledChunk = ShortArray(chunkSize)
            var sampleCursor = 0
            var tickCounter = 0

            try {
                if (audioTrack != null && fallbackSamples.isNotEmpty()) {
                    // Предзаполняем первый блок перед вызовом play(), чтобы избежать underrun на реальном железе
                    val vol = (targetVolumePercent / 100.0).coerceIn(0.05, 1.0)
                    for (i in 0 until chunkSize) {
                        val raw = fallbackSamples[sampleCursor]
                        sampleCursor = (sampleCursor + 1) % fallbackSamples.size
                        scaledChunk[i] = (raw * vol).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }
                    runCatching {
                        audioTrack.write(scaledChunk, 0, chunkSize)
                        audioTrack.play()
                    }
                }

                while (isActive) {
                    if (audioTrack != null && fallbackSamples.isNotEmpty() && targetVolumePercent > 0) {
                        val vol = (targetVolumePercent / 100.0).coerceIn(0.05, 1.0)
                        for (i in 0 until chunkSize) {
                            val raw = fallbackSamples[sampleCursor]
                            sampleCursor = (sampleCursor + 1) % fallbackSamples.size
                            scaledChunk[i] = (raw * vol).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                        }
                        runCatching {
                            audioTrack.write(scaledChunk, 0, chunkSize)
                        }
                    } else {
                        updateMediaPlayerVolume(targetVolumePercent)
                        delay(200L)
                    }

                    if (tickCounter % 5 == 0) {
                        triggerVibrationPulse(vibrationLevel)
                    }

                    tickCounter++
                }
            } finally {
                runCatching {
                    audioTrack?.stop()
                    audioTrack?.release()
                }
                stopMediaPlayer()
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
        ensureAlarmStreamAudibleForPreview()
        startOrUpdate(
            stage = 2,
            volumePercent = 85,
            dualTone = true,
            vibrationCadenceLevel = 2,
            signalMode = signalMode,
            soundSource = soundSource,
            customRingtoneUri = customRingtoneUri
        )
        previewStopJob = scope.launch {
            delay(4000L)
            stopAll()
            mainHandler.post { onPreviewEnded() }
        }
    }

    private fun ensureAlarmStreamAudibleForPreview() {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val currentAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxAlarmVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (currentAlarmVol == 0 && maxAlarmVol > 0) {
                val targetVol = (maxAlarmVol * 0.6f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
            }
        }
    }

    /**
     * Запускает MediaPlayer как для системных мелодий телефона, так и для встроенных синтезированных сигналов (.wav).
     */
    private fun startAudioPlayer(
        soundSource: AlarmSoundSource,
        customUriString: String,
        volumePercent: Int
    ): Boolean {
        stopMediaPlayer()

        val isSynthSource = soundSource in setOf(
            AlarmSoundSource.SYNTH_TACTICAL_SIREN,
            AlarmSoundSource.SYNTH_RADAR_SONAR,
            AlarmSoundSource.SYNTH_GENTLE_CHORD
        )

        val synthFile: File? = if (isSynthSource) {
            ensureSynthesizedWavFile(context, soundSource)
        } else {
            null
        }

        val targetUri: Uri? = if (isSynthSource) {
            synthFile?.let { Uri.fromFile(it) }
        } else {
            when {
                soundSource == AlarmSoundSource.SYSTEM_CUSTOM_RINGTONE && customUriString.isNotBlank() ->
                    runCatching { Uri.parse(customUriString) }.getOrNull() ?: resolveDefaultAlarmUri()
                else -> resolveDefaultAlarmUri()
            }
        }

        return runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            if (synthFile != null && synthFile.exists()) {
                mp.setDataSource(synthFile.absolutePath)
            } else if (targetUri != null) {
                mp.setDataSource(context, targetUri)
            } else {
                val fallbackWav = ensureSynthesizedWavFile(context, AlarmSoundSource.SYNTH_TACTICAL_SIREN)
                    ?: return false
                mp.setDataSource(fallbackWav.absolutePath)
            }
            mp.isLooping = true
            mp.prepare()
            val vol = (volumePercent / 100f).coerceIn(0.05f, 1f)
            mp.setVolume(vol, vol)
            mp.start()
            mediaPlayer = mp
            true
        }.getOrElse {
            stopMediaPlayer()
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

    private fun stopMediaPlayer() {
        val mp = mediaPlayer
        mediaPlayer = null
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
        stopMediaPlayer()
        if (wasVibrating) {
            runCatching { vibrator?.cancel() }
            wasVibrating = false
        }
    }
}
