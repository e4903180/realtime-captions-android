import Combine
import CaptionsCore
import SwiftUI
import UIKit

/// One finished caption line, ready for SwiftUI.
struct Line: Identifiable, Equatable {
    /// Stable id from the shared CaptionAssembler (indexes shift when old lines are dropped).
    let id: Int64
    let text: String
    let speaker: String?
    /// Marked as background by the background filter (shown dimmed).
    var background = false
}

@MainActor
final class CaptionViewModel: ObservableObject {
    @Published private(set) var lines: [Line] = []
    @Published private(set) var stableText = ""
    @Published private(set) var tentativeText = ""
    @Published private(set) var currentSpeaker: String?
    /// The current utterance continues the last line (same speaker, short pause): shown on that line.
    @Published private(set) var continuesLastLine = false
    /// The line in progress is background speech (dimmed).
    @Published private(set) var currentBackground = false
    @Published private(set) var level: Float = 0
    @Published private(set) var hint: String?
    @Published private(set) var error: String?
    /// A passing note about AI proof-reading (shown in yellow, only while it is switched on).
    @Published private(set) var proofreadNotice: String?
    @Published private(set) var running = false
    @Published private(set) var preparing = false
    @Published var fontSize: Double = UserDefaults.standard.object(forKey: "fontSize") as? Double ?? 34 {
        didSet { UserDefaults.standard.set(fontSize, forKey: "fontSize") }
    }

    /// 自訂詞彙: names and terms both engines should prefer, used from the next 開始 (ADR-038).
    @Published private(set) var vocabulary: [String] = Hotwords.shared.parse(text: UserDefaults.standard.string(forKey: "vocabulary") ?? "")

    /// One word per line (commas also split).
    func setVocabulary(_ text: String) {
        vocabulary = Hotwords.shared.parse(text: text)
        UserDefaults.standard.set(vocabulary.joined(separator: "\n"), forKey: "vocabulary")
    }

    /// How many people are talking: 0 = work it out (ADR-027).
    @Published var speakerCount: Int = UserDefaults.standard.integer(forKey: "speakerCount") {
        didSet {
            UserDefaults.standard.set(speakerCount, forKey: "speakerCount")
            apply(regroupSpeakers())
        }
    }

    enum BackgroundFilter: String, CaseIterable, Identifiable {
        case off = "OFF", dim = "DIM", hide = "HIDE"
        var id: String { rawValue }
        var label: String { self == .off ? "關" : self == .dim ? "淡化" : "隱藏" }
    }

    /// 背景過濾 (ADR-035): quiet speech and, once someone is remembered, unknown voices count
    /// as background, shown dimmed or hidden. Offline engine only. Off in TV mode.
    @Published var backgroundFilter: BackgroundFilter = BackgroundFilter(rawValue: UserDefaults.standard.string(forKey: "backgroundFilter") ?? "") ?? .off {
        didSet {
            UserDefaults.standard.set(backgroundFilter.rawValue, forKey: "backgroundFilter")
            applyBackgroundMode(restart: true)
        }
    }
    private var tvMode: Bool { UserDefaults.standard.bool(forKey: "tvMode") }
    private var effectiveBackground: BackgroundFilter { tvMode ? .off : backgroundFilter }

    private func applyBackgroundMode(restart: Bool) {
        assembler.backgroundMode = IosBridgeKt.backgroundModeOf(name: effectiveBackground.rawValue)
        if restart, running, engineId != EngineInfo.azureId || budgetFallback,
           offline?.backgroundFilter != (effectiveBackground != .off) {
            stop(keepBudgetChoice: true)
            Task { await start() }
        }
        apply(assembler.state)
    }

    /// The caption screen switched TV mode: everything shows while it is on.
    func tvModeChanged() { applyBackgroundMode(restart: true) }

