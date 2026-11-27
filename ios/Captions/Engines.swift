import CaptionsCore
import Foundation

/// What the engine list shows. Numbers come from the Taiwan-accent benchmarks
/// (docs/benchmark-engines.md), same text as the Android app's EngineCatalog.
struct EngineInfo: Identifiable {
    let id: String
    let name: String
    let subtitle: String
    let offline: Bool
    let needsKey: Bool
    let recommended: Bool

    static let offlineId = "sherpa-onnx"
    static let azureId = "azure"

    static let all = [
        EngineInfo(id: offlineId, name: "離線＋說話者",
                   subtitle: "不用網路、免費，約 1 秒出字，會標示 A／B／C 說話者。台灣腔錯字率約 12%。",
                   offline: true, needsKey: false, recommended: true),
        EngineInfo(id: azureId, name: "Azure 雲端",
                   subtitle: "台灣腔最準（錯字率約 5%），約 1.3 秒出字，會標示說話者。需要網路和你自己的 Azure 金鑰，按使用時間計費。",
                   offline: false, needsKey: true, recommended: false),
    ]

    static func byId(_ id: String) -> EngineInfo { all.first { $0.id == id } ?? all[0] }
}

/// A running recognizer that takes 16 kHz mono audio and hands back TranscriptEvents.
/// Audio arrives on the audio thread; events may arrive on any thread.
protocol CaptionEngine: AnyObject {
    func begin(onEvents: @escaping ([TranscriptEvent]) -> Void) throws
    func feed(_ samples: [Float], onEvents: @escaping ([TranscriptEvent]) -> Void)
    func finish(onEvents: @escaping ([TranscriptEvent]) -> Void)
}

extension OfflineEngine: CaptionEngine {
    func begin(onEvents: @escaping ([TranscriptEvent]) -> Void) throws { restartCount() }
}
