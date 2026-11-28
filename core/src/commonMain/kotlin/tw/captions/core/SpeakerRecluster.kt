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
     * Groups merge while their average similarity is at least [threshold]; with [speakers] > 0
     * they also keep merging until there are at most that many people.
     *
     * Letters: each group takes the letter its lines were first shown with ([live], the label the
     * live grouping gave when the line finished; defaults to [current]), matching the biggest
     * overlaps first, so a group can never take another group's letter just because it is bigger.
     * Groups left over get letters no line uses.
     *
     * Lines marked in [frozen] (older lines the reader has already taken in) only change together
     * with every other frozen line of the same letter: a whole person can be renamed (one person
     * split in two, merged back), but single old lines never jump between people.
     */
    fun relabel(
        voices: List<FloatArray?>,
        current: List<SpeakerId?>,
        threshold: Float = DEFAULT_THRESHOLD,
        speakers: Int = 0,
        live: List<SpeakerId?> = current,
        frozen: List<Boolean>? = null,
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
            // A given number of people is a maximum: similar groups still merge below it.
            val done = best < threshold && (speakers <= 0 || groups.size <= speakers)
            if (done) break
            groups[bi].addAll(groups[bj])
            groups.removeAt(bj)
        }

        // Letters: biggest overlap between a group and a first-shown letter wins, one letter per group.
        val overlaps = mutableListOf<Triple<Int, Int, Int>>() // group, letter, lines
        groups.forEachIndexed { g, members ->
            members.mapNotNull { (live.getOrNull(idx[it]) ?: current[idx[it]])?.index }
                .groupingBy { it }.eachCount()
                .forEach { (letter, count) -> overlaps += Triple(g, letter, count) }
        }
        overlaps.sortWith(compareByDescending<Triple<Int, Int, Int>> { it.third }.thenBy { it.second })
        val label = IntArray(groups.size) { -1 }
        val used = mutableSetOf<Int>()
        for ((g, letter, _) in overlaps) {
            if (label[g] >= 0 || letter in used) continue
            label[g] = letter
            used += letter
        }
        // Letters still shown on lines outside the regrouping stay reserved for their people.
        val reserved = current.filterIndexed { i, _ -> voices[i] == null }.mapNotNull { it?.index }.toSet()
        var next = 0
        for (g in groups.indices.sortedByDescending { groups[it].size }) if (label[g] < 0) {
            while (next in used || next in reserved) next++
            label[g] = next
            used += next
        }
        val proposed = current.toMutableList()
        idx.forEachIndexed { k, line -> proposed[line] = SpeakerId(label[groupOf(groups, k)]) }
        if (frozen == null) return proposed

        // Frozen lines move only as a whole letter: all of that letter's frozen lines agree.
        val moves = mutableMapOf<SpeakerId?, MutableSet<SpeakerId?>>()
        for (i in current.indices) if (frozen.getOrElse(i) { false } && voices[i] != null) {
            moves.getOrPut(current[i]) { mutableSetOf() } += proposed[i]
        }
        val out = proposed.toMutableList()
        for (i in current.indices) if (frozen.getOrElse(i) { false } && voices[i] != null) {
            val targets = moves[current[i]]!!
            out[i] = if (targets.size == 1) targets.first() else current[i]
        }
        return out
    }

    /** Similarity two groups need to count as one person (average linkage); see docs/benchmark-speakers.md. */
    const val DEFAULT_THRESHOLD = 0.45f

    private fun groupOf(groups: List<List<Int>>, k: Int): Int = groups.indexOfFirst { k in it }

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
