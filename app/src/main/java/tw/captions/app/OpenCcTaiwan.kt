package tw.captions.app

import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import tw.captions.core.ChineseScriptConverter

/**
 * Simplified → Traditional with Taiwan phrasing, like OpenCC s2twp (软件→軟體, 视频→影片, 出租车→計程車),
 * for engines that output Simplified Chinese (the sherpa-onnx models).
 */
object OpenCcTaiwan : ChineseScriptConverter {
    // opencc4j's Taiwan table still uses a few forms that are uncommon in Taiwan.
    private val taiwanForms = mapOf('裏' to '裡', '着' to '著', '麪' to '麵', '爲' to '為', '綫' to '線', '衆' to '眾')

    override fun toTraditional(text: String): String {
        if (text.isBlank()) return text
        val t = ZhTwConverterUtil.toTraditional(text)
        return buildString(t.length) { for (c in t) append(taiwanForms[c] ?: c) }
    }
}
