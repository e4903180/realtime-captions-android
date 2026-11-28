package tw.captions.app.engine

import android.util.Log
import com.microsoft.cognitiveservices.speech.ProfanityOption
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeakerId
import tw.captions.core.SpeechEngine
import tw.captions.core.TranscriptEvent
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Azure AI Speech, real-time conversation transcription (text + speaker labels), using the user's
 * own key. Best accuracy on Taiwan-accented speech in our tests (docs/benchmark-engines.md).
 * Azure's profanity masking is turned off: it replaced ordinary words such as 幹線 with "*".
 */
class AzureSpeechEngine(
    private val credentials: () -> Pair<String, String>?,
    private val openInput: () -> AudioInput,
    /** 說話者辨識 on/off (ADR-033): off drops Azure's speaker labels. */
    private val speakersOn: () -> Boolean = { true },
) : SpeechEngine {
    override val id = ID
    override val displayName = "Azure 雲端"
    override val requiresNetwork = true

    companion object {
        const val ID = "azure"
        private const val TAG = "CaptionsAzure"
        val REGIONS = listOf(
            "eastasia" to "East Asia（香港）",
            "japaneast" to "Japan East（東京）",
            "southeastasia" to "Southeast Asia（新加坡）",
            "westus2" to "West US 2",
            "westeurope" to "West Europe",
        )

        /** Checks a key without sending audio: asks Azure for an access token. */
        fun testKey(key: String, region: String): Result<Unit> = runCatching {
            val conn = URL("https://$region.api.cognitive.microsoft.com/sts/v1.0/issueToken").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Ocp-Apim-Subscription-Key", key)
            conn.setRequestProperty("Content-Length", "0")
            conn.doOutput = true
            conn.outputStream.close()
            when (val code = conn.responseCode) {
                200 -> Unit
                401, 403 -> error("金鑰錯誤，或金鑰不屬於這個區域")
                else -> error("Azure 回應 $code")
            }
        }
    }

    override fun transcribe(config: RecognitionConfig): Flow<TranscriptEvent> = callbackFlow {
        val cred = credentials()
        if (cred == null) {
            trySend(TranscriptEvent.Error("請先在「雲端服務設定」填入 Azure 金鑰", recoverable = false))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val (key, region) = cred
        val speechConfig = SpeechConfig.fromSubscription(key, region).apply {
            speechRecognitionLanguage = if (config.languageTag.startsWith("zh")) "zh-TW" else config.languageTag
            setProfanity(ProfanityOption.Raw)
            setProperty(PropertyId.SpeechServiceResponse_DiarizeIntermediateResults, "true")
        }
        val stream = PushAudioInputStream.create(AudioStreamFormat.getWaveFormatPCM(SAMPLE_RATE.toLong(), 16.toShort(), 1.toShort()))
        val transcriber = ConversationTranscriber(speechConfig, AudioConfig.fromStreamInput(stream))

        // The user's own words (names, places, terms): Azure prefers them when the sound fits.
        if (config.hotwords.isNotEmpty()) {
            runCatching {
                val phrases = com.microsoft.cognitiveservices.speech.PhraseListGrammar.fromRecognizer(transcriber)
                config.hotwords.forEach { phrases.addPhrase(it) }
            }.onFailure { Log.w(TAG, "phrase list not applied", it) }
        }

        val withSpeakers = speakersOn()
        fun speaker(id: String?): SpeakerId? = if (!withSpeakers) null else id?.substringAfter("Guest-", "")?.toIntOrNull()?.let { SpeakerId(it - 1) }

        transcriber.transcribing.addEventListener { _, e ->
            e.result.text?.takeIf { it.isNotBlank() }?.let { trySend(TranscriptEvent.Partial(it, speaker(e.result.speakerId))) }
        }
        transcriber.transcribed.addEventListener { _, e ->
            e.result.text?.takeIf { it.isNotBlank() }?.let { trySend(TranscriptEvent.Final(it, speaker(e.result.speakerId))) }
        }
        transcriber.canceled.addEventListener { _, e ->
            Log.w(TAG, "canceled: ${e.reason} ${e.errorCode}")
            val msg = when (e.errorCode?.name) {
                "AuthenticationFailure" -> "Azure 金鑰無效或區域不對，請到雲端服務設定檢查"
                "ConnectionFailure", "ServiceTimeout" -> "連不上 Azure，請確認網路"
                "TooManyRequests", "Forbidden" -> "Azure 拒絕了請求（用量或額度已滿），請稍後再試或改用離線"
                else -> "Azure 連線中斷（${e.errorCode?.name ?: "未知原因"}），請再按一次開始"
            }
            // EndOfStream is the normal end of a wav file; everything else stops captions, so say why.
            if (e.reason == com.microsoft.cognitiveservices.speech.CancellationReason.Error) {
                trySend(TranscriptEvent.Error(msg, recoverable = false))
                close()
            }
        }
        transcriber.startTranscribingAsync().get()
        trySend(TranscriptEvent.Listening)

        val pump = launch(Dispatchers.IO) {
            val input = try {
                BufferedAudioInput(openInput())
            } catch (e: Exception) {
                trySend(TranscriptEvent.Error("無法開啟麥克風", recoverable = false))
                close()
                return@launch
            }
            input.use {
                val chunk = FloatArray(SAMPLE_RATE / 10)
                val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (isActive) {
                    val n = it.read(chunk)
                    if (n < 0) break
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i].coerceIn(-1f, 1f)
                        sum += v * v
                        bytes.putShort((v * 32767).toInt().toShort())
                    }
                    stream.write(bytes.array().copyOf(n * 2))
                    val db = 20 * log10(sqrt(sum / n.coerceAtLeast(1)) + 1e-9)
                    trySend(TranscriptEvent.AudioLevel(((db + 60) / 50).toFloat().coerceIn(0f, 1f)))
                }
            }
            // Input ended (wav file): let Azure finish the last line, then stop.
            stream.close()
            kotlinx.coroutines.delay(3000)
            close()
        }

        awaitClose {
            pump.cancel()
            runCatching { transcriber.stopTranscribingAsync().get() }
            transcriber.close()
            speechConfig.close()
        }
    }.flowOn(Dispatchers.IO)
}
