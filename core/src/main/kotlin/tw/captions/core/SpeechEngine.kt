package tw.captions.core

import kotlinx.coroutines.flow.Flow

/**
 * A speech recognition engine (online or offline).
 *
 * Implementations own their audio input and emit [TranscriptEvent]s until the
 * collecting coroutine is cancelled. This module has no Android dependency,
 * so the same contract can later be implemented on other devices (for example smart glasses).
 */
interface SpeechEngine {
    /** Stable identifier, used in settings. */
    val id: String

    /** Name shown to the user. */
    val displayName: String

    /** True if the engine needs an internet connection. */
    val requiresNetwork: Boolean

    /** Starts listening. Cancel the collecting coroutine to stop. */
    fun transcribe(config: RecognitionConfig): Flow<TranscriptEvent>
}

data class RecognitionConfig(
    /** BCP-47 tag of the main language, e.g. "zh-TW". */
    val languageTag: String = "zh-TW",
    /** Other languages the speaker may switch to (engine support varies). */
    val alternativeLanguageTags: List<String> = listOf("en-US"),
    /** Words the engine should be biased towards (names, places, ...). */
    val hotwords: List<String> = emptyList(),
)

sealed interface TranscriptEvent {
    /** Engine is ready and listening. */
    data object Listening : TranscriptEvent

    /**
     * In-progress hypothesis for the current utterance; may still change.
     * [speaker] is null when the engine cannot tell speakers apart.
     */
    data class Partial(val text: String, val speaker: SpeakerId? = null) : TranscriptEvent

    /** Finished utterance; will not change any more. */
    data class Final(val text: String, val speaker: SpeakerId? = null) : TranscriptEvent

    data class Error(val message: String, val recoverable: Boolean) : TranscriptEvent
}

/** Anonymous speaker label assigned by speaker diarization, e.g. 0 → "A", 1 → "B". */
@JvmInline
value class SpeakerId(val index: Int) {
    val label: String get() = if (index in 0..25) ('A' + index).toString() else "S${index + 1}"
}
