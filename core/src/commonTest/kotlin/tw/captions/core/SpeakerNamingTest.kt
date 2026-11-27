package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeakerNamingTest {
    private val a = SpeakerId(0)
    private val b = SpeakerId(1)

    private fun final(text: String, speaker: SpeakerId?) = TranscriptEvent.Final(text, speaker)

    @Test
    fun namingALineNamesThatSpeakersOtherAndLaterLines() {
        val asm = CaptionAssembler()
        asm.onEvent(final("你好", a))
        asm.onEvent(final("早安", b))
        asm.onEvent(final("吃飽了嗎", a))
        val named = asm.nameSpeaker(0, "媽媽")
        assertEquals(listOf("媽媽", null, "媽媽"), named.lines.map { it.speakerName })

        asm.onEvent(TranscriptEvent.Partial("還沒", a))
        assertEquals("媽媽", asm.state.currentSpeakerName)
        val later = asm.onEvent(final("還沒", a))
        assertEquals("媽媽", later.lines.last().speakerName)
        assertNull(asm.onEvent(final("我也還沒", b)).lines.last().speakerName)
    }

    @Test
    fun renamingAPersonUpdatesTheirLines() {
        val asm = CaptionAssembler()
        asm.onEvent(final("你好", a))
        asm.nameSpeaker(0, "媽")
        val renamed = asm.renameSpeaker("媽", "媽媽")
        assertEquals("媽媽", renamed.lines[0].speakerName)
        assertEquals("媽媽", asm.onEvent(final("再見", a)).lines.last().speakerName)
    }

    @Test
    fun resetForgetsSessionNames() {
        val asm = CaptionAssembler()
        asm.onEvent(final("你好", a))
        asm.nameSpeaker(0, "媽媽")
        asm.reset()
        assertNull(asm.onEvent(final("你好", a)).lines.last().speakerName)
    }

    @Test
    fun mergingPeopleKeepsOneWithBothVoices() {
        val r = VoiceRegistry()
        val dad = r.add("爸", floatArrayOf(1f, 0f))
        val dad2 = r.add("爸爸", floatArrayOf(0.8f, 0.6f))
        r.merge(dad.id, dad2.id)
        assertEquals(listOf("爸爸"), r.people.map { it.name })
        assertEquals("爸爸", r.match(floatArrayOf(1f, 0f))?.name)
        assertEquals("爸爸", r.match(floatArrayOf(0.8f, 0.6f))?.name)
    }
}
