package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest {
    @Test
    fun srtTimes() {
        assertEquals("00:00:00,000", Transcript.srtTime(0.0))
        assertEquals("01:02:03,450", Transcript.srtTime(3723.45))
    }

    @Test
    fun srtEndsOneSecondAfterButNotPastNextLine() {
        val t = Transcript(
            "x", startedMs = 0, endedMs = 10_000, engine = "e",
            lines = listOf(
                Transcript.Line(1, "A", "你好", 1.0, 1.2),
                Transcript.Line(2, null, "晚餐吃什麼", 2.0, 4.0),
            ),
        )
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nA：你好\n\n" +
                "2\n00:00:02,000 --> 00:00:05,000\n晚餐吃什麼\n\n",
            t.srt(),
        )
    }

    @Test
    fun plainTextUsesClock() {
        val t = Transcript("x", startedMs = 1000, endedMs = 5000, engine = "e", lines = listOf(Transcript.Line(1, "媽媽", "吃飯了", 2.0, 2.5)))
        assertEquals("標題\n\n[t3000] 媽媽：吃飯了\n", t.plainText("標題") { "t$it" })
    }

    @Test
    fun recorderSkipsOldLinesUnlessTheyChange() {
        val r = TranscriptRecorder("x", startedMs = 0, engine = "e", existing = listOf(1L to "舊的"))
        assertFalse(r.update(listOf(Triple(1L, null, "舊的")), nowMs = 1000, recording = true))
        assertTrue(r.update(listOf(Triple(1L, null, "舊的"), Triple(2L, "A", "新的")), nowMs = 2000, recording = true))
        assertEquals(listOf("新的"), r.transcript.lines.map { it.text })
        // A later correction updates the same line.
        assertTrue(r.update(listOf(Triple(2L, "媽媽", "新的句子")), nowMs = 3000, recording = true))
        assertEquals(Transcript.Line(2, "媽媽", "新的句子", 2.0, 3.0), r.transcript.lines.single())
        assertEquals(3000, r.transcript.endedMs)
        // Cleared screen: nothing removed from the transcript.
        assertFalse(r.update(emptyList(), nowMs = 4000, recording = true))
        assertEquals(1, r.transcript.lines.size)
    }

    @Test
    fun searchMatchesTitleAndText() {
        val t = Transcript("x", title = "家庭聚餐", startedMs = 0, endedMs = 0, engine = "e", lines = listOf(Transcript.Line(1, null, "明天去高雄", 0.0, 0.0)))
        assertTrue(t.matches("聚餐"))
        assertTrue(t.matches("高雄"))
        assertFalse(t.matches("台北"))
        assertTrue(t.matches(" "))
    }
}
