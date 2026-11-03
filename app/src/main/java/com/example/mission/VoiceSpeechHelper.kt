package com.example.mission

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Обёртка над системным SpeechRecognizer для локального/системного распознавания
 * голосовой фразы пробуждения без записи и сохранения аудиофайлов.
 */
class VoiceSpeechHelper(
    private val context: Context,
    private val onStatusChanged: (isListening: Boolean, statusText: String) -> Unit,
    private val onRmsChanged: (rmsDb: Float) -> Unit,
    private val onSpeechResult: (recognizedText: String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        stopListening()
        if (!isAvailable()) {
            onStatusChanged(false, "Системный сервис распознавания речи недоступен на этом эмуляторе — используйте контрольный ввод ниже.")
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatusChanged(true, "Микрофон активен: чётко произнесите фразу...")
            }

            override fun onBeginningOfSpeech() {
                onStatusChanged(true, "Фиксация голоса...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                onRmsChanged(rmsdB.coerceIn(0f, 12f))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                onStatusChanged(false, "Обработка произнесённой фразы...")
            }

            override fun onError(error: Int) {
                onStatusChanged(false, "Голос не распознан (код $error). Нажмите микрофон ещё раз или введите фразу.")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull().orEmpty()
                onStatusChanged(false, if (best.isNotBlank()) "Распознано: «$best»" else "Пустой результат")
                if (best.isNotBlank()) {
                    onSpeechResult(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    onStatusChanged(true, "Слышу: «$partial»...")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { sr.startListening(intent) }.onFailure {
            onStatusChanged(false, "Не удалось запустить SpeechRecognizer: ${it.localizedMessage}")
        }
    }

    fun stopListening() {
        runCatching {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }
}
