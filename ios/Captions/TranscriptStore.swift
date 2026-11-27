import Foundation

/// Saved transcripts (ADR-029): every start…stop is one transcript, kept only on the phone
/// (Application Support/transcripts, one JSON file each). No audio is kept.
struct Transcript: Codable, Identifiable, Equatable {
    struct Line: Codable, Equatable {
        /// The caption line's id, so later corrections (second pass, AI proof-reading) update it.
        var id: Int64
        var speaker: String?
        var text: String
        /// Seconds from the start of the transcript: when the line appeared, and when it last grew.
        var start: Double
        var end: Double
    }

    var id: UUID
    var title: String?
    var started: Date
    var ended: Date
    var engine: String
    var lines: [Line]

    var duration: TimeInterval { ended.timeIntervalSince(started) }
    var displayTitle: String {
        if let title, !title.isEmpty { return title }
        return Self.dateFormat.string(from: started)
    }

    static let dateFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_TW")
        f.dateFormat = "M月d日 EEE HH:mm"
        return f
    }()

    // MARK: Export

    var plainText: String {
        let clock = DateFormatter()
        clock.dateFormat = "HH:mm:ss"
        var out = "\(displayTitle)\n\(Self.dateFormat.string(from: started))，\(Self.minutes(duration))，\(engine)\n\n"
        for l in lines {
            let time = clock.string(from: started.addingTimeInterval(l.start))
            out += "[\(time)] " + (l.speaker.map { "\($0)：" } ?? "") + l.text + "\n"
        }
        return out
    }

    var srt: String {
        var out = ""
        for (i, l) in lines.enumerated() {
            // Shown until 1 s after its last word (at least 1.5 s), but not past the next line.
            var end = max(l.end + 1, l.start + 1.5)
            if i + 1 < lines.count, lines[i + 1].start > l.start { end = min(end, lines[i + 1].start) }
            out += "\(i + 1)\n\(Self.srtTime(l.start)) --> \(Self.srtTime(end))\n"
            out += (l.speaker.map { "\($0)：" } ?? "") + l.text + "\n\n"
        }
        return out
    }

    static func srtTime(_ t: Double) -> String {
        let ms = Int((max(0, t) * 1000).rounded())
        return String(format: "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    static func minutes(_ t: TimeInterval) -> String {
        let m = Int(t / 60)
        return m >= 60 ? "\(m / 60) 小時 \(m % 60) 分" : (m > 0 ? "\(m) 分鐘" : "\(Int(t)) 秒")
    }

    /// A file name without characters iOS or other systems reject.
    var fileStem: String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd_HHmm"
        let base = "逐字稿_" + f.string(from: started) + (title.map { "_" + $0 } ?? "")
        return base.components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>\n")).joined(separator: "-")
    }
}

@MainActor
final class TranscriptStore: ObservableObject {
    static let shared = TranscriptStore()

    static let enabledKey = "transcripts_enabled"
    static var isEnabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    /// Newest first.
    @Published private(set) var all: [Transcript] = []

    let directory: URL

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        directory = base.appendingPathComponent("transcripts", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
    }

    private func url(_ id: UUID) -> URL { directory.appendingPathComponent(id.uuidString + ".json") }

    private func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        let decoder = JSONDecoder()
        all = files.filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(Transcript.self, from: Data(contentsOf: $0)) }
            .sorted { $0.started > $1.started }
    }

    func save(_ t: Transcript) {
        if let i = all.firstIndex(where: { $0.id == t.id }) { all[i] = t } else { all.insert(t, at: 0) }
        let data = try? JSONEncoder().encode(t)
        let target = url(t.id)
        DispatchQueue.global(qos: .utility).async {
            try? data?.write(to: target, options: [.atomic, .completeFileProtection])
        }
    }

    func rename(_ id: UUID, to title: String) {
        guard var t = all.first(where: { $0.id == id }) else { return }
        t.title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        save(t)
    }

    /// Every line of transcript [id] said by [old] is shown as [name] (also in exports).
    func renameSpeaker(_ id: UUID, from old: String, to name: String) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, var t = all.first(where: { $0.id == id }) else { return }
        for i in t.lines.indices where t.lines[i].speaker == old { t.lines[i].speaker = name }
        save(t)
    }

    func delete(_ id: UUID) {
        all.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: url(id))
    }

    func deleteAll() {
        for t in all { try? FileManager.default.removeItem(at: url(t.id)) }
        all = []
    }

    /// Writes an export into the temporary folder for the share sheet.
    func exportFile(_ t: Transcript, srt: Bool) -> URL {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(t.fileStem + (srt ? ".srt" : ".txt"))
        try? (srt ? t.srt : t.plainText).write(to: file, atomically: true, encoding: .utf8)
        return file
    }
}

/// Follows the caption lines of one start…stop and saves them as a transcript.
@MainActor
final class TranscriptRecorder {
    private(set) var transcript: Transcript
    /// Ids of lines that were on screen before this recording, with their text then: a line
    /// only joins this transcript if it is new or changes while recording.
    private var before: [Int64: String]
    private var dirty = 0
    private var lastSave = Date()

    init(engine: String, existing: [Line]) {
        transcript = Transcript(id: UUID(), title: nil, started: Date(), ended: Date(), engine: engine, lines: [])
        before = Dictionary(existing.map { ($0.id, $0.text) }, uniquingKeysWith: { a, _ in a })
    }

    /// Called with the current caption lines whenever they change.
    func update(_ lines: [Line], recording: Bool) {
        let now = Date().timeIntervalSince(transcript.started)
        var changed = false
        for line in lines {
            if let old = before[line.id] {
                if old == line.text { continue }
                before[line.id] = nil   // a line from before that grows now belongs here too
            }
            if let i = transcript.lines.firstIndex(where: { $0.id == line.id }) {
                guard transcript.lines[i].text != line.text || transcript.lines[i].speaker != line.speaker else { continue }
                transcript.lines[i].text = line.text
                transcript.lines[i].speaker = line.speaker
                if recording { transcript.lines[i].end = now }
            } else {
                transcript.lines.append(.init(id: line.id, speaker: line.speaker, text: line.text, start: now, end: now))
            }
            changed = true
        }
        guard changed else { return }
        if recording { transcript.ended = Date() }
        dirty += 1
        // Save every few lines, so a crash or flat battery loses little.
        if !recording || dirty >= 3 || Date().timeIntervalSince(lastSave) > 20 { flush() }
    }

    private var saved = false

    func flush() {
        guard dirty > 0, !transcript.lines.isEmpty else { return }
        dirty = 0
        lastSave = Date()
        let store = TranscriptStore.shared
        if saved {
            // Deleted from the list meanwhile: leave it deleted. Renamed: keep the new title.
            guard let stored = store.all.first(where: { $0.id == transcript.id }) else { return }
            transcript.title = stored.title
        }
        saved = true
        store.save(transcript)
    }

    func finish() {
        transcript.ended = Date()
        dirty += 1
        flush()
    }
}
