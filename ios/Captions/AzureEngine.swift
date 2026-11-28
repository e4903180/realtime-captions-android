import CaptionsCore
import Foundation
import MicrosoftCognitiveServicesSpeech

/// Azure AI Speech real-time conversation transcription (text + speaker labels) with the user's
/// own key; same settings as the Android app's AzureSpeechEngine. Profanity masking is off: it
/// replaced ordinary words such as 幹線 with "*".
final class AzureEngine: CaptionEngine {
    static let regions: [(id: String, name: String)] = [
        ("eastasia", "East Asia（香港）"),
        ("japaneast", "Japan East（東京）"),
        ("southeastasia", "Southeast Asia（新加坡）"),
        ("westus2", "West US 2"),
        ("westeurope", "West Europe"),
    ]
    static let regionDefaultsKey = "azure_region"
    static var savedRegion: String { UserDefaults.standard.string(forKey: regionDefaultsKey) ?? "eastasia" }

    private let key: String
    private let region: String
    private var stream: SPXPushAudioInputStream?
    private var transcriber: SPXConversationTranscriber?
    private let lock = NSLock()

    /// Off: speaker tags from Azure are ignored (ADR-033).
    var speakers = true

    /// The user's 自訂詞彙 (ADR-038).
    var phrases: [String] = []

    init(key: String, region: String) {
        self.key = key
        self.region = region
    }

    /// Checks a key without sending audio: asks Azure for an access token.
    static func testKey(_ key: String, region: String) async -> String? {
        var req = URLRequest(url: URL(string: "https://\(region).api.cognitive.microsoft.com/sts/v1.0/issueToken")!)
        req.httpMethod = "POST"
        req.setValue(key, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        req.httpBody = Data()
        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
            case 200: return nil
            case 401, 403: return "金鑰錯誤，或金鑰不屬於這個區域"
            case let code: return "Azure 回應 \(code)"
            }
        } catch {
            return "連不上 Azure，請確認網路"
        }
    }

    func begin(onEvents: @escaping ([TranscriptEvent]) -> Void) throws {
        let config = try SPXSpeechConfiguration(subscription: key, region: region)
        config.speechRecognitionLanguage = "zh-TW"
        config.setProfanityOptionTo(.profanityRaw)
        config.setPropertyTo("true", byName: "SpeechServiceResponse_DiarizeIntermediateResults")

        guard let format = SPXAudioStreamFormat(usingPCMWithSampleRate: 16_000, bitsPerSample: 16, channels: 1),
              let stream = SPXPushAudioInputStream(audioFormat: format),
              let audio = SPXAudioConfiguration(streamInput: stream)
        else { throw NSError(domain: "Azure", code: 1, userInfo: [NSLocalizedDescriptionKey: "無法建立 Azure 音訊串流"]) }
        let transcriber = try SPXConversationTranscriber(speechConfiguration: config, audioConfiguration: audio)
        // 自訂詞彙 as a phrase list, like Android: names and terms are preferred.
        if !phrases.isEmpty {
            let grammar: SPXPhraseListGrammar? = SPXPhraseListGrammar(recognizer: transcriber)
            for p in phrases { grammar?.addPhrase(p) }
        }

        let speakers = self.speakers
        transcriber.addTranscribingEventHandler { _, e in
            guard let text = e.result?.text, !text.isEmpty else { return }
            onEvents([IosBridgeKt.partialEvent(text: text, speakerIndex: speakers ? Self.speakerIndex(e.result?.speakerId) : -1)])
        }
        transcriber.addTranscribedEventHandler { _, e in
            guard let text = e.result?.text, !text.isEmpty else { return }
            onEvents([IosBridgeKt.finalEvent(text: text, speakerIndex: speakers ? Self.speakerIndex(e.result?.speakerId) : -1)])
        }
        transcriber.addCanceledEventHandler { _, e in
            let message: String?
            switch e.errorCode {
            case .authenticationFailure, .forbidden: message = "Azure 金鑰無效或區域不對，請到雲端服務設定檢查"
            case .connectionFailure, .serviceTimeout: message = "連不上 Azure，請確認網路"
            case .noError: message = nil
            default: message = "Azure 停止辨識：\(e.errorDetails ?? "")"
            }
            if let message { onEvents([IosBridgeKt.errorEvent(message: message, recoverable: false)]) }
        }
        try transcriber.startTranscribingAsync { started, error in
            if !started {
                onEvents([IosBridgeKt.errorEvent(message: "Azure 無法開始辨識：\(error?.localizedDescription ?? "")", recoverable: false)])
            }
        }
        lock.lock()
        self.stream = stream
        self.transcriber = transcriber
        lock.unlock()
        onEvents([IosBridgeKt.listeningEvent()])
    }

    func feed(_ samples: [Float], onEvents: @escaping ([TranscriptEvent]) -> Void) {
        lock.lock()
        let stream = self.stream
        lock.unlock()
        guard let stream, !samples.isEmpty else { return }
        countSent(samples.count, flush: false)
        var pcm = [Int16](repeating: 0, count: samples.count)
        for i in 0..<samples.count { pcm[i] = Int16(max(-1, min(1, samples[i])) * 32767) }
        stream.write(pcm.withUnsafeBufferPointer { Data(buffer: $0) })
        var copy = samples
        let level = copy.withUnsafeMutableBufferPointer { IosBridgeKt.levelOfSamples(src: $0.baseAddress!, count: Int32($0.count)) }
        onEvents([IosBridgeKt.levelEvent(level: level)])
    }

    /// Audio sent to Azure, in samples, not yet added to the usage log (Azure bills by audio time).
    private var unloggedSamples = 0

    private func countSent(_ samples: Int, flush: Bool) {
        lock.lock()
        unloggedSamples += samples
        let seconds = Double(unloggedSamples) / 16_000
        let log = flush || seconds >= 10
        if log { unloggedSamples = 0 }
        lock.unlock()
        if log { UsageLog.shared.addAzure(seconds: seconds) }
    }

    func finish(onEvents: @escaping ([TranscriptEvent]) -> Void) {
        lock.lock()
        let stream = self.stream
        let transcriber = self.transcriber
        self.stream = nil
        self.transcriber = nil
        lock.unlock()
        countSent(0, flush: true)
        stream?.close()
        DispatchQueue.global(qos: .userInitiated).async {
            try? transcriber?.stopTranscribingAsync { _, _ in }
        }
    }

    /// Azure labels speakers "Guest-1", "Guest-2", …; "Unknown" while it is not sure yet.
    static func speakerIndex(_ id: String?) -> Int32 {
        guard let id, id.hasPrefix("Guest-"), let n = Int32(id.dropFirst(6)) else { return -1 }
        return n - 1
    }
}
