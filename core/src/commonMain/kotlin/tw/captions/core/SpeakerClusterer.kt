package tw.captions.core

import kotlin.math.sqrt

/**
 * Online speaker clustering for live captions.
 *
 * Each finished utterance gives one speaker embedding. The embedding joins the most similar
 * known speaker if the cosine similarity is at least [threshold], otherwise it starts a new
 * speaker (up to [maxSpeakers]; after that it joins the closest one). Speaker centroids are
 * running means, so a speaker's voice profile improves as they talk more.
 */
class SpeakerClusterer(
    private val threshold: Float = 0.5f,
    private val maxSpeakers: Int = 8,
) {
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    val speakerCount: Int get() = centroids.size

    fun reset() {
        centroids.clear()
        counts.clear()
    }

    /**
     * Assigns [embedding] to a speaker and updates that speaker's profile. With [allowNew] = false
     * (e.g. for very short lines, whose embeddings are unreliable) it always joins the closest
     * known speaker instead of starting a new one.
     */
    fun assign(embedding: FloatArray, allowNew: Boolean = true): SpeakerId {
        val e = normalized(embedding)
        val (best, sim) = closest(e)
        if (best >= 0 && (sim >= threshold || centroids.size >= maxSpeakers || !allowNew)) {
            update(best, e)
            return SpeakerId(best)
        }
        centroids += e
        counts += 1
        return SpeakerId(centroids.size - 1)
    }

    /**
     * Best guess for [embedding] without changing any profile, e.g. for a provisional label on a
     * partial result. Returns the id a new speaker would get if nobody is similar enough.
     */
    fun peek(embedding: FloatArray): SpeakerId {
        val (best, sim) = closest(normalized(embedding))
        return if (best >= 0 && (sim >= threshold || centroids.size >= maxSpeakers)) SpeakerId(best) else SpeakerId(centroids.size)
    }

    /** Cosine similarity to every known speaker, in id order (for diagnostics and tuning). */
    fun similarities(embedding: FloatArray): List<Float> {
        val e = normalized(embedding)
        return centroids.map { dot(it, e) }
    }

    private fun closest(e: FloatArray): Pair<Int, Float> {
        var best = -1
        var bestSim = -2f
        for (i in centroids.indices) {
            val s = dot(centroids[i], e)
            if (s > bestSim) {
                bestSim = s
                best = i
            }
        }
        return best to bestSim
    }

    private fun update(i: Int, e: FloatArray) {
        val n = counts[i]
        val c = centroids[i]
        for (k in c.indices) c[k] = (c[k] * n + e[k]) / (n + 1)
        centroids[i] = normalized(c)
        counts[i] = n + 1
    }

    private companion object {
        fun dot(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "embedding size ${b.size} != ${a.size}" }
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun normalized(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            val norm = sqrt(n)
            return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
        }
    }
}
