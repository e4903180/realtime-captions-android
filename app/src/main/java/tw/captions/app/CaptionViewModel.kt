package tw.captions.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Stable id of each line in [captions] (same order), for list keys and long-press naming. */
    val lineIds: List<Long> = emptyList(),
    val running: Boolean = false,
    val engineId: String = EngineCatalog.DEFAULT_ID,
    val fontSizeSp: Int = 36,
    /** People the app recognises (id to name), for the 認得的人 list. */
    val people: List<Pair<Int, String>> = emptyList(),
    /** AI 校稿 (ADR-026): Claude corrects finished lines; off by default, needs the user's Anthropic key. */
    val proofreadEnabled: Boolean = false,
    val proofreadModel: tw.captions.app.proofread.Proofreader.Model = tw.captions.app.proofread.Proofreader.Model.OPUS,
    /** "••••1a2b" when an Anthropic key is stored. */
    val anthropicKeyHint: String? = null,
    val anthropicTesting: Boolean = false,
    val anthropicTestMessage: String? = null,
    /** Microphone: 0 = automatic, otherwise an AudioDeviceInfo type (phone, wired, USB). */
    val micType: Int = 0,
    val micChoices: List<Pair<Int, String>> = emptyList(),
    /** The phone's own noise suppression on the microphone. */
    val systemNoiseSuppression: Boolean = true,
    /** Shown in yellow: the cloud engine failed and captions continue with the offline engine. */
    val cloudNotice: String? = null,
    /** Shown in yellow under the captions when proof-reading is retrying or paused. */
    val proofreadNotice: String? = null,
    /** AI proof-reading this month: requests and estimated US dollars. */
    val proofreadRequests: Int = 0,
    val proofreadCost: Double = 0.0,
    /** 自訂詞彙: names and terms the recognizer should prefer (Azure phrase list). */
    val vocabulary: List<String> = emptyList(),
    /** 說話者辨識 (ADR-033): off = no speaker model, no labels. */
    val speakersOn: Boolean = true,
    /** 說話人數: 0 = automatic, 2..6 = fixed; finished lines are regrouped by voice (ADR-027). */
    val speakerCount: Int = 0,
    /** Dark / light: follow the system (default) or fixed (ADR-031). */
    val appearance: tw.captions.app.ui.Appearance = tw.captions.app.ui.Appearance.SYSTEM,
    /** Saved transcripts, newest first (ADR-029). */
    val transcripts: List<tw.captions.core.Transcript> = emptyList(),
    val transcriptsEnabled: Boolean = true,
    /** 看電視: show everything (TV speech included) in a few large lines; filters pause (ADR-032). */
    val tvMode: Boolean = false,
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
    /** With [usageLimitHit]: the offline models are not on the phone, so captions stopped instead (no silent 160 MB download). */
    val limitNeedsOfflineModels: Boolean = false,
    /** Another app (a call, a recorder) has the microphone; this app only gets silence. */
    val micSilenced: Boolean = false,
    /** Paused by itself after a long time without speech (the cloud stops billing too). */
    val idlePaused: Boolean = false,
)

class CaptionViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** Same as iPhone (ADR-027). */
        const val RECLUSTER_THRESHOLD = 0.45f

        /** Azure accepts a few hundred phrases; more also stops helping. */
        const val MAX_VOCABULARY = 200

        /** Pause by itself after this long without speech (battery, and cloud billing). */
        const val IDLE_PAUSE_MS = 10 * 60 * 1000L
    }

    private val models = ModelStore(app)

    /** Debug builds only: play this wav file instead of using the microphone (see README). */
    private var debugWav: File? = null

    private val registry = tw.captions.core.VoiceRegistry()

    /** Live speaker groups of the offline engine; corrected after every regrouping of finished lines. */
    private val clusterer = tw.captions.core.SpeakerClusterer()

    val engines: List<SpeechEngine> = listOf(
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: openMic() }, variant = SherpaOnnxEngine.Variant.X160, registry = registry, speakersOn = { _ui.value.speakersOn }, clusterer = clusterer),
        SherpaOnnxEngine(models, openInput = { debugWav?.let { WavFileInput(it) } ?: openMic() }, variant = SherpaOnnxEngine.Variant.X480, registry = registry, speakersOn = { _ui.value.speakersOn }, clusterer = clusterer),
        AzureSpeechEngine(
            credentials = { vault.get(KeyVault.AZURE_KEY)?.let { it to (vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia") } },
            openInput = { debugWav?.let { WavFileInput(it) } ?: openMic() },
            speakersOn = { _ui.value.speakersOn },
        ),
    )

    private val vault = KeyVault(app)

    private fun openMic() = MicInput(
        preferred = tw.captions.app.engine.Microphones.find(getApplication(), _ui.value.micType),
        noiseSuppression = _ui.value.systemNoiseSuppression,
    ).also { micSessionId = it.audioSessionId }

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val usageStore = tw.captions.app.settings.UsageStore(app)

    private val transcriptStore = tw.captions.app.history.TranscriptStore(app)

    /**
     * For writing transcripts to disk. Not tied to the screen: when the app is closed while
     * captions run, the last lines are still saved (viewModelScope is already cancelled then).
     */
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Transcript files are written one at a time, in order, so an older version never overwrites a newer one. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val transcriptWriter = Dispatchers.IO.limitedParallelism(1)

    /** Newest unsaved version of each transcript; a queued write saves whatever is newest when it runs. */
    private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, tw.captions.core.Transcript>()

    /** Transcripts the user deleted: a write still queued for one of them must not bring it back. */
    private val deletedTranscripts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun persistTranscript(t: tw.captions.core.Transcript) {
        if (t.id in deletedTranscripts) return
        pendingSaves[t.id] = t
        ioScope.launch(transcriptWriter) {
            val latest = pendingSaves.remove(t.id) ?: return@launch
            if (latest.id !in deletedTranscripts) transcriptStore.save(latest)
        }
    }

    private val assembler = CaptionAssembler(TextProcessor.default(converter = OpenCcTaiwan), mergeGapSec = 1.5)

    init {
        // Load the conversion tables off the main thread before the first caption needs them.
        viewModelScope.launch(Dispatchers.Default) { OpenCcTaiwan.toTraditional("预热") }
    }

    fun useDebugWav(file: File?) {
        if (!BuildConfig.DEBUG) return
        debugWav = file
        WavFileInput.rewindAll()
        _ui.update { it.copy(debugWav = file?.name) }
    }
    private val _ui = MutableStateFlow(
        UiState(engineId = prefs.getString("engine", null)?.takeIf { id -> engines.any { it.id == id } } ?: EngineCatalog.DEFAULT_ID),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Microphone level 0..1, separate from [ui] so the level meter does not redraw the whole screen. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /** A short buzz for things the reader may not be looking at: captions stopped, engine switched, microphone taken. */
    private fun vibrate() {
        runCatching {
            val app = getApplication<Application>()
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") app.getSystemService(android.os.Vibrator::class.java)
            }
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- Microphone taken by another app (a call, a recorder): API 29+ tells us we only get silence ----

    @Volatile private var micSessionId = 0
    private val audioManager = app.getSystemService(android.media.AudioManager::class.java)
    private var watchingMic = false
    private val recordingCallback = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) = checkMicSilenced(configs)
    }

    private fun checkMicSilenced(configs: List<android.media.AudioRecordingConfiguration>) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val id = micSessionId
        val silenced = job != null && id != 0 && configs.any { it.clientAudioSessionId == id && it.isClientSilenced }
        if (silenced == _ui.value.micSilenced) return
        _ui.update { it.copy(micSilenced = silenced) }
        if (silenced) vibrate()
    }

    private fun watchMic(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 29 || on == watchingMic) return
        watchingMic = on
        if (on) {
            audioManager.registerAudioRecordingCallback(recordingCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        } else {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
            micSessionId = 0
            _ui.update { it.copy(micSilenced = false) }
        }
    }

    // ---- 停止 from the notification ----

    private val stopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) = stopByUser()
    }

    init {
        androidx.core.content.ContextCompat.registerReceiver(
            app, stopReceiver, android.content.IntentFilter(ListeningService.ACTION_STOP), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---- Idle auto-pause: nobody spoke for a long time ----

    private var lastSpeechMs = 0L
    private var idleTimer: Job? = null

    private fun watchIdle() {
        lastSpeechMs = android.os.SystemClock.elapsedRealtime()
        idleTimer?.cancel()
        idleTimer = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (android.os.SystemClock.elapsedRealtime() - lastSpeechMs >= IDLE_PAUSE_MS) {
                    _ui.update { it.copy(idlePaused = true) }
                    vibrate()
                    stop()
                    return@launch
                }
            }
        }
    }

    /** The 繼續 button after an idle pause. */
    fun resumeAfterIdle() {
        _ui.update { it.copy(idlePaused = false) }
        start()
    }

    /**
     * Every UI update goes through here: when the caption lines change, their ids are taken from
     * the assembler at the same moment, so [UiState.lineIds] always matches [UiState.captions].
     */
    private inline fun MutableStateFlow<UiState>.update(function: (UiState) -> UiState) {
        while (true) {
            val prev = value
            var next = function(prev)
            if (next.captions.lines !== prev.captions.lines || next.lineIds.size != next.captions.lines.size) {
                next = next.copy(lineIds = List(next.captions.lines.size) { assembler.lineId(it) })
            }
            if (compareAndSet(prev, next)) return
        }
    }

    init {
        val mode = runCatching { tw.captions.core.BackgroundMode.valueOf(prefs.getString("background_mode", "OFF")!!) }.getOrDefault(tw.captions.core.BackgroundMode.OFF)
        val tv = prefs.getBoolean("tv_mode", false)
        assembler.backgroundMode = if (tv) tw.captions.core.BackgroundMode.OFF else mode
        _ui.update { it.copy(backgroundMode = mode, tvMode = tv) }
        refreshInstalled()
        refreshKeys()
        loadPeople()
        _ui.update { it.copy(usage = usageStore.load(), usagePrices = usageStore.loadPrices(), today = usageStore.today()) }
        _ui.update { it.copy(transcriptsEnabled = transcriptStore.enabled) }
        val appearance = runCatching { tw.captions.app.ui.Appearance.valueOf(prefs.getString("appearance", "SYSTEM")!!) }
            .getOrDefault(tw.captions.app.ui.Appearance.SYSTEM)
        _ui.update { it.copy(appearance = appearance) }
        _ui.update { it.copy(speakersOn = prefs.getBoolean("speakers_on", true), speakerCount = prefs.getInt("speaker_count", 0)) }
        _ui.update { it.copy(fontSizeSp = prefs.getInt("font_size", it.fontSizeSp).coerceIn(20, 72)) }
        _ui.update { it.copy(vocabulary = parseVocabulary(prefs.getString("vocabulary", "")!!)) }
        _ui.update { it.copy(micType = prefs.getInt("mic_type", 0), systemNoiseSuppression = prefs.getBoolean("system_ns", true)) }
        refreshMics()
        val proofModel = runCatching { tw.captions.app.proofread.Proofreader.Model.valueOf(prefs.getString("proofread_model", "OPUS")!!) }
            .getOrDefault(tw.captions.app.proofread.Proofreader.Model.OPUS)
        val (proofRequests, proofCost) = usageStore.claudeThisMonth()
        _ui.update { it.copy(proofreadEnabled = prefs.getBoolean("proofread_enabled", false), proofreadModel = proofModel, proofreadRequests = proofRequests, proofreadCost = proofCost) }
        viewModelScope.launch(transcriptWriter) {
            val all = transcriptStore.loadAll()
            // Keep a transcript that was started (and shown) while the files were still loading.
            _ui.update { s ->
                val loaded = all.map { it.id }.toSet()
                s.copy(transcripts = (all + s.transcripts.filter { it.id !in loaded }).filter { it.id !in deletedTranscripts }.sortedByDescending { it.startedMs })
            }
        }
    }

    // ---- Transcripts (ADR-029): every start…stop is saved on the phone ----

    /** Follows the current recording; kept after stop so later corrections still reach it. */
    private var recorder: tw.captions.core.TranscriptRecorder? = null
    private var unsavedChanges = 0
    private var lastSaveMs = 0L

    private fun lineRows(): List<Triple<Long, String?, String>> =
        _ui.value.captions.lines.mapIndexed { i, l ->
            Triple(assembler.lineId(i), l.speakerName ?: if (l.background) "背景" else l.speaker?.label, l.text)
        }

    private fun beginTranscript(engineId: String) {
        recorder = null
        if (!transcriptStore.enabled) return
        val name = EngineCatalog.byId(engineId)?.name ?: engineId
        recorder = tw.captions.core.TranscriptRecorder(
            id = java.util.UUID.randomUUID().toString(),
            startedMs = System.currentTimeMillis(),
            engine = name,
            existing = lineRows().map { it.first to it.third },
        )
        unsavedChanges = 0
        lastSaveMs = System.currentTimeMillis()
    }

    /** Called whenever caption lines change. Saves every 3 lines or 20 s, so a crash loses little. */
    private fun recordLines() {
        val r = recorder ?: return
        val now = System.currentTimeMillis()
        if (!r.update(lineRows(), now, recording = job != null)) return
        unsavedChanges++
        if (job == null || unsavedChanges >= 3 || now - lastSaveMs > 20_000) saveTranscript()
    }

    private fun saveTranscript() {
        val t = recorder?.transcript ?: return
        if (t.lines.isEmpty()) return
        unsavedChanges = 0
        lastSaveMs = System.currentTimeMillis()
        if (t.id in deletedTranscripts) return
        _ui.update { s -> s.copy(transcripts = (listOf(t) + s.transcripts.filter { it.id != t.id }).sortedByDescending { it.startedMs }) }
        persistTranscript(t)
    }

    /** Ends the current recording. Does nothing when it already ended (the recorder ignores a second finish). */
    private fun finishTranscript() {
        val r = recorder ?: return
        if (r.finished) return
        r.update(lineRows(), System.currentTimeMillis(), recording = true)
        r.finish(System.currentTimeMillis())
        saveTranscript()
    }

    fun renameTranscript(id: String, title: String) {
        val t = _ui.value.transcripts.firstOrNull { it.id == id } ?: return
        val current = recorder?.takeIf { it.transcript.id == id }
        current?.rename(title.trim().ifEmpty { null })
        // The recorder may hold newer lines than the list does.
        val renamed = current?.transcript ?: t.copy(title = title.trim().ifEmpty { null })
        _ui.update { s -> s.copy(transcripts = s.transcripts.map { if (it.id == id) renamed else it }) }
        persistTranscript(renamed)
    }

    fun deleteTranscript(id: String) {
        if (recorder?.transcript?.id == id) recorder = null
        deletedTranscripts += id
        pendingSaves.remove(id)
        _ui.update { s -> s.copy(transcripts = s.transcripts.filter { it.id != id }) }
        ioScope.launch(transcriptWriter) { transcriptStore.delete(id) }
    }

    fun deleteAllTranscripts() {
        recorder?.let { deletedTranscripts += it.transcript.id }
        recorder = null
        deletedTranscripts += _ui.value.transcripts.map { it.id }
        pendingSaves.clear()
        _ui.update { it.copy(transcripts = emptyList()) }
        ioScope.launch(transcriptWriter) { transcriptStore.deleteAll() }
    }

    fun setTranscriptsEnabled(on: Boolean) {
        transcriptStore.enabled = on
        if (!on) recorder = null
        _ui.update { it.copy(transcriptsEnabled = on) }
    }

    fun transcriptText(t: tw.captions.core.Transcript, srt: Boolean): String =
        if (srt) t.srt() else tw.captions.app.history.TranscriptStore.plainText(t)

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

    /** The user chose to keep using the cloud past the limit; holds until they press stop. */
    private var limitOverridden = false

    private fun isPaid(engineId: String) = EngineCatalog.byId(engineId)?.paid == true

    private fun limitReached() = !limitOverridden && _ui.value.usage.overLimit(usageStore.today(), _ui.value.usagePrices)

    /** Over the monthly limit: pause the cloud for now and continue with the offline engine (not saved as the user's choice). */
    private fun switchToOfflineForLimit() {
        stop()
        vibrate()
        if (!offlineReady()) {
            // Never start a 160 MB download by itself: stop and let the user decide.
            _ui.update { it.copy(usageLimitHit = true, limitNeedsOfflineModels = true) }
            return
        }
        _ui.update { it.copy(engineId = EngineCatalog.DEFAULT_ID, usageLimitHit = true, limitNeedsOfflineModels = false) }
        start()
    }

    /** From the limit notice when the offline models are missing: download them, then continue offline. */
    fun downloadOfflineAfterLimit() {
        _ui.update { it.copy(usageLimitHit = false, limitNeedsOfflineModels = false, engineId = EngineCatalog.DEFAULT_ID) }
        download(EngineCatalog.DEFAULT_ID, startAfter = true)
    }

    private fun offlineReady() = offlineEngines.firstOrNull { it.id == EngineCatalog.DEFAULT_ID }?.let { models.isInstalled(it.packs) } == true

    /** The cloud engine stopped with an error: continue offline for now (the user's engine choice stays saved). */
    private fun switchToOfflineForOutage(reason: String) {
        if (!isPaid(_ui.value.engineId)) return
        stop()
        vibrate()
        _ui.update { it.copy(engineId = EngineCatalog.DEFAULT_ID, cloudNotice = "$reason。先改用離線辨識，按停止再開始會重新連雲端。") }
        start()
    }

    /** From the limit notice: go back to the cloud engine and stop asking until the user presses stop. */
    fun continueCloudAnyway() {
        limitOverridden = true
        _ui.update { it.copy(usageLimitHit = false, limitNeedsOfflineModels = false) }
        selectEngine(AzureSpeechEngine.ID)
        if (job == null) start()
    }

    /** The stop button: ends this session, so the next start asks again if still over the limit. */
    fun stopByUser() {
        limitOverridden = false
        stop()
        // The next start plays the debug wav from the beginning (an engine switch continues it).
        WavFileInput.rewindAll()
        if (_ui.value.cloudNotice != null) {
            val saved = prefs.getString("engine", null)?.takeIf { id -> engines.any { it.id == id } } ?: EngineCatalog.DEFAULT_ID
            _ui.update { it.copy(engineId = saved, cloudNotice = null) }
        }
    }

    fun dismissLimitNotice() {
        _ui.update { it.copy(usageLimitHit = false, limitNeedsOfflineModels = false) }
    }

    fun setUsagePrices(prices: tw.captions.core.CloudUsage.Prices) {
        usageStore.savePrices(prices)
        _ui.update { it.copy(usagePrices = prices) }
    }

    fun resetUsage() {
        usageStore.reset()
        _ui.update { it.copy(usage = tw.captions.core.CloudUsage(), today = usageStore.today(), proofreadRequests = 0, proofreadCost = 0.0) }
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

    /**
     * "This line is <name>": learns the voice from that line. Voice profiles stay on the phone.
     * [lineId] was taken at the long-press; the line may have moved since (old lines dropped).
     */
    fun nameLine(lineId: Long, name: String) {
        val index = _ui.value.captions.lines.indices.firstOrNull { assembler.lineId(it) == lineId } ?: return
        val line = _ui.value.captions.lines[index]
        val voice = line.voice ?: return
        val first = registry.isEmpty
        registry.add(name.trim(), voice)
        savePeople()
        // Every line with the same letter gets the name, and so do that speaker's later lines.
        _ui.update { it.copy(captions = assembler.nameSpeaker(index, name.trim())) }
        recordLines()
        // Once someone is known, dim voices nobody registered (TV, other tables) unless the user chose otherwise.
        if (first && !_ui.value.tvMode && _ui.value.backgroundMode == tw.captions.core.BackgroundMode.OFF) setBackgroundMode(tw.captions.core.BackgroundMode.DIM)
    }

    fun forgetPerson(id: Int) {
        val name = registry.people.firstOrNull { it.id == id }?.name
        registry.remove(id)
        savePeople()
        // Lines already on screen go back to their letters (unless someone else has the same name).
        if (name != null && registry.people.none { it.name == name }) {
            _ui.update { it.copy(captions = assembler.forgetSpeaker(name)) }
            recordLines()
        }
    }

    /** New name for a known person. If someone else already has that name, the two become one person. */
    fun renamePerson(id: Int, newName: String) {
        val name = newName.trim()
        val person = registry.people.firstOrNull { it.id == id } ?: return
        if (name.isEmpty() || name == person.name) return
        val same = registry.people.firstOrNull { it.id != id && it.name == name }
        if (same != null) {
            mergePeople(id, same.id)
            return
        }
        registry.rename(id, name)
        savePeople()
        _ui.update { it.copy(captions = assembler.renameSpeaker(person.name, name)) }
        recordLines()
    }

    /** Two entries turned out to be the same person: [fromId] joins [intoId], voices combined. */
    fun mergePeople(fromId: Int, intoId: Int) {
        val from = registry.people.firstOrNull { it.id == fromId } ?: return
        val into = registry.people.firstOrNull { it.id == intoId } ?: return
        if (from.id == into.id) return
        registry.merge(fromId, intoId)
        savePeople()
        _ui.update { it.copy(captions = assembler.renameSpeaker(from.name, into.name)) }
        recordLines()
    }

    private fun setBackgroundMode(mode: tw.captions.core.BackgroundMode) {
        if (!_ui.value.tvMode) assembler.backgroundMode = mode
        prefs.edit().putString("background_mode", mode.name).apply()
        _ui.update { it.copy(backgroundMode = mode) }
    }

    private fun refreshKeys() {
        _ui.update {
            it.copy(
                azureKeyHint = vault.hint(KeyVault.AZURE_KEY),
                azureRegion = vault.getPlain(KeyVault.AZURE_REGION) ?: "eastasia",
                anthropicKeyHint = vault.hint(KeyVault.ANTHROPIC_KEY),
            )
        }
    }

    // ---- AI 校稿 (ADR-026): finished lines are corrected by Claude a moment after they appear ----

    private val Proof = tw.captions.app.proofread.Proofreader

    /** Text of each line as it was last sent (or as corrected), so a line is checked once per version. */
    private val reviewed = mutableMapOf<Long, String>()
    private var proofTimer: Job? = null
    private var proofreading = false
    private var proofFailures = 0
    /** The key that failed for good (wrong key, no credit): no more requests until it changes. */
    private var pausedForKey: String? = null

    private fun proofreadActive() = _ui.value.proofreadEnabled && _ui.value.anthropicKeyHint != null

    private fun uncheckedLines(): List<tw.captions.core.ProofreadLine> =
        _ui.value.captions.lines.mapIndexedNotNull { i, l ->
            val id = assembler.lineId(i)
            if (id < 0 || l.background || l.text.isBlank() || reviewed[id] == l.text) null
            else tw.captions.core.ProofreadLine(id, l.speakerName ?: l.speaker?.label, l.text)
        }

    private fun scheduleProofreading(afterMs: Long? = null) {
        if (!proofreadActive() || proofreading) return
        val unchecked = uncheckedLines()
        if (unchecked.isEmpty()) return
        proofTimer?.cancel()
        // Wait a moment: the same person may still add to the line (lines join within 1.5 s).
        val wait = afterMs ?: if (unchecked.size >= tw.captions.core.Proofreading.MAX_LINES) 0L else 2000L
        proofTimer = viewModelScope.launch {
            kotlinx.coroutines.delay(wait)
            proofreadNow()
        }
    }

    private suspend fun proofreadNow() {
        if (!proofreadActive() || proofreading) return
        val key = vault.get(KeyVault.ANTHROPIC_KEY) ?: return
        if (key == pausedForKey) return
        val batch = uncheckedLines().take(tw.captions.core.Proofreading.MAX_LINES)
        if (batch.isEmpty()) return
        val lines = _ui.value.captions.lines
        val first = lines.indices.firstOrNull { assembler.lineId(it) == batch.first().id } ?: return
        val context = lines.subList(maxOf(0, first - tw.captions.core.Proofreading.CONTEXT_LINES), first)
            .map { "${it.speakerName ?: it.speaker?.label ?: "?"}：${it.text}" }
        val model = _ui.value.proofreadModel
        proofreading = true
        val result = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { Proof.proofread(key, model, context, batch) } }
        proofreading = false
        result.onSuccess { r ->
            proofFailures = 0
            usageStore.addClaude((r.inputTokens * model.inputPerMTok + r.outputTokens * model.outputPerMTok) / 1_000_000)
            val (requests, cost) = usageStore.claudeThisMonth()
            var captions = _ui.value.captions
            for (sent in batch) {
                reviewed[sent.id] = sent.text
                val fixed = r.fixed[sent.id] ?: continue
                // Only if the line is still what was sent: it may have grown in the meantime.
                val index = captions.lines.indices.firstOrNull { assembler.lineId(it) == sent.id } ?: continue
                if (captions.lines[index].text != sent.text) continue
                captions = assembler.replaceLineText(sent.id, fixed)
                reviewed[sent.id] = fixed
            }
            _ui.update { it.copy(captions = captions, proofreadNotice = null, proofreadRequests = requests, proofreadCost = cost) }
            recordLines()
            scheduleProofreading()
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            val failure = e as? tw.captions.app.proofread.Proofreader.Failure
            val reason = failure?.message ?: "發生錯誤"
            if (failure?.permanent == true) {
                pausedForKey = key
                _ui.update { it.copy(proofreadNotice = "AI 校稿暫停：$reason") }
                return
            }
            proofFailures++
            val waitSec = minOf(60, 5 * (1 shl minOf(proofFailures - 1, 4)))
            if (proofFailures >= 2) _ui.update { it.copy(proofreadNotice = "AI 校稿$reason，$waitSec 秒後重試") }
            scheduleProofreading(afterMs = waitSec * 1000L)
        }
    }

    fun setProofreadEnabled(on: Boolean) {
        prefs.edit().putBoolean("proofread_enabled", on).apply()
        _ui.update { it.copy(proofreadEnabled = on, proofreadNotice = null) }
        if (on) scheduleProofreading() else proofTimer?.cancel()
    }

    fun setProofreadModel(model: tw.captions.app.proofread.Proofreader.Model) {
        prefs.edit().putString("proofread_model", model.name).apply()
        _ui.update { it.copy(proofreadModel = model) }
    }

    fun saveAnthropicKey(key: String) {
        if (key.isEmpty()) return
        vault.put(KeyVault.ANTHROPIC_KEY, key)
        pausedForKey = null
        proofFailures = 0
        _ui.update { it.copy(anthropicTestMessage = null, proofreadNotice = null) }
        refreshKeys()
        testAnthropicKey()
    }

    fun removeAnthropicKey() {
        vault.remove(KeyVault.ANTHROPIC_KEY)
        Proof.close()
        proofTimer?.cancel()
        _ui.update { it.copy(anthropicTestMessage = null, proofreadNotice = null) }
        refreshKeys()
    }

    fun testAnthropicKey() {
        val key = vault.get(KeyVault.ANTHROPIC_KEY) ?: return
        _ui.update { it.copy(anthropicTesting = true, anthropicTestMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val problem = Proof.testKey(key)
            _ui.update { it.copy(anthropicTesting = false, anthropicTestMessage = problem?.let { p -> "不能使用：$p" } ?: "可以使用：金鑰正確") }
            if (problem == null) kotlinx.coroutines.withContext(Dispatchers.Main) { scheduleProofreading() }
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
        _ui.update { it.copy(running = true, idlePaused = false, captions = it.captions.copy(error = null)) }
        sessionActive = true
        beginTranscript(engine.id)
        watchIdle()
        if (debugWav == null) watchMic(true)
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
                var lastLevelAt = 0L
                engine.transcribe(RecognitionConfig(languageTag = "zh-TW", hotwords = _ui.value.vocabulary)).collect { event ->
                    if (event is tw.captions.core.TranscriptEvent.AudioLevel) {
                        // Arrives 50 times a second; the meter needs far fewer screen updates.
                        val state = assembler.onEvent(event)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLevelAt >= 66) {
                            lastLevelAt = now
                            _audioLevel.value = state.audioLevel
                        }
                        return@collect
                    }
                    if ((event is tw.captions.core.TranscriptEvent.Partial && event.text.isNotBlank()) || event is tw.captions.core.TranscriptEvent.Final) {
                        lastSpeechMs = android.os.SystemClock.elapsedRealtime()
                    }
                    if (event is tw.captions.core.TranscriptEvent.Error && isPaid(engine.id) && !event.message.contains("金鑰") && offlineReady()) {
                        // No network or the service is down: keep captions going with the offline engine.
                        viewModelScope.launch { switchToOfflineForOutage(event.message) }
                        return@collect
                    }
                    var captions = assembler.onEvent(event)
                    // Regroup every line by voice after each finished one, so earlier speaker
                    // mistakes get fixed too (ADR-027).
                    if (event is tw.captions.core.TranscriptEvent.Final && _ui.value.speakersOn) {
                        captions = assembler.recluster(RECLUSTER_THRESHOLD, _ui.value.speakerCount)
                        // The next line is compared with the corrected groups, so its letter does not jump.
                        if (!isPaid(engine.id)) clusterer.replaceProfiles(assembler.speakerProfiles())
                    }
                    _ui.update { it.copy(captions = captions) }
                    // Only finished lines change the transcript.
                    if (event is tw.captions.core.TranscriptEvent.Final) {
                        recordLines()
                        scheduleProofreading()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug or a native failure in an engine: say so and stop cleanly instead of crashing.
                Log.e("Captions", "engine failed", e)
                _ui.update { it.copy(captions = it.captions.copy(error = "辨識發生錯誤，字幕已停止。請再按一次開始。")) }
            } finally {
                // The engine stopped by itself (e.g. a fatal error), not via stop().
                if (job === self) {
                    job = null
                    if (_ui.value.captions.error != null) vibrate()
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
                val message = if (ModelStore.isNoSpace(e)) {
                    val mb = ((e as? ModelStore.NotEnoughSpace)?.neededBytes ?: engine.packs.sumOf { it.downloadBytes } * 2) / 1_000_000
                    "手機空間不足：下載離線模型需要約 $mb MB，請先清出空間再試一次"
                } else "模型下載失敗，請確認網路後再試一次"
                _ui.update {
                    it.copy(modelDownload = null, downloadingEngine = null, captions = it.captions.copy(error = message))
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

    /** The screen is gone for good (app closed): end the session so usage and the transcript are saved. */
    override fun onCleared() {
        stop()
        runCatching { getApplication<Application>().unregisterReceiver(stopReceiver) }
        Proof.close()
        ListeningService.stop(getApplication())
        super.onCleared()
    }

    fun selectEngine(id: String) {
        val wasRunning = job != null
        stop()
        _ui.update { it.copy(engineId = id, cloudNotice = null) }
        prefs.edit().putString("engine", id).apply()
        if (wasRunning) start()
    }

    /** True from start() until the session ends; stop() without a running session must not touch the transcript. */
    private var sessionActive = false

    private fun markStopped() {
        cloudTimer?.cancel()
        idleTimer?.cancel()
        watchMic(false)
        _audioLevel.value = 0f
        flushUsage()
        if (sessionActive) {
            sessionActive = false
            finishTranscript()
        }
        _ui.update { it.copy(running = false, cloudSeconds = null, captions = it.captions.copy(isListening = false)) }
    }

    fun clear() {
        assembler.reset()
        reviewed.clear()
        proofTimer?.cancel()
        clusterer.replaceProfiles(emptyMap())
        _ui.update { it.copy(captions = CaptionState(isListening = it.running)) }
    }

    /** Cycles the background filter: off → dim → hide → off. */
    fun toggleBackground() {
        val modes = tw.captions.core.BackgroundMode.entries
        val mode = modes[(modes.indexOf(_ui.value.backgroundMode) + 1) % modes.size]
        if (!_ui.value.tvMode) assembler.backgroundMode = mode
        prefs.edit().putString("background_mode", mode.name).apply()
        _ui.update { it.copy(backgroundMode = mode) }
    }

    /**
     * 看電視 on: every voice is shown normally (the background filter and the dimming of unknown
     * voices pause). Off: back to the user's own filter setting. Remembered across launches.
     */
    fun toggleTvMode() {
        val on = !_ui.value.tvMode
        assembler.backgroundMode = if (on) tw.captions.core.BackgroundMode.OFF else _ui.value.backgroundMode
        prefs.edit().putBoolean("tv_mode", on).apply()
        _ui.update { it.copy(tvMode = on) }
    }

    private fun parseVocabulary(text: String): List<String> =
        text.split('\n', ',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_VOCABULARY)

    /** One word per line (commas also split). Used from the next time captions start. */
    fun setVocabulary(text: String) {
        val words = parseVocabulary(text)
        prefs.edit().putString("vocabulary", words.joinToString("\n")).apply()
        _ui.update { it.copy(vocabulary = words) }
    }

    /** Looks again at what is plugged in (called when the settings sheet opens). */
    fun refreshMics() {
        _ui.update { it.copy(micChoices = tw.captions.app.engine.Microphones.choices(getApplication())) }
    }

    private fun restartIfRunning(change: () -> Unit) {
        val wasRunning = job != null
        if (wasRunning) stop()
        change()
        if (wasRunning) start()
    }

    fun setMic(type: Int) {
        prefs.edit().putInt("mic_type", type).apply()
        restartIfRunning { _ui.update { it.copy(micType = type) } }
    }

    fun setSystemNoiseSuppression(on: Boolean) {
        prefs.edit().putBoolean("system_ns", on).apply()
        restartIfRunning { _ui.update { it.copy(systemNoiseSuppression = on) } }
    }

    fun setSpeakersOn(on: Boolean) {
        prefs.edit().putBoolean("speakers_on", on).apply()
        val wasRunning = job != null
        if (wasRunning) stop()
        _ui.update { it.copy(speakersOn = on) }
        if (wasRunning) start()
    }

    fun setSpeakerCount(n: Int) {
        prefs.edit().putInt("speaker_count", n).apply()
        _ui.update { it.copy(speakerCount = n, captions = assembler.recluster(RECLUSTER_THRESHOLD, n)) }
        if (!isPaid(_ui.value.engineId)) clusterer.replaceProfiles(assembler.speakerProfiles())
        recordLines()
    }

    fun setAppearance(a: tw.captions.app.ui.Appearance) {
        prefs.edit().putString("appearance", a.name).apply()
        _ui.update { it.copy(appearance = a) }
    }

    fun changeFontSize(deltaSp: Int) {
        _ui.update { it.copy(fontSizeSp = (it.fontSizeSp + deltaSp).coerceIn(20, 72)) }
        prefs.edit().putInt("font_size", _ui.value.fontSizeSp).apply()
    }
}
