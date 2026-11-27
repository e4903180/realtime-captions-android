package tw.captions.core

/**
 * Second recognition pass (ADR-025): after a line is finished, a whole-sentence model hears the
 * same audio again with the full sentence as context and its text replaces the live one.
 *
 * Whole-sentence models sometimes drop words on noisy audio or repeat themselves; [choose] keeps
 * the live text in those cases.
 */
object SecondPass {
    /** Returns the text to show instead of [live], or null to keep [live]. */
    fun choose(live: String, second: String): String? {
        val a = letters(live)
        val b = letters(second)
        if (b == 0) return null
        if (a >= 4 && b < a / 2) return null
        if (b > 2 * a + 4) return null
        return second.trim()
    }

    private fun letters(text: String): Int = text.count { it.isLetterOrDigit() || it.isCjk() }
}
