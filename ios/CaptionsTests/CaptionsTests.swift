import CaptionsCore
import XCTest
@testable import Captions

final class CaptionsTests: XCTestCase {
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
}
