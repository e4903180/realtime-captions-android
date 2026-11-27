package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusTrackerTest {
    @Test
    fun quietLinesAfterTheConversationAreBackground() {
        val f = FocusTracker(marginDb = 8f)
        assertFalse(f.observe(-20f)) // first line sets the conversation level
        assertFalse(f.observe(-24f)) // a slightly quieter partner is still foreground
        assertTrue(f.observe(-35f)) // a TV 15 dB below is background
        assertFalse(f.isBackground(-22f))
    }

    @Test
    fun backgroundLinesDoNotMoveTheReference() {
        val f = FocusTracker(marginDb = 8f)
        f.observe(-20f)
        repeat(20) { f.observe(-40f) }
        assertEquals(-20f, f.foregroundDb)
    }

    @Test
    fun verySoftSoundIsAlwaysBackground() {
        assertTrue(FocusTracker(minDb = -55f).observe(-60f))
    }

    @Test
    fun levelOfAFullScaleSineIsAboutMinus3Db() {
        val s = FloatArray(1600) { kotlin.math.sin(it * 0.1).toFloat() }
        assertEquals(-3f, FocusTracker.levelDb(s), 0.2f)
    }
}

class FocusInPipelineTest {
    private class OneCharAsr : StreamingAsr {
        val text = StringBuilder()
        var silent = 0
        override fun accept(samples: FloatArray) {
            if (samples.any { it != 0f }) { text.append('字'); silent = 0 } else silent++
        }
        override fun text() = text.toString()
        override fun isEndpoint() = text.isNotEmpty() && silent >= 3
        override fun reset() { text.clear(); silent = 0 }
    }

    private class EnergyVad : VoiceActivityDetector {
        var speech = false
        override fun accept(samples: FloatArray) { speech = samples.any { it != 0f } }
        override fun isSpeech() = speech
        override fun reset() { speech = false }
    }

    @Test
    fun quietTalkBetweenTurnsIsMarkedBackgroundAndCanBeHidden() {
        val p = StreamingPipeline(OneCharAsr(), EnergyVad(), config = PipelineConfig(vadEndpointSilenceSec = null))
        fun say(level: Float, chunks: Int) = (List(chunks) { level } + List(4) { 0f }).flatMap { v -> p.feed(FloatArray(1600) { v }) }
        val events = say(0.5f, 10) + say(0.05f, 10) + say(0.5f, 10) // loud, TV 20 dB lower, loud
        val finals = events.filterIsInstance<TranscriptEvent.Final>()
        assertEquals(listOf(false, true, false), finals.map { it.background })

        val shown = CaptionAssembler(backgroundMode = BackgroundMode.HIDE)
        events.forEach { shown.onEvent(it) }
        assertEquals(2, shown.state.lines.size)
        val dimmed = CaptionAssembler(backgroundMode = BackgroundMode.DIM)
        events.forEach { dimmed.onEvent(it) }
        assertEquals(listOf(false, true, false), dimmed.state.lines.map { it.background })
        val off = CaptionAssembler()
        events.forEach { off.onEvent(it) }
        assertEquals(listOf(false, false, false), off.state.lines.map { it.background })
    }
}
