package tw.captions.app.engine

import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.io.File

/**
 * The user's 自訂詞彙 as sherpa-onnx hotwords for X-ASR (ADR-038).
 *
 * X-ASR writes Simplified Chinese and its BPE has one token per Chinese character with a word
 * start mark ("▁林"). So each word is converted to Simplified and its characters are spaced out
 * ("林怡君" → "林 怡 君"); written together, everything after the first character would be an
 * unknown token and the boost would do nothing (docs/benchmark-hotwords.md).
 */
object Hotwords {
    /** Bonus per token; 4.0 got the most names right with no false insertions in the benchmark. */
    const val SCORE = 4.0f

    class Files(val file: File, val vocab: File, val lines: List<String>)

    /** Writes the hotwords file (and the BPE vocabulary, built from tokens.txt) next to the model, or null when there are no words. */
    fun prepare(models: ModelStore, prefix: String, words: List<String>): Files? {
        val lines = words.map { it.trim() }.filter { it.isNotEmpty() }.map { spaceCjk(ZhConverterUtil.toSimple(it)) }.distinct()
        if (lines.isEmpty()) return null
        // v2: scores in merge order (v1 had all 0.0, which split "▁林" into "▁" + unknown).
        val vocab = models.file("$prefix-bpe-v2.vocab")
        if (!vocab.isFile) {
            val tokens = models.file("$prefix-tokens.txt").readLines(Charsets.UTF_8)
                .mapNotNull { it.substringBeforeLast(' ').takeIf { p -> p.isNotEmpty() } }
            vocab.writeText(bpeVocab(tokens), Charsets.UTF_8)
        }
        val file = models.file("$prefix-hotwords.txt")
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        return Files(file, vocab, lines)
    }

    /**
     * BPE vocabulary ("piece<TAB>score" lines) from tokens.txt. The release archives ship bpe.model
     * but no bpe.vocab. In bpe.model the special and Chinese pieces up to `<unk>` score 0 and the
     * learned pieces after it score lower and lower; BPE only uses the order, so 0 then -1, -2, …
     * encodes exactly like the real scores (checked against sentencepiece's export on the benchmark).
     * All zeros would tie "▁" with "▁林" and break every Chinese hotword.
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

    /** Puts a space between Chinese characters (and between Chinese and other text). */
    fun spaceCjk(word: String): String = buildString {
        fun han(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        for ((i, c) in word.withIndex()) {
            if (i > 0 && c != ' ' && last() != ' ' && (han(c) || han(word[i - 1]))) append(' ')
            append(c)
        }
    }
}
