import CaptionsCore
import XCTest
@testable import Captions

final class CaptionsTests: XCTestCase {
    func testTranscriptExport() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let t = Transcript(id: UUID(), title: "測試", started: start, ended: start.addingTimeInterval(75), engine: "離線", lines: [
            .init(id: 1, speaker: "A", text: "你好", start: 0.5, end: 1.2),
            .init(id: 2, speaker: "B", text: "早安", start: 1.6, end: 3725.25),
        ])
        XCTAssertEqual(Transcript.srtTime(3725.25), "01:02:05,250")
        let srt = t.srt
        XCTAssertTrue(srt.hasPrefix("1\n00:00:00,500 --> 00:00:01,600\nA：你好\n\n2\n"), srt)
        XCTAssertTrue(srt.contains("B：早安"))
        XCTAssertTrue(t.plainText.contains("A：你好\n"))
        XCTAssertFalse(t.fileStem.contains("/"))
    }

    func testUsageCosts() {
        var day = UsageLog.Day()
        day.claude["claude-haiku-5-5"] = .init(requests: 2, failures: 0, inputTokens: 1_000_000, outputTokens: 1_000_000)
        XCTAssertEqual(UsageLog.shared.claudeCost(day), UsageLog.shared.prices.haikuInput + UsageLog.shared.prices.haikuOutput, accuracy: 1e-9)
    }

    func testFloatArraysRoundTrip() {
        let samples: [Float] = (0..<1600).map { Float($0) / 1600 }
        XCTAssertEqual(FloatArrays.toSwift(FloatArrays.toKotlin(samples)), samples)
        XCTAssertEqual(FloatArrays.toSwift(FloatArrays.toKotlin([])), [])
    }

    func testTaiwanWordingFromBundledTables() {
        let converter = TaiwanChinese.makeConverter()
        XCTAssertEqual(converter.toTraditional(text: "这个软件的视频很好看"), "這個軟體的影片很好看")
        XCTAssertEqual(converter.toTraditional(text: "需要重新启动"), "需要重新啟動")
    }

    func testAssemblerCleansUpText() {
        let assembler = IosBridgeKt.createCaptionAssembler(converter: TaiwanChinese.makeConverter())
        let state = assembler.onEvent(event: TranscriptEventFinal(text: "我们 坐 出租车 去,好吗?", speaker: nil, background: false, speakerName: nil, voice: nil))
        XCTAssertEqual(state.lines.map(\.text), ["我們坐計程車去，好嗎？"])
    }

    /// End to end on the simulator: unpack the real model archive with TarBz2, then caption the
    /// model's own test recordings with the offline engine. CI downloads the files first and passes
    /// the folder in CAPTIONS_MODEL_DOWNLOADS (see .github/workflows/ios.yml); skipped otherwise.
    func testOfflineEngineOnRecordings() throws {
        guard let downloads = ProcessInfo.processInfo.environment["CAPTIONS_MODEL_DOWNLOADS"] else {
            throw XCTSkip("CAPTIONS_MODEL_DOWNLOADS not set")
        }
        let src = URL(fileURLWithPath: downloads)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("models-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let unpack = Date()
        var entries = ModelStore.asr.extract
        for i in 0..<4 { entries["\(i).wav"] = "test\(i).wav" }
        try TarBz2.extract(archive: src.appendingPathComponent("asr.tar.bz2"), entries: entries, to: dir)
        print("unpack: \(String(format: "%.1f", Date().timeIntervalSince(unpack))) s")
        for name in ["silero_vad.onnx", "speaker.onnx"] {
            try FileManager.default.copyItem(at: src.appendingPathComponent(name), to: dir.appendingPathComponent(name))
        }
        let files = ModelFiles(directory: dir)
        XCTAssertTrue(files.isComplete)
        for f in files.all {
            let size = (try FileManager.default.attributesOfItem(atPath: f.path)[.size] as? NSNumber)?.intValue ?? 0
            XCTAssertGreaterThan(size, 1000, f.lastPathComponent)
        }

        let engine = OfflineEngine(models: files)
        let converter = TaiwanChinese.makeConverter()
        var allText = ""
        for i in 0..<4 {
            let samples = try WavReader.read16kMono(dir.appendingPathComponent("test\(i).wav"))
            let start = Date()
            let finals = engine.transcribe(samples: samples)
            let seconds = Date().timeIntervalSince(start)
            let audioSeconds = Double(samples.count) / 16_000
            let text = finals.map { "[\(IosBridgeKt.speakerLabelOf(line: CaptionLine(text: "", speaker: $0.speaker, background: false, speakerName: nil, voice: nil)) ?? "-")] \(converter.toTraditional(text: $0.text))" }
            print("test\(i).wav \(String(format: "%.1f", audioSeconds)) s audio, RTF \(String(format: "%.2f", seconds / audioSeconds)): \(text.joined(separator: " / "))")
            allText += finals.map(\.text).joined()
        }
        let cjk = allText.unicodeScalars.filter { (0x4E00...0x9FFF).contains($0.value) }.count
        XCTAssertGreaterThan(cjk, 10, "expected Chinese text, got: \(allText)")
    }

    func testKeyVaultRoundTrip() throws {
        let name = "test_key_\(UUID().uuidString)"
        XCTAssertNil(KeyVault.get(name))
        let status = KeyVault.put(name, "abcd1234")
        // CI builds the test host unsigned, and an unsigned app has no Keychain access.
        if status == errSecMissingEntitlement { throw XCTSkip("Keychain needs a signed app (CI builds unsigned)") }
        XCTAssertEqual(status, errSecSuccess)
        XCTAssertEqual(KeyVault.get(name), "abcd1234")
        XCTAssertEqual(KeyVault.hint(name), "••••1234")
        KeyVault.remove(name)
        XCTAssertNil(KeyVault.get(name))
    }

    func testAzureSpeakerLabels() {
        XCTAssertEqual(AzureEngine.speakerIndex("Guest-1"), 0)
        XCTAssertEqual(AzureEngine.speakerIndex("Guest-3"), 2)
        XCTAssertEqual(AzureEngine.speakerIndex("Unknown"), -1)
        XCTAssertEqual(AzureEngine.speakerIndex(nil), -1)
        let assembler = IosBridgeKt.createCaptionAssembler(converter: TaiwanChinese.makeConverter())
        let state = assembler.onEvent(event: IosBridgeKt.finalEvent(text: "你好", speakerIndex: 1))
        XCTAssertEqual(state.lines.compactMap { IosBridgeKt.speakerLabelOf(line: $0) }, ["B"])
    }

    /// Links and runs the Azure SDK end to end: a made-up key must come back as a clear error.
    func testAzureReportsBadKey() async throws {
        let engine = AzureEngine(key: String(repeating: "0", count: 32), region: "eastasia")
        let got = expectation(description: "error event")
        let lock = NSLock()
        var message: String?
        let handler: ([TranscriptEvent]) -> Void = { events in
            for e in events {
                if let err = e as? TranscriptEventError {
                    lock.lock(); defer { lock.unlock() }
                    if message == nil { message = err.message; got.fulfill() }
                }
            }
        }
        try engine.begin(onEvents: handler)
        engine.feed([Float](repeating: 0, count: 16_000), onEvents: handler)
        await fulfillment(of: [got], timeout: 30)
        engine.finish(onEvents: handler)
        print("Azure with a bad key: \(message ?? "-")")
        XCTAssertTrue(message?.contains("金鑰") == true || message?.contains("Azure") == true)
        let problem = await AzureEngine.testKey(String(repeating: "0", count: 32), region: "eastasia")
        XCTAssertEqual(problem, "金鑰錯誤，或金鑰不屬於這個區域")
    }
}
