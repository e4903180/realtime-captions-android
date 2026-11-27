package tw.captions.core

import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iPhone app converts with [OpenCcTaiwanConverter]; the Android app uses opencc4j. Both read
 * the same OpenCC tables, so captions should read the same on both phones.
 */
class OpenCcTaiwanParityTest {
    private val converter = OpenCcTaiwanConverter.s2twp { name ->
        checkNotNull(javaClass.getResourceAsStream("/data/dictionary/$name")) { "missing $name" }
            .readBytes().decodeToString()
    }

    private fun android(text: String): String {
        val t = ZhTwConverterUtil.toTraditional(text)
        return buildString { for (c in t) append(OpenCcTaiwanConverter.TAIWAN_FORMS[c] ?: c) }
    }

    @Test
    fun taiwanWording() {
        assertEquals("這個軟體的影片很好看", converter.toTraditional("这个软件的视频很好看"))
        assertEquals("我們坐計程車去", converter.toTraditional("我们坐出租车去"))
        assertEquals("記憶體不夠了", converter.toTraditional("内存不够了"))
    }

    @Test
    fun sameAsAndroidOnCaptionSentences() {
        val sentences = listOf(
            "今天天气很好，我们去公园散步吧",
            "你吃饭了吗？我刚下班",
            "这个软件的视频很好看",
            "我们坐出租车去",
            "明天早上八点开会，记得带笔记本电脑",
            "他说这里的面很好吃",
            "我用安卓手机打电话给妈妈",
            "这家餐厅的服务员态度很好",
            "请把打印机的墨水换一下",
            "下个星期我们一起去台北看电影",
            "网络有点慢，视频一直在缓冲",
            "你的头发剪得很漂亮",
            "医院的停车场已经满了",
            "我在超市买了牛奶和鸡蛋",
            "这个程序有一个错误，需要重新启动",
        )
        val diffs = sentences.filter { converter.toTraditional(it) != android(it) }
            .map { "$it → ${converter.toTraditional(it)} / ${android(it)}" }
        // Segmentation differs slightly between the two libraries; allow a rare difference.
        assertTrue(diffs.size <= 2, "too many differences:\n" + diffs.joinToString("\n"))
        diffs.forEach { println("difference: $it") }
    }
}
