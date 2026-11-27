package tw.captions.core

/** One finished caption line. */
/** [background]: speech much quieter than the conversation (TV, other tables), shown dimmed. */
data class CaptionLine(
    val text: String,
    val speaker: SpeakerId? = null,
    val background: Boolean = false,
    /** Registered person's name, shown instead of A/B. */
    val speakerName: String? = null,
    /** Voice profile, for "this is …" registration from a long-press. */
    val voice: FloatArray? = null,
)

/**
 * Lines much quieter than the conversation (see [FocusTracker]). Off by default: on real
 * recordings a quieter partner was hidden as often as a TV (docs/benchmark-focus.md).
 */
enum class BackgroundMode { OFF, DIM, HIDE }

/** What the caption screen shows. */
data class CaptionState(
    /** Finished lines, oldest first. */
    val lines: List<CaptionLine> = emptyList(),
    /** The current utterance sounds like background speech (TV, other tables). */
    val currentBackground: Boolean = false,
    /** Registered person speaking the current utterance, if recognised. */
    val currentSpeakerName: String? = null,
    /** Speaker of the current utterance, if known. */
    val currentSpeaker: SpeakerId? = null,
    /** Part of the current utterance that has stayed the same across recent partial results. */
    val stableText: String = "",
    /** Rest of the current utterance; may still change, shown in a lighter style. */
    val tentativeText: String = "",
    val isListening: Boolean = false,
    val error: String? = null,
    /** Current microphone level, 0.0 to 1.0, for a level meter. */
    val audioLevel: Float = 0f,
    /** Gentle hint shown when nothing has been heard for a while, e.g. a muted microphone. */
    val hint: String? = null,
    /**
     * The current utterance continues the last finished line (same speaker, short pause), so the
     * screen shows it on that line instead of starting a new one. See [CaptionAssembler.mergeGapSec].
     */
    val continuesLastLine: Boolean = false,
)

/**
 * Turns engine events into [CaptionState].
 *
 * To reduce flicker for the reader, the current utterance is split into a stable prefix
 * (unchanged between the last two partials) and a tentative tail.
 */
