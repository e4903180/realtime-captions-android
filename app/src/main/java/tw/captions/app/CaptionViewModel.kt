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
import tw.captions.app.engine.AndroidSpeechEngine
import tw.captions.core.CaptionAssembler
import tw.captions.core.CaptionState
import tw.captions.core.RecognitionConfig
import tw.captions.core.SpeechEngine

data class UiState(
    val captions: CaptionState = CaptionState(),
    val running: Boolean = false,
    val engineId: String = "android-online",
    val fontSizeSp: Int = 36,
)

class CaptionViewModel(app: Application) : AndroidViewModel(app) {

    val engines: List<SpeechEngine> = listOf(
        AndroidSpeechEngine(app, onDevice = false),
        AndroidSpeechEngine(app, onDevice = true),
    )

    private val assembler = CaptionAssembler()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val engine = engines.first { it.id == _ui.value.engineId }
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

    fun stop() {
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
