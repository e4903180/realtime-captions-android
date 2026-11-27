package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Correcting finished lines afterwards: a second recognition pass (ADR-025) and proof-reading (ADR-026). */
class SecondPassTest {
    private var now = 0.0
    private fun assembler() = CaptionAssembler(mergeGapSec = 1.5, clockSec = { now })

    private fun CaptionAssembler.say(text: String, speaker: Int, startAt: Double, endAt: Double): Int {
        now = startAt
        onEvent(TranscriptEvent.Partial(text.take(1), SpeakerId(speaker)))
        now = endAt
        onEvent(TranscriptEvent.Final(text, SpeakerId(speaker)))
        return lastFinalSeq
    }

    @Test
    fun reviseReplacesOneUtteranceOfAJoinedLine() {
        val a = assembler()
        val first = a.say("今天天器很好", 0, 0.0, 1.0)
        val second = a.say("我們去散布", 0, 1.5, 2.5)
        a.say("好啊", 1, 3.0, 3.5)
        a.revise(second, "我們去散步")
        val s = a.revise(first, "今天天氣很好")
        assertEquals(listOf("今天天氣很好，我們去散步", "好啊"), s.lines.map { it.text })
        assertEquals(SpeakerId(0), s.lines[0].speaker)
    }

    @Test
    fun reviseIgnoresUnknownOrBlankText() {
        val a = assembler()
        val seq = a.say("你好", 0, 0.0, 1.0)
        a.revise(seq + 5, "別的")
        val s = a.revise(seq, "  ")
        assertEquals(listOf("你好"), s.lines.map { it.text })
    }

    @Test
    fun proofReadLineIsNotOverwrittenByALateRevision() {
        val a = assembler()
        val seq = a.say("在見", 0, 0.0, 1.0)
        a.replaceLineText(a.lineId(0), "再見。")
        val s = a.revise(seq, "在見")
        assertEquals(listOf("再見。"), s.lines.map { it.text })
    }

    @Test
    fun lineIdsStayStableWhenOldLinesAreDropped() {
        val a = CaptionAssembler(maxLines = 2)
        a.onEvent(TranscriptEvent.Final("一"))
        a.onEvent(TranscriptEvent.Final("二"))
        val idOfTwo = a.lineId(1)
        a.onEvent(TranscriptEvent.Final("三"))
        assertEquals(idOfTwo, a.lineId(0))
        assertEquals(listOf("貳", "三"), a.replaceLineText(idOfTwo, "貳").lines.map { it.text })
    }

    @Test
    fun secondPassGuardKeepsTheFirstTextWhenTheSecondLooksWrong() {
        assertEquals("今天天氣很好", SecondPass.choose("今天天器很好", "今天天氣很好"))
        assertNull(SecondPass.choose("今天天氣很好我們去散步", "嗯"))          // much shorter: probably lost words
        assertNull(SecondPass.choose("你好", ""))
        assertNull(SecondPass.choose("好", "好好好好好好好好好好"))             // much longer: hallucination
    }

    // --- Pipeline keeps each utterance's audio ---

    private class CharAsr : StreamingAsr {
        val text = StringBuilder()
        override fun accept(samples: FloatArray) { if (samples.any { it != 0f }) text.append('字') }
        override fun text() = text.toString()
        override fun isEndpoint() = false
        override fun reset() { text.clear() }
    }

    private class EnergyVad : VoiceActivityDetector {
        var speech = false
        override fun accept(samples: FloatArray) { speech = samples.any { it != 0f } }
        override fun isSpeech() = speech
        override fun reset() { speech = false }
    }

    @Test
    fun pipelineHandsOverTheAudioOfEachFinishedUtterance() {
        val p = StreamingPipeline(CharAsr(), EnergyVad(), config = PipelineConfig(keepUtteranceAudioSec = 10f, vadEndpointStableSec = 0f))
        val chunk = 1600
        fun feed(v: Float, n: Int) = (0 until n).flatMap { p.feed(FloatArray(chunk) { v }) }
        feed(0f, 20) // 2 s of silence before speaking: only the last 0.3 s is kept
        feed(0.3f, 10)
        val events = feed(0f, 6)
        assertTrue(events.any { it is TranscriptEvent.Final })
        val audio = assertNotNull(p.takeUtteranceAudio())
        // 0.3 s before + 1 s speech + the silence until the line ended (0.4 s endpoint + rounding)
        assertTrue(audio.size in (13 * chunk)..(18 * chunk), "got ${audio.size / chunk.toFloat()} chunks")
        assertEquals(0.3f, audio[3 * chunk + 10])
        assertNull(p.takeUtteranceAudio())
    }

    @Test
    fun noAudioIsKeptByDefault() {
        val p = StreamingPipeline(CharAsr(), EnergyVad())
        (0 until 10).forEach { _ -> p.feed(FloatArray(1600) { 0.3f }) }
        p.flush()
        assertNull(p.takeUtteranceAudio())
    }
}
