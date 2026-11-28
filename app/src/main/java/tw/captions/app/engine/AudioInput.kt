package tw.captions.app.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono audio for the offline engine. */
interface AudioInput : AutoCloseable {
    /** Blocks until [buffer] is filled (or the input ends). Returns the number of samples, or -1 at the end. */
    fun read(buffer: FloatArray): Int
}

/** The phone microphone, tuned for speech recognition, with the platform noise suppressor when there is one. */
class MicInput @SuppressLint("MissingPermission") constructor(
    /** Wired or USB microphone to use instead of the phone's own, when it is plugged in. */
    preferred: android.media.AudioDeviceInfo? = null,
    /** The phone's own noise suppression. Some phones remove quiet speech with it; can be switched off. */
    noiseSuppression: Boolean = true,
) : AudioInput {
    private val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        // 3 s: a busy phone (recognizer, speaker model, screen) must not make the microphone overrun.
        maxOf(minBytes, SAMPLE_RATE * 2 * 3),
    )
    private val suppressor: NoiseSuppressor? =
        if (noiseSuppression && NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
    private var pcm = ShortArray(0)

    /** For telling whether another app took the microphone (see AudioManager.AudioRecordingCallback). */
    val audioSessionId: Int get() = record.audioSessionId

    init {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        Log.i(TAG, "mic: noise suppressor ${if (suppressor != null) "on" else "off or not available"}")
        if (preferred != null) Log.i(TAG, "mic: prefer ${preferred.productName} -> ${record.setPreferredDevice(preferred)}")
        record.startRecording()
    }

    override fun read(buffer: FloatArray): Int {
        if (pcm.size != buffer.size) pcm = ShortArray(buffer.size)
        var got = 0
        while (got < buffer.size) {
            val n = record.read(pcm, got, buffer.size - got)
            if (n < 0) return if (got > 0) got.also { convert(buffer, it) } else -1
            got += n
        }
        convert(buffer, got)
        return got
    }

    private fun convert(buffer: FloatArray, n: Int) {
        for (i in 0 until n) buffer[i] = pcm[i] / 32768f
    }

    override fun close() {
        runCatching { record.stop() }
        suppressor?.release()
        record.release()
    }
}

/**
 * Debug builds only: plays a 16 kHz mono 16-bit wav file as if it were the microphone, in real
 * time, so captions and speaker tags can be checked on an emulator without anyone speaking.
 * Ends with a few seconds of silence so the last line gets finished.
 */
class WavFileInput(file: File) : AudioInput {
    private val samples: ShortArray
    private var pos = 0
    private var tail = SAMPLE_RATE * 3
    private var startMs = SystemClock.elapsedRealtime()
    private var played = 0L
    private var lastReadMs = startMs

    /** True once the file and the closing silence have been played. */
    val finished: Boolean @Synchronized get() = pos >= samples.size && tail <= 0

    init {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var data: ShortArray? = null
        while (p + 8 <= bytes.size && data == null) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val len = bb.getInt(p + 4)
            if (id == "fmt ") {
                val ch = bb.getShort(p + 10).toInt()
                val rate = bb.getInt(p + 12)
                val bits = bb.getShort(p + 22).toInt()
                require(ch == 1 && rate == SAMPLE_RATE && bits == 16) { "need 16 kHz mono 16-bit wav, got $rate Hz $ch ch $bits bit" }
            } else if (id == "data") {
                val n = minOf(len, bytes.size - p - 8) / 2
                data = ShortArray(n) { bb.getShort(p + 8 + it * 2) }
            }
            p += 8 + len + (len and 1)
        }
        samples = requireNotNull(data) { "$file has no audio data" }
        Log.i(TAG, "wav input: ${file.name}, ${samples.size / SAMPLE_RATE.toFloat()} s")
    }

    @Synchronized
    override fun read(buffer: FloatArray): Int {
        if (pos >= samples.size && tail <= 0) return -1
        // The same file continues after an engine switch (cloud to offline): pace from now on
        // instead of racing to catch up with the pause.
        val sinceLast = SystemClock.elapsedRealtime() - lastReadMs
        if (sinceLast > 500) startMs += sinceLast
        var n = 0
        while (n < buffer.size && pos < samples.size) buffer[n++] = samples[pos++] / 32768f
        while (n < buffer.size && tail > 0) {
            buffer[n++] = 0f
            tail--
        }
        // Pace like a real microphone.
        played += n
        val due = startMs + played * 1000 / SAMPLE_RATE
        val wait = due - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        lastReadMs = SystemClock.elapsedRealtime()
        return n
    }

    /** Nothing to release; the view model keeps the file open so a restarted engine continues where it was. */
    override fun close() {}
}

