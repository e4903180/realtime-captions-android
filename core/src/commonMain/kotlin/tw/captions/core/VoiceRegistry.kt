package tw.captions.core

import kotlin.math.sqrt

/**
 * People the user has taught the app to recognise ("認聲音"): a name and a voice profile
 * (speaker embedding). When anyone is registered, every finished caption line is compared with
 * them; lines from nobody registered are marked as background (TV, other tables), which the
 * caption screen can dim or hide.
 */
class VoiceRegistry(
    /** Cosine similarity needed to say "this is that person". */
    private val threshold: Float = 0.5f,
) {
    data class Person(val id: Int, val name: String, val profile: FloatArray) {
        override fun equals(other: Any?) = other is Person && other.id == id
        override fun hashCode() = id
    }

    private val list = mutableListOf<Person>()
    private var nextId = 0

    val people: List<Person> get() = list.toList()
    val isEmpty: Boolean get() = list.isEmpty()

    /** Adds a person, or refines an existing one's profile when [name] is already registered. */
    fun add(name: String, embedding: FloatArray): Person {
        val e = normalized(embedding)
        val existing = list.indexOfFirst { it.name == name }
        if (existing >= 0) {
            val p = list[existing]
            val merged = normalized(FloatArray(e.size) { p.profile[it] + e[it] })
            return p.copy(profile = merged).also { list[existing] = it }
        }
        return Person(nextId++, name, e).also { list += it }
    }

    fun remove(id: Int) {
        list.removeAll { it.id == id }
    }

    fun rename(id: Int, name: String) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(name = name)
    }

    fun clear() = list.clear()

    /** Restores saved people (e.g. from app settings). */
    fun restore(saved: List<Person>) {
        list.clear()
        list += saved
        nextId = (saved.maxOfOrNull { it.id } ?: -1) + 1
    }

    /** The registered person this voice belongs to, or null if it is nobody known. */
    fun match(embedding: FloatArray): Person? {
        if (list.isEmpty()) return null
        val e = normalized(embedding)
        var best: Person? = null
        var bestSim = -2f
        for (p in list) {
            var s = 0f
            for (i in e.indices) s += e[i] * p.profile[i]
            if (s > bestSim) {
                bestSim = s
                best = p
            }
        }
        return best?.takeIf { bestSim >= threshold }
    }

    private fun normalized(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val norm = sqrt(n)
        return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }
}
