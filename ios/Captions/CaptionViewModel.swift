import CaptionsCore
import SwiftUI
import UIKit

/// One finished caption line, ready for SwiftUI.
struct Line: Identifiable, Equatable {
    /// Stable id from the shared CaptionAssembler (indexes shift when old lines are dropped).
    let id: Int64
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
    lazy var secondPassStore = SecondPassStore(directory: models.files.directory)
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
                let wantSecond = SecondPassModel.isEnabled && secondPassStore.isReady
                if offline == nil || offline?.hasSecondPass != wantSecond {
                    let files = models.files
                    let second = wantSecond ? secondPassStore.directory : nil
                    offline = await Task.detached(priority: .userInitiated) { OfflineEngine(models: files, secondPassDirectory: second) }.value
                }
                guard let offline else { return }
                offline.onRevision = { [weak self] n, text in
                    Task { @MainActor in self?.revise(n, text) }
                }
                engine = offline
            }
            finalSeqs.removeAll()
            pendingRevisions.removeAll()
            proofreadPaused = false
            finalsSeen = 0
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
        scheduleProofreading()
    }

    func clear() {
        assembler.reset()
        finalSeqs.removeAll()
        reviewed.removeAll()
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

    // MARK: Correcting finished lines (second pass ADR-025, proof-reading ADR-026)

    /// Engine's n-th Final since start → the assembler's number for it.
    private var finalSeqs: [Int: Int32] = [:]
    private var finalsSeen = 0
    /// Second-pass results that arrived before their Final was handled.
    private var pendingRevisions: [Int: String] = [:]

    private func revise(_ n: Int, _ text: String) {
        guard let seq = finalSeqs[n] else { pendingRevisions[n] = text; return }
        apply(assembler.revise(seq: seq, text: text))
    }

    /// Text of each line when it was last proof-read (or sent for it), by line id.
    private var reviewed: [Int64: String] = [:]
    private var proofreadTask: Task<Void, Never>?
    private var proofreading = false
    /// Set after a failed request (wrong key, no network…); cleared when listening starts again.
    private var proofreadPaused = false

    /// Waits for a short pause in the conversation, then proof-reads the lines not yet checked.
    private func scheduleProofreading() {
        guard Proofreader.isActive, !proofreadPaused else { return }
        proofreadTask?.cancel()
        let unchecked = lines.filter { reviewed[$0.id] != $0.text }.count
        let delay: UInt64 = unchecked >= Int(Proofreading.shared.MAX_LINES) ? 0 : 2_000_000_000
        proofreadTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            await self?.proofreadNow()
        }
    }

    private func proofreadNow() async {
        guard !proofreading, let key = KeyVault.get(KeyVault.anthropicKey) else { return }
        // The last line may still grow while someone is talking; leave it for the next round.
        let candidates = lines.enumerated().filter { i, l in
            reviewed[l.id] != l.text && !(i == lines.count - 1 && (continuesLastLine || running && !(stableText + tentativeText).isEmpty))
        }
        let batch = Array(candidates.prefix(Int(Proofreading.shared.MAX_LINES)))
        guard let firstIndex = batch.first?.offset else { return }
        let context = lines[max(0, firstIndex - Int(Proofreading.shared.CONTEXT_LINES))..<firstIndex].map { "\($0.speaker ?? "?")：\($0.text)" }
        let sent = batch.map { (id: $0.element.id, speaker: $0.element.speaker, text: $0.element.text) }
        for l in sent { reviewed[l.id] = l.text }
        proofreading = true
        defer { proofreading = false }
        do {
            let fixed = try await Proofreader.proofread(key: key, model: Proofreader.model, context: Array(context), lines: sent)
            for l in sent {
                guard let text = fixed[l.id], lines.first(where: { $0.id == l.id })?.text == l.text else { continue }
                apply(assembler.replaceLineText(lineId: l.id, text: text))
                reviewed[l.id] = text
            }
        } catch {
            self.error = "AI 校稿暫停：\(error.localizedDescription)"
            proofreadPaused = true
            return
        }
        if lines.contains(where: { reviewed[$0.id] != $0.text }) { scheduleProofreading() }
    }

    private func handle(_ events: [TranscriptEvent]) {
        var state = assembler.state
        var sawFinal = false
        for e in events {
            state = assembler.onEvent(event: e)
            if e is TranscriptEventFinal {
                sawFinal = true
                let n = finalsSeen
                finalsSeen += 1
                finalSeqs[n] = assembler.lastFinalSeq
                if let early = pendingRevisions.removeValue(forKey: n) { state = assembler.revise(seq: assembler.lastFinalSeq, text: early) }
            }
        }
        apply(state)
        if sawFinal { scheduleProofreading() }
        // A fatal engine error (e.g. a wrong Azure key) ends listening.
        if running, events.contains(where: { ($0 as? TranscriptEventError)?.recoverable == false }) { stop() }
    }

    private func apply(_ state: CaptionState) {
        let newLines = state.lines.enumerated().map { i, line in
            Line(id: assembler.lineId(index: Int32(i)), text: line.text, speaker: IosBridgeKt.speakerLabelOf(line: line))
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
