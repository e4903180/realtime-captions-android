package tw.captions.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.captions.app.engine.AudioInput
import tw.captions.app.engine.BufferedAudioInput

class BufferedAudioInputTest {
    /** Counts up from 0, [total] samples, then ends. */
    private class Counter(private val total: Int) : AudioInput {
        private var next = 0
        var closed = false
        override fun read(buffer: FloatArray): Int {
            if (next >= total) return -1
            val n = minOf(buffer.size, total - next)
            for (i in 0 until n) buffer[i] = (next + i).toFloat()
            next += n
            return n
        }
        override fun close() { closed = true }
    }

    @Test
    fun deliversEverySampleInOrderThenEnds() {
        val source = Counter(1000)
        val input = BufferedAudioInput(source, chunkSamples = 64, raisePriority = {})
        val out = ArrayList<Float>()
        val buf = FloatArray(150)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) out += buf[i]
        }
        input.close()
        assertEquals(1000, out.size)
        assertEquals((0 until 1000).map { it.toFloat() }, out)
        assertTrue(source.closed)
    }

    @Test
    fun dropsOldestAudioWhenProcessingFallsBehind() {
        // Room for 1 second (50 chunks of 20 ms); the source has 3 seconds ready at once.
        val source = Counter(48_000)
        val input = BufferedAudioInput(source, chunkSamples = 320, maxBufferedSec = 1, raisePriority = {})
        Thread.sleep(300)
        val buf = FloatArray(320)
        assertEquals(320, input.read(buf))
        // The first audio was dropped to make room: what comes out is from later in the stream.
        assertTrue(buf[0] > 0f)
        assertTrue(input.dropped > 0)
        input.close()
    }
}
