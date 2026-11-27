package tw.captions.core

/** One finished caption line. */
data class CaptionLine(val text: String, val speaker: SpeakerId? = null)

/** What the caption screen shows. */
data class CaptionState(
    /** Finished lines, oldest first. */
    val lines: List<CaptionLine> = emptyList(),
    /** Speaker of the current utterance, if known. */
    val currentSpeaker: SpeakerId? = null,
    /** Part of the current utterance that has stayed the same across recent partial results. */
    val stableText: String = "",
    /** Rest of the current utterance; may still change, shown in a lighter style. */
    val tentativeText: String = "",
    val isListening: Boolean = false,
    val error: String? = null,
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
) {
    var state = CaptionState()
        private set

    private var lastPartial = ""

    fun reset() {
        state = CaptionState()
        lastPartial = ""
    }

    fun onEvent(event: TranscriptEvent): CaptionState {
        state = when (event) {
            TranscriptEvent.Listening -> state.copy(isListening = true, error = null)

            is TranscriptEvent.Partial -> {
                val text = processor.process(event.text)
                val stableLen = if (lastPartial.isEmpty()) 0 else commonPrefixLength(lastPartial, text)
                lastPartial = text
                state.copy(
                    currentSpeaker = event.speaker,
                    stableText = text.substring(0, stableLen),
                    tentativeText = text.substring(stableLen),
                    error = null,
                )
            }

            is TranscriptEvent.Final -> {
                val text = processor.process(event.text)
                lastPartial = ""
                val lines = if (text.isBlank()) {
                    state.lines
                } else {
                    (state.lines + CaptionLine(text, event.speaker)).takeLast(maxLines)
                }
                state.copy(lines = lines, currentSpeaker = null, stableText = "", tentativeText = "", error = null)
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