    /// 說話者辨識 (ADR-033). Off: no voice prints, no speaker tags, lines split by pauses only.
    /// Takes effect the next time listening starts.
    @Published var speakersOn: Bool = UserDefaults.standard.object(forKey: "speakersOn") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(speakersOn, forKey: "speakersOn")
            if running { stop(); Task { await start() } }
        }
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
    /// The monthly cloud limit was passed (ADR-030): the view asks whether to keep using the cloud.
    @Published var budgetAlert: String?
    /// This session runs offline because of the limit, although the chosen engine is a cloud one.
    @Published private(set) var budgetFallback = false
    /// The user chose to keep using the cloud this session.
    private var budgetOverride = false
    /// Asked once per session.
    private var budgetAsked = false
    private var usageWatch: AnyCancellable?
    private var budgetBlocked: Bool { UsageLog.shared.overLimit && !budgetOverride }

    /// Saves this start…stop as a transcript (ADR-029).
    private var recorder: TranscriptRecorder?

    /// Which start…stop the events belong to: bumped on every start, so late events and
    /// second-pass revisions from an earlier session never land on the new one's numbering.
    private var session = 0

    /// What the engines hand back, tagged with their session.
    private enum Delivery {
        case events([TranscriptEvent])
        case revision(Int, String)
    }
    private typealias Tagged = (session: Int, delivery: Delivery)

    /// One queue for everything engines deliver, read in order on the main actor (separate
    /// `Task { @MainActor }` hops may run out of order).
    private let deliveries: AsyncStream<Tagged>.Continuation

    init() {
        var continuation: AsyncStream<Tagged>.Continuation!
        let stream = AsyncStream<Tagged> { continuation = $0 }
        deliveries = continuation
        Task { [weak self] in
            for await item in stream {
                guard let self else { return }
                guard item.session == self.session else { continue }
                switch item.delivery {
                case .events(let events): self.handle(events)
                case .revision(let n, let text): self.revise(n, text)
                }
            }
        }
        audio.onStopped = { [weak self] message in
            guard let self, self.running else { return }
            self.stop()
            self.error = message
        }
    }

    func toggle() {
        if running { stop() } else { Task { await start() } }
    }

    func start() async {
        guard !running, !preparing else { return }
        error = nil
        if usageWatch == nil {
            usageWatch = UsageLog.shared.$days.dropFirst().receive(on: DispatchQueue.main).sink { [weak self] _ in
                self?.checkBudget()
            }
        }
        preparing = true
        defer { preparing = false }
        guard await AudioCapture.requestPermission() else {
            error = "請到「設定 > 隱私權與安全性 > 麥克風」允許「即時字幕」使用麥克風"
            return
        }
        session += 1
        let deliver = Self.eventHandler(deliveries, session: session)
        var begun: CaptionEngine?
        do {
            let engine: CaptionEngine
            var useCloud = engineId == EngineInfo.azureId
            if useCloud, budgetBlocked {
                useCloud = false
                budgetFallback = true
                budgetAsked = true
                budgetAlert = Self.budgetMessage(switched: true)
            }
            if useCloud {
                guard let key = KeyVault.get(KeyVault.azureKey) else {
                    error = "請先在「雲端服務設定」填入 Azure 金鑰"
                    showCloudSettings = true
                    return
                }
                let azure = AzureEngine(key: key, region: AzureEngine.savedRegion)
                azure.speakers = speakersOn
                azure.phrases = vocabulary
                engine = azure
            } else {
                if !models.isReady { try await models.download() }
                let wantSecond = SecondPassModel.isEnabled && SecondPassModel.available && secondPassStore.isReady
                let wantSpeakers = speakersOn
                let wantBackground = effectiveBackground != .off
                assembler.backgroundMode = IosBridgeKt.backgroundModeOf(name: effectiveBackground.rawValue)
                let wantHotwords = vocabulary
                if offline == nil || offline?.hasSecondPass != wantSecond || offline?.hasSpeakers != wantSpeakers || offline?.backgroundFilter != wantBackground || offline?.hotwords != wantHotwords {
                    let files = models.files
                    let second = wantSecond ? secondPassStore.directory : nil
                    let registry = VoiceStore.shared.registry
                    offline = await Task.detached(priority: .userInitiated) {
                        OfflineEngine(models: files, speakers: wantSpeakers, secondPassDirectory: second, registry: registry, backgroundFilter: wantBackground, hotwords: wantHotwords)
                    }.value
                }
                guard let offline else { return }
                offline.onRevision = { [deliveries, session] n, text in
                    deliveries.yield((session: session, delivery: .revision(n, text)))
                }
                engine = offline
            }
            finalSeqs.removeAll()
            pendingRevisions.removeAll()
            pausedForKey = nil
            proofreadNotice = nil
            finalsSeen = 0
            recorder = TranscriptStore.isEnabled ? TranscriptRecorder(engine: EngineInfo.byId(engineId).name, existing: lines) : nil
            try engine.begin(onEvents: deliver)
            begun = engine
            try audio.start(onSamples: Self.audioHandler(engine: engine, deliver: deliver))
            self.engine = engine
            running = true
            UIApplication.shared.isIdleTimerDisabled = true
        } catch {
            // Nothing may keep running after a failed start: the microphone, the engine, the recording.
            audio.stop()
            begun?.finish(onEvents: deliver)
            recorder = nil
            self.error = error.localizedDescription
        }
    }

    /// Usage went up: past the limit, a cloud session moves to offline and proof-reading stops.
    private func checkBudget() {
        guard running, budgetBlocked, !budgetAsked else { return }
        let onCloud = engineId == EngineInfo.azureId && !budgetFallback
        guard onCloud || Proofreader.isActive else { return }
        proofreadTimer?.cancel()
        budgetAsked = true
        if onCloud {
            stop(keepBudgetChoice: true)
            if models.isReady {
                budgetFallback = true
                Task { await start() }
                budgetAlert = Self.budgetMessage(switched: true)
            } else {
                budgetAlert = Self.budgetMessage(switched: false)
            }
        } else {
            budgetAlert = Self.budgetMessage(switched: false)
        }
    }

    private static func budgetMessage(switched: Bool) -> String {
        let log = UsageLog.shared
        let head = "本月雲端估計費用 \(UsageView.usd(log.monthCost))，已超過上限 \(UsageView.usd(log.monthlyLimit))。"
        return head + (switched ? "這次已改用離線辨識，AI 校稿暫停。" : "已暫停雲端辨識和 AI 校稿。")
    }

    /// From the limit alert: keep using the cloud for the rest of this session.
    func continueWithCloud() {
        budgetAlert = nil
        budgetOverride = true
        if budgetFallback || (!running && engineId == EngineInfo.azureId) {
            stop(keepBudgetChoice: true)
            budgetFallback = false
            Task { await start() }
        } else {
            scheduleProofreading()
        }
    }

    func selectEngine(_ id: String) {
        if running { stop() }
        engineId = id
        error = nil
        if EngineInfo.byId(id).needsKey, KeyVault.get(KeyVault.azureKey) == nil { showCloudSettings = true }
    }

    func stop() {
        stop(keepBudgetChoice: false)
    }

    private func stop(keepBudgetChoice: Bool) {
        guard running else { return }
        if !keepBudgetChoice {
            budgetOverride = false
            budgetFallback = false
            budgetAsked = false
        }
        audio.stop()
        running = false
        level = 0
        UIApplication.shared.isIdleTimerDisabled = false
        engine?.finish(onEvents: Self.eventHandler(deliveries, session: session))
        engine = nil
        recorder?.finish()
        scheduleProofreading()
    }

    /// Shared rule with Android (ADR-039): with 說話人數「自動」 letters come from live grouping only;
    /// with a number of people every line is grouped again and the live grouping takes it over.
    private func regroupSpeakers() -> CaptionState {
        let clusterer = offline.flatMap { $0.hasSpeakers ? $0.clusterer : nil }
        return SpeakerPolicy.shared.afterFinal(assembler: assembler, clusterer: clusterer, speakers: Int32(speakerCount))
    }

    func clear() {
        if let offline { IosBridgeKt.resetSpeakers(clusterer: offline.clusterer) }
        assembler.reset()
        finalSeqs.removeAll()
        reviewed.removeAll()
        apply(assembler.state)
    }

    /// Whether line [id] has a voice print (offline engine with speakers on), so it can be remembered.
    func hasVoice(_ id: Int64) -> Bool {
        guard let i = lines.firstIndex(where: { $0.id == id }), i < assembler.state.lines.count else { return false }
        return assembler.state.lines[i].voice != nil
    }

    /// 「這是誰」: names the speaker of line [id] (all their lines with [wholeSpeaker]) and, with
    /// [remember], learns the voice so later sessions show the name too (ADR-034).
    func nameSpeaker(of id: Int64, as name: String, wholeSpeaker: Bool, remember: Bool) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, let i = lines.firstIndex(where: { $0.id == id }), i < assembler.state.lines.count else { return }
        if remember, let voice = assembler.state.lines[i].voice {
            // The background filter is left as the user set it (same as Android).
            VoiceStore.shared.remember(name: name, voice: voice)
        }
        apply(wholeSpeaker ? assembler.nameSpeaker(index: Int32(i), name: name) : assembler.nameLine(index: Int32(i), name: name))
    }

    /// A person was renamed on the 認得的人 page: their lines on screen follow.
    func renameSpeaker(_ old: String, to name: String) {
        apply(assembler.renameSpeaker(oldName: old, name: name))
    }

    /// Frees the space of the downloaded offline models; they download again on the next start.
    func removeOfflineModels() {
        if running && (engineId != EngineInfo.azureId || budgetFallback) { stop() }
        offline = nil
        models.delete()
    }

    func removeSecondPassModel() {
        if running && offline?.hasSecondPass == true { stop() }
        offline = nil
        secondPassStore.delete()
    }

    func changeFontSize(by delta: Double) {
        fontSize = min(72, max(20, fontSize + delta))
    }

    /// Built outside the main actor: the audio callback runs on the audio thread.
    private nonisolated static func audioHandler(engine: CaptionEngine, deliver: @escaping ([TranscriptEvent]) -> Void) -> ([Float]) -> Void {
        return { samples in engine.feed(samples, onEvents: deliver) }
    }

    /// Hands pipeline events (from the decoding queue) to the main actor, in order.
    private nonisolated static func eventHandler(_ deliveries: AsyncStream<Tagged>.Continuation, session: Int) -> ([TranscriptEvent]) -> Void {
        return { events in deliveries.yield((session: session, delivery: .events(events))) }
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
    /// The short wait before a round; cancelling it never cancels a request already sent.
    private var proofreadTimer: Task<Void, Never>?
    private var proofreading = false
    /// The key that failed for good (wrong key, no credit…): proof-reading waits for another key.
    private var pausedForKey: String?
    /// After a failure that may pass (timeout, network, busy): no request before this time.
    private var retryAfter: Date?
    private var failuresInARow = 0
    private var noticeTimer: Task<Void, Never>?

    /// Waits for a short pause in the conversation, then proof-reads the lines not yet checked.
    private func scheduleProofreading() {
        guard Proofreader.isActive, !proofreading, !budgetBlocked else { return }
        if let paused = pausedForKey {
            guard KeyVault.get(KeyVault.anthropicKey) != paused else { return }
            pausedForKey = nil   // a new key was saved: try again
            proofreadNotice = nil
        }
        proofreadTimer?.cancel()
        let unchecked = lines.filter { reviewed[$0.id] != $0.text }.count
        var delay: TimeInterval = unchecked >= Int(Proofreading.shared.MAX_LINES) ? 0 : 2
        if let retryAfter { delay = max(delay, retryAfter.timeIntervalSinceNow) }
        proofreadTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(0, delay) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            // Its own task, so a later schedule (which cancels the timer) cannot cancel the request.
            Task { await self?.proofreadNow() }
        }
    }

    private func showProofreadNotice(_ text: String, sticky: Bool) {
        proofreadNotice = text
        noticeTimer?.cancel()
        guard !sticky else { return }
        noticeTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard !Task.isCancelled else { return }
            self?.proofreadNotice = nil
        }
    }

    private func proofreadNow() async {
        guard Proofreader.isActive, !proofreading, !budgetBlocked, let key = KeyVault.get(KeyVault.anthropicKey), key != pausedForKey else { return }
        // The last line may still grow while someone is talking; leave it for the next round.
        let candidates = lines.enumerated().filter { i, l in
            reviewed[l.id] != l.text && !(i == lines.count - 1 && (continuesLastLine || running && !(stableText + tentativeText).isEmpty))
        }
        let batch = Array(candidates.prefix(Int(Proofreading.shared.MAX_LINES)))
        guard let firstIndex = batch.first?.offset else { return }
        let context = lines[max(0, firstIndex - Int(Proofreading.shared.CONTEXT_LINES))..<firstIndex].map { "\($0.speaker ?? "?")：\($0.text)" }
        let sent = batch.map { (id: $0.element.id, speaker: $0.element.speaker, text: $0.element.text) }
        let before = Dictionary(sent.map { ($0.id, reviewed[$0.id]) }, uniquingKeysWith: { a, _ in a })
        for l in sent { reviewed[l.id] = l.text }
        proofreading = true
        do {
            let fixed = try await Proofreader.proofread(key: key, model: Proofreader.model, context: Array(context), lines: sent)
            proofreading = false
            failuresInARow = 0
            retryAfter = nil
            for l in sent {
                guard let text = fixed[l.id], lines.first(where: { $0.id == l.id })?.text == l.text else { continue }
                apply(assembler.replaceLineText(lineId: l.id, text: text))
                reviewed[l.id] = text
            }
        } catch {
            proofreading = false
            // Not checked after all: send these lines again next round.
            for l in sent where reviewed[l.id] == l.text { reviewed[l.id] = before[l.id] ?? nil }
            if error is CancellationError { return }
            let reason = error.localizedDescription
            if let f = error as? Proofreader.Failure, f.permanent {
                pausedForKey = key
                showProofreadNotice("AI 校稿暫停：\(reason)", sticky: true)
                return
            }
            failuresInARow += 1
            var wait = min(60, 5 * pow(2, Double(failuresInARow - 1)))
            // Rate-limited: the server says when to come back (at most 10 minutes).
            if let asked = (error as? Proofreader.Failure)?.retryAfter { wait = min(600, max(wait, asked.rounded(.up))) }
            retryAfter = Date().addingTimeInterval(wait)
            if failuresInARow >= 2 { showProofreadNotice("AI 校稿\(reason)，\(Int(wait)) 秒後重試", sticky: false) }
        }
        if lines.contains(where: { reviewed[$0.id] != $0.text }) { scheduleProofreading() }
    }

    private func handle(_ events: [TranscriptEvent]) {
        // Most batches are only the meter (several per second): update just the level.
        if events.allSatisfy({ $0 is TranscriptEventAudioLevel }) {
            var state = assembler.state
            for e in events { state = assembler.onEvent(event: e) }
            if level != state.audioLevel { level = state.audioLevel }
            return
        }
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
        if sawFinal {
            state = regroupSpeakers()
        }
        apply(state)
        if sawFinal { scheduleProofreading() }
        // A fatal engine error (e.g. a wrong Azure key) ends listening.
        if running, events.contains(where: { ($0 as? TranscriptEventError)?.recoverable == false }) { stop() }
    }

    private func apply(_ state: CaptionState) {
        let newLines = state.lines.enumerated().map { i, line in
            Line(id: assembler.lineId(index: Int32(i)), text: line.text, speaker: IosBridgeKt.speakerLabelOf(line: line), background: line.background)
        }
        if newLines != lines {
            lines = newLines
            // Keeps following corrections after stop, until the next start.
            recorder?.update(newLines, recording: running)
        }
        // Every assignment to @Published redraws the view, even with the same value.
        if stableText != state.stableText { stableText = state.stableText }
        if tentativeText != state.tentativeText { tentativeText = state.tentativeText }
        let speaker = IosBridgeKt.currentSpeakerLabelOf(state: state)
        if currentSpeaker != speaker { currentSpeaker = speaker }
        let continues = state.continuesLastLine && !newLines.isEmpty
        if continuesLastLine != continues { continuesLastLine = continues }
        if currentBackground != state.currentBackground { currentBackground = state.currentBackground }
        if level != state.audioLevel { level = state.audioLevel }
        if hint != state.hint { hint = state.hint }
        if let e = state.error, error != e { error = e }
    }
}
