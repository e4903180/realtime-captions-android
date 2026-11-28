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

    class Files(val file: File, val vocab: File)

    /** Writes the hotwords file (and the BPE vocabulary, built from tokens.txt) next to the model, or null when there are no words. */
    fun prepare(models: ModelStore, prefix: String, words: List<String>): Files? {
        val lines = words.map { it.trim() }.filter { it.isNotEmpty() }.map { spaceCjk(ZhConverterUtil.toSimple(it)) }.distinct()
        if (lines.isEmpty()) return null
        val vocab = models.file("$prefix-bpe.vocab")
        if (!vocab.isFile) {
            // The release archives have bpe.model but no bpe.vocab. With every hotword spaced into
            // single tokens the scores do not matter, so the token list with score 0 is enough.
            val tokens = models.file("$prefix-tokens.txt").readLines(Charsets.UTF_8)
            vocab.writeText(tokens.mapNotNull { it.substringBeforeLast(' ').takeIf { p -> p.isNotEmpty() } }.joinToString("\n") { "$it\t0.0" }, Charsets.UTF_8)
        }
        val file = models.file("$prefix-hotwords.txt")
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        return Files(file, vocab)
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
