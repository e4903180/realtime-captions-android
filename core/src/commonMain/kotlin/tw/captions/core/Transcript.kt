package tw.captions.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A saved transcript (ADR-029): everything from one start to stop, kept only on the phone.
 * Same fields as the iPhone version. Times of lines are seconds from [startedMs].
 */
data class Transcript(
    val id: String,
    val title: String? = null,
    val startedMs: Long,
    val endedMs: Long,
    val engine: String,
    val lines: List<Line> = emptyList(),
) {
    data class Line(
        /** The caption line's id ([CaptionAssembler.lineId]), so later corrections update it. */
        val id: Long,
        val speaker: String?,
        val text: String,
        /** When the line appeared and when it last grew. */
        val start: Double,
        val end: Double,
    )

    val durationSec: Double get() = (endedMs - startedMs) / 1000.0

    /** Subtitle file: each line shows until 1 s after its last word (at least 1.5 s), not past the next one. */
    fun srt(): String = buildString {
        lines.forEachIndexed { i, l ->
            var end = max(l.end + 1, l.start + 1.5)
            val next = lines.getOrNull(i + 1)
            if (next != null && next.start > l.start) end = min(end, next.start)
            append(i + 1).append('\n')
            append(srtTime(l.start)).append(" --> ").append(srtTime(end)).append('\n')
            append(l.speaker?.let { "$it：" } ?: "").append(l.text).append("\n\n")
        }
    }

    /**
     * Plain text: a header, then "[HH:mm:ss] 說話者：文字" per line. [clock] turns epoch milliseconds
     * into local "HH:mm:ss"; [header] is the title and date line, formatted by the app.
     */
    fun plainText(header: String, clock: (Long) -> String): String = buildString {
        append(header).append("\n\n")
        for (l in lines) {
            append('[').append(clock(startedMs + (l.start * 1000).roundToLong())).append("] ")
            append(l.speaker?.let { "$it：" } ?: "").append(l.text).append('\n')
        }
    }

    /** Lines (and title) containing [query], ignoring case. */
    fun matches(query: String): Boolean =
        query.isBlank() || title.orEmpty().contains(query, ignoreCase = true) || lines.any { it.text.contains(query, ignoreCase = true) }

    companion object {
        fun srtTime(t: Double): String {
            val ms = (max(0.0, t) * 1000).roundToLong()
            fun p(v: Long, n: Int) = v.toString().padStart(n, '0')
            return "${p(ms / 3_600_000, 2)}:${p(ms / 60_000 % 60, 2)}:${p(ms / 1000 % 60, 2)},${p(ms % 1000, 3)}"
        }

        fun minutes(sec: Double): String {
            val m = (sec / 60).toInt()
            return when {
                m >= 60 -> "${m / 60} 小時 ${m % 60} 分"
                m > 0 -> "$m 分鐘"
                else -> "${sec.toInt()} 秒"
            }
        }
    }
}

/**
 * Follows the caption lines of one start…stop and builds the transcript. Lines already on screen
 * before the recording started only join if they change while recording.
 */
class TranscriptRecorder(
    id: String,
    startedMs: Long,
    engine: String,
    existing: List<Pair<Long, String>>,
) {
    var transcript = Transcript(id = id, startedMs = startedMs, endedMs = startedMs, engine = engine)
        private set
    private val before = existing.toMap().toMutableMap()

    /** Set by [finish]: later corrections still change the text, but never the times. */
    var finished = false
        private set

    /** Updates from the current lines (id, speaker, text); returns true when something changed. */
    fun update(lines: List<Triple<Long, String?, String>>, nowMs: Long, recording: Boolean): Boolean {
        @Suppress("NAME_SHADOWING") val recording = recording && !finished
        val now = (nowMs - transcript.startedMs) / 1000.0
        val out = transcript.lines.toMutableList()
        var changed = false
        for ((id, speaker, text) in lines) {
            if (id < 0 || text.isBlank()) continue
            val old = before[id]
            if (old != null) {
                if (old == text) continue
                before.remove(id) // a line from before that grows now belongs here too
            }
            val i = out.indexOfFirst { it.id == id }
            if (i >= 0) {
                val l = out[i]
                if (l.text == text && l.speaker == speaker) continue
                out[i] = l.copy(text = text, speaker = speaker, end = if (recording) now else l.end)
            } else {
                out += Transcript.Line(id, speaker, text, now, now)
            }
            changed = true
        }
        if (changed) transcript = transcript.copy(lines = out, endedMs = if (recording) nowMs else transcript.endedMs)
        return changed
    }

    /** Ends the recording once; calling it again does nothing, so the duration cannot grow after stop. */
    fun finish(nowMs: Long) {
        if (finished) return
        finished = true
        transcript = transcript.copy(endedMs = nowMs)
    }

    /** The user renamed it in the list meanwhile. */
    fun rename(title: String?) {
        transcript = transcript.copy(title = title)
    }
}
