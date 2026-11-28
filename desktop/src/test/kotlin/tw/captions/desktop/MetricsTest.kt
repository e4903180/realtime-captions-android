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
        // Taiwan phrasing (OpenCC s2twp), not only character conversion.
        assertEquals("軟體 影片 計程車 網路 資訊", OpenCcTaiwan.toTraditional("软件 视频 出租车 网络 信息"))
        assertEquals("我想在家裡煮麵", OpenCcTaiwan.toTraditional("我想在家里煮面"))
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
    fun chineseNumeralsBecomeArabic() {
        assertEquals("123", chineseNumberToArabic("一百二十三"))
        assertEquals("105", chineseNumberToArabic("一百零五"))
        assertEquals("15", chineseNumberToArabic("十五"))
        assertEquals("20", chineseNumberToArabic("二十"))
        assertEquals("2026", chineseNumberToArabic("二〇二六"))
        assertEquals("119", chineseNumberToArabic("一一九"))
        assertEquals("2300", chineseNumberToArabic("兩千三"))
        assertEquals("150", chineseNumberToArabic("一百五"))
        assertEquals("35000", chineseNumberToArabic("三萬五"))
        assertEquals("120000", chineseNumberToArabic("十二萬"))
        assertEquals("120000000", chineseNumberToArabic("一億二千萬"))
    }

    @Test
    fun numbersAreNormalizedBothWays() {
        assertEquals("我要買3張票，總共1200元", normalizeNumbers("我要買三張票，總共一千兩百元", NumberForm.ARABIC))
        assertEquals("1000元", normalizeNumbers("1,000元", NumberForm.ARABIC))
        assertEquals("120號", normalizeNumbers("１２０號", NumberForm.ARABIC))
        // 萬一 and 千萬 are words, not numbers: a run never starts with 百/千/萬/億.
        assertEquals("萬1", normalizeNumbers("萬一", NumberForm.ARABIC))
        assertEquals("二零二六年", normalizeNumbers("2026年", NumberForm.CHINESE_DIGITS))
        assertEquals("二零二六年", normalizeNumbers("二〇二六年", NumberForm.CHINESE_DIGITS))
        assertEquals("一百二十", normalizeNumbers("一百二十", NumberForm.AS_IS))
    }

    @Test
    fun normalizedCerIgnoresHowNumbersAreWritten() {
        val ref = "我住在三樓，電話是一一九，房租一萬兩千"
        val hyp = "我住在3樓，電話是119，房租12000"
        assertEquals(true, editDistance(scoringTokens(ref), scoringTokens(hyp)) > 0)
        assertEquals(0, editDistance(scoringTokens(ref, NumberForm.ARABIC), scoringTokens(hyp, NumberForm.ARABIC)))
        // Each digit is one token, so a wrong digit is one error like a wrong character.
        assertEquals(listOf("1", "2", "0", "元"), scoringTokens("一百二十元", NumberForm.ARABIC))
        assertEquals(1, editDistance(scoringTokens("一百二十元", NumberForm.ARABIC), scoringTokens("130元", NumberForm.ARABIC)))
        // Digit-by-digit Chinese: 2026 = 二〇二六, but 10 and 十 still differ.
        assertEquals(0, editDistance(scoringTokens("二〇二六", NumberForm.CHINESE_DIGITS), scoringTokens("2026", NumberForm.CHINESE_DIGITS)))
    }

    @Test
    fun taiwanFormsMatchThePythonBench() {
        // tools/whisper-bench/bench.py TAIWAN_FORMS has the same entries.
        val py = java.io.File("../tools/whisper-bench/bench.py").takeIf { it.isFile } ?: return
        val line = py.readLines(Charsets.UTF_8).first { it.startsWith("TAIWAN_FORMS") }
        val pairs = Regex("\"(.)\": \"(.)\"").findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(6, pairs.size)
        for ((from, to) in pairs) assertEquals(to, OpenCcTaiwan.toTraditional(from))
    }

    @Test
    fun percentileInterpolates() {
        assertEquals(2.5, percentile(listOf(1.0, 2.0, 3.0, 4.0), 50.0))
    }
}
