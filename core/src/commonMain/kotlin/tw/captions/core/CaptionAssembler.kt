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
        /** How many of the newest lines regrouping may still relabel one by one (ADR-036). */
        const val DEFAULT_OPEN_LINES = 4
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
    private class LineParts(val id: Long, val parts: MutableList<Pair<Int, String>>, val live: SpeakerId? = null)
    private val parts = ArrayList<LineParts>()
    private var nextLineId = 0L
    /** First line of the current listening session; [recluster] leaves older lines alone. */
    private var sessionStartLineId = 0L
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
        sessionStartLineId = nextLineId
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

    fun onEvent(event: TranscriptEvent): CaptionState = handle(withSpeakerName(event))

    private fun handle(event: TranscriptEvent): CaptionState {
        state = when (event) {
            TranscriptEvent.Listening -> {
                // Each engine start sends this once: lines from before are settled history.
                sessionStartLineId = nextLineId
                state.copy(isListening = true, error = null)
            }

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
                            voice = blendVoices(last.voice, last.text.length, event.voice, text.length),
                        )
                    }
                    else -> {
                        parts += LineParts(nextLineId++, mutableListOf(seq to text), event.speaker)
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
        parts[index] = LineParts(lineId, mutableListOf(-1 to text), parts[index].live)
        return setLineText(index, text)
    }

    private fun setLineText(index: Int, text: String): CaptionState {
        if (state.lines[index].text == text) return state
        state = state.copy(lines = state.lines.mapIndexed { i, l -> if (i == index) l.copy(text = text) else l })
        return state
    }

    /** [recluster] with the usual four open lines; the two-argument form the iPhone app calls (Swift sees no default arguments). */
    fun recluster(threshold: Float = SpeakerRecluster.DEFAULT_THRESHOLD, speakers: Int = 0): CaptionState =
        recluster(threshold, speakers, DEFAULT_OPEN_LINES)

    /**
     * Groups this session's finished lines by voice again and updates their speaker letters
     * ([SpeakerRecluster], ADR-027, ADR-036). Lines from earlier sessions, lines without a voice
     * profile, background lines and lines with a registered name keep their label. Only the last
     * [openLines] lines may change on their own; older lines change only together with every other
     * older line of the same letter (one person renamed as a whole), so text the reader has
     * already taken in does not jump between people. [speakers] > 0 is the most people there are.
     */
    fun recluster(threshold: Float, speakers: Int, openLines: Int): CaptionState {
        val lines = state.lines
        val inSession = lines.indices.map { (parts.getOrNull(it)?.id ?: -1) >= sessionStartLineId }
        val voices = lines.mapIndexed { i, l -> if (!inSession[i] || l.background || l.speakerName != null) null else l.voice }
        val sessionCount = inSession.count { it }
        var seen = 0
        val frozen = lines.indices.map { i -> inSession[i] && seen++ < sessionCount - openLines }
        val live = lines.indices.map { parts.getOrNull(it)?.live ?: lines[it].speaker }
        val relabeled = SpeakerRecluster.relabel(voices, lines.map { it.speaker }, threshold, speakers, live, frozen)
        if (relabeled == lines.map { it.speaker }) return state
        state = state.copy(lines = lines.mapIndexed { i, l -> if (l.speaker == relabeled[i]) l else l.copy(speaker = relabeled[i]) })
        return state
    }

    /**
     * Voice profile of every speaker letter, from the finished lines as they are labelled now
     * (longer lines count more), with the number of lines behind it. After [recluster] changed
     * labels, hand this to [SpeakerClusterer.replaceProfiles] so the next line is compared with
     * the corrected groups instead of the ones from before the correction.
     */
    fun speakerProfiles(): Map<SpeakerId, SpeakerClusterer.Profile> {
        val sums = mutableMapOf<SpeakerId, FloatArray>()
        val counts = mutableMapOf<SpeakerId, Int>()
        for (l in state.lines) {
            val speaker = l.speaker ?: continue
            val voice = l.voice ?: continue
            if (l.background) continue
            val unit = unitVector(voice)
            val w = l.text.length.coerceAtLeast(1)
            val sum = sums.getOrPut(speaker) { FloatArray(unit.size) }
            if (sum.size != unit.size) continue
            for (k in unit.indices) sum[k] += unit[k] * w
            counts[speaker] = (counts[speaker] ?: 0) + 1
        }
        return sums.mapValues { (id, sum) -> SpeakerClusterer.Profile(unitVector(sum), counts[id] ?: 1) }
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

    /**
     * The user forgot the person called [name]: lines showing that name go back to their speaker
     * letter, and later lines from that letter are no longer named.
     */
    fun forgetSpeaker(name: String): CaptionState {
        speakerNames.entries.removeAll { it.value == name }
        state = state.copy(
            lines = state.lines.map { if (it.speakerName == name) it.copy(speakerName = null) else it },
            currentSpeakerName = state.currentSpeakerName?.takeIf { it != name },
        )
        return state
    }
}

private fun unitVector(v: FloatArray): FloatArray {
    var n = 0f
    for (x in v) n += x * x
    val norm = kotlin.math.sqrt(n)
    return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
}

/** Average of two voice profiles weighted by how much speech each stands for (text length). */
internal fun blendVoices(a: FloatArray?, aWeight: Int, b: FloatArray?, bWeight: Int): FloatArray? {
    if (a == null) return b
    if (b == null || a.size != b.size) return a
    val ua = unitVector(a)
    val ub = unitVector(b)
    val wa = aWeight.coerceAtLeast(1)
    val wb = bWeight.coerceAtLeast(1)
    return unitVector(FloatArray(a.size) { ua[it] * wa + ub[it] * wb })
}

private fun defaultClock(): () -> Double {
    val start = kotlin.time.TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds / 1000.0 }
}
