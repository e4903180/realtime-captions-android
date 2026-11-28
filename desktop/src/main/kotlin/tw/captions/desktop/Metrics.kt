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
    // Same list as TAIWAN_FORMS in tools/whisper-bench/bench.py: keep the two in sync.
    private val taiwanForms = mapOf('裏' to '裡', '着' to '著', '麪' to '麵', '爲' to '為', '綫' to '線', '衆' to '眾')

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

/** How numbers are written before scoring. Engines and transcribers mix 一百二十 and 120 freely. */
enum class NumberForm {
    /** Score the text as written (raw CER). */
    AS_IS,
    /** Chinese numerals become Arabic digits (一百二十三 → 123, 二〇二六 → 2026); each digit is one token. */
    ARABIC,
    /** Arabic digits become Chinese digits one by one (2026 → 二零二六, 〇 → 零); simple, but 10 ≠ 十. */
    CHINESE_DIGITS,
}

private val CHINESE_DIGIT = mapOf(
    '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)
private val CHINESE_UNIT = mapOf('十' to 10L, '百' to 100L, '千' to 1000L, '萬' to 10_000L, '億' to 100_000_000L)
private const val DIGIT_NAMES = "零一二三四五六七八九"

/**
 * Value of a run of Chinese numeral characters as Arabic digits: 一百零五 → 105, 兩千三 → 2300
 * (spoken shorthand for 2300), 十五 → 15, 三萬五 → 35000; runs without units are read digit by
 * digit (一一九 → 119).
 */
fun chineseNumberToArabic(run: String): String {
    if (run.none { it in CHINESE_UNIT }) return run.map { CHINESE_DIGIT.getValue(it) }.joinToString("")
    var total = 0L
    var section = 0L
    var num = 0L
    var lastUnit = 0L
    for (c in run) {
        val d = CHINESE_DIGIT[c]
        when {
            d != null -> num = d.toLong()
            c == '萬' || c == '億' -> {
                val u = CHINESE_UNIT.getValue(c)
                total = if (c == '億') (total + section + num) * u else total + (section + num) * u
                section = 0
                num = 0
                lastUnit = u
            }
            else -> {
                val u = CHINESE_UNIT.getValue(c)
                section += (if (num == 0L) 1L else num) * u
                num = 0
                lastUnit = u
            }
        }
    }
    // 一百五 means 150 and 三萬五 35000: a trailing digit right after a unit ≥ 百 is one place lower.
    val tail = if (num > 0 && lastUnit >= 100 && run.length >= 2 && run[run.length - 2] in CHINESE_UNIT) num * (lastUnit / 10) else num
    return (total + section + tail).toString()
}

/** Rewrites numbers in [text] into one [form] so that 120 and 一百二十 score the same. */
fun normalizeNumbers(text: String, form: NumberForm): String {
    if (form == NumberForm.AS_IS) return text
    // Full-width digits and thousands separators first: １２０ → 120, 1,000 → 1000.
    val t = buildString(text.length) {
        for ((i, c) in text.withIndex()) {
            val ascii = if (c in '０'..'９') '0' + (c - '０') else c
            val sep = ascii == ',' && i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()
            if (!sep) append(ascii)
        }
    }
    return when (form) {
        NumberForm.CHINESE_DIGITS -> buildString(t.length) {
            for (c in t) append(if (c in '0'..'9') DIGIT_NAMES[c - '0'] else if (c == '〇') '零' else c)
        }
        else -> buildString(t.length) {
            var i = 0
            while (i < t.length) {
                val c = t[i]
                // A run may not start with 百/千/萬/億 (萬一, 千萬別 are words, not numbers).
                if (c in CHINESE_DIGIT || c == '十') {
                    var j = i
                    while (j < t.length && (t[j] in CHINESE_DIGIT || t[j] in CHINESE_UNIT)) j++
                    append(chineseNumberToArabic(t.substring(i, j)))
                    i = j
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

/**
 * Splits text into scoring tokens: every CJK character is a token, every Latin word/number is a
 * token, punctuation and spaces are dropped. Gives CER for Chinese, WER for English and a mixed
 * error rate for code-switched speech. With [numbers] other than [NumberForm.AS_IS], numbers are
 * first rewritten into one form; with [NumberForm.ARABIC] every digit is its own token, so a wrong
 * digit costs one error like a wrong character.
 */
fun scoringTokens(text: String, numbers: NumberForm = NumberForm.AS_IS): List<String> {
    if (numbers == NumberForm.ARABIC) {
        val spaced = buildString { for (c in normalizeNumbers(text, numbers)) if (c in '0'..'9') append(' ').append(c).append(' ') else append(c) }
        return scoringTokens(spaced)
    }
    if (numbers == NumberForm.CHINESE_DIGITS) return scoringTokens(normalizeNumbers(text, numbers))
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
