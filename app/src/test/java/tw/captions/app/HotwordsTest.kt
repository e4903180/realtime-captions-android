package tw.captions.app

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.captions.app.engine.Hotwords

class HotwordsTest {
    @Test
    fun chineseCharactersAreSpacedOut() {
        assertEquals("林 怡 君", Hotwords.spaceCjk("林怡君"))
        assertEquals("饶 河 夜 市", Hotwords.spaceCjk("饶河夜市"))
    }

    @Test
    fun latinWordsStayWholeAndAreSeparatedFromChinese() {
        assertEquals("iPhone", Hotwords.spaceCjk("iPhone"))
        assertEquals("台 北 101", Hotwords.spaceCjk("台北101"))
        assertEquals("我 的 iPhone", Hotwords.spaceCjk("我的iPhone"))
    }
}
