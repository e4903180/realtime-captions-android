package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Letters people have already read should not jump between people (ADR-036). */
class SpeakerStabilityTest {
    private val a = floatArrayOf(1f, 0.05f, 0f)
    private val a2 = floatArrayOf(0.95f, 0f, 0.1f)
    private val b = floatArrayOf(0f, 1f, 0.05f)
    private val b2 = floatArrayOf(0.1f, 0.95f, 0f)
    private val c = floatArrayOf(0f, 0.05f, 1f)
    private val c2 = floatArrayOf(0.05f, 0f, 0.95f)
    private fun id(i: Int) = SpeakerId(i)

    @Test
    fun linesFromAnEarlierSessionAreNotRegrouped() {
        val asm = CaptionAssembler()
        asm.onEvent(TranscriptEvent.Listening)
        asm.onEvent(TranscriptEvent.Final("一", id(0), voice = a))
        asm.onEvent(TranscriptEvent.Final("二", id(2), voice = a2)) // live mistake in session 1
        asm.onEvent(TranscriptEvent.Listening) // stop, start again
        asm.onEvent(TranscriptEvent.Final("三", id(0), voice = a))
        asm.onEvent(TranscriptEvent.Final("四", id(1), voice = b))
        val out = asm.recluster(0.5f).lines.map { it.speaker }
        assertEquals(listOf(id(0), id(2), id(0), id(1)), out)
    }

    @Test
    fun aBiggerGroupCannotTakeAnotherGroupsLetter() {
        // The C voice was wrongly shown as A twice before; its group (3 lines) must not take
        // letter A from the real A group, which would swap both people's letters.
        val voices = listOf(a, c, a2, c2, c)
        val current = listOf(id(0), id(0), id(0), id(0), id(2))
        val live = listOf(id(0), id(2), id(0), id(2), id(2))
        val out = SpeakerRecluster.relabel(voices, current, 0.5f, live = live)
        assertEquals(listOf(id(0), id(2), id(0), id(2), id(2)), out)
    }

    @Test
    fun frozenLinesOnlyMoveAsAWholeLetter() {
        // Old lines 0..3: A, B, A, B. Line 1 alone looks like A now, but B's other old line does
        // not, so the old B lines keep their letter.
        val voices = listOf(a, a2, a, b, b2)
        val current = listOf(id(0), id(1), id(0), id(1), id(1))
        val frozen = listOf(true, true, true, true, false)
        val out = SpeakerRecluster.relabel(voices, current, 0.5f, frozen = frozen)
        assertEquals(listOf(id(0), id(1), id(0), id(1), id(1)), out)
    }

    @Test
    fun aSplitPersonIsMergedBackAsAWhole() {
        // One person shown as A and as D (all old lines): both letters' lines become A together.
        val voices = listOf(a, b, a2, b2)
        val current = listOf(id(0), id(1), id(3), id(1))
        val frozen = listOf(true, true, true, true)
        val out = SpeakerRecluster.relabel(voices, current, 0.5f, frozen = frozen)
        assertEquals(listOf(id(0), id(1), id(0), id(1)), out)
    }

    @Test
    fun givenNumberOfPeopleIsAMaximum() {
        // Three people expected, but the first three lines are A, A, B: A's lines still merge.
        val out = SpeakerRecluster.relabel(listOf(a, a2, b), listOf(id(0), id(1), id(2)), 0.5f, speakers = 3)
        assertEquals(out[0], out[1])
        assertEquals(2, out.toSet().size)
    }

    @Test
    fun newGroupDoesNotReuseALetterShownOnLinesOutsideTheRegrouping() {
        // Line 0 has no voice and shows B; a new group must not also be called B.
        val voices = listOf(null, a, c)
        val current = listOf(id(1), id(0), null)
        val out = SpeakerRecluster.relabel(voices, current, 0.5f)
        assertEquals(id(0), out[1])
        assertEquals(id(2), out[2])
    }

    @Test
    fun shortLineWithoutVoiceGetsNoLetterAndIsNotJoined() {
        val asm = CaptionAssembler(mergeGapSec = 1.5, clockSec = { 0.0 })
        asm.onEvent(TranscriptEvent.Final("你今天要去嗎", id(0), voice = a))
        asm.onEvent(TranscriptEvent.Final("好", null))
        assertEquals(2, asm.state.lines.size)
        assertNull(asm.state.lines[1].speaker)
    }
}
