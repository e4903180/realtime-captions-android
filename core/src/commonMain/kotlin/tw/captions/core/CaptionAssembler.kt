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
) {
    companion object {
        const val NO_SPEECH_HINT = "沒有聽到說話聲，請確認麥克風有開，或靠近一點說話"
    }

    var state = CaptionState()
        private set

    private var lastPartial = ""
    private var silentRounds = 0

    fun reset() {
        state = CaptionState()
        lastPartial = ""
        silentRounds = 0
    }

    fun onEvent(event: TranscriptEvent): CaptionState {
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
                lastPartial = text
                state.copy(
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
                val lines = if (text.isBlank() || (event.background && backgroundMode == BackgroundMode.HIDE)) {
                    state.lines
                } else {
                    (state.lines + CaptionLine(text, event.speaker, event.background && backgroundMode == BackgroundMode.DIM, event.speakerName, event.voice)).takeLast(maxLines)
                }
                state.copy(hint = null, lines = lines, currentSpeaker = null, currentSpeakerName = null, currentBackground = false, stableText = "", tentativeText = "", error = null)
            }

            is TranscriptEvent.Error -> state.copy(
                error = event.message,
                isListening = event.recoverable && state.isListening,
            )
        }
        return state
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
