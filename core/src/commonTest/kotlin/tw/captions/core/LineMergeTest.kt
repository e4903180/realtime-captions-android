package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineMergeTest {
    private var now = 0.0
    private fun assembler(gap: Double? = 1.5, maxChars: Int = 80) =
        CaptionAssembler(mergeGapSec = gap, maxMergedChars = maxChars, clockSec = { now })

    private fun CaptionAssembler.say(text: String, speaker: Int?, startAt: Double, endAt: Double): CaptionState {
        now = startAt
        onEvent(TranscriptEvent.Partial(text.take(1), speaker?.let { SpeakerId(it) }))
        now = endAt
        return onEvent(TranscriptEvent.Final(text, speaker?.let { SpeakerId(it) }))
    }

    @Test
    fun sameSpeakerShortPauseJoinsLine() {
        val a = assembler()
        a.say("今天天氣很好", 0, 0.0, 1.5)
        val s = a.say("我們去散步吧", 0, 2.2, 3.5)
        assertEquals(listOf("今天天氣很好 我們去散步吧"), s.lines.map { it.text })
    }

    @Test
    fun otherSpeakerOrLongPauseStartsNewLine() {
        val a = assembler()
        a.say("你好", 0, 0.0, 1.0)
        a.say("你好啊", 1, 1.5, 2.5)          // other speaker
        val s = a.say("最近好嗎", 1, 6.0, 7.0) // long pause
        assertEquals(listOf("你好", "你好啊", "最近好嗎"), s.lines.map { it.text })
    }

    @Test
    fun partialShowsItContinuesTheLine() {
        val a = assembler()
        a.say("第一句", 0, 0.0, 1.0)
        now = 1.4
        assertTrue(a.onEvent(TranscriptEvent.Partial("第", null)).continuesLastLine)
        now = 1.8
        assertTrue(a.onEvent(TranscriptEvent.Partial("第二", SpeakerId(0))).continuesLastLine)
        assertFalse(a.onEvent(TranscriptEvent.Partial("第二句", SpeakerId(1))).continuesLastLine)
    }

    @Test
    fun longLinesStopGrowing() {
        val a = assembler(maxChars = 10)
        a.say("一二三四五六七八九十", 0, 0.0, 1.0)
        val s = a.say("再來", 0, 1.2, 2.0)
        assertEquals(2, s.lines.size)
    }

    @Test
    fun offByDefault() {
        val a = CaptionAssembler(clockSec = { now })
        a.say("一", 0, 0.0, 1.0)
        assertEquals(2, a.say("二", 0, 1.1, 2.0).lines.size)
    }

    @Test
    fun aLineThatStartedOnItsOwnDoesNotJumpBackUp() {
        val a = assembler()
        a.say("第一句", 0, 0.0, 1.0)
        now = 5.0 // long pause: new line
        assertFalse(a.onEvent(TranscriptEvent.Partial("第", SpeakerId(0))).continuesLastLine)
        now = 5.5
        assertFalse(a.onEvent(TranscriptEvent.Partial("第二", SpeakerId(0))).continuesLastLine)
    }
}
