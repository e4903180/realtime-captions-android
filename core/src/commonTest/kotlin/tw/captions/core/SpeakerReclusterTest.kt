package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerReclusterTest {
    private val a = floatArrayOf(1f, 0.05f, 0f)
    private val a2 = floatArrayOf(0.95f, 0f, 0.1f)
    private val b = floatArrayOf(0f, 1f, 0.05f)
    private val b2 = floatArrayOf(0.1f, 0.95f, 0f)

    @Test
    fun fixesALineTheLiveClusteringGotWrongAndKeepsLetters() {
        // Live labels: the 3rd line (voice A) was wrongly given a new speaker C.
        val current = listOf(SpeakerId(0), SpeakerId(1), SpeakerId(2), SpeakerId(1), SpeakerId(0))
        val out = SpeakerRecluster.relabel(listOf(a, b, a2, b2, a), current)
        assertEquals(listOf(SpeakerId(0), SpeakerId(1), SpeakerId(0), SpeakerId(1), SpeakerId(0)), out)
    }

    @Test
    fun fixedNumberOfSpeakers() {
        val c = floatArrayOf(0.7f, 0.7f, 0f) // in between
        val current = listOf(SpeakerId(0), SpeakerId(1), SpeakerId(2))
        val out = SpeakerRecluster.relabel(listOf(a, b, c), current, speakers = 2)
        assertEquals(2, out.toSet().size)
    }

    @Test
    fun linesWithoutVoiceKeepTheirLabel() {
        val current = listOf(SpeakerId(0), null, SpeakerId(3))
        val out = SpeakerRecluster.relabel(listOf(a, null, a2), current)
        assertEquals(listOf(SpeakerId(0), null, SpeakerId(0)), out)
    }

    @Test
    fun assemblerRelabelsLines() {
        val asm = CaptionAssembler()
        asm.onEvent(TranscriptEvent.Final("你好", SpeakerId(0), voice = a))
        asm.onEvent(TranscriptEvent.Final("嗨", SpeakerId(1), voice = b))
        asm.onEvent(TranscriptEvent.Final("今天好嗎", SpeakerId(2), voice = a2))
        assertEquals(listOf(SpeakerId(0), SpeakerId(1), SpeakerId(0)), asm.recluster().lines.map { it.speaker })
    }
}
