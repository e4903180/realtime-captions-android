package tw.captions.core

/**
 * The user's 自訂詞彙 as sherpa-onnx hotwords for X-ASR (ADR-038). Shared helpers; the Android app
 * still has its own copy (app/.../engine/Hotwords.kt, with opencc4j), the iPhone app uses these.
 *
 * X-ASR writes Simplified Chinese and its BPE has one token per Chinese character with a word
 * start mark ("▁林"). So each word is converted to Simplified and its characters are spaced out
 * ("林怡君" → "林 怡 君"); written together, everything after the first character would be an
 * unknown token and the boost would do nothing (docs/benchmark-hotwords.md).
 */
object Hotwords {
    /** Bonus per token; 4.0 got the most names right with no false insertions in the benchmark. */
    const val SCORE = 4.0f

    /** Most words kept, like the Android app. */
    const val MAX_WORDS = 200

    /** The words typed by the user: one per line (commas also split), trimmed, no repeats. */
    fun parse(text: String): List<String> =
        text.split('\n', ',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_WORDS)

    /** Hotwords file lines: each word in Simplified, characters spaced out. Empty when there are no words. */
    fun lines(words: List<String>, simplifier: OpenCcSimplifier): List<String> =
        words.map { it.trim() }.filter { it.isNotEmpty() }.map { spaceCjk(simplifier.convert(it)) }.distinct()

    /**
     * BPE vocabulary ("piece<TAB>score" lines) from the pieces of tokens.txt, in order. The release
     * archives ship bpe.model but no bpe.vocab. In bpe.model the special and Chinese pieces up to
     * `<unk>` score 0 and the learned pieces after it score lower and lower; BPE only uses the
     * order, so 0 then -1, -2, … encodes exactly like the real scores. All zeros would tie "▁" with
     * "▁林" and break every Chinese hotword.
     */
    fun bpeVocab(tokens: List<String>): String {
        var after = false
        var k = 0
        return tokens.joinToString("\n") { t ->
            val score = if (after) -(++k).toFloat() else 0f
            if (t == "<unk>") after = true
            "$t\t$score"
        }
    }

    /** The piece of one tokens.txt line ("▁林 1234" → "▁林"), or null for a malformed line. */
    fun tokenPiece(line: String): String? = line.substringBeforeLast(' ').takeIf { it.isNotEmpty() && ' ' in line }

    /** Puts a space between Chinese characters (and between Chinese and other text). */
    fun spaceCjk(word: String): String = buildString {
        for ((i, c) in word.withIndex()) {
            if (i > 0 && c != ' ' && last() != ' ' && (isHan(c) || isHan(word[i - 1]))) append(' ')
            append(c)
        }
    }

    internal fun isHan(c: Char): Boolean =
        c in '㐀'..'䶿' || c in '一'..'鿿' || c in '豈'..'﫿'
}

/**
 * Traditional → Simplified, one character at a time, from the same OpenCC tables the app already
 * bundles for Simplified → Taiwan (STCharacters, TWVariants), read backwards. Enough for hotwords,
 * which are spaced out per character anyway. A character that is itself a Simplified entry is kept.
 */
class OpenCcSimplifier(stCharacters: String, twVariants: String) {
    private val map: Map<Char, Char>

    init {
        val st = OpenCcDictionary.parse(stCharacters)
        val simplified = st.mapNotNull { (s, _) -> s.singleOrNull() }.toSet()
        val m = HashMap<Char, Char>()
        // Taiwan variants first (啟 → 启), then the general Traditional forms (陳 → 陈).
        for ((s, t) in OpenCcDictionary.parse(twVariants) + st) {
            val sc = s.singleOrNull() ?: continue
            for (form in t.split(' ')) {
                val tc = form.singleOrNull() ?: continue
                if (tc != sc && tc !in simplified && tc !in m) m[tc] = sc
            }
        }
        map = m
    }

    fun convert(text: String): String = buildString { for (c in text) append(map[c] ?: c) }
}
