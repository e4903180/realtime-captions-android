package tw.captions.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.captions.core.TranscriptRecorder

class TranscriptLifecycleTest {
    private fun recorder() = TranscriptRecorder(id = "t", startedMs = 0, engine = "test", existing = emptyList())

    @Test
    fun durationStopsGrowingAfterFinish() {
        val r = recorder()
        r.update(listOf(Triple(1L, "A", "你好")), nowMs = 5_000, recording = true)
        r.finish(10_000)
        assertEquals(10.0, r.transcript.durationSec, 0.0)

        // A second finish (stop pressed again, engine switched while stopped) changes nothing.
        r.finish(60_000)
        assertEquals(10.0, r.transcript.durationSec, 0.0)

        // A late correction (AI proof-reading) changes the text, not the times, even if marked as recording.
        assertTrue(r.update(listOf(Triple(1L, "A", "您好")), nowMs = 90_000, recording = true))
        assertEquals(10.0, r.transcript.durationSec, 0.0)
        assertEquals("您好", r.transcript.lines.single().text)
        assertEquals(5.0, r.transcript.lines.single().end, 0.0)
    }

    @Test
    fun finishedFlag() {
        val r = recorder()
        assertFalse(r.finished)
        r.finish(1_000)
        assertTrue(r.finished)
    }

    @Test
    fun linesFromBeforeOnlyJoinWhenTheyChange() {
        val r = TranscriptRecorder(id = "t", startedMs = 0, engine = "test", existing = listOf(1L to "舊的"))
        assertFalse(r.update(listOf(Triple(1L, null, "舊的")), nowMs = 1_000, recording = true))
        assertTrue(r.update(listOf(Triple(1L, null, "舊的，新的")), nowMs = 2_000, recording = true))
        assertEquals(1, r.transcript.lines.size)
    }
}
