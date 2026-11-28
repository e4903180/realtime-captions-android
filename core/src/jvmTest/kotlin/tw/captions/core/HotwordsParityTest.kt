package tw.captions.core

import com.github.houbb.opencc4j.util.ZhConverterUtil
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iPhone app builds hotwords with [Hotwords] and [OpenCcSimplifier]; the Android app with
 * opencc4j (ADR-038). The hotwords file must come out the same, or a name boosted on one phone
 * would be an unknown token on the other.
 */
class HotwordsParityTest {
    private fun table(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/data/dictionary/$name")) { "missing $name" }.readBytes().decodeToString()

    private val simplifier = OpenCcSimplifier(table("STCharacters.txt"), table("TWVariants.txt"))

    @Test
    fun namesComeOutLikeAndroid() {
        val words = listOf(
            "林怡君", "陳冠宇", "劉德華", "臺灣大學", "台北車站", "鄭家豪", "張惠妹", "蕭敬騰", "嚴爵", "盧廣仲",
            "黃韻玲", "謝和弦", "賴清德", "蘇貞昌", "鍾鎮濤", "龔詩嘉", "顏寬恆", "羅志祥", "饒河街", "關渡",
            "豐原", "鹿港", "淡水", "麵線", "啟德", "萬華", "鶯歌", "雲林", "彰化", "嘉義",
        )
        for (w in words) {
            val android = Hotwords.spaceCjk(ZhConverterUtil.toSimple(w))
            assertEquals(android, Hotwords.lines(listOf(w), simplifier).single(), w)
        }
    }

    @Test
    fun spacedPerCharacter() {
        assertEquals(listOf("林 怡 君", "台 湾 大 学", "iPhone 手 机"), Hotwords.lines(listOf("林怡君", "臺灣大學", "iPhone手機", " ", "林怡君"), simplifier))
    }

    @Test
    fun vocabScoresFollowOrder() {
        assertEquals("<blk>\t0.0\n<unk>\t0.0\n▁林\t-1.0\n▁\t-2.0", Hotwords.bpeVocab(listOf("<blk>", "<unk>", "▁林", "▁")))
        assertEquals("▁林", Hotwords.tokenPiece("▁林 1234"))
    }

    @Test
    fun parseSplitsLinesAndCommas() {
        assertEquals(listOf("小明", "阿華", "台北"), Hotwords.parse("小明\n阿華，台北、小明\n\n"))
    }
}
