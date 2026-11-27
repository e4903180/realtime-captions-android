package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerClustererTest {
    @Test
    fun similarVoicesShareASpeakerAndNewVoicesGetANewOne() {
        val c = SpeakerClusterer(threshold = 0.7f)
        assertEquals(SpeakerId(0), c.assign(floatArrayOf(1f, 0f, 0f)))
        assertEquals(SpeakerId(0), c.assign(floatArrayOf(0.9f, 0.1f, 0f)))
        assertEquals(SpeakerId(1), c.assign(floatArrayOf(0f, 1f, 0f)))
        assertEquals(SpeakerId(0), c.assign(floatArrayOf(2f, 0.2f, 0f))) // scale does not matter
        assertEquals(2, c.speakerCount)
    }

    @Test
    fun peekDoesNotCreateSpeakers() {
        val c = SpeakerClusterer(threshold = 0.7f)
        assertEquals(SpeakerId(0), c.peek(floatArrayOf(1f, 0f)))
        assertEquals(0, c.speakerCount)
        c.assign(floatArrayOf(1f, 0f))
        assertEquals(SpeakerId(1), c.peek(floatArrayOf(0f, 1f)))
        assertEquals(1, c.speakerCount)
    }

    @Test
    fun capsTheNumberOfSpeakers() {
        val c = SpeakerClusterer(threshold = 0.99f, maxSpeakers = 2)
        c.assign(floatArrayOf(1f, 0f, 0f))
        c.assign(floatArrayOf(0f, 1f, 0f))
        assertEquals(SpeakerId(1), c.assign(floatArrayOf(0.1f, 1f, 0.3f)))
        assertEquals(2, c.speakerCount)
    }
}

class StreamingPipelineTest {
    /** Recognizer that "hears" one character per non-silent chunk and ends after 3 silent chunks. */
    private class FakeAsr : StreamingAsr {
        val text = StringBuilder()
        var silentChunks = 0
        override fun accept(samples: FloatArray) {
            if (samples.any { it != 0f }) {
                text.append(if (samples[0] > 0.5f) 'B' else 'A')
                silentChunks = 0
            } else {
                silentChunks++
            }
        }
        override fun text() = text.toString()
        override fun isEndpoint() = text.isNotEmpty() && silentChunks >= 3
        override fun reset() {
            text.clear()
            silentChunks = 0
        }
    }

    private class EnergyVad : VoiceActivityDetector {
        var speech = false
        override fun accept(samples: FloatArray) { speech = samples.any { it != 0f } }
        override fun isSpeech() = speech
        override fun reset() { speech = false }
    }

    /** Voice "A" is amplitude 0.3, voice "B" is 0.8. */
    private val embedder = SpeakerEmbedder { s -> if (s.average() > 0.5) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f) }

    private fun chunk(v: Float) = FloatArray(1600) { v }

    private fun run(p: StreamingPipeline, values: List<Float>) = values.flatMap { p.feed(chunk(it)) } + p.flush()

    @Test
    fun emitsPartialsThenAFinalWithSpeakers() {
        val p = StreamingPipeline(FakeAsr(), EnergyVad(), embedder, SpeakerClusterer(0.7f), PipelineConfig(vadEndpointSilenceSec = null))
        val speechA = List(12) { 0.3f }
        val speechB = List(12) { 0.8f }
        val silence = List(4) { 0f }
        val events = run(p, speechA + silence + speechB + silence + speechA + silence)
        val finals = events.filterIsInstance<TranscriptEvent.Final>()
        assertEquals(listOf("A".repeat(12), "B".repeat(12), "A".repeat(12)), finals.map { it.text })
        assertEquals(listOf(SpeakerId(0), SpeakerId(1), SpeakerId(0)), finals.map { it.speaker })
        assertTrue(events.first() == TranscriptEvent.Listening)
        // After 1 s of speech, partials carry the provisional speaker.
        val partialsB = events.filterIsInstance<TranscriptEvent.Partial>().filter { it.text.startsWith("B") }
        assertNull(partialsB.first().speaker) // too little speech for a label yet
        assertEquals(SpeakerId(1), partialsB.last().speaker)
    }

    @Test
    fun vadEndpointFinishesBeforeTheRecognizer() {
        val p = StreamingPipeline(FakeAsr(), EnergyVad(), null, config = PipelineConfig(vadEndpointSilenceSec = 0.2f, vadEndpointStableSec = 0.2f))
        val events = List(5) { 0.3f }.flatMap { p.feed(chunk(it)) }
        assertTrue(events.none { it is TranscriptEvent.Final })
        assertTrue(p.feed(chunk(0f)).none { it is TranscriptEvent.Final })
        val final = p.feed(chunk(0f)).filterIsInstance<TranscriptEvent.Final>().single()
        assertEquals("AAAAA", final.text)
        assertNull(final.speaker)
    }

    @Test
    fun flushEmitsTheUnfinishedUtterance() {
        val p = StreamingPipeline(FakeAsr())
        p.feed(chunk(0.3f))
        assertEquals(listOf(TranscriptEvent.Final("A", null)), p.flush())
        assertEquals(emptyList(), p.flush())
    }

    @Test
    fun enhancerOutputIsWhatTheRecognizerHears() {
        // An "enhancer" that silences everything: no captions, but the level meter still moves.
        val mute = object : AudioEnhancer {
            override fun process(samples: FloatArray) = FloatArray(samples.size)
            override fun reset() {}
        }
        val p = StreamingPipeline(FakeAsr(), EnergyVad(), enhancer = mute)
        val events = List(10) { 0.3f }.flatMap { p.feed(chunk(it)) } + p.flush()
        assertTrue(events.none { it is TranscriptEvent.Partial || it is TranscriptEvent.Final })
        assertTrue(events.filterIsInstance<TranscriptEvent.AudioLevel>().all { it.level > 0f })
    }

    @Test
    fun sampleBufferKeepsTheLatestSamples() {
        val b = SampleBuffer(4)
        b.append(floatArrayOf(1f, 2f, 3f))
        b.append(floatArrayOf(4f, 5f))
        assertEquals(listOf(2f, 3f, 4f, 5f), b.toArray().toList())
    }
}
