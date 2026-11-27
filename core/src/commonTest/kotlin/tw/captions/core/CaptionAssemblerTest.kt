package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptionAssemblerTest {
    @Test
    fun splitsStableAndTentative() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.Partial("今天"))
        assertEquals("", a.state.stableText)
        assertEquals("今天", a.state.tentativeText)

        a.onEvent(TranscriptEvent.Partial("今天天器"))
        assertEquals("今天", a.state.stableText)
        assertEquals("天器", a.state.tentativeText)

        a.onEvent(TranscriptEvent.Partial("今天天氣很好"))
        assertEquals("今天天", a.state.stableText)
        assertEquals("氣很好", a.state.tentativeText)
    }

    @Test
    fun finalMovesToLines() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.Partial("你好"))
        a.onEvent(TranscriptEvent.Final("你好嗎?"))
        assertEquals(listOf(CaptionLine("你好嗎？")), a.state.lines)
        assertEquals("", a.state.stableText + a.state.tentativeText)

        // A new utterance starts fresh, with nothing marked stable yet.
        a.onEvent(TranscriptEvent.Partial("我很好"))
        assertEquals("", a.state.stableText)
    }

    @Test
    fun ignoresBlankFinalsAndCapsLines() {
        val a = CaptionAssembler(maxLines = 2)
        listOf("一", " ", "二", "三").forEach { a.onEvent(TranscriptEvent.Final(it)) }
        assertEquals(listOf("二", "三"), a.state.lines.map { it.text })
    }

    @Test
    fun errorsAreShown() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.Listening)
        a.onEvent(TranscriptEvent.Error("沒有網路", recoverable = true))
        assertEquals("沒有網路", a.state.error)
        assertEquals(true, a.state.isListening)
    }

    @Test
    fun keepsSpeakerLabels() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.Partial("吃飯了嗎", SpeakerId(0)))
        assertEquals(SpeakerId(0), a.state.currentSpeaker)
        a.onEvent(TranscriptEvent.Final("吃飯了嗎", SpeakerId(0)))
        a.onEvent(TranscriptEvent.Final("還沒", SpeakerId(1)))
        assertEquals(listOf("A", "B"), a.state.lines.map { it.speaker?.label })
        assertEquals(null, a.state.currentSpeaker)
    }

    @Test
    fun hintAfterRepeatedSilenceAndClearsOnSpeech() {
        val a = CaptionAssembler(silentRoundsBeforeHint = 2)
        a.onEvent(TranscriptEvent.NoSpeechDetected)
        assertEquals(null, a.state.hint)
        a.onEvent(TranscriptEvent.NoSpeechDetected)
        assertEquals(CaptionAssembler.NO_SPEECH_HINT, a.state.hint)
        a.onEvent(TranscriptEvent.Partial("有了"))
        assertEquals(null, a.state.hint)
    }

    @Test
    fun audioLevelIsClamped() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.AudioLevel(1.7f))
        assertEquals(1f, a.state.audioLevel)
    }
}
