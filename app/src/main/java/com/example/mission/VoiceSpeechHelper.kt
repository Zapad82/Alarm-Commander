package com.example.mission

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Надёжная обёртка над системным SpeechRecognizer для локального/системного распознавания
 * голосовой фразы пробуждения без записи и сохранения аудиофайлов.
 *
 * Защита от сбоев на Android 8–16 и кастомных оболочках (Xiaomi HyperOS/MIUI, Samsung OneUI, Huawei и др.):
 * - Все вызовы SpeechRecognizer выполняются строго на главном потоке (Main Looper).
 * - Перехватываются любые Throwable (включая NullPointerException / SecurityException / IllegalArgumentException).
 * - Исключён реентерабельный вызов destroy() внутри колбэка onResults(), который на ряде прошивок
 *   приводил к падению SpeechRecognizer$Connection.
 * - При недоступности фонового сервиса SpeechRecognizer автоматически вызывается системное диалоговое
 *   окно распознавания речи (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) через ActivityResultLauncher.
 */
class VoiceSpeechHelper(
    private val context: Context,
    onStatusChanged: (isListening: Boolean, statusText: String) -> Unit,
    onRmsChanged: (rmsDb: Float) -> Unit,
    onSpeechResult: (recognizedText: String) -> Unit,
    onFallbackActivityRequested: ((Intent) -> Boolean)? = null
) {
    private val statusCallback: (Boolean, String) -> Unit = onStatusChanged
    private val rmsLevelCallback: (Float) -> Unit = onRmsChanged
    private val speechResultCallback: (String) -> Unit = onSpeechResult
    private val fallbackDialogLauncher: ((Intent) -> Boolean)? = onFallbackActivityRequested

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var isDestroyed: Boolean = false
    private var lastPartialTranscript: String = ""

    fun isAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
    }

    fun buildRecognizerIntent(): Intent {
        val langTag = runCatching { Locale.getDefault().toLanguageTag() }
            .getOrDefault("ru-RU")
            .ifBlank { "ru-RU" }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    fun startListening() {
        mainHandler.post {
            if (isDestroyed) return@post
            lastPartialTranscript = ""
            val speechIntent = buildRecognizerIntent()

            if (!isAvailable()) {
                val launched = fallbackDialogLauncher?.invoke(speechIntent) == true
                if (!launched) {
                    statusCallback(
                        false,
                        "Системный сервис распознавания речи недоступен на этом эмуляторе — используйте контрольный ввод ниже."
                    )
                }
                return@post
            }

            try {
                var sr = recognizer
                if (sr == null) {
                    val created: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)
                    if (created == null) {
                        val launched = fallbackDialogLauncher?.invoke(speechIntent) == true
                        if (!launched) {
                            statusCallback(
                                false,
                                "Системный сервис распознавания речи недоступен на этом эмуляторе — используйте контрольный ввод ниже."
                            )
                        }
                        return@post
                    }
                    created.setRecognitionListener(createListener(speechIntent))
                    recognizer = created
                    sr = created
                } else {
                    runCatching { sr.cancel() }
                }

                statusCallback(true, "Микрофон активен: чётко произнесите фразу...")
                sr.startListening(speechIntent)
            } catch (t: Throwable) {
                val launched = fallbackDialogLauncher?.invoke(speechIntent) == true
                if (!launched) {
                    statusCallback(
                        false,
                        "Не удалось запустить SpeechRecognizer: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    internal fun createRecognitionListenerForTest(speechIntent: Intent): RecognitionListener {
        return createListener(speechIntent)
    }

    private fun createListener(speechIntent: Intent): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isDestroyed) return
                this@VoiceSpeechHelper.statusCallback(true, "Микрофон активен: чётко произнесите фразу...")
            }

            override fun onBeginningOfSpeech() {
                if (isDestroyed) return
                this@VoiceSpeechHelper.statusCallback(true, "Фиксация голоса...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (isDestroyed) return
                val safeRms = if (rmsdB.isNaN() || rmsdB.isInfinite()) 0f else rmsdB.coerceIn(0f, 12f)
                this@VoiceSpeechHelper.rmsLevelCallback(safeRms)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (isDestroyed) return
                this@VoiceSpeechHelper.statusCallback(false, "Обработка произнесённой фразы...")
            }

            override fun onError(error: Int) {
                if (isDestroyed) return
                // Если во время речи были получены частичные результаты, но в конце сработал таймаут паузы или ERROR_NO_MATCH,
                // используем зафиксированную частичную фразу вместо потери ввода.
                val partialFallback = lastPartialTranscript.trim()
                if (partialFallback.isNotBlank() &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    lastPartialTranscript = ""
                    this@VoiceSpeechHelper.statusCallback(false, "Распознано: «$partialFallback»")
                    mainHandler.post {
                        if (!isDestroyed) {
                            this@VoiceSpeechHelper.speechResultCallback(partialFallback)
                        }
                    }
                    return
                }

                // Если прямой биндинг к сервису заблокирован прошивкой (ERROR_CLIENT = 5 / ERROR_INSUFFICIENT_PERMISSIONS = 9),
                // пробуем открыть стандартное системное окно голосового ввода
                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    val launched = this@VoiceSpeechHelper.fallbackDialogLauncher?.invoke(speechIntent) == true
                    if (launched) {
                        this@VoiceSpeechHelper.statusCallback(false, "Обработка произнесённой фразы...")
                        return
                    }
                }
                this@VoiceSpeechHelper.statusCallback(
                    false,
                    "Голос не распознан (код $error). Нажмите микрофон ещё раз или введите фразу."
                )
            }

            override fun onResults(results: Bundle?) {
                if (isDestroyed) return
                val matches = runCatching {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                }.getOrNull().orEmpty().map { it.trim() }.filter { it.isNotBlank() }

                val primaryDisplay = matches.firstOrNull().orEmpty().ifBlank { lastPartialTranscript.trim() }
                val combinedTranscript = if (matches.isNotEmpty()) {
                    matches.joinToString(" ")
                } else {
                    lastPartialTranscript.trim()
                }
                lastPartialTranscript = ""

                this@VoiceSpeechHelper.statusCallback(
                    false,
                    if (primaryDisplay.isNotBlank()) "Распознано: «$primaryDisplay»" else "Пустой результат"
                )
                if (combinedTranscript.isNotBlank()) {
                    // Откладываем передачу результата на следующий такт главного цикла,
                    // чтобы завершение миссии и закрытие экрана не вызвали destroy() внутри колбэка Binder!
                    mainHandler.post {
                        if (!isDestroyed) {
                            this@VoiceSpeechHelper.speechResultCallback(combinedTranscript)
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (isDestroyed) return
                val partial = runCatching {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                }.getOrNull().orEmpty().trim()
                if (partial.isNotBlank()) {
                    lastPartialTranscript = partial
                    this@VoiceSpeechHelper.statusCallback(true, "Слышу: «$partial»...")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    fun stopListening() {
        mainHandler.post {
            val sr = recognizer ?: return@post
            runCatching { sr.stopListening() }
        }
    }

    fun release() {
        isDestroyed = true
        mainHandler.post {
            val sr = recognizer
            recognizer = null
            if (sr != null) {
                runCatching { sr.destroy() }
            }
        }
    }
}