/**
 * Reads [source] on its own high-priority thread into a queue, so slow recognition never keeps
 * the microphone waiting (which loses audio). Holds up to [maxBufferedSec] seconds; if processing
 * falls further behind, the oldest audio is dropped.
 */
class BufferedAudioInput(
    private val source: AudioInput,
    private val chunkSamples: Int = SAMPLE_RATE / 50,
    maxBufferedSec: Int = 30,
    /** Raises the reader thread's priority; replaceable in tests (android.os.Process is not on the JVM). */
    private val raisePriority: () -> Unit = { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) },
) : AudioInput {
    private val queue = java.util.concurrent.LinkedBlockingDeque<FloatArray>(maxOf(1, maxBufferedSec * SAMPLE_RATE / chunkSamples))
    @Volatile private var stopped = false
    @Volatile private var ended = false
    @Volatile var dropped = 0L
        private set
    private var current: FloatArray? = null
    private var offset = 0

    private val reader = Thread({
        runCatching { raisePriority() }
        try {
            val chunk = FloatArray(chunkSamples)
            while (!stopped) {
                val n = source.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                val copy = chunk.copyOf(n)
                while (!queue.offerLast(copy)) {
                    queue.pollFirst()
                    dropped += n
                    if (dropped % (SAMPLE_RATE * 5L) < n) Log.w(TAG, "processing is behind; dropped ${dropped / SAMPLE_RATE} s of audio")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio reader stopped", e)
        } finally {
            ended = true
        }
    }, "captions-audio").apply { isDaemon = true; start() }

    override fun read(buffer: FloatArray): Int {
        var got = 0
        while (got < buffer.size) {
            val c = current ?: run {
                // Hand back what we have rather than wait when the input has ended.
                val next = if (got > 0 && ended) queue.pollFirst() else pollWhileRunning()
                next ?: return if (got > 0) got else -1
            }
            current = c
            val n = minOf(c.size - offset, buffer.size - got)
            System.arraycopy(c, offset, buffer, got, n)
            got += n
            offset += n
            if (offset >= c.size) {
                current = null
                offset = 0
            }
        }
        return got
    }

    /** Next chunk, or null when the input ended (or was closed) and nothing is left. */
    private fun pollWhileRunning(): FloatArray? {
        while (true) {
            queue.pollFirst(100, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
            if (stopped || (ended && queue.isEmpty())) return null
        }
    }

    override fun close() {
        stopped = true
        // A read returns within one chunk (20 ms); only then is it safe to release the source.
        reader.join(1000)
        source.close()
        queue.clear()
    }
}

const val SAMPLE_RATE = 16_000
private const val TAG = "CaptionsAudio"

/** Microphones the user can choose: the phone's own, or a wired / USB one that is plugged in. */
object Microphones {
    /** 0 = let the phone decide. */
    const val AUTO = 0

    private val external = mapOf(
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET to "有線耳機麥克風",
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE to "USB 麥克風",
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET to "USB 耳機麥克風",
    )

    private fun inputs(context: android.content.Context) =
        context.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)

    /** Device type to label, for the settings list: automatic, the phone's microphone, then plugged-in ones. */
    fun choices(context: android.content.Context): List<Pair<Int, String>> =
        listOf(AUTO to "自動", android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC to "手機麥克風") +
            inputs(context).filter { it.type in external }.distinctBy { it.type }.map { d ->
                d.type to "${external[d.type]}（${d.productName}）"
            }

    /** The plugged-in device of [type], or null (automatic, or it was unplugged: the phone decides). */
    fun find(context: android.content.Context, type: Int): android.media.AudioDeviceInfo? =
        if (type == AUTO) null else inputs(context).firstOrNull { it.type == type }
}
