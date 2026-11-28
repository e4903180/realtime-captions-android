package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** ADR-039: 自動 keeps live letters; a given number of people regroups. */
class SpeakerPolicyTest {
    private val a = floatArrayOf(1f, 0.05f, 0f)
    private val a2 = floatArrayOf(0.95f, 0f, 0.1f)
    private val b = floatArrayOf(0f, 1f, 0.05f)
    private fun id(i: Int) = SpeakerId(i)

    private fun lines(asm: CaptionAssembler) = asm.state.lines.map { it.speaker }

    @Test
    fun automaticKeepsTheLiveLettersEvenWhenAVoiceWasSplit() {
        val asm = CaptionAssembler()
        asm.onEvent(TranscriptEvent.Listening)
        asm.onEvent(TranscriptEvent.Final("一", id(0), voice = a))
        asm.onEvent(TranscriptEvent.Final("二", id(1), voice = b))
        asm.onEvent(TranscriptEvent.Final("三", id(2), voice = a2)) // live grouping split A
        val state = SpeakerPolicy.afterFinal(asm, SpeakerClusterer(), speakers = 0)
        assertEquals(listOf(id(0), id(1), id(2)), state.lines.map { it.speaker })
    }

    @Test
    fun givenNumberRegroupsAndTheLiveGroupingFollows() {
        val asm = CaptionAssembler()
        val clusterer = SpeakerClusterer()
        asm.onEvent(TranscriptEvent.Listening)
        asm.onEvent(TranscriptEvent.Final("一", id(0), voice = a))
        asm.onEvent(TranscriptEvent.Final("二", id(1), voice = b))
        asm.onEvent(TranscriptEvent.Final("三", id(2), voice = a2))
        SpeakerPolicy.afterFinal(asm, clusterer, speakers = 2)
        assertEquals(listOf(id(0), id(1), id(0)), lines(asm))
        // The live grouping now knows two people, so the next A line is A at once.
        assertEquals(id(0), clusterer.assign(a2))
    }
}
