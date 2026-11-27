import CaptionsCore
import SwiftUI
import UIKit

/// One finished caption line, ready for SwiftUI.
struct Line: Identifiable, Equatable {
    let id: Int
    let text: String
    let speaker: String?
}

@MainActor
final class CaptionViewModel: ObservableObject {
    @Published private(set) var lines: [Line] = []
    @Published private(set) var stableText = ""
    @Published private(set) var tentativeText = ""
    @Published private(set) var currentSpeaker: String?
    /// The current utterance continues the last line (same speaker, short pause): shown on that line.
    @Published private(set) var continuesLastLine = false
    @Published private(set) var level: Float = 0
    @Published private(set) var hint: String?
    @Published private(set) var error: String?
    @Published private(set) var running = false
    @Published private(set) var preparing = false
    @Published var fontSize: Double = UserDefaults.standard.object(forKey: "fontSize") as? Double ?? 34 {
        didSet { UserDefaults.standard.set(fontSize, forKey: "fontSize") }
    }

    @Published var engineId: String = UserDefaults.standard.string(forKey: "engineId") ?? EngineInfo.offlineId {
        didSet { UserDefaults.standard.set(engineId, forKey: "engineId") }
    }
    /// Set when the chosen engine needs a key that is missing; the view opens cloud settings.
    @Published var showCloudSettings = false

    let models = ModelStore()
    private let assembler = IosBridgeKt.createCaptionAssembler(converter: TaiwanChinese.makeConverter())
    private var offline: OfflineEngine?
    private var engine: CaptionEngine?
    private let audio = AudioCapture()

    func toggle() {
        if running { stop() } else { Task { await start() } }
    }

    func start() async {
        guard !running, !preparing else { return }
        error = nil
        preparing = true
        defer { preparing = false }
        guard await AudioCapture.requestPermission() else {
            error = "請到「設定 > 隱私權與安全性 > 麥克風」允許「即時字幕」使用麥克風"
            return
        }
        do {
            let engine: CaptionEngine
            if engineId == EngineInfo.azureId {
                guard let key = KeyVault.get(KeyVault.azureKey) else {
                    error = "請先在「雲端服務設定」填入 Azure 金鑰"
                    showCloudSettings = true
                    return
                }
                engine = AzureEngine(key: key, region: AzureEngine.savedRegion)
            } else {
                if !models.isReady { try await models.download() }
                if offline == nil {
                    let files = models.files
                    offline = await Task.detached(priority: .userInitiated) { OfflineEngine(models: files) }.value
                }
                guard let offline else { return }
                engine = offline
            }
            try engine.begin(onEvents: Self.eventHandler(model: self))
            try audio.start(onSamples: Self.audioHandler(engine: engine, model: self))
            self.engine = engine
            running = true
            UIApplication.shared.isIdleTimerDisabled = true
        } catch {
            self.error = error.localizedDescription
        }
    }

    func selectEngine(_ id: String) {
        if running { stop() }
        engineId = id
        error = nil
        if EngineInfo.byId(id).needsKey, KeyVault.get(KeyVault.azureKey) == nil { showCloudSettings = true }
    }

    func stop() {
        guard running else { return }
        audio.stop()
        running = false
        level = 0
        UIApplication.shared.isIdleTimerDisabled = false
        engine?.finish(onEvents: Self.eventHandler(model: self))
        engine = nil
    }

    func clear() {
        assembler.reset()
        apply(assembler.state)
    }

    func changeFontSize(by delta: Double) {
        fontSize = min(72, max(20, fontSize + delta))
    }

    /// Built outside the main actor: the audio callback runs on the audio thread.
    private nonisolated static func audioHandler(engine: CaptionEngine, model: CaptionViewModel) -> ([Float]) -> Void {
        let deliver = eventHandler(model: model)
        return { samples in engine.feed(samples, onEvents: deliver) }
    }

    /// Hands pipeline events (from the decoding queue) to the main actor.
    private nonisolated static func eventHandler(model: CaptionViewModel) -> ([TranscriptEvent]) -> Void {
        return { [weak model] events in
            Task { @MainActor in model?.handle(events) }
        }
    }

    private func handle(_ events: [TranscriptEvent]) {
        var state = assembler.state
        for e in events { state = assembler.onEvent(event: e) }
        apply(state)
        // A fatal engine error (e.g. a wrong Azure key) ends listening.
        if running, events.contains(where: { ($0 as? TranscriptEventError)?.recoverable == false }) { stop() }
    }

    private func apply(_ state: CaptionState) {
        let newLines = state.lines.enumerated().map { i, line in
            Line(id: i, text: line.text, speaker: IosBridgeKt.speakerLabelOf(line: line))
        }
        if newLines != lines { lines = newLines }
        stableText = state.stableText
        tentativeText = state.tentativeText
        currentSpeaker = IosBridgeKt.currentSpeakerLabelOf(state: state)
        continuesLastLine = state.continuesLastLine && !newLines.isEmpty
        level = state.audioLevel
        hint = state.hint
        if let e = state.error { error = e }
    }
}
