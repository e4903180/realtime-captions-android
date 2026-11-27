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

    /// Second pass (ADR-025): runs on its own queue so live captions never wait for it.
    private let secondPass: SherpaSecondPass?
    private let secondQueue = DispatchQueue(label: "tw.captions.second-pass", qos: .utility)
    private var finalCount = 0
    /// Called with (n, text): the n-th finished line since `begin` (from 0) should read `text`.
    var onRevision: ((Int, String) -> Void)?

    let hasSpeakers: Bool
    /// Live speaker grouping; the view model syncs it after regrouping (ADR-027).
    let clusterer = IosBridgeKt.createSpeakerClusterer(threshold: OfflineEngine.speakerThreshold)
    let backgroundFilter: Bool

    init(models: ModelFiles, speakers: Bool = true, secondPassDirectory: URL? = nil, registry: VoiceRegistry? = nil, backgroundFilter: Bool = false) {
        hasSpeakers = speakers
        self.backgroundFilter = backgroundFilter
        secondPass = secondPassDirectory.map { SherpaSecondPass(directory: $0) }
        pipeline = IosBridgeKt.createOfflinePipeline(
            asr: SherpaAsr(models: models),
            vad: SherpaVad(models: models),
            embedder: speakers ? SherpaEmbedder(models: models) : nil,
            clusterer: clusterer,
            keepAudio: secondPass != nil,
            registry: speakers ? registry : nil,
            backgroundFilter: backgroundFilter
        )
    }

    var hasSecondPass: Bool { secondPass != nil }

    /// Starts counting finished lines from 0 again.
    func restartCount() { queue.async { [self] in finalCount = 0 } }

    /// After a Final, hands its audio to the second pass. Runs on `queue`.
    private func reviseFinals(in events: [TranscriptEvent]) {
        for e in events {
            guard let final = e as? TranscriptEventFinal else { continue }
            let n = finalCount
            finalCount += 1
            guard let secondPass, let audio = pipeline.takeUtteranceAudio() else { continue }
            let samples = FloatArrays.toSwift(audio)
            let live = final.text
            let deliver = onRevision
            secondQueue.async {
                let again = secondPass.recognize(samples)
                if let chosen = SecondPass.shared.choose(live: live, second: again) { deliver?(n, chosen) }
            }
        }
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
                reviseFinals(in: events)
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
            reviseFinals(in: events)
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
