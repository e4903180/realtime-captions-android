package tw.captions.core

/**
 * Proof-reading finished caption lines with a language model (ADR-026). The platform sends
 * [SYSTEM_PROMPT] and [userMessage] to the model and asks for JSON matching [JSON_SCHEMA]; every
 * corrected line goes through [accept] before it replaces the caption.
 */
object Proofreading {
    const val SYSTEM_PROMPT =
        "你是即時字幕的校稿員。字幕是語音辨識自動產生的，常見問題是同音字或近音字寫錯、詞被聽錯、標點不對。\n" +
            "請依照上下文修正「待校稿」裡的每一行：\n" +
            "- 只修正明顯的辨識錯誤（錯字、聽錯的詞、標點）。不確定時保留原文。\n" +
            "- 不要改寫語氣、不要摘要、不要加入原文沒有的內容，也不要刪掉內容；口語的語助詞可以保留。\n" +
            "- 用台灣繁體中文和台灣用語；英文單字照原樣保留。\n" +
            "- 每一行分開回傳，id 和輸入相同；不用改的行也照原文回傳。"

    /** Structured-output schema: {"lines": [{"id": 3, "text": "..."}]}. */
    const val JSON_SCHEMA =
        """{"type":"object","properties":{"lines":{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"text":{"type":"string"}},"required":["id","text"],"additionalProperties":false}}},"required":["lines"],"additionalProperties":false}"""

    /** At most this many earlier lines are sent as context. */
    const val CONTEXT_LINES = 4

    /** At most this many lines are corrected per request. */
    const val MAX_LINES = 6

    /**
     * The user turn: earlier lines for context (not to be corrected), then the lines to correct,
     * each as "id｜speaker｜text".
     */
    fun userMessage(context: List<String>, lines: List<ProofreadLine>): String = buildString {
        if (context.isNotEmpty()) {
            appendLine("前文（不用改）：")
            context.forEach { appendLine(it) }
            appendLine()
        }
        appendLine("待校稿（id｜說話者｜文字）：")
        for (l in lines) appendLine("${l.id}｜${l.speaker ?: "?"}｜${l.text}")
    }

    /**
     * Whether [corrected] may replace [original]: not blank, and not so different that the model
     * probably rewrote or summarised the line instead of fixing recognition errors.
     */
    fun accept(original: String, corrected: String): Boolean {
        val a = original.trim()
        val b = corrected.trim()
        if (b.isEmpty() || a == b) return false
        val ka = keep(a)
        val kb = keep(b)
        if (ka.isEmpty()) return false
        val changed = editDistance(ka, kb)
        return changed <= maxOf(2, (ka.length * 0.35).toInt())
    }

    /** Letters and digits only: punctuation changes are always fine. */
    private fun keep(s: String) = s.filter { it.isLetterOrDigit() || it.isCjk() }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** One line to proof-read: [id] is [CaptionAssembler.lineId]. */
data class ProofreadLine(val id: Long, val speaker: String?, val text: String)
