package tw.captions.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeechEngine
import tw.captions.core.TranscriptEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The user's own caption server (server/server.py on a home PC with a GPU, e.g. Breeze-ASR-25).
 * Audio goes out as 16 kHz 16-bit PCM over a WebSocket; partial and final lines come back as JSON.
 */
class ServerSpeechEngine(
    private val endpoint: () -> Pair<String, String>?,
    private val openInput: () -> AudioInput,
) : SpeechEngine {
    override val id = ID
    override val displayName = "自架伺服器"
    override val requiresNetwork = true

    companion object {
        const val ID = "server"
        private const val TAG = "CaptionsServer"
        private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()

        /** Connects, sends the token and waits for "ready". */
        fun test(url: String, token: String): Result<Unit> = runCatching {
            val done = java.util.concurrent.CompletableFuture<String>()
            val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("token", token).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) { done.complete(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.completeExceptionally(t) }
            })
            val reply = try { done.get(8, TimeUnit.SECONDS) } finally { ws.close(1000, null) }
            if (JSONObject(reply).optString("type") != "ready") error("伺服器拒絕：權杖不對")
        }
    }

    override fun transcribe(config: RecognitionConfig): Flow<TranscriptEvent> = callbackFlow {
        val ep = endpoint()
        if (ep == null) {
            trySend(TranscriptEvent.Error("請先在「雲端設定」填入伺服器網址和權杖", recoverable = false))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val (url, token) = ep
        var ready = false
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                when (m.optString("type")) {
                    "ready" -> { ready = true; trySend(TranscriptEvent.Listening) }
                    "partial" -> trySend(TranscriptEvent.Partial(m.getString("text")))
                    "final" -> trySend(TranscriptEvent.Final(m.getString("text")))
                    "error" -> { trySend(TranscriptEvent.Error("伺服器：${m.optString("message")}", recoverable = false)); close() }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "connection failed", t)
                trySend(TranscriptEvent.Error("連不上伺服器，請確認網址、網路，以及電腦上的伺服器有開", recoverable = false))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })

        val pump = launch(Dispatchers.IO) {
            val input = try { openInput() } catch (e: Exception) {
                trySend(TranscriptEvent.Error("無法開啟麥克風", recoverable = false)); close(); return@launch
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
                    if (ready) ws.send(bytes.array().copyOf(n * 2).toByteString())
                    val db = 20 * log10(sqrt(sum / n.coerceAtLeast(1)) + 1e-9)
                    trySend(TranscriptEvent.AudioLevel(((db + 60) / 50).toFloat().coerceIn(0f, 1f)))
                }
            }
            // wav input ended: two seconds of silence so the last line is finished, then stop
            repeat(20) { ws.send(ByteArray(SAMPLE_RATE / 10 * 2).toByteString()); delay(100) }
            delay(4000)
            close()
        }

        awaitClose {
            pump.cancel()
            ws.close(1000, null)
        }
    }.flowOn(Dispatchers.IO)
}
