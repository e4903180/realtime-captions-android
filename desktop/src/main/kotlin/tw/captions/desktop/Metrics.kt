package tw.captions.desktop

import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import tw.captions.core.ChineseScriptConverter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simplified → Traditional with Taiwan phrasing, like OpenCC s2twp (软件→軟體, 视频→影片, 出租车→計程車),
 * using opencc4j's Taiwan tables.
 */
object OpenCcTaiwan : ChineseScriptConverter {
    // opencc4j's Taiwan table still uses a few forms that are uncommon in Taiwan.
    private val taiwanForms = mapOf('裏' to '裡', '着' to '著', '麪' to '麵')

    override fun toTraditional(text: String): String {
        if (text.isBlank()) return text
        val t = ZhTwConverterUtil.toTraditional(text)
        return buildString(t.length) { for (c in t) append(taiwanForms[c] ?: c) }
    }
}

/** Reads a 16 kHz mono 16-bit PCM wav file. */
fun readWav16k(file: File): FloatArray {
    val bytes = file.readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "$file is not a wav file" }
    var pos = 12
    var channels = 0
    var rate = 0
    var bits = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val len = bb.getInt(pos + 4)
        if (id == "fmt ") {
            channels = bb.getShort(pos + 10).toInt()
            rate = bb.getInt(pos + 12)
            bits = bb.getShort(pos + 22).toInt()
        } else if (id == "data") {
            require(channels == 1 && rate == 16000 && bits == 16) { "$file: need 16 kHz mono 16-bit, got $rate Hz, $channels ch, $bits bit" }
            val n = minOf(len, bytes.size - pos - 8) / 2
            return FloatArray(n) { bb.getShort(pos + 8 + it * 2) / 32768f }
        }
        pos += 8 + len + (len and 1)
    }
    error("$file has no data chunk")
}

/**
 * Splits text into scoring tokens: every CJK character is a token, every Latin word/number is a
 * token, punctuation and spaces are dropped. Gives CER for Chinese, WER for English and a mixed
 * error rate for code-switched speech.
 */
fun scoringTokens(text: String): List<String> {
    val out = ArrayList<String>()
    val word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) out += word.toString().lowercase()
        word.clear()
    }
    for (c in text) {
        val block = Character.UnicodeBlock.of(c)
        val cjk = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        when {
            cjk -> {
                flush()
                out += c.toString()
            }
            c.isLetterOrDigit() || c == '\'' -> word.append(c)
            else -> flush()
        }
    }
    flush()
    return out
}

/** Levenshtein distance between token lists. */
fun editDistance(a: List<String>, b: List<String>): Int {
    var prev = IntArray(b.size + 1) { it }
    var cur = IntArray(b.size + 1)
    for (i in 1..a.size) {
        cur[0] = i
        for (j in 1..b.size) {
            val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
        }
        val t = prev
        prev = cur
        cur = t
    }
    return prev[b.size]
}

data class Segment(val start: Double, val end: Double, val speaker: String, val text: String)

/** One recognized utterance as the caption screen showed it. */
data class Utterance(
    val text: String,
    val speaker: Int?,
    /** Audio time of the first partial (≈ when the engine noticed speech). */
    val audioStart: Double,
    /** Audio time at which the final result was produced. */
    val audioEnd: Double,
    /** Wall-clock times relative to stream start, as a listener would see them. */
    val firstPartialAt: Double,
    val finalAt: Double,
)

/** Index of the truth segment that overlaps [u] most, or -1. */
fun matchSegment(u: Utterance, segments: List<Segment>): Int {
    var best = -1
    var bestOverlap = 0.0
    for ((i, s) in segments.withIndex()) {
        val ov = minOf(u.audioEnd, s.end + 1.0) - maxOf(u.audioStart, s.start - 0.3)
        if (ov > bestOverlap) {
            bestOverlap = ov
            best = i
        }
    }
    return best
}

/**
 * Speaker-label accuracy: fraction of recognized tokens whose speaker label is right, after
 * mapping engine speaker ids to true speakers one-to-one in the best possible way (labels are
 * anonymous, so "A" vs "B" naming does not matter). Utterances without a label count as wrong.
 * An utterance that runs over several turns (e.g. two people talking without a pause) is split
 * across those turns in proportion to time, so merging two speakers into one line is penalized.
 */
fun speakerAccuracy(utterances: List<Utterance>, segments: List<Segment>): Double {
    val weights = HashMap<Pair<Int, String>, Double>()
    var total = 0.0
    for (u in utterances) {
        val w = scoringTokens(u.text).size.toDouble()
        if (w == 0.0) continue
        val overlaps = segments.map { s -> maxOf(0.0, minOf(u.audioEnd, s.end + 1.0) - maxOf(u.audioStart, s.start - 0.3)) }
        val sum = overlaps.sum()
        if (sum <= 0.0) continue
        total += w
        val hyp = u.speaker ?: continue
        for ((i, ov) in overlaps.withIndex()) {
            if (ov <= 0.0) continue
            val key = hyp to segments[i].speaker
            weights[key] = (weights[key] ?: 0.0) + w * ov / sum
        }
    }
    if (total == 0.0) return Double.NaN
    val hyps = weights.keys.map { it.first }.distinct()
    val truths = segments.map { it.speaker }.distinct()
    var best = 0.0
    fun search(k: Int, used: Set<String>, acc: Double) {
        if (k == hyps.size) {
            best = maxOf(best, acc)
            return
        }
        search(k + 1, used, acc) // this engine speaker maps to nobody
        for (t in truths) if (t !in used) search(k + 1, used + t, acc + (weights[hyps[k] to t] ?: 0.0))
    }
    search(0, emptySet(), 0.0)
    return best / total
}

fun percentile(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    val idx = (p / 100.0 * (s.size - 1))
    val lo = idx.toInt()
    val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (s[hi] - s[lo]) * (idx - lo)
}
