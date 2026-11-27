import CryptoKit
import Foundation

/// Paths of the offline model files, wherever they live (the app's model folder or a test folder).
struct ModelFiles {
    let directory: URL
    var asrEncoder: URL { directory.appendingPathComponent("asr-encoder.onnx") }
    var asrDecoder: URL { directory.appendingPathComponent("asr-decoder.onnx") }
    var asrJoiner: URL { directory.appendingPathComponent("asr-joiner.onnx") }
    var asrTokens: URL { directory.appendingPathComponent("asr-tokens.txt") }
    var vad: URL { directory.appendingPathComponent("silero_vad.onnx") }
    var speaker: URL { directory.appendingPathComponent("speaker.onnx") }

    var all: [URL] { [asrEncoder, asrDecoder, asrJoiner, asrTokens, vad, speaker] }
    var isComplete: Bool { all.allSatisfy { FileManager.default.fileExists(atPath: $0.path) } }
}

/// Downloads the offline models on first use (ADR-012), checked by SHA-256. Same files and hashes as
/// the Android app's ModelStore.
@MainActor
final class ModelStore: ObservableObject {
    struct Remote: Sendable {
        let url: URL
        let sha256: String
        let bytes: Int64
        /// Archive entry file name → local file name; empty for a plain file saved as `localName`.
        let extract: [String: String]
        let localName: String
    }

    private nonisolated static let releases = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    nonisolated static let asr = Remote(
        url: URL(string: "\(releases)/asr-models/sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2")!,
        sha256: "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399",
        bytes: 133_898_007,
        extract: [
            "encoder.int8.onnx": "asr-encoder.onnx",
            "decoder.onnx": "asr-decoder.onnx",
            "joiner.int8.onnx": "asr-joiner.onnx",
            "tokens.txt": "asr-tokens.txt",
        ],
        localName: "asr.tar.bz2"
    )
    nonisolated static let vad = Remote(
        url: URL(string: "\(releases)/asr-models/silero_vad.onnx")!,
        sha256: "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        bytes: 643_854, extract: [:], localName: "silero_vad.onnx"
    )
    nonisolated static let speaker = Remote(
        url: URL(string: "\(releases)/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")!,
        sha256: "53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b",
        bytes: 28_281_165, extract: [:], localName: "speaker.onnx"
    )
    nonisolated static let all = [asr, vad, speaker]
    nonisolated static var totalBytes: Int64 { all.reduce(0) { $0 + $1.bytes } }

    let files: ModelFiles
    @Published private(set) var isReady: Bool
    /// 0...1 while downloading, nil otherwise.
    @Published private(set) var progress: Double?

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = support.appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Models can be downloaded again; keep them out of iCloud backups.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        files = ModelFiles(directory: dir)
        isReady = files.isComplete
    }

    func download() async throws {
        if files.isComplete { isReady = true; return }
        progress = 0
        defer { progress = nil }
        var done: Int64 = 0
        for remote in Self.all {
            if remote.extract.isEmpty, FileManager.default.fileExists(atPath: files.directory.appendingPathComponent(remote.localName).path) {
                done += remote.bytes
                continue
            }
            if !remote.extract.isEmpty, remote.extract.values.allSatisfy({ FileManager.default.fileExists(atPath: files.directory.appendingPathComponent($0).path) }) {
                done += remote.bytes
                continue
            }
            let base = done
            let tmp = try await Downloader.download(remote.url) { [weak self] got in
                Task { @MainActor in self?.progress = Double(base + got) / Double(Self.totalBytes) }
            }
            defer { try? FileManager.default.removeItem(at: tmp) }
            guard try Self.sha256(of: tmp) == remote.sha256 else { throw ModelError.checksum(remote.url.lastPathComponent) }
            if remote.extract.isEmpty {
                let dest = files.directory.appendingPathComponent(remote.localName)
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.moveItem(at: tmp, to: dest)
            } else {
                let dir = files.directory
                try await Task.detached(priority: .userInitiated) {
                    try TarBz2.extract(archive: tmp, entries: remote.extract, to: dir)
                }.value
            }
            done += remote.bytes
            progress = Double(done) / Double(Self.totalBytes)
        }
        isReady = files.isComplete
        if !isReady { throw ModelError.incomplete }
    }

    func delete() {
        for f in files.all { try? FileManager.default.removeItem(at: f) }
        isReady = false
    }

    nonisolated static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 4 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

enum ModelError: LocalizedError {
    case checksum(String)
    case incomplete
    case archive(String)

    var errorDescription: String? {
        switch self {
        case .checksum(let name): return "下載的檔案不完整（\(name)），請再試一次"
        case .incomplete: return "離線模型還沒下載完成"
        case .archive(let why): return "解壓縮模型失敗：\(why)"
        }
    }
}

/// URLSession download with byte progress.
private final class Downloader: NSObject, URLSessionDownloadDelegate {
    private let onProgress: (Int64) -> Void
    private var continuation: CheckedContinuation<URL, Error>?

    private init(onProgress: @escaping (Int64) -> Void) { self.onProgress = onProgress }

    static func download(_ url: URL, onProgress: @escaping (Int64) -> Void) async throws -> URL {
        let d = Downloader(onProgress: onProgress)
        let session = URLSession(configuration: .default, delegate: d, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        return try await withCheckedThrowingContinuation { c in
            d.continuation = c
            session.downloadTask(with: url).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        onProgress(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file at `location` is deleted when this returns, so move it first.
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do {
            if let http = downloadTask.response as? HTTPURLResponse, http.statusCode != 200 {
                throw URLError(.badServerResponse)
            }
            try FileManager.default.moveItem(at: location, to: dest)
            continuation?.resume(returning: dest)
        } catch {
            continuation?.resume(throwing: error)
        }
        continuation = nil
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { continuation?.resume(throwing: error); continuation = nil }
    }
}
