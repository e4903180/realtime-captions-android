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
import tw.captions.app.engine.AndroidSpeechEngine
import tw.captions.app.engine.AzureSpeechEngine
import tw.captions.app.engine.ServerSpeechEngine
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
    /** Hide background speech (TV, other tables) instead of showing it small and gray. */
    val hideBackground: Boolean = false,
    /** 0..1 while the offline models are downloading, otherwise null. */
    val modelDownload: Float? = null,
    /** Which engine the running download is for. */
    val downloadingEngine: String? = null,
    /** Offline engines whose models are all on the phone. */
    val installedEngines: Set<String> = emptySet(),
    /** Disk space each installed offline engine would free if removed (bytes). */
    val freeableBytes: Map<String, Long> = emptyMap(),
    /** "••••1a2b" when an Azure key is stored. */
    val azureKeyHint: String? = null,
    val azureRegion: String = "eastasia",
    val cloudTesting: Boolean = false,
    val cloudTestMessage: String? = null,
    /** Home caption server: address and a hint of its token. */
    val serverUrl: String = "",
    val serverTokenHint: String? = null,
    val serverTestMessage: String? = null,
    /** Seconds the current paid cloud session has been running, shown so users know what they spend. */
    val cloudSeconds: Int? = null,
    /** Debug builds: wav file played instead of the microphone. */
    val debugWav: String? = null,
)

class CaptionViewModel(app: Application) : AndroidViewModel(app) {

    private val models = ModelStore(app)

    /** Debug builds only: play this wav file instead of using the microphone (see README). */
    private var debugWav: File? = null

    val engines: List<SpeechEngine> = listOf(
        AndroidSpeechEngine(app, onDevice = false),
        AndroidSpeechEngine(app, onDevice = true),
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() }, variant = SherpaOnnxEngine.Variant.X160),
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() }, variant = SherpaOnnxEngine.Variant.X480),
        AzureSpeechEngine(
            credentials = { vault.get(KeyVault.AZURE_KEY)?.let { it to (vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia") } },
            openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() },
        ),
        ServerSpeechEngine(
            endpoint = { vault.getPlain(KeyVault.SERVER_URL)?.let { u -> vault.get(KeyVault.SERVER_TOKEN)?.let { u to it } } },
            openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() },
        ),
    )

    private val vault = KeyVault(app)

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

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
        val hide = prefs.getBoolean("hide_background", false)
        assembler.hideBackground = hide
        _ui.update { it.copy(hideBackground = hide) }
        refreshInstalled()
        refreshKeys()
    }

    private fun refreshKeys() {
        _ui.update {
            it.copy(
                azureKeyHint = vault.hint(KeyVault.AZURE_KEY),
                azureRegion = vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia",
                serverUrl = vault.getPlain(KeyVault.SERVER_URL) ?: "",
                serverTokenHint = vault.hint(KeyVault.SERVER_TOKEN),
            )
        }
    }

    fun saveServer(url: String, token: String) {
        vault.putPlain(KeyVault.SERVER_URL, url.trim())
        if (token.isNotEmpty()) vault.put(KeyVault.SERVER_TOKEN, token.trim())
        refreshKeys()
        testServer()
    }

    fun removeServer() {
        if (_ui.value.engineId == ServerSpeechEngine.ID) stop()
        vault.remove(KeyVault.SERVER_TOKEN)
        vault.putPlain(KeyVault.SERVER_URL, "")
        _ui.update { it.copy(serverTestMessage = null) }
        refreshKeys()
    }

    fun testServer() {
        val url = vault.getPlain(KeyVault.SERVER_URL)?.takeIf { it.isNotBlank() } ?: return
        val token = vault.get(KeyVault.SERVER_TOKEN) ?: return
        _ui.update { it.copy(serverTestMessage = "測試中…") }
        viewModelScope.launch(Dispatchers.IO) {
            val r = ServerSpeechEngine.test(url, token)
            _ui.update {
                it.copy(serverTestMessage = r.fold({ "可以使用：已連上伺服器" }, { e -> "不能使用：${e.message ?: "連不上伺服器"}" }))
            }
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

    /** Debug builds: import keys from a file (AZURE_SPEECH_KEY, AZURE_SPEECH_REGION, CAPTIONS_SERVER_URL, CAPTIONS_TOKEN) for emulator tests. */
    fun importAzureEnv(file: File) {
        if (!BuildConfig.DEBUG || !file.isFile) return
        val env = file.readLines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        env["AZURE_SPEECH_KEY"]?.let { vault.put(KeyVault.AZURE_KEY, it) }
        env["AZURE_SPEECH_REGION"]?.let { vault.putPlain(KeyVault.AZURE_REGION, it) }
        env["CAPTIONS_SERVER_URL"]?.let { vault.putPlain(KeyVault.SERVER_URL, it) }
        env["CAPTIONS_TOKEN"]?.let { vault.put(KeyVault.SERVER_TOKEN, it) }
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
        _ui.update { it.copy(installedEngines = installed.map { e -> e.id }.toSet(), freeableBytes = freeable) }
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val engine = engines.first { it.id == _ui.value.engineId }
        if (engine is SherpaOnnxEngine && !models.isInstalled(engine.packs)) {
            download(engine.id, startAfter = true)
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
        _ui.update { it.copy(running = false, cloudSeconds = null, captions = it.captions.copy(isListening = false)) }
    }

    fun clear() {
        assembler.reset()
        _ui.update { it.copy(captions = CaptionState(isListening = it.running)) }
    }

    fun toggleBackground() {
        val hide = !_ui.value.hideBackground
        assembler.hideBackground = hide
        prefs.edit().putBoolean("hide_background", hide).apply()
        _ui.update { it.copy(hideBackground = hide) }
    }

    fun changeFontSize(deltaSp: Int) {
        _ui.update { it.copy(fontSizeSp = (it.fontSizeSp + deltaSp).coerceIn(20, 72)) }
    }
}
