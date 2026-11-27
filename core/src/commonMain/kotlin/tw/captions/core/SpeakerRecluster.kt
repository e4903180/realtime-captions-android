package tw.captions.core

import kotlin.math.sqrt

/**
 * Second look at who said what (ADR-027). Live clustering ([SpeakerClusterer]) decides each line's
 * speaker once, from what it has heard so far, so an early mistake sticks and one person can be
 * split in two. After every finished line this groups all lines again, from all their voice
 * profiles at once (average-linkage agglomerative clustering on cosine similarity), and keeps the
 * letters people already see wherever the groups agree with them.
 */
object SpeakerRecluster {
    /**
     * New speaker per line. [voices] has one entry per line (null: no profile, keeps [current]).
     * [weights] (seconds of speech) let long lines count more. With [speakers] > 0 the lines are
     * grouped into exactly that many people; otherwise groups merge while their average
     * similarity is at least [threshold].
     */
    fun relabel(
        voices: List<FloatArray?>,
        current: List<SpeakerId?>,
        threshold: Float = 0.45f,
        speakers: Int = 0,
    ): List<SpeakerId?> {
        val idx = voices.indices.filter { voices[it] != null }
        if (idx.size < 2) return current
        val v = idx.map { normalized(voices[it]!!) }
        val n = v.size
        val sim = Array(n) { i -> FloatArray(n) { j -> dot(v[i], v[j]) } }

        // Average linkage: similarity between groups = mean pairwise similarity.
        val groups = MutableList(n) { mutableListOf(it) }
        fun link(a: List<Int>, b: List<Int>): Float {
            var s = 0f
            for (i in a) for (j in b) s += sim[i][j]
            return s / (a.size * b.size)
        }
        while (groups.size > 1) {
            var bi = -1
            var bj = -1
            var best = -2f
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val l = link(groups[i], groups[j])
                if (l > best) { best = l; bi = i; bj = j }
            }
            val done = if (speakers > 0) groups.size <= speakers else best < threshold
            if (done) break
            groups[bi].addAll(groups[bj])
            groups.removeAt(bj)
        }

        // Keep the letters people already see: give each group the label most of its lines had,
        // biggest groups first; groups left over get unused labels.
        val groupOf = IntArray(n)
        groups.forEachIndexed { g, members -> members.forEach { groupOf[it] = g } }
        val order = groups.indices.sortedByDescending { groups[it].size }
        val used = mutableSetOf<Int>()
        val label = IntArray(groups.size) { -1 }
        for (g in order) {
            val votes = groups[g].mapNotNull { current[idx[it]]?.index }.filter { it !in used }
            val pick = votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (pick != null) { label[g] = pick; used += pick }
        }
        var next = 0
        for (g in order) if (label[g] < 0) {
            while (next in used) next++
            label[g] = next
            used += next
        }
        val out = current.toMutableList()
        idx.forEachIndexed { k, line -> out[line] = SpeakerId(label[groupOf[k]]) }
        return out
    }

    private fun normalized(x: FloatArray): FloatArray {
        var s = 0f
        for (a in x) s += a * a
        val n = sqrt(s).takeIf { it > 0f } ?: 1f
        return FloatArray(x.size) { x[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
