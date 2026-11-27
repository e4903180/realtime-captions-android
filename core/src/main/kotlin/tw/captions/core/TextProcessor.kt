package tw.captions.core

/** One step of caption text clean-up. Must be pure and fast: it runs on every partial result. */
fun interface TextStep {
    fun apply(text: String): String
}

/** Runs [steps] in order. */
class TextProcessor(private val steps: List<TextStep>) {
    fun process(text: String): String = steps.fold(text) { acc, step -> step.apply(acc) }

    companion object {
        fun default(
            converter: ChineseScriptConverter = ChineseScriptConverter.None,
            corrections: Map<String, String> = emptyMap(),
        ) = TextProcessor(
            listOf(
                WhitespaceNormalizer,
                converter.asStep(),
                CorrectionDictionary(corrections),
                ChinesePunctuation,
            ),
        )
    }
}

internal fun Char.isCjk(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.BOPOMOFO
}

/**
 * Removes the spaces some engines put between Chinese characters ("今 天 好" -> "今天好"),
 * keeps a single space between Chinese and Latin text ("我用Android" -> "我用 Android").
 */
object WhitespaceNormalizer : TextStep {
    override fun apply(text: String): String {
        val collapsed = text.trim().replace(Regex("\\s+"), " ")
        val sb = StringBuilder()
        for (i in collapsed.indices) {
            val c = collapsed[i]
            if (c == ' ') {
                val prev = collapsed.getOrNull(i - 1)
                val next = collapsed.getOrNull(i + 1)
                if (prev != null && next != null && prev.isCjk() && next.isCjk()) continue
                sb.append(c)
                continue
            }
            val prev = sb.lastOrNull()
            if (prev != null && prev != ' ' && (prev.isCjk() && c.isLetterOrDigit() && !c.isCjk() ||
                    c.isCjk() && prev.isLetterOrDigit() && !prev.isCjk())
            ) {
                sb.append(' ')
            }
            sb.append(c)
        }
        return sb.toString()
    }
}

/** Uses full-width punctuation next to Chinese text ("好,走吧?" -> "好，走吧？"). */
object ChinesePunctuation : TextStep {
    private val map = mapOf(',' to '，', '?' to '？', '!' to '！', ':' to '：', ';' to '；')

    override fun apply(text: String): String {
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            val full = map[c]
            val prev = text.getOrNull(i - 1)
            if (full != null && prev != null && prev.isCjk()) {
                sb.append(full)
                // drop a space directly after a converted mark
                continue
            }
            if (c == ' ' && sb.lastOrNull()?.let { it in map.values } == true) continue
            sb.append(c)
        }
        return sb.toString()
    }
}

/** User dictionary for words the engine keeps getting wrong (e.g. family names). */
class CorrectionDictionary(corrections: Map<String, String>) : TextStep {
    // Longest keys first so that longer phrases win over their substrings.
    private val entries = corrections.entries
        .filter { it.key.isNotEmpty() }
        .sortedByDescending { it.key.length }

    override fun apply(text: String): String {
        if (entries.isEmpty()) return text
        var result = text
        for ((wrong, right) in entries) result = result.replace(wrong, right)
        return result
    }
}

/** Converts Simplified Chinese to Traditional (Taiwan). The app plugs in a real converter, e.g. OpenCC. */
fun interface ChineseScriptConverter {
    fun toTraditional(text: String): String

    fun asStep(): TextStep = TextStep { toTraditional(it) }

    companion object {
        val None = ChineseScriptConverter { it }
    }
}
