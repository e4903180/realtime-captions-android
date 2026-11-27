package tw.captions.app.engine

/**
 * What the engine list shows for each engine. Numbers come from the Taiwan-accent benchmarks
 * (docs/benchmark-engines.md); keep them in this one place so they are easy to update.
 */
data class EngineInfo(
    val id: String,
    val name: String,
    /** One line under the name: what it is good for and what it needs. */
    val subtitle: String,
    val offline: Boolean,
    val paid: Boolean = false,
    val speakerTags: Boolean = false,
    val recommended: Boolean = false,
    /** Offline engines: model packs that must be downloaded first. */
    val packs: List<ModelStore.Pack> = emptyList(),
    /** Cloud engines: needs an API key from the user (see settings). */
    val needsKey: Boolean = false,
)

object EngineCatalog {
    val ALL = listOf(
        EngineInfo(
            SherpaOnnxEngine.Variant.X160.id, "離線＋說話者",
            "不用網路、免費，約 1 秒出字，會標示 A／B／C 說話者。台灣腔錯字率約 12%。",
            offline = true, speakerTags = true, recommended = true,
            packs = SherpaOnnxEngine.Variant.X160.packs,
        ),
        EngineInfo(
            SherpaOnnxEngine.Variant.X480.id, "離線＋說話者（省電）",
            "準確度和上面相同、運算量約一半，但字慢約 0.1 秒出現。適合較舊的手機。",
            offline = true, speakerTags = true,
            packs = SherpaOnnxEngine.Variant.X480.packs,
        ),
        EngineInfo(
            "android-online", "手機內建（連線）",
            "使用手機內建的 Google 語音辨識，需要網路，不分說話者。",
            offline = false,
        ),
        EngineInfo(
            "android-offline", "手機內建（離線）",
            "使用手機的離線語音包，要先在系統設定下載中文（台灣），不分說話者。",
            offline = true,
        ),
    )

    fun byId(id: String): EngineInfo? = ALL.firstOrNull { it.id == id }

    val DEFAULT_ID: String = SherpaOnnxEngine.Variant.X160.id
}
