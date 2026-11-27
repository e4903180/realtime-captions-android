package tw.captions.core

/** One finished caption line. */
/** [background]: speech much quieter than the conversation (TV, other tables), shown dimmed. */
data class CaptionLine(val text: String, val speaker: SpeakerId? = null, val background: Boolean = false)

/** What the caption screen shows. */
data class CaptionState(
    /** Finished lines, oldest first. */
    val lines: List<CaptionLine> = emptyList(),
    /** The current utterance sounds like background speech (TV, other tables). */
    val currentBackground: Boolean = false,
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
    /** Drop background lines (TV, other tables) instead of showing them dimmed. */
    var hideBackground: Boolean = false,
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

            is TranscriptEvent.Partial -> if (event.background && hideBackground) state else {
                silentRounds = 0
                val text = processor.process(event.text)
                val stableLen = if (lastPartial.isEmpty()) 0 else commonPrefixLength(lastPartial, text)
                lastPartial = text
                state.copy(
                    hint = null,
                    currentSpeaker = event.speaker,
                    stableText = text.substring(0, stableLen),
                    tentativeText = text.substring(stableLen),
                    currentBackground = event.background,
                    error = null,
                )
            }

            is TranscriptEvent.Final -> {
                silentRounds = 0
                val text = processor.process(event.text)
                lastPartial = ""
                val lines = if (text.isBlank() || (event.background && hideBackground)) {
                    state.lines
                } else {
                    (state.lines + CaptionLine(text, event.speaker, event.background)).takeLast(maxLines)
                }
                state.copy(hint = null, lines = lines, currentSpeaker = null, currentBackground = false, stableText = "", tentativeText = "", error = null)
            }

            is TranscriptEvent.Error -> state.copy(
                error = event.message,
                isListening = event.recoverable && state.isListening,
            )
        }
        return state
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
