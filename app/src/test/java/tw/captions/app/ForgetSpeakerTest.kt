package tw.captions.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.captions.core.CaptionAssembler
import tw.captions.core.SpeakerId
import tw.captions.core.TranscriptEvent

class ForgetSpeakerTest {
    private val a = SpeakerId(0)
    private val b = SpeakerId(1)

    private fun assembler() = CaptionAssembler(clockSec = { 0.0 })

    @Test
    fun forgetPutsLinesBackToLetters() {
        val asm = assembler()
        asm.onEvent(TranscriptEvent.Final("早安", a))
        asm.onEvent(TranscriptEvent.Final("你好", b))
        asm.nameSpeaker(0, "媽媽")
        asm.nameSpeaker(1, "爸爸")
        assertEquals("媽媽", asm.state.lines[0].speakerName)

        val state = asm.forgetSpeaker("媽媽")
        assertNull(state.lines[0].speakerName)
        assertEquals(a, state.lines[0].speaker)
        // Someone else keeps their name.
        assertEquals("爸爸", state.lines[1].speakerName)

        // Later lines of that letter are no longer named; the other letter still is.
        asm.onEvent(TranscriptEvent.Final("吃飯了", a))
        asm.onEvent(TranscriptEvent.Final("好", b))
        assertNull(asm.state.lines[2].speakerName)
        assertEquals("爸爸", asm.state.lines[3].speakerName)
    }

    @Test
    fun forgetUnknownNameChangesNothing() {
        val asm = assembler()
        asm.onEvent(TranscriptEvent.Final("早安", a))
        asm.nameSpeaker(0, "媽媽")
        val before = asm.state.lines
        assertEquals(before, asm.forgetSpeaker("阿姨").lines)
    }

    @Test
    fun lineIdsStayWithTheirLine() {
        val asm = CaptionAssembler(maxLines = 2, clockSec = { 0.0 })
        asm.onEvent(TranscriptEvent.Final("一", a))
        asm.onEvent(TranscriptEvent.Final("二", b))
        val idOfSecond = asm.lineId(1)
        asm.onEvent(TranscriptEvent.Final("三", a))
        // The oldest line was dropped: "二" moved to index 0 but kept its id.
        assertEquals("二", asm.state.lines[0].text)
        assertEquals(idOfSecond, asm.lineId(0))
    }
}
