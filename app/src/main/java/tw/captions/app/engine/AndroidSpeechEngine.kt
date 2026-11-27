package tw.captions.app.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeechEngine
import tw.captions.core.TranscriptEvent

/**
 * Uses the phone's built-in speech recognition (usually Google's).
 *
 * The system recognizer stops after each utterance, so this engine restarts it right away
 * to give continuous captions. With [onDevice] = true it runs fully offline when the
 * device has an offline language pack installed (Android 12+ is best).
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val onDevice: Boolean,
) : SpeechEngine {

    override val id = if (onDevice) "android-offline" else "android-online"
    override val displayName = if (onDevice) "離線" else "連線"
    override val requiresNetwork = !onDevice

    override fun transcribe(config: RecognitionConfig): Flow<TranscriptEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(TranscriptEvent.Error("這支手機沒有可用的語音辨識服務", recoverable = false))
            close()
            awaitClose { }
            return@callbackFlow
        }

        val recognizer = createRecognizer()
        val intent = buildIntent(config)
        val handler = Handler(Looper.getMainLooper())
        var active = true

        fun restart(delayMs: Long = 0) {
            if (!active) return
            handler.postDelayed({ if (active) recognizer.startListening(intent) }, delayMs)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                trySend(TranscriptEvent.Listening)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstText()?.let { trySend(TranscriptEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                Log.d(TAG, "onResults ${results.firstText()}")
                results.firstText()?.let { trySend(TranscriptEvent.Final(it)) }
                restart()
            }

            override fun onError(error: Int) {
                Log.d(TAG, "onError $error")
                when (error) {
                    // Silence or nothing understood: just keep listening.
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        trySend(TranscriptEvent.NoSpeechDetected)
                        restart()
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> restart(delayMs = 300)

                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        trySend(TranscriptEvent.Error("網路有問題，可以改用離線模式", recoverable = true))
                        restart(delayMs = 1000)
                    }

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        trySend(TranscriptEvent.Error("需要麥克風權限", recoverable = false))
                        close()
                    }

                    else -> {
                        if (Build.VERSION.SDK_INT >= 31 &&
                            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                        ) {
                            trySend(
                                TranscriptEvent.Error(
                                    "這個語言還沒有離線語音包，請到系統設定下載，或改用連線模式",
                                    recoverable = false,
                                ),
                            )
                            close()
                        } else {
                            trySend(TranscriptEvent.Error("辨識錯誤（代碼 $error），重新開始", recoverable = true))
                            restart(delayMs = 500)
                        }
                    }
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Typical range is about -2 dB (silence) to 10 dB (loud speech).
                trySend(TranscriptEvent.AudioLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)

        awaitClose {
            active = false
            handler.removeCallbacksAndMessages(null)
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)

    private companion object {
        const val TAG = "CaptionsEngine"
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun buildIntent(config: RecognitionConfig) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33) {
                // Ask for punctuation, tuned for speed rather than perfect formatting.
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY,
                )
                if (config.hotwords.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(config.hotwords))
                }
            }
        }

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}
