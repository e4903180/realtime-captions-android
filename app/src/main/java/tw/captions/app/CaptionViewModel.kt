package tw.captions.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import android.util.Log
import java.io.File
import tw.captions.app.engine.AzureSpeechEngine
import tw.captions.app.settings.KeyVault
import tw.captions.app.engine.EngineCatalog
import tw.captions.app.engine.MicInput
import tw.captions.app.engine.ModelStore
import tw.captions.app.engine.SherpaOnnxEngine
import tw.captions.app.engine.WavFileInput
import tw.captions.core.CaptionAssembler
import tw.captions.core.TextProcessor
import tw.captions.core.CaptionState
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeechEngine

data class UiState(
    val captions: CaptionState = CaptionState(),
    val running: Boolean = false,
    val engineId: String = EngineCatalog.DEFAULT_ID,
    val fontSizeSp: Int = 36,
    /** People the app recognises (id to name), for the 認得的人 list. */
    val people: List<Pair<Int, String>> = emptyList(),
    /** Background-speech filter: off (default), dim, or hide. */
    val backgroundMode: tw.captions.core.BackgroundMode = tw.captions.core.BackgroundMode.OFF,
    /** 0..1 while the offline models are downloading, otherwise null. */
    val modelDownload: Float? = null,
    /** Which engine the running download is for. */
    val downloadingEngine: String? = null,
    /** Offline engines whose models are all on the phone. */
    val installedEngines: Set<String> = emptySet(),
    /** Disk space each installed offline engine would free if removed (bytes). */
    val freeableBytes: Map<String, Long> = emptyMap(),
    /** What downloading each offline engine would still fetch (shared models already present are not counted). */
    val downloadBytes: Map<String, Long> = emptyMap(),
    /** "••••1a2b" when an Azure key is stored. */
    val azureKeyHint: String? = null,
    val azureRegion: String = "eastasia",
    val cloudTesting: Boolean = false,
    val cloudTestMessage: String? = null,
    /** Seconds the current paid cloud session has been running, shown so users know what they spend. */
    val cloudSeconds: Int? = null,
    /** Debug builds: wav file played instead of the microphone. */
    val debugWav: String? = null,
    /** Paid cloud audio sent from this phone, by day (ADR-028). */
    val usage: tw.captions.core.CloudUsage = tw.captions.core.CloudUsage(),
    val usagePrices: tw.captions.core.CloudUsage.Prices = tw.captions.core.CloudUsage.Prices(),
    /** "yyyy-MM-dd" for the usage screen. */
    val today: String = "",
    /** The monthly cloud limit was reached and captions switched to the offline engine. */
    val usageLimitHit: Boolean = false,
)

class CaptionViewModel(app: Application) : AndroidViewModel(app) {

    private val models = ModelStore(app)

    /** Debug builds only: play this wav file instead of using the microphone (see README). */
    private var debugWav: File? = null

    private val registry = tw.captions.core.VoiceRegistry()

