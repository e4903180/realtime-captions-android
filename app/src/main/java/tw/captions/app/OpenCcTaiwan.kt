package tw.captions.app

import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import tw.captions.core.ChineseScriptConverter

/** Simplified → Traditional (Taiwan) for engines that output Simplified Chinese (sherpa-onnx models). */
object OpenCcTaiwan : ChineseScriptConverter {
    // opencc4j's Taiwan table still uses a few forms that are uncommon in Taiwan.
    private val taiwanForms = mapOf('裏' to '裡', '着' to '著', '麪' to '麵')

    override fun toTraditional(text: String): String {
        if (text.isBlank()) return text
        val t = ZhTwConverterUtil.toTraditional(text)
        return buildString(t.length) { for (c in t) append(taiwanForms[c] ?: c) }
    }
}