class CaptionAssembler(
    private val processor: TextProcessor = TextProcessor.default(),
    private val maxLines: Int = 200,
    private val silentRoundsBeforeHint: Int = 3,
    /** What to do with lines the focus filter marks as background (TV, other tables). */
    var backgroundMode: BackgroundMode = BackgroundMode.OFF,
    /**
     * One person talking with short breaths otherwise gets a new line every 0.4 s pause (the
     * recognizer ends lines quickly so that a change of speaker shows at once). When set, a line
     * that starts within this many seconds after the previous one, from the same speaker, is
     * joined to it. Null keeps one line per utterance.
     */
    private val mergeGapSec: Double? = null,
    /** Joined lines stop growing at this length; the next utterance starts a new line. */
    private val maxMergedChars: Int = 80,
    /** Seconds on a monotonic clock; replaceable in tests. */
    private val clockSec: () -> Double = defaultClock(),
) {
    companion object {
        const val NO_SPEECH_HINT = "沒有聽到說話聲，請確認麥克風有開，或靠近一點說話"
    }

    var state = CaptionState()
        private set

    private var lastPartial = ""
    private var silentRounds = 0
    private var lastFinalAt: Double? = null
    private var utteranceStartAt: Double? = null
    /** Whether the utterance in progress is shown on the last line; decided once so it does not jump back and forth. */
    private var joining: Boolean? = null

    /** Utterances that make up each line in [CaptionState.lines] (same order), so one can be corrected later. */
    private class LineParts(val id: Long, val parts: MutableList<Pair<Int, String>>)
    private val parts = ArrayList<LineParts>()
    private var nextLineId = 0L
    private var finals = 0

    /**
     * Number of the last [TranscriptEvent.Final] handled (counting from 0), for [revise]. Each Final
     * gets a number, also ones that are hidden or empty.
     */
    val lastFinalSeq: Int get() = finals - 1

    /** Stable id of the line at [index] (indexes shift when old lines are dropped), or -1. */
    fun lineId(index: Int): Long = parts.getOrNull(index)?.id ?: -1

    fun reset() {
        state = CaptionState()
        speakerNames.clear()
        parts.clear()
        finals = 0
        lastPartial = ""
        silentRounds = 0
        lastFinalAt = null
        utteranceStartAt = null
        joining = null
    }

    /** Whether an utterance by [speaker] that started at [startAt] should join the last line. */
    private fun joinsLastLine(speaker: SpeakerId?, background: Boolean, startAt: Double?, finalSpeakerKnown: Boolean): Boolean {
        val gap = mergeGapSec ?: return false
        val last = state.lines.lastOrNull() ?: return false
        val since = lastFinalAt ?: return false
        if (startAt == null || startAt - since > gap) return false
        if (background || last.background || last.text.length >= maxMergedChars) return false
        // While the line is in progress its speaker may not be known yet; at the end it must match.
        return if (speaker == null) !finalSpeakerKnown || last.speaker == null else speaker == last.speaker
    }

    private fun join(a: String, b: String): String {
        if (a.isEmpty()) return b
        val end = a.last()
        val needsComma = end.isCjk() && b.firstOrNull()?.isCjk() == true
        return if (needsComma) "$a，$b" else if (end.isLetterOrDigit() && b.firstOrNull()?.isLetterOrDigit() == true) "$a $b" else a + b
    }

    fun onEvent(incoming: TranscriptEvent): CaptionState {
        val event = withSpeakerName(incoming)
        state = when (event) {
            TranscriptEvent.Listening -> state.copy(isListening = true, error = null)

            is TranscriptEvent.AudioLevel -> state.copy(audioLevel = event.level.coerceIn(0f, 1f))

            TranscriptEvent.NoSpeechDetected -> {
                silentRounds++
                state.copy(hint = if (silentRounds >= silentRoundsBeforeHint) NO_SPEECH_HINT else state.hint)
            }

            is TranscriptEvent.Partial -> if (event.background && backgroundMode == BackgroundMode.HIDE) state else {
                silentRounds = 0
                val text = processor.process(event.text)
                val stableLen = if (lastPartial.isEmpty()) 0 else commonPrefixLength(lastPartial, text)
                if (lastPartial.isEmpty() && utteranceStartAt == null) utteranceStartAt = clockSec()
                lastPartial = text
                // Decide once; only leave the last line if the speaker turns out to be someone else.
                joining = when (val j = joining) {
                    null -> joinsLastLine(event.speaker, event.background, utteranceStartAt, finalSpeakerKnown = false)
                    true -> joinsLastLine(event.speaker, event.background, utteranceStartAt, finalSpeakerKnown = false)
                    false -> j
                }
                state.copy(
                    continuesLastLine = joining == true,
                    hint = null,
                    currentSpeaker = event.speaker,
                    currentSpeakerName = event.speakerName,
                    stableText = text.substring(0, stableLen),
                    tentativeText = text.substring(stableLen),
                    currentBackground = event.background && backgroundMode == BackgroundMode.DIM,
                    error = null,
                )
            }

            is TranscriptEvent.Final -> {
                silentRounds = 0
                val text = processor.process(event.text)
                lastPartial = ""
                val now = clockSec()
                val startAt = utteranceStartAt ?: now
                utteranceStartAt = null
                joining = null
                val hidden = text.isBlank() || (event.background && backgroundMode == BackgroundMode.HIDE)
                val seq = finals++
                val lines = when {
                    hidden -> state.lines
                    joinsLastLine(event.speaker, event.background, startAt, finalSpeakerKnown = true) -> {
                        val last = state.lines.last()
                        parts.last().parts += seq to text
                        state.lines.dropLast(1) + last.copy(
                            text = join(last.text, text),
                            speakerName = last.speakerName ?: event.speakerName,
                            voice = event.voice ?: last.voice,
                        )
                    }
                    else -> {
                        parts += LineParts(nextLineId++, mutableListOf(seq to text))
                        while (parts.size > maxLines) parts.removeAt(0)
                        (state.lines + CaptionLine(text, event.speaker, event.background && backgroundMode == BackgroundMode.DIM, event.speakerName, event.voice)).takeLast(maxLines)
                    }
                }
                if (!hidden) lastFinalAt = now
                state.copy(hint = null, lines = lines, currentSpeaker = null, currentSpeakerName = null, currentBackground = false, stableText = "", tentativeText = "", continuesLastLine = false, error = null)
            }

            is TranscriptEvent.Error -> state.copy(
                error = event.message,
                isListening = event.recoverable && state.isListening,
            )
        }
        return state
    }

    /**
     * Replaces the text of utterance [seq] (see [lastFinalSeq]) with [text], e.g. from a second,
     * more accurate pass over the same audio. The line keeps its speaker; an utterance whose line
     * is gone, or that a [replaceLineText] already covered, is left alone.
     */
    fun revise(seq: Int, text: String): CaptionState {
        val processed = processor.process(text)
        if (processed.isBlank()) return state
        val index = parts.indexOfFirst { p -> p.parts.any { it.first == seq } }
        if (index < 0) return state
        val line = parts[index]
        val at = line.parts.indexOfFirst { it.first == seq }
        line.parts[at] = seq to processed
        return setLineText(index, line.parts.map { it.second }.reduce(::join))
    }

    /** Replaces a whole line's text, e.g. after proof-reading. Later [revise] calls for its utterances are ignored. */
    fun replaceLineText(lineId: Long, text: String): CaptionState {
        val index = parts.indexOfFirst { it.id == lineId }
        if (index < 0 || text.isBlank()) return state
        parts[index] = LineParts(lineId, mutableListOf(-1 to text))
        return setLineText(index, text)
    }

    private fun setLineText(index: Int, text: String): CaptionState {
        if (state.lines[index].text == text) return state
        state = state.copy(lines = state.lines.mapIndexed { i, l -> if (i == index) l.copy(text = text) else l })
        return state
    }

    /**
     * Groups all finished lines by voice again and updates their speaker letters
     * ([SpeakerRecluster], ADR-027). Lines without a voice profile, background lines and lines
     * with a registered name keep their label. [speakers] > 0 fixes the number of people.
     */
    fun recluster(threshold: Float = 0.45f, speakers: Int = 0): CaptionState {
        val lines = state.lines
        val voices = lines.map { if (it.background || it.speakerName != null) null else it.voice }
        val relabeled = SpeakerRecluster.relabel(voices, lines.map { it.speaker }, threshold, speakers)
        if (relabeled == lines.map { it.speaker }) return state
        state = state.copy(lines = lines.mapIndexed { i, l -> if (l.speaker == relabeled[i]) l else l.copy(speaker = relabeled[i]) })
        return state
    }

    /** Names given to speaker labels this session: new lines from that label show the name too. */
    private val speakerNames = mutableMapOf<SpeakerId, String>()

    /**
     * Names the speaker of line [index]: that line and every other line with the same label get
     * [name], and so do later lines with that label (until [reset]). Lines without a label only
     * name themselves.
     */
    fun nameSpeaker(index: Int, name: String): CaptionState {
        val line = state.lines.getOrNull(index) ?: return state
        val speaker = line.speaker
        if (speaker != null) speakerNames[speaker] = name
        state = state.copy(lines = state.lines.mapIndexed { i, l ->
            if (i == index || (speaker != null && l.speaker == speaker && l.speakerName == null)) l.copy(speakerName = name, background = false) else l
        })
        return state
    }

    /** Puts [name] on every line that shows [oldName] (renaming a person). */
    fun renameSpeaker(oldName: String, name: String): CaptionState {
        for (k in speakerNames.filterValues { it == oldName }.keys) speakerNames[k] = name
        state = state.copy(lines = state.lines.map { if (it.speakerName == oldName) it.copy(speakerName = name) else it })
        return state
    }

    private fun withSpeakerName(event: TranscriptEvent): TranscriptEvent = when {
        event is TranscriptEvent.Final && event.speakerName == null && event.speaker != null ->
            speakerNames[event.speaker]?.let { event.copy(speakerName = it) } ?: event
        event is TranscriptEvent.Partial && event.speakerName == null && event.speaker != null ->
            speakerNames[event.speaker]?.let { event.copy(speakerName = it) } ?: event
        else -> event
    }

    /** Shows [name] on a finished line (after the user said who it was). */
    fun nameLine(index: Int, name: String): CaptionState {
        if (index !in state.lines.indices) return state
        state = state.copy(lines = state.lines.mapIndexed { i, l -> if (i == index) l.copy(speakerName = name, background = false) else l })
        return state
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}

private fun defaultClock(): () -> Double {
    val start = kotlin.time.TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds / 1000.0 }
}
