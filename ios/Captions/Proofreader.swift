import CaptionsCore
import Foundation

/// AI proof-reading of finished lines with Claude (ADR-026). Off by default; needs the user's own
/// Anthropic API key. Only the caption text is sent (never audio), to api.anthropic.com.
enum Proofreader {
    enum Model: String, CaseIterable, Identifiable {
        case opus = "claude-opus-5-5"
        case haiku = "claude-haiku-5-5"
        var id: String { rawValue }
        var label: String {
            switch self {
            case .opus: return "Claude Opus 5.5（最準）"
            case .haiku: return "Claude Haiku 5.5（快、便宜）"
            }
        }
    }

    static let enabledKey = "proofread_enabled"
    static let modelKey = "proofread_model"

    static var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: enabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    static var model: Model {
        get { UserDefaults.standard.string(forKey: modelKey).flatMap(Model.init(rawValue:)) ?? .opus }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: modelKey) }
    }

    /// Ready to use: switched on and a key is saved.
    /// Checked after every finished line, so it asks KeyVault's cache rather than the Keychain.
    static var isActive: Bool { isEnabled && KeyVault.has(KeyVault.anthropicKey) }

    struct Failure: LocalizedError {
        let errorDescription: String?
        /// Retrying will not help (wrong key, no permission, no credit): proof-reading pauses
        /// until the key changes. Anything else (timeout, network, busy) is retried.
        var permanent = false
        /// The server said how long to wait (retry-after on 429 / busy), in seconds.
        var retryAfter: TimeInterval?
    }

    private static let api = URL(string: "https://api.anthropic.com/v1/messages")!
    private static let version = "2026-10-21"

    /// Corrected text by line id, for the lines the model changed.
    static func proofread(key: String, model: Model, context: [String], lines: [(id: Int64, speaker: String?, text: String)]) async throws -> [Int64: String] {
        let user = Proofreading.shared.userMessage(
            context: context,
            lines: lines.map { ProofreadLine(id: $0.id, speaker: $0.speaker, text: $0.text) }
        )
        let schema = try JSONSerialization.jsonObject(with: Data(Proofreading.shared.JSON_SCHEMA.utf8))
        var body: [String: Any] = [
            "model": model.rawValue,
            "max_tokens": 4000,
            "system": Proofreading.shared.SYSTEM_PROMPT,
            "output_config": ["effort": "low", "format": ["type": "json_schema", "schema": schema]],
            "messages": [["role": "user", "content": user]],
        ]
        var request = URLRequest(url: api, timeoutInterval: 60)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue(version, forHTTPHeaderField: "anthropic-version")
        if model == .opus {
            // If a safety check declines the request, the API retries it on a fallback model.
            body["fallbacks"] = "default"
            request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch let e as URLError {
            if e.code == .cancelled { throw CancellationError() }
            UsageLog.shared.addClaudeFailure(model: model.rawValue)
            throw Failure(errorDescription: e.code == .timedOut ? "等太久沒回應" : "連不上 Anthropic")
        }
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard status == 200 else {
            UsageLog.shared.addClaudeFailure(model: model.rawValue)
            throw Failure(errorDescription: problem(status: status, data: data), permanent: isPermanent(status: status, data: data),
                          retryAfter: retryAfter(http?.value(forHTTPHeaderField: "retry-after")))
        }
        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        if let usage = json?["usage"] as? [String: Any] {
            UsageLog.shared.addClaude(
                model: json?["model"] as? String ?? model.rawValue,
                inputTokens: (usage["input_tokens"] as? NSNumber)?.intValue ?? 0,
                outputTokens: (usage["output_tokens"] as? NSNumber)?.intValue ?? 0
            )
        }
        return try corrections(from: data, lines: lines)
    }

    /// Reads the API response. A cut-off or unreadable answer throws a retryable Failure, so the
    /// lines are sent again rather than marked as checked; a declined request changes nothing.
    static func corrections(from data: Data, lines: [(id: Int64, speaker: String?, text: String)]) throws -> [Int64: String] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure(errorDescription: "回覆格式不對")
        }
        switch json["stop_reason"] as? String {
        case "refusal": return [:]   // no usable answer: keep the captions as they are
        case "max_tokens": throw Failure(errorDescription: "回覆被截斷")
        default: break
        }
        let text = (json["content"] as? [[String: Any]] ?? [])
            .filter { $0["type"] as? String == "text" }
            .compactMap { $0["text"] as? String }
            .joined()
        guard let answer = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let items = answer["lines"] as? [[String: Any]] else { throw Failure(errorDescription: "回覆格式不對") }
        var out: [Int64: String] = [:]
        for item in items {
            guard let id = (item["id"] as? NSNumber)?.int64Value, let t = item["text"] as? String,
                  let original = lines.first(where: { $0.id == id })?.text,
                  Proofreading.shared.accept(original: original, corrected: t) else { continue }
            out[id] = t.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return out
    }

    /// nil when the key works; otherwise what is wrong, in words. Listing models costs nothing.
    static func testKey(_ key: String) async -> String? {
        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/models?limit=1")!, timeoutInterval: 15)
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue(version, forHTTPHeaderField: "anthropic-version")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return status == 200 ? nil : problem(status: status, data: data)
        } catch {
            return "連不上 Anthropic，請確認網路"
        }
    }

    /// Seconds from a retry-after header (seconds or an HTTP date); nil if missing or unreadable.
    static func retryAfter(_ value: String?) -> TimeInterval? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        if let seconds = Double(value) { return max(0, seconds) }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f.date(from: value).map { max(0, $0.timeIntervalSinceNow) }
    }

    private static func isPermanent(status: Int, data: Data) -> Bool {
        switch status {
        case 401, 402, 403: return true
        case 400: return String(data: data, encoding: .utf8)?.contains("credit") == true
        default: return false
        }
    }

    private static func problem(status: Int, data: Data) -> String {
        switch status {
        case 401: return "金鑰不對，請重新貼上"
        case 403: return "這組金鑰沒有權限使用這個模型"
        case 429: return "用量太多，稍後再試"
        case 500...599: return "Anthropic 服務忙碌"
        case 402: return "帳號額度不足，請到 Anthropic 主控台儲值"
        case 400 where String(data: data, encoding: .utf8)?.contains("credit") == true: return "帳號額度不足，請到 Anthropic 主控台儲值"
        default: return "校稿服務錯誤（\(status)）"
        }
    }
}
