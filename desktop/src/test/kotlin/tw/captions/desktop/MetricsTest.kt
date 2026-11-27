package tw.captions.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsTest {
    @Test
    fun tokensAreCharsForChineseAndWordsForEnglish() {
        assertEquals(listOf("我", "用", "google", "meet", "開", "會"), scoringTokens("我用 Google Meet 開會。"))
        assertEquals(listOf("can", "i", "pay", "by", "card"), scoringTokens("Can I pay by card?"))
    }

    @Test
    fun editDistanceCountsSubstitutionsInsertionsAndDeletions() {
        assertEquals(0, editDistance(scoringTokens("今天好"), scoringTokens("今天，好")))
        assertEquals(1, editDistance(scoringTokens("今天好"), scoringTokens("今天很好")))
        assertEquals(2, editDistance(scoringTokens("今天好"), scoringTokens("明天")))
    }

    @Test
    fun convertsSimplifiedToTaiwanTraditional() {
        assertEquals("醫生你好，我這幾天一直咳嗽", OpenCcTaiwan.toTraditional("医生你好，我这几天一直咳嗽"))
        assertEquals("請問今天哪裡不舒服", OpenCcTaiwan.toTraditional("请问今天哪里不舒服"))
    }

    private fun u(text: String, spk: Int?, start: Double, end: Double) = Utterance(text, spk, start, end, start, end)

    @Test
    fun speakerAccuracyIgnoresLabelNames() {
        val segs = listOf(Segment(0.0, 2.0, "A", "一二三"), Segment(3.0, 5.0, "B", "四五六"), Segment(6.0, 8.0, "A", "七八九"))
        assertEquals(1.0, speakerAccuracy(listOf(u("一二三", 1, 0.2, 2.5), u("四五六", 0, 3.2, 5.5), u("七八九", 1, 6.2, 8.5)), segs))
        assertEquals(2.0 / 3, speakerAccuracy(listOf(u("一二三", 0, 0.2, 2.5), u("四五六", 0, 3.2, 5.5), u("七八九", 0, 6.2, 8.5)), segs), 1e-9)
        assertEquals(2.0 / 3, speakerAccuracy(listOf(u("一二三", 0, 0.2, 2.5), u("四五六", null, 3.2, 5.5), u("七八九", 0, 6.2, 8.5)), segs), 1e-9)
    }

    @Test
    fun mergedTurnsAreSplitByTime() {
        val segs = listOf(Segment(0.0, 2.0, "A", "一二三"), Segment(2.5, 4.5, "B", "四五六"))
        // One line covering both turns, labelled with one speaker: about half right.
        val acc = speakerAccuracy(listOf(u("一二三四五六", 0, 0.0, 4.6)), segs)
        assertEquals(0.5, acc, 0.1)
    }

    @Test
    fun percentileInterpolates() {
        assertEquals(2.5, percentile(listOf(1.0, 2.0, 3.0, 4.0), 50.0))
    }
}
