import AVFoundation
import os

/// Microphone input as 16 kHz mono Float32 samples, delivered on the audio thread.
///
/// Survives what iOS does to a running audio engine: calls and Siri (interruptions), plugging in
/// or removing a headset or Bluetooth mic (route / configuration changes, which can change the
/// input format), and the media services restarting. When listening cannot go on, `onStopped`
/// gets a message for the user.
final class AudioCapture {
    private var engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
    private var onSamples: (([Float]) -> Void)?
    private var observers: [NSObjectProtocol] = []
    /// A call or Siri has the microphone; changes wait until the interruption ends.
    private var interrupted = false
    private static let log = Logger(subsystem: "tw.captions", category: "audio")

    /// Capture stopped by itself (interruption not resumed, or restarting failed), with why.
    var onStopped: (@MainActor (String) -> Void)?

    /// Asks for microphone permission; true if granted.
    static func requestPermission() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        }
        return await withCheckedContinuation { c in
            AVAudioSession.sharedInstance().requestRecordPermission { c.resume(returning: $0) }
        }
    }

    func start(onSamples: @escaping ([Float]) -> Void) throws {
        self.onSamples = onSamples
        do {
            try activateSession()
            try startEngine()
            observe()
        } catch {
            stop()
            throw error
        }
    }

    func stop() {
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        interrupted = false
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        converter = nil
        onSamples = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func activateSession() throws {
        let session = AVAudioSession.sharedInstance()
        // .measurement turns off the system's voice processing: like the Android app, the raw
        // signal goes to the models (noise suppression made results worse, see ADR-014).
        try session.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
        try session.setActive(true)
    }

    /// (Re)installs the tap for the input's current format and starts the engine.
    private func startEngine() throws {
        let input = engine.inputNode
        // A tap left from an earlier start (or the format before a route change) would crash installTap.
        input.removeTap(onBus: 0)
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0, let converter = AVAudioConverter(from: format, to: target) else {
            throw NSError(domain: "AudioCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "無法開啟麥克風"])
        }
        self.converter = converter
        let ratio = target.sampleRate / format.sampleRate
        let target = self.target
        let deliver = onSamples

        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 32)
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
            var fed = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true
                status.pointee = .haveData
                return buffer
            }
            guard error == nil, let ch = out.floatChannelData, out.frameLength > 0 else { return }
            deliver?(Array(UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength))))
        }
        engine.prepare()
        try engine.start()
    }

    /// Stops the engine and starts it again with the input as it is now.
    private func restart(reason: String) {
        guard onSamples != nil, !interrupted else { return }
        Self.log.info("restarting audio: \(reason, privacy: .public)")
        engine.stop()
        do {
            try startEngine()
        } catch {
            fail("麥克風切換後無法重新開始收音，已停止聆聽，請再按一次「開始」")
        }
    }

    private func fail(_ message: String) {
        Self.log.error("audio stopped: \(message, privacy: .public)")
        let handler = onStopped
        stop()
        Task { @MainActor in handler?(message) }
    }

    private func observe() {
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
                self?.interruption(note)
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
                guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
                switch reason {
                case .newDeviceAvailable, .oldDeviceUnavailable, .override, .routeConfigurationChange:
                    self?.restart(reason: "route change \(raw)")
                default: break
                }
            },
            // Sent when the engine stopped itself because the input changed (e.g. a new sample rate).
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { [weak self] note in
                guard let self, note.object as? AVAudioEngine === self.engine else { return }
                self.restart(reason: "engine configuration change")
            },
            // The audio server restarted: every audio object is invalid and must be made again.
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main) { [weak self] _ in
                self?.recreate()
            },
        ]
    }

    private func interruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            // The system already paused the engine.
            interrupted = true
        case .ended:
            interrupted = false
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
            guard options.contains(.shouldResume) else {
                fail("麥克風被來電或其他 App 中斷，已停止聆聽")
                return
            }
            do {
                try AVAudioSession.sharedInstance().setActive(true)
                engine.stop()
                try startEngine()
            } catch {
                fail("中斷結束後無法重新開始收音，已停止聆聽，請再按一次「開始」")
            }
        @unknown default:
            break
        }
    }

    private func recreate() {
        guard onSamples != nil else { return }
        Self.log.info("media services were reset: recreating the audio engine")
        engine = AVAudioEngine()
        converter = nil
        interrupted = false
        do {
            try activateSession()
            try startEngine()
        } catch {
            fail("系統音訊服務重新啟動，無法繼續收音，已停止聆聽，請再按一次「開始」")
        }
    }
}