    val engines: List<SpeechEngine> = listOf(
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() }, variant = SherpaOnnxEngine.Variant.X160, registry = registry),
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() }, variant = SherpaOnnxEngine.Variant.X480, registry = registry),
        AzureSpeechEngine(
            credentials = { vault.get(KeyVault.AZURE_KEY)?.let { it to (vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia") } },
            openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() },
        ),
    )

    private val vault = KeyVault(app)

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val usageStore = tw.captions.app.settings.UsageStore(app)

    private val assembler = CaptionAssembler(TextProcessor.default(converter = OpenCcTaiwan))

    init {
        // Load the conversion tables off the main thread before the first caption needs them.
        viewModelScope.launch(Dispatchers.Default) { OpenCcTaiwan.toTraditional("预热") }
    }

    fun useDebugWav(file: File?) {
        if (!BuildConfig.DEBUG) return
        debugWav = file
        _ui.update { it.copy(debugWav = file?.name) }
    }
    private val _ui = MutableStateFlow(
        UiState(engineId = prefs.getString("engine", null)?.takeIf { id -> engines.any { it.id == id } } ?: EngineCatalog.DEFAULT_ID),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val mode = runCatching { tw.captions.core.BackgroundMode.valueOf(prefs.getString("background_mode", "OFF")!!) }.getOrDefault(tw.captions.core.BackgroundMode.OFF)
        assembler.backgroundMode = mode
        _ui.update { it.copy(backgroundMode = mode) }
        refreshInstalled()
        refreshKeys()
        loadPeople()
        _ui.update { it.copy(usage = usageStore.load(), usagePrices = usageStore.loadPrices(), today = usageStore.today()) }
    }

    // ---- Cloud usage (ADR-028): audio seconds sent to Azure, written every 10 s and on stop ----

    private var unloggedSeconds = 0

    private fun flushUsage() {
        val sec = unloggedSeconds
        unloggedSeconds = 0
        if (sec <= 0) return
        val today = usageStore.today()
        val next = _ui.value.usage.add(today, sec.toDouble())
        usageStore.save(next)
        _ui.update { it.copy(usage = next, today = today) }
        if (job != null && isPaid(_ui.value.engineId) && limitReached()) switchToOfflineForLimit()
    }

    /** The user chose to keep using the cloud past the limit; holds until the app is closed. */
    private var limitOverridden = false

    private fun isPaid(engineId: String) = EngineCatalog.byId(engineId)?.paid == true

    private fun limitReached() = !limitOverridden && _ui.value.usage.overLimit(usageStore.today(), _ui.value.usagePrices)

    /** Over the monthly limit: pause the cloud for now and continue with the offline engine (not saved as the user's choice). */
    private fun switchToOfflineForLimit() {
        stop()
        _ui.update { it.copy(engineId = EngineCatalog.DEFAULT_ID, usageLimitHit = true) }
        start()
    }

    /** From the limit notice: go back to the cloud engine and stop asking until the app is closed. */
    fun continueCloudAnyway() {
        limitOverridden = true
        _ui.update { it.copy(usageLimitHit = false) }
        selectEngine(AzureSpeechEngine.ID)
        if (job == null) start()
    }

    fun dismissLimitNotice() {
        _ui.update { it.copy(usageLimitHit = false) }
    }

    fun setUsagePrices(prices: tw.captions.core.CloudUsage.Prices) {
        usageStore.savePrices(prices)
        _ui.update { it.copy(usagePrices = prices) }
    }

    fun resetUsage() {
        usageStore.reset()
        _ui.update { it.copy(usage = tw.captions.core.CloudUsage(), today = usageStore.today()) }
    }

    // ---- 認聲音: people the user named ----

    private fun loadPeople() {
        val saved = runCatching {
            val arr = org.json.JSONArray(prefs.getString("people", "[]"))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val v = o.getJSONArray("voice")
                tw.captions.core.VoiceRegistry.Person(o.getInt("id"), o.getString("name"), FloatArray(v.length()) { v.getDouble(it).toFloat() })
            }
        }.getOrDefault(emptyList())
        registry.restore(saved)
        _ui.update { it.copy(people = registry.people.map { p -> p.id to p.name }) }
    }

    private fun savePeople() {
        val arr = org.json.JSONArray()
        registry.people.forEach { p ->
            arr.put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("voice", org.json.JSONArray(p.profile.map { it.toDouble() })))
        }
        prefs.edit().putString("people", arr.toString()).apply()
        _ui.update { it.copy(people = registry.people.map { p -> p.id to p.name }) }
    }

    /** "This line is <name>": learns the voice from that line. Voice profiles stay on the phone. */
    fun nameLine(index: Int, name: String) {
        val line = _ui.value.captions.lines.getOrNull(index) ?: return
        val voice = line.voice ?: return
        val first = registry.isEmpty
        registry.add(name.trim(), voice)
        savePeople()
        _ui.update { it.copy(captions = assembler.nameLine(index, name.trim())) }
        // Once someone is known, dim voices nobody registered (TV, other tables) unless the user chose otherwise.
        if (first && _ui.value.backgroundMode == tw.captions.core.BackgroundMode.OFF) setBackgroundMode(tw.captions.core.BackgroundMode.DIM)
    }

    fun forgetPerson(id: Int) {
        registry.remove(id)
        savePeople()
    }

    private fun setBackgroundMode(mode: tw.captions.core.BackgroundMode) {
        assembler.backgroundMode = mode
        prefs.edit().putString("background_mode", mode.name).apply()
        _ui.update { it.copy(backgroundMode = mode) }
    }

    private fun refreshKeys() {
        _ui.update {
            it.copy(
                azureKeyHint = vault.hint(KeyVault.AZURE_KEY),
                azureRegion = vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia",
            )
        }
    }



    fun saveAzure(key: String, region: String) {
        if (key.isNotEmpty()) vault.put(KeyVault.AZURE_KEY, key)
        vault.putPlain(KeyVault.AZURE_REGION, region)
        _ui.update { it.copy(cloudTestMessage = null) }
        refreshKeys()
        testAzure()
    }

    fun removeAzure() {
        if (_ui.value.engineId == AzureSpeechEngine.ID) stop()
        vault.remove(KeyVault.AZURE_KEY)
        _ui.update { it.copy(cloudTestMessage = null) }
        refreshKeys()
    }

    fun testAzure() {
        val key = vault.get(KeyVault.AZURE_KEY) ?: return
        val region = vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia"
        _ui.update { it.copy(cloudTesting = true, cloudTestMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = AzureSpeechEngine.testKey(key, region)
            _ui.update {
                it.copy(
                    cloudTesting = false,
                    cloudTestMessage = r.fold({ "可以使用：金鑰和區域都正確" }, { e -> "不能使用：${e.message ?: "連不上 Azure，請確認網路"}" }),
                )
            }
        }
    }

    /** Debug builds: import an Azure key from a file (AZURE_SPEECH_KEY=..., AZURE_SPEECH_REGION=...) for emulator tests. */
    fun importAzureEnv(file: File) {
        if (!BuildConfig.DEBUG || !file.isFile) return
        val env = file.readLines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        env["AZURE_SPEECH_KEY"]?.let { vault.put(KeyVault.AZURE_KEY, it) }
        env["AZURE_SPEECH_REGION"]?.let { vault.putPlain(KeyVault.AZURE_REGION, it) }
        file.delete()
        refreshKeys()
    }

    private var cloudTimer: Job? = null

    private val offlineEngines get() = engines.filterIsInstance<SherpaOnnxEngine>()

    /** Recomputes which offline engines are installed and what removing each would free. */
    private fun refreshInstalled() {
        val installed = offlineEngines.filter { models.isInstalled(it.packs) }
        val freeable = installed.associate { e ->
            val othersNeed = installed.filter { it !== e }.flatMap { it.packs }.toSet()
            e.id to e.packs.filter { it !in othersNeed }.sumOf { models.sizeOnDisk(it) }
        }
        val toDownload = offlineEngines.associate { e -> e.id to e.packs.filter { !models.isInstalled(it) }.sumOf { it.downloadBytes } }
        _ui.update { it.copy(installedEngines = installed.map { e -> e.id }.toSet(), freeableBytes = freeable, downloadBytes = toDownload) }
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val engine = engines.first { it.id == _ui.value.engineId }
        if (engine is SherpaOnnxEngine && !models.isInstalled(engine.packs)) {
            download(engine.id, startAfter = true)
            return
        }
        if (isPaid(engine.id) && limitReached()) {
            switchToOfflineForLimit()
            return
        }
        _ui.update { it.copy(running = true) }
        if (EngineCatalog.byId(engine.id)?.paid == true) {
            cloudTimer?.cancel()
            cloudTimer = viewModelScope.launch {
                var sec = 0
                while (true) {
                    _ui.update { it.copy(cloudSeconds = sec) }
                    kotlinx.coroutines.delay(1000)
                    sec++
                    unloggedSeconds++
                    if (unloggedSeconds >= 10) flushUsage()
                }
            }
        }
        lateinit var self: Job
        self = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.transcribe(RecognitionConfig(languageTag = "zh-TW")).collect { event ->
                    val captions = assembler.onEvent(event)
                    _ui.update { it.copy(captions = captions) }
                }
            } finally {
                // The engine stopped by itself (e.g. a fatal error), not via stop().
                if (job === self) {
                    job = null
                    markStopped()
                }
            }
        }
        job = self
        self.start()
    }

    private var download: Job? = null

    /** Downloads the models of an offline engine; starts captions afterwards if [startAfter]. */
    fun download(engineId: String, startAfter: Boolean = false) {
        val engine = offlineEngines.firstOrNull { it.id == engineId } ?: return
        if (download?.isActive == true) return
        _ui.update { it.copy(modelDownload = 0f, downloadingEngine = engineId, captions = it.captions.copy(error = null)) }
        download = viewModelScope.launch {
            try {
                models.ensure(engine.packs) { p -> _ui.update { it.copy(modelDownload = p) } }
                _ui.update { it.copy(modelDownload = null, downloadingEngine = null) }
                refreshInstalled()
                if (startAfter && _ui.value.engineId == engineId) start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(modelDownload = null, downloadingEngine = null) }
                throw e
            } catch (e: Exception) {
                Log.e("CaptionsModels", "download failed", e)
                _ui.update {
                    it.copy(modelDownload = null, downloadingEngine = null, captions = it.captions.copy(error = "模型下載失敗，請確認網路後再試一次"))
                }
            }
        }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    /**
     * Removes an offline engine's models. Packs another installed engine still uses (the shared
     * VAD / speaker / denoiser pack) stay until nothing needs them.
     */
    fun uninstall(engineId: String) {
        val engine = offlineEngines.firstOrNull { it.id == engineId } ?: return
        if (_ui.value.engineId == engineId) stop()
        val stillNeeded = offlineEngines.filter { it !== engine && models.isInstalled(it.packs) }.flatMap { it.packs }.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            engine.packs.filter { it !in stillNeeded }.forEach { models.delete(it) }
            refreshInstalled()
        }
    }

    fun stop() {
        if (_ui.value.downloadingEngine == _ui.value.engineId) download?.cancel()
        val current = job
        job = null
        current?.cancel()
        markStopped()
    }

    fun selectEngine(id: String) {
        val wasRunning = job != null
        stop()
        _ui.update { it.copy(engineId = id) }
        prefs.edit().putString("engine", id).apply()
        if (wasRunning) start()
    }

    private fun markStopped() {
        cloudTimer?.cancel()
        flushUsage()
        _ui.update { it.copy(running = false, cloudSeconds = null, captions = it.captions.copy(isListening = false)) }
    }

    fun clear() {
        assembler.reset()
        _ui.update { it.copy(captions = CaptionState(isListening = it.running)) }
    }

    /** Cycles the background filter: off → dim → hide → off. */
    fun toggleBackground() {
        val modes = tw.captions.core.BackgroundMode.entries
        val mode = modes[(modes.indexOf(_ui.value.backgroundMode) + 1) % modes.size]
        assembler.backgroundMode = mode
        prefs.edit().putString("background_mode", mode.name).apply()
        _ui.update { it.copy(backgroundMode = mode) }
    }

    fun changeFontSize(deltaSp: Int) {
        _ui.update { it.copy(fontSizeSp = (it.fontSizeSp + deltaSp).coerceIn(20, 72)) }
    }
}
