package tw.captions.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpeakerSyncTest {
    private fun v(vararg x: Float) = floatArrayOf(*x)

    @Test
    fun replacedProfilesDecideTheNextLine() {
        val c = SpeakerClusterer(threshold = 0.5f)
        assertEquals(SpeakerId(0), c.assign(v(1f, 0f, 0f)))
        assertEquals(SpeakerId(1), c.assign(v(0f, 1f, 0f)))
        // Regrouping decided: the first voice is C, the second stays B.
        c.replaceProfiles(mapOf(SpeakerId(2) to SpeakerClusterer.Profile(v(1f, 0f, 0f), 3), SpeakerId(1) to SpeakerClusterer.Profile(v(0f, 1f, 0f), 2)))
        assertEquals(2, c.speakerCount)
        assertEquals(SpeakerId(2), c.peek(v(0.9f, 0.1f, 0f)))
        assertEquals(SpeakerId(2), c.assign(v(0.9f, 0.1f, 0f)))
        assertEquals(SpeakerId(1), c.assign(v(0.1f, 0.9f, 0f)))
        // A new voice takes the smallest free letter.
        assertEquals(SpeakerId(0), c.assign(v(0f, 0f, 1f)))
    }

    @Test
    fun joinedLinesKeepAWeightedVoice() {
        val long = v(1f, 0f)
        val short = v(0f, 1f)
        val blended = assertNotNull(blendVoices(long, 9, short, 1))
        // Mostly the long utterance's voice.
        assertEquals(true, blended[0] > 0.98f && blended[1] in 0.05f..0.2f)
        assertContentEquals(short, blendVoices(null, 5, short, 1))
        assertContentEquals(long, blendVoices(long, 5, null, 1))
    }

    @Test
    fun assemblerReportsProfilesPerSpeaker() {
        val a = CaptionAssembler()
        a.onEvent(TranscriptEvent.Final("今天天氣很好我們出去走走", SpeakerId(0), voice = v(1f, 0f)))
        a.onEvent(TranscriptEvent.Final("好", SpeakerId(1), voice = v(0f, 1f)))
        a.onEvent(TranscriptEvent.Final("電視的聲音", null, background = true, voice = v(1f, 1f)))
        val p = a.speakerProfiles()
        assertEquals(setOf(SpeakerId(0), SpeakerId(1)), p.keys)
        assertEquals(1, p.getValue(SpeakerId(0)).lines)
        assertContentEquals(v(1f, 0f), p.getValue(SpeakerId(0)).voice)
    }
}
