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
    @Published private(set) var level: Float = 0
    @Published private(set) var hint: String?
    @Published private(set) var error: String?
    @Published private(set) var running = false
    @Published private(set) var preparing = false
    @Published var fontSize: Double = UserDefaults.standard.object(forKey: "fontSize") as? Double ?? 34 {
        didSet { UserDefaults.standard.set(fontSize, forKey: "fontSize") }
    }

    let models = ModelStore()
    private let assembler = IosBridgeKt.createCaptionAssembler(converter: TaiwanChinese.makeConverter())
    private var engine: OfflineEngine?
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
            if !models.isReady { try await models.download() }
            if engine == nil {
                let files = models.files
                engine = await Task.detached(priority: .userInitiated) { OfflineEngine(models: files) }.value
            }
            guard let engine else { return }
            try audio.start(onSamples: Self.audioHandler(engine: engine, model: self))
            running = true
            UIApplication.shared.isIdleTimerDisabled = true
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() {
        guard running else { return }
        audio.stop()
        running = false
        level = 0
        UIApplication.shared.isIdleTimerDisabled = false
        engine?.finish(onEvents: Self.eventHandler(model: self))
    }

    func clear() {
        assembler.reset()
        apply(assembler.state)
    }

    func changeFontSize(by delta: Double) {
        fontSize = min(72, max(20, fontSize + delta))
    }

    /// Built outside the main actor: the audio callback runs on the audio thread.
    private nonisolated static func audioHandler(engine: OfflineEngine, model: CaptionViewModel) -> ([Float]) -> Void {
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
    }

    private func apply(_ state: CaptionState) {
        let newLines = state.lines.enumerated().map { i, line in
            Line(id: i, text: line.text, speaker: IosBridgeKt.speakerLabelOf(line: line))
        }
        if newLines != lines { lines = newLines }
        stableText = state.stableText
        tentativeText = state.tentativeText
        currentSpeaker = IosBridgeKt.currentSpeakerLabelOf(state: state)
        level = state.audioLevel
        hint = state.hint
        if let e = state.error { error = e }
    }
}
