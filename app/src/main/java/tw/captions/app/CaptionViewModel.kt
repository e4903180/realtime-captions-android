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
    /** 0..1 while the offline models are downloading, otherwise null. */
    val modelDownload: Float? = null,
    /** Which engine the running download is for. */
    val downloadingEngine: String? = null,
    /** Offline engines whose models are all on the phone. */
    val installedEngines: Set<String> = emptySet(),
    /** Disk space each installed offline engine would free if removed (bytes). */
    val freeableBytes: Map<String, Long> = emptyMap(),
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
    )

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
        refreshInstalled()
    }

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
        _ui.update { it.copy(running = false, captions = it.captions.copy(isListening = false)) }
    }

    fun clear() {
        assembler.reset()
        _ui.update { it.copy(captions = CaptionState(isListening = it.running)) }
    }

    fun changeFontSize(deltaSp: Int) {
        _ui.update { it.copy(fontSizeSp = (it.fontSizeSp + deltaSp).coerceIn(20, 72)) }
    }
}
