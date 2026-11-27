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
        maxOf(minBytes, SAMPLE_RATE * 2 / 2), // at least 0.5 s
    )
    private val suppressor: NoiseSuppressor? =
        if (noiseSuppression && NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
    private var pcm = ShortArray(0)

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
    private val startMs = SystemClock.elapsedRealtime()
    private var played = 0L

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

    override fun read(buffer: FloatArray): Int {
        if (pos >= samples.size && tail <= 0) return -1
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
        return n
    }

    override fun close() {}
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
