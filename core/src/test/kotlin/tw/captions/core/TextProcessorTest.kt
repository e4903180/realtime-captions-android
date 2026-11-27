package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TextProcessorTest {
    @Test
    fun removesSpacesBetweenChineseCharacters() {
        assertEquals("今天天氣很好", WhitespaceNormalizer.apply(" 今 天  天氣 很好 "))
    }

    @Test
    fun spacesBetweenChineseAndLatin() {
        assertEquals("我用 Android 手機", WhitespaceNormalizer.apply("我用Android手機"))
        assertEquals("我用 Android 手機", WhitespaceNormalizer.apply("我用 Android 手機"))
    }

    @Test
    fun keepsEnglishSpacing() {
        assertEquals("hello world", WhitespaceNormalizer.apply("hello   world"))
    }

    @Test
    fun fullWidthPunctuationAfterChinese() {
        assertEquals("好，走吧？", ChinesePunctuation.apply("好, 走吧?"))
        assertEquals("ok, go?", ChinesePunctuation.apply("ok, go?"))
    }

    @Test
    fun correctionDictionaryPrefersLongerKeys() {
        val dict = CorrectionDictionary(mapOf("小明" to "曉明", "王小明" to "王曉明"))
        assertEquals("王曉明和曉明", dict.apply("王小明和小明"))
    }

    @Test
    fun defaultPipelineUsesConverter() {
        val processor = TextProcessor.default(converter = { it.replace("语音", "語音") })
        assertEquals("語音，測試", processor.process("语 音,測 試"))
    }
}
