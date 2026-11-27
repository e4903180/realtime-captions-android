package tw.captions.core

/**
 * Simplified → Traditional Chinese with Taiwan phrasing (软件→軟體, 视频→影片, 出租车→計程車), in the
 * spirit of OpenCC's s2twp, written in plain Kotlin so every platform gets the same output.
 *
 * It reads the OpenCC dictionary files that the opencc4j jar ships (the .txt files in data/dictionary), the same
 * tables the Android app converts with. In that set the Taiwan tables (TWPhrases, TWVariants) take
 * Simplified keys, so they come first and win over the general STPhrases/STCharacters tables:
 * longest match first, and on equal length the earlier table wins. A second pass fixes characters
 * that a longer ST phrase turned into a form Taiwan does not use (启动 → 啓動 → 啟動).
 * A test in jvmTest checks the output against opencc4j on typical caption sentences.
 */
class OpenCcTaiwanConverter(private val stages: List<OpenCcDictionary>) : ChineseScriptConverter {

    override fun toTraditional(text: String): String {
        if (text.isBlank()) return text
        var t = text
        for (stage in stages) t = stage.convert(t)
        return buildString(t.length) { for (c in t) append(TAIWAN_FORMS[c] ?: c) }
    }

    companion object {
        /** Dictionary files used, highest priority first. */
        val FILES = listOf("TWPhrases.txt", "TWVariants.txt", "STPhrases.txt", "STCharacters.txt")

        /** Builds the converter; [read] returns the contents of one dictionary file from [FILES]. */
        fun s2twp(read: (fileName: String) -> String): OpenCcTaiwanConverter {
            val tw = read("TWPhrases.txt")
            val twVariants = read("TWVariants.txt")
            val stCharacters = read("STCharacters.txt")
            val main = OpenCcDictionary(listOf(tw, twVariants, read("STPhrases.txt"), stCharacters))
            // TWVariants maps Simplified → Taiwan form (启 → 啟); STCharacters gives the general
            // Traditional form (启 → 啓). Map the general form to the Taiwan one as well.
            val st = OpenCcDictionary(listOf(stCharacters))
            val fix = OpenCcDictionary.parse(twVariants).mapNotNull { (simplified, taiwan) ->
                val general = st.convert(simplified)
                if (general != taiwan && general.length == 1) "$general\t$taiwan" else null
            }.joinToString("\n")
            return OpenCcTaiwanConverter(listOf(main, OpenCcDictionary(listOf(fix))))
        }

        /** A few forms the s2twp tables still produce that are uncommon in Taiwan (same as the Android app). */
        val TAIWAN_FORMS = mapOf('裏' to '裡', '着' to '著', '麪' to '麵', '爲' to '為', '綫' to '線', '衆' to '眾')
    }
}

/**
 * One conversion stage: OpenCC dictionary lines ("key<TAB>value [alternatives]"; a few files use a
 * space instead of the tab). The first value wins. When several files define the same key, the
 * earlier file wins, like OpenCC's dictionary groups.
 */
class OpenCcDictionary(fileContents: List<String>) {
    private val map = HashMap<String, String>()
    private val keyLengths: IntArray

    init {
        for (content in fileContents) {
            for ((key, value) in parse(content)) if (key !in map) map[key] = value
        }
        keyLengths = map.keys.map { it.length }.distinct().sortedDescending().toIntArray()
    }

    val size: Int get() = map.size

    companion object {
        /** (key, first value) pairs of one dictionary file. */
        fun parse(content: String): List<Pair<String, String>> = content.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r', '\n')
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val sep = line.indexOf('\t').takeIf { it > 0 } ?: line.indexOf(' ').takeIf { it > 0 } ?: return@mapNotNull null
            val value = line.substring(sep + 1).trim().substringBefore(' ')
            if (value.isEmpty()) null else line.substring(0, sep) to value
        }.toList()
    }

    /** Longest match first, left to right; characters with no entry are kept. */
    fun convert(text: String): String {
        if (map.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in keyLengths) {
                if (i + len > text.length) continue
                val value = map[text.substring(i, i + len)] ?: continue
                sb.append(value)
                i += len
                matched = true
                break
            }
            if (!matched) {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }
}
