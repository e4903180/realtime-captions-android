package tw.captions.core

import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * Online speaker clustering for live captions.
 *
 * Each finished utterance gives one speaker embedding. The embedding joins the most similar
 * known speaker if the cosine similarity is at least [threshold], otherwise it starts a new
 * speaker (up to [maxSpeakers]; after that it joins the closest one). Speaker centroids are
 * running means, so a speaker's voice profile improves as they talk more.
 *
 * The caption screen may regroup finished lines afterwards ([SpeakerRecluster]) and change their
 * letters. [replaceProfiles] brings this clusterer in line with that result; without it the next
 * line would be compared with the old groups, get the old letter and be changed again a moment
 * later.
 */
@OptIn(ExperimentalStdlibApi::class)
class SpeakerClusterer(
    private val threshold: Float = 0.5f,
    private val maxSpeakers: Int = 8,
) {
    /** A speaker's voice profile and how many lines it was averaged from. */
    class Profile(val voice: FloatArray, val lines: Int)

    private val ids = mutableListOf<Int>()
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    /** Set from another thread by [replaceProfiles]; taken over at the next [assign] / [peek]. */
    @Volatile
    private var pending: Map<SpeakerId, Profile>? = null

    val speakerCount: Int get() = (pending?.size) ?: centroids.size

    fun reset() {
        pending = null
        ids.clear()
        centroids.clear()
        counts.clear()
    }

    /**
     * Replaces every speaker profile, e.g. with [CaptionAssembler.speakerProfiles] after lines
     * were regrouped. May be called from another thread than [assign]: the new profiles are used
     * from the next utterance on.
     */
    fun replaceProfiles(profiles: Map<SpeakerId, Profile>) {
        pending = profiles
    }

    private fun applyPending() {
        val p = pending ?: return
        pending = null
        ids.clear()
        centroids.clear()
        counts.clear()
        for ((id, profile) in p.entries.sortedBy { it.key.index }) {
            ids += id.index
            centroids += normalized(profile.voice)
            counts += profile.lines.coerceAtLeast(1)
        }
    }

    /** Smallest letter nobody has yet. */
    private fun nextId(): Int {
        var id = 0
        while (id in ids) id++
        return id
    }

    /**
     * Assigns [embedding] to a speaker and updates that speaker's profile. With [allowNew] = false
     * (e.g. for very short lines, whose embeddings are unreliable) it always joins the closest
     * known speaker instead of starting a new one.
     */
    fun assign(embedding: FloatArray, allowNew: Boolean = true): SpeakerId {
        applyPending()
        val e = normalized(embedding)
        val (best, sim) = closest(e)
        if (best >= 0 && (sim >= threshold || centroids.size >= maxSpeakers || !allowNew)) {
            update(best, e)
            return SpeakerId(ids[best])
        }
        val id = nextId()
        ids += id
        centroids += e
        counts += 1
        return SpeakerId(id)
    }

    /**
     * Best guess for [embedding] without changing any profile, e.g. for a provisional label on a
     * partial result. Returns the id a new speaker would get if nobody is similar enough.
     */
    fun peek(embedding: FloatArray): SpeakerId {
        applyPending()
        val (best, sim) = closest(normalized(embedding))
        return if (best >= 0 && (sim >= threshold || centroids.size >= maxSpeakers)) SpeakerId(ids[best]) else SpeakerId(nextId())
    }

    /** Cosine similarity to every known speaker, in id order (for diagnostics and tuning). */
    fun similarities(embedding: FloatArray): List<Float> {
        applyPending()
        val e = normalized(embedding)
        return centroids.indices.sortedBy { ids[it] }.map { dot(centroids[it], e) }
    }

    private fun closest(e: FloatArray): Pair<Int, Float> {
        var best = -1
        var bestSim = -2f
        for (i in centroids.indices) {
            if (centroids[i].size != e.size) continue
            val s = dot(centroids[i], e)
            if (s > bestSim) {
                bestSim = s
                best = i
            }
        }
        return best to bestSim
    }

    private fun update(i: Int, e: FloatArray) {
        // Cap the weight of history so a profile rebuilt from many lines can still follow the voice.
        val n = counts[i].coerceAtMost(50)
        val c = centroids[i]
        for (k in c.indices) c[k] = (c[k] * n + e[k]) / (n + 1)
        centroids[i] = normalized(c)
        counts[i] = counts[i] + 1
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
