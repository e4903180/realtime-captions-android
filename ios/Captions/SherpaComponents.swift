import CaptionsCore
import os
import Foundation

/// sherpa-onnx parts plugged into the shared Kotlin StreamingPipeline. Settings match the Android
/// app (app/src/main/java/tw/captions/app/engine/SherpaOnnxEngine.kt) and the desktop benchmark.
enum Sherpa {
    static let sampleRate = 16_000
}

/// Streaming X-ASR zipformer (zh + en, punctuation; the 160 ms model, fed 20 ms of audio at a time).
final class SherpaAsr: NSObject, StreamingAsr {
    private let recognizer: SherpaOnnxRecognizer

    /// [hotwords]: the user's 自訂詞彙. With any, decoding switches to modified beam search with the
    /// words boosted (ADR-038, same as Android): names right 43% → 74%, no false insertions.
    init(models: ModelFiles, hotwords: [String] = []) {
        let boost = Self.prepareHotwords(models: models, words: hotwords)
        let transducer = sherpaOnnxOnlineTransducerModelConfig(
            encoder: models.asrEncoder.path,
            decoder: models.asrDecoder.path,
            joiner: models.asrJoiner.path
        )
        let modelConfig = sherpaOnnxOnlineModelConfig(
            tokens: models.asrTokens.path,
            transducer: transducer,
            // 4 threads, like Android (ADR-021): phones are slower than the benchmark PC, so compute matters more.
            numThreads: 4,
            modelingUnit: boost != nil ? "bpe" : "",
            bpeVocab: boost?.vocab.path ?? ""
        )
        var config = sherpaOnnxOnlineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: Sherpa.sampleRate, featureDim: 80),
            modelConfig: modelConfig,
            enableEndpoint: true,
            rule1MinTrailingSilence: 2.4,
            rule2MinTrailingSilence: 0.8,
            rule3MinUtteranceLength: 20,
            decodingMethod: boost != nil ? "modified_beam_search" : "greedy_search",
            maxActivePaths: 4,
            hotwordsFile: boost?.file.path ?? "",
            hotwordsScore: Hotwords.shared.SCORE
        )
        recognizer = SherpaOnnxRecognizer(config: &config)
        super.init()
    }

    /// Writes the hotwords file and the BPE vocabulary (built from tokens.txt) next to the model,
    /// or nil when there are no words or the files cannot be written.
    private static func prepareHotwords(models: ModelFiles, words: [String]) -> (file: URL, vocab: URL)? {
        let lines = Hotwords.shared.lines(words: words, simplifier: TaiwanChinese.makeSimplifier())
        guard !lines.isEmpty else { return nil }
        let vocab = models.directory.appendingPathComponent("asr-bpe-v2.vocab")
        let file = models.directory.appendingPathComponent("asr-hotwords.txt")
        do {
            if !FileManager.default.fileExists(atPath: vocab.path) {
                let tokens = try String(contentsOf: models.asrTokens, encoding: .utf8)
                    .components(separatedBy: .newlines)
                    .compactMap { Hotwords.shared.tokenPiece(line: $0) }
                try Hotwords.shared.bpeVocab(tokens: tokens).write(to: vocab, atomically: true, encoding: .utf8)
            }
            try lines.joined(separator: "\n").write(to: file, atomically: true, encoding: .utf8)
            return (file, vocab)
        } catch {
            FileWriter.log.error("hotwords: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    func accept(samples: KotlinFloatArray) {
        recognizer.acceptWaveform(samples: FloatArrays.toSwift(samples), sampleRate: Sherpa.sampleRate)
        while recognizer.isReady() { recognizer.decode() }
    }

    func text() -> String { recognizer.getResult().text }
    func isEndpoint() -> Bool { recognizer.isEndpoint() }
    func reset() { recognizer.reset() }
}

/// Silero VAD. The C API exposes the detector state rather than a per-frame probability (which the
/// Android JNI API gives), so silence is reported after a short minimum silence instead.
final class SherpaVad: NSObject, VoiceActivityDetector {
    private let vad: SherpaOnnxVoiceActivityDetectorWrapper

    init(models: ModelFiles, threshold: Float = 0.65) {
        var config = sherpaOnnxVadModelConfig(
            sileroVad: sherpaOnnxSileroVadModelConfig(
                model: models.vad.path,
                threshold: threshold,
                minSilenceDuration: 0.1,
                minSpeechDuration: 0.25,
                windowSize: 512,
                maxSpeechDuration: 30
            ),
            sampleRate: Int32(Sherpa.sampleRate),
            numThreads: 1
        )
        vad = SherpaOnnxVoiceActivityDetectorWrapper(config: &config, buffer_size_in_seconds: 30)
        super.init()
    }

    func accept(samples: KotlinFloatArray) {
        vad.acceptWaveform(samples: FloatArrays.toSwift(samples))
        // Only the on/off state is used; drop finished segments so the buffer stays small.
        while !vad.isEmpty() { vad.pop() }
    }

    func isSpeech() -> Bool { vad.isSpeechDetected() }
    func reset() { vad.reset() }
}

/// 3D-Speaker CAM++ speaker embeddings, clustered online by the shared SpeakerClusterer.
final class SherpaEmbedder: NSObject, SpeakerEmbedder {
    private let extractor: SherpaOnnxSpeakerEmbeddingExtractorWrapper

    init(models: ModelFiles) {
        var config = sherpaOnnxSpeakerEmbeddingExtractorConfig(model: models.speaker.path, numThreads: 1)
        extractor = SherpaOnnxSpeakerEmbeddingExtractorWrapper(config: &config)
        super.init()
    }

    func embed(samples: KotlinFloatArray) -> KotlinFloatArray {
        let stream = extractor.createStream()
        stream.acceptWaveform(samples: FloatArrays.toSwift(samples), sampleRate: Sherpa.sampleRate)
        stream.inputFinished()
        return FloatArrays.toKotlin(extractor.compute(stream: stream))
    }
}
