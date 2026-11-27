import AVFoundation

/// Reads an audio file (e.g. a test wav) as 16 kHz mono Float32 samples.
enum WavReader {
    static func read16kMono(_ url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
        let src = file.processingFormat
        guard let inBuf = AVAudioPCMBuffer(pcmFormat: src, frameCapacity: AVAudioFrameCount(file.length)) else { return [] }
        try file.read(into: inBuf)
        if src.sampleRate == 16_000, src.channelCount == 1, src.commonFormat == .pcmFormatFloat32, let ch = inBuf.floatChannelData {
            return Array(UnsafeBufferPointer(start: ch[0], count: Int(inBuf.frameLength)))
        }
        guard let converter = AVAudioConverter(from: src, to: target),
              let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(Double(inBuf.frameLength) * 16_000 / src.sampleRate) + 64)
        else { return [] }
        var fed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed { status.pointee = .endOfStream; return nil }
            fed = true
            status.pointee = .haveData
            return inBuf
        }
        if let error { throw error }
        guard let ch = out.floatChannelData else { return [] }
        return Array(UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength)))
    }
}
