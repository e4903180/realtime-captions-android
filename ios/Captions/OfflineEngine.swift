import CaptionsCore
import Foundation

/// Fully offline engine: sherpa-onnx models driven by the shared Kotlin StreamingPipeline.
/// Audio arrives on the audio thread; decoding runs on one serial queue; events are handed back
/// through `onEvents`.
final class OfflineEngine {
    static let chunkSamples = Sherpa.sampleRate / 10 // 100 ms, like the Android app
    static let speakerThreshold: Float = 0.5

    private let queue = DispatchQueue(label: "tw.captions.pipeline", qos: .userInitiated)
    private let pipeline: StreamingPipeline
    private var pending: [Float] = []

    init(models: ModelFiles, speakers: Bool = true) {
        pipeline = IosBridgeKt.createOfflinePipeline(
            asr: SherpaAsr(models: models),
            vad: SherpaVad(models: models),
            embedder: speakers ? SherpaEmbedder(models: models) : nil,
            speakerThreshold: Self.speakerThreshold
        )
    }

    /// Queues audio for decoding; call from any thread.
    func feed(_ samples: [Float], onEvents: @escaping ([TranscriptEvent]) -> Void) {
        queue.async { [self] in
            pending.append(contentsOf: samples)
            while pending.count >= Self.chunkSamples {
                let chunk = Array(pending.prefix(Self.chunkSamples))
                pending.removeFirst(Self.chunkSamples)
                let events = pipeline.feed(input: FloatArrays.toKotlin(chunk))
                if !events.isEmpty { onEvents(events) }
            }
        }
    }

    /// Ends the current line, e.g. when listening stops.
    func finish(onEvents: @escaping ([TranscriptEvent]) -> Void) {
        queue.async { [self] in
            var events: [TranscriptEvent] = []
            if !pending.isEmpty {
                events += pipeline.feed(input: FloatArrays.toKotlin(pending))
                pending.removeAll()
            }
            events += pipeline.flush()
            onEvents(events)
            pipeline.reset()
        }
    }

    /// Runs a whole recording synchronously (tests and benchmarks). Returns the finished lines.
    func transcribe(samples: [Float]) -> [TranscriptEventFinal] {
        var finals: [TranscriptEventFinal] = []
        queue.sync {
            var i = 0
            while i < samples.count {
                let end = min(i + Self.chunkSamples, samples.count)
                for e in pipeline.feed(input: FloatArrays.toKotlin(Array(samples[i..<end]))) {
                    if let f = e as? TranscriptEventFinal { finals.append(f) }
                }
                i = end
            }
            for e in pipeline.flush() {
                if let f = e as? TranscriptEventFinal { finals.append(f) }
            }
            pipeline.reset()
        }
        return finals
    }
}
