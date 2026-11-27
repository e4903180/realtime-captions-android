import CaptionsCore
import Foundation

/// The whole-sentence model for the second pass (ADR-025): after a line ends, it hears that line's
/// audio again with the whole sentence as context, and its text replaces the live text.
/// Chosen from the Taiwan-accent comparison in docs/benchmark-second-pass.md.
enum SecondPassModel {
    static let name = "SenseVoice"
    static let kind = "sense_voice" // or "paraformer"
    static let remote = ModelStore.Remote(
        url: URL(string: "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2")!,
        sha256: "SECOND_PASS_SHA256",
        bytes: 0,
        extract: ["model.int8.onnx": "sp-model.onnx", "tokens.txt": "sp-tokens.txt"],
        localName: "sp.tar.bz2"
    )

    static func model(in dir: URL) -> URL { dir.appendingPathComponent("sp-model.onnx") }
    static func tokens(in dir: URL) -> URL { dir.appendingPathComponent("sp-tokens.txt") }

    /// UserDefaults switch; on by default once the model is downloaded.
    static let enabledKey = "second_pass_enabled"
    static var isEnabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }
}

/// sherpa-onnx offline (non-streaming) recognizer. Not thread-safe: use from one queue.
final class SherpaSecondPass {
    private let recognizer: SherpaOnnxOfflineRecognizer

    init(directory: URL) {
        let modelPath = SecondPassModel.model(in: directory).path
        let tokens = SecondPassModel.tokens(in: directory).path
        let modelConfig = SecondPassModel.kind == "paraformer"
            ? sherpaOnnxOfflineModelConfig(
                tokens: tokens,
                paraformer: sherpaOnnxOfflineParaformerModelConfig(model: modelPath),
                numThreads: 2
            )
            : sherpaOnnxOfflineModelConfig(
                tokens: tokens,
                numThreads: 2,
                senseVoice: sherpaOnnxOfflineSenseVoiceModelConfig(model: modelPath, language: "zh", useInverseTextNormalization: true)
            )
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: Sherpa.sampleRate, featureDim: 80),
            modelConfig: modelConfig
        )
        recognizer = SherpaOnnxOfflineRecognizer(config: &config)
    }

    func recognize(_ samples: [Float]) -> String {
        recognizer.decode(samples: samples, sampleRate: Sherpa.sampleRate).text
    }
}

/// Download, size and removal of the second-pass model (kept next to the live models).
@MainActor
final class SecondPassStore: ObservableObject {
    let directory: URL
    @Published private(set) var isReady: Bool
    @Published private(set) var progress: Double?
    @Published var error: String?

    init(directory: URL) {
        self.directory = directory
        isReady = Self.complete(directory)
    }

    nonisolated static func complete(_ dir: URL) -> Bool {
        [SecondPassModel.model(in: dir), SecondPassModel.tokens(in: dir)].allSatisfy { FileManager.default.fileExists(atPath: $0.path) }
    }

    func download() async {
        guard !isReady, progress == nil else { return }
        error = nil
        progress = 0
        defer { progress = nil }
        do {
            try await ModelStore.fetch(SecondPassModel.remote, into: directory) { [weak self] p in
                Task { @MainActor in self?.progress = p }
            }
            isReady = Self.complete(directory)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func delete() {
        try? FileManager.default.removeItem(at: SecondPassModel.model(in: directory))
        try? FileManager.default.removeItem(at: SecondPassModel.tokens(in: directory))
        isReady = false
    }
}
