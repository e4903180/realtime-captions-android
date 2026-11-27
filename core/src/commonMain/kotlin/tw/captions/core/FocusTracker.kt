package tw.captions.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Tells the people the user is talking with apart from background talkers (a TV, the next table).
 *
 * The people in the conversation are usually the loudest speech at the phone. The tracker keeps
 * the level of recent conversation lines; a new line more than [marginDb] quieter than that, or
 * quieter than [minDb] overall, is marked as background. Background lines never update the
 * reference level or the speaker profiles.
 *
 * It cannot help when nobody is talking to the user and only a TV is on: then the TV is the
 * loudest speech and counts as foreground.
 */
class FocusTracker(
    private val marginDb: Float = 8f,
    private val minDb: Float = -55f,
    private val history: Int = 12,
) {
    private val recent = ArrayDeque<Float>()

    /** Typical level of the conversation (dBFS), once at least one line has been accepted. */
    val foregroundDb: Float?
        get() = if (recent.isEmpty()) null else recent.sorted().let { it[((it.size - 1) * 0.75f).toInt()] }

    fun isBackground(db: Float): Boolean {
        if (db < minDb) return true
        val fg = foregroundDb ?: return false
        return db < fg - marginDb
    }

    /** Records a finished line; returns true if it was background. */
    fun observe(db: Float): Boolean {
        val bg = isBackground(db)
        if (!bg) {
            recent.addLast(db)
            while (recent.size > history) recent.removeFirst()
        }
        return bg
    }

    fun reset() = recent.clear()

    companion object {
        /** RMS level of [samples] in dBFS. */
        fun levelDb(samples: FloatArray): Float {
            if (samples.isEmpty()) return -120f
            var s = 0.0
            for (x in samples) s += x * x
            return (20 * log10(sqrt(s / samples.size) + 1e-9)).toFloat()
        }
    }
}
