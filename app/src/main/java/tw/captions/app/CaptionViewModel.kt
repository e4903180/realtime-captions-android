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
    val engineId: String = "android-online",
    val fontSizeSp: Int = 36,
    /** 0..1 while the offline models are downloading, otherwise null. */
    val modelDownload: Float? = null,
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
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: MicInput() }),
    )

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
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val engine = engines.first { it.id == _ui.value.engineId }
        if (engine is SherpaOnnxEngine && !models.isReady) {
            downloadModelsThenStart()
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

    private fun downloadModelsThenStart() {
        if (download?.isActive == true) return
        _ui.update { it.copy(modelDownload = 0f, captions = it.captions.copy(error = null)) }
        download = viewModelScope.launch {
            try {
                models.ensure { p -> _ui.update { it.copy(modelDownload = p) } }
                _ui.update { it.copy(modelDownload = null) }
                start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(modelDownload = null) }
                throw e
            } catch (e: Exception) {
                Log.e("CaptionsModels", "download failed", e)
                _ui.update {
                    it.copy(modelDownload = null, captions = it.captions.copy(error = "模型下載失敗，請確認網路後再按一次開始"))
                }
            }
        }
    }

    fun stop() {
        download?.cancel()
        val current = job
        job = null
        current?.cancel()
        markStopped()
    }

    fun selectEngine(id: String) {
        val wasRunning = job != null
        stop()
        _ui.update { it.copy(engineId = id) }
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
