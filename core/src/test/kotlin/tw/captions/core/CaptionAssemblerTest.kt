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
        assertEquals(listOf("你好嗎？"), a.state.lines)
        assertEquals("", a.state.stableText + a.state.tentativeText)

        // A new utterance starts fresh, with nothing marked stable yet.
        a.onEvent(TranscriptEvent.Partial("我很好"))
        assertEquals("", a.state.stableText)
    }

    @Test
    fun ignoresBlankFinalsAndCapsLines() {
        val a = CaptionAssembler(maxLines = 2)
        listOf("一", " ", "二", "三").forEach { a.onEvent(TranscriptEvent.Final(it)) }
        assertEquals(listOf("二", "三"), a.state.lines)
    }

    @Test
    fun errorsAreShown() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.Listening)
        a.onEvent(TranscriptEvent.Error("沒有網路", recoverable = true))
        assertEquals("沒有網路", a.state.error)
        assertEquals(true, a.state.isListening)
    }
}
