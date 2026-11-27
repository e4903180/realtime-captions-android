import Foundation

/// How much this phone has used the paid cloud services, kept only on the phone (ADR-028).
/// Azure bills by seconds of audio sent; Claude by tokens, which each response reports.
/// These are estimates for this phone only: the real bill is in the Azure portal and the
/// Anthropic Console, and other devices using the same key are not counted.
final class UsageLog: ObservableObject {
    struct Claude: Codable, Equatable {
        var requests = 0
        var failures = 0
        var inputTokens = 0
        var outputTokens = 0
    }

    struct Day: Codable, Equatable {
        var azureSeconds: Double = 0
        /// By the model that answered (a fallback model can answer an Opus request).
        var claude: [String: Claude] = [:]

        mutating func add(_ other: Day) {
            azureSeconds += other.azureSeconds
            for (model, c) in other.claude {
                var mine = claude[model] ?? Claude()
                mine.requests += c.requests
                mine.failures += c.failures
                mine.inputTokens += c.inputTokens
                mine.outputTokens += c.outputTokens
                claude[model] = mine
            }
        }

        var claudeRequests: Int { claude.values.reduce(0) { $0 + $1.requests } }
        var claudeFailures: Int { claude.values.reduce(0) { $0 + $1.failures } }
        var claudeTokens: Int { claude.values.reduce(0) { $0 + $1.inputTokens + $1.outputTokens } }
    }

    enum Range: String, CaseIterable, Identifiable {
        case today = "今天", month = "本月", all = "全部"
        var id: String { rawValue }
    }

    static let shared = UsageLog()

    /// Azure Speech free tier (F0): 5 audio hours a month.
    static let azureFreeHours = 5.0

    @Published private(set) var days: [String: Day]
    @Published var prices: Prices {
        didSet { prices.save() }
    }
    /// Monthly limit for the estimated cloud cost in US$; 0 = no limit. When it is passed the
    /// app moves to offline recognition for that session and asks first (ADR-030).
    @Published var monthlyLimit: Double = UserDefaults.standard.double(forKey: "usage_limit") {
        didSet { UserDefaults.standard.set(max(0, monthlyLimit), forKey: "usage_limit") }
    }

    var monthCost: Double { azureCost(.month) + claudeCost(total(.month)) }
    var overLimit: Bool { monthlyLimit > 0 && monthCost >= monthlyLimit }

    private static let storageKey = "usage_days"
    private static let dayFormat: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private init() {
        let data = UserDefaults.standard.data(forKey: Self.storageKey)
        days = data.flatMap { try? JSONDecoder().decode([String: Day].self, from: $0) } ?? [:]
        prices = Prices.load()
    }

    // MARK: Recording (safe from any thread)

    func addAzure(seconds: Double) {
        guard seconds > 0 else { return }
        update { $0.azureSeconds += seconds }
    }

    func addClaude(model: String, inputTokens: Int, outputTokens: Int) {
        update { day in
            var c = day.claude[model] ?? Claude()
            c.requests += 1
            c.inputTokens += inputTokens
            c.outputTokens += outputTokens
            day.claude[model] = c
        }
    }

    func addClaudeFailure(model: String) {
        update { day in
            var c = day.claude[model] ?? Claude()
            c.failures += 1
            day.claude[model] = c
        }
    }

    func reset() {
        DispatchQueue.main.async {
            self.days = [:]
            UserDefaults.standard.removeObject(forKey: Self.storageKey)
        }
    }

    private func update(_ change: @escaping (inout Day) -> Void) {
        let key = Self.dayFormat.string(from: Date())
        DispatchQueue.main.async {
            var day = self.days[key] ?? Day()
            change(&day)
            self.days[key] = day
            // Keep a little over a year.
            if self.days.count > 400, let oldest = self.days.keys.min() { self.days[oldest] = nil }
            if let data = try? JSONEncoder().encode(self.days) {
                UserDefaults.standard.set(data, forKey: Self.storageKey)
            }
        }
    }

    // MARK: Reading

    func total(_ range: Range, now: Date = Date()) -> Day {
        let today = Self.dayFormat.string(from: now)
        let month = String(today.prefix(7))
        var sum = Day()
        for (key, day) in days {
            switch range {
            case .today where key != today: continue
            case .month where !key.hasPrefix(month): continue
            default: break
            }
            sum.add(day)
        }
        return sum
    }

    /// Estimated Azure cost in US dollars. With the free tier, the first 5 hours of each month are free.
    func azureCost(_ range: Range, now: Date = Date()) -> Double {
        let today = Self.dayFormat.string(from: now)
        var byMonth: [String: Double] = [:]
        for (key, day) in days { byMonth[String(key.prefix(7)), default: 0] += day.azureSeconds }
        func paid(_ seconds: Double) -> Double {
            let hours = seconds / 3600
            return (prices.azureFreeTier ? max(0, hours - Self.azureFreeHours) : hours) * prices.azurePerHour
        }
        let month = String(today.prefix(7))
        switch range {
        case .all: return byMonth.values.reduce(0) { $0 + paid($1) }
        case .month: return paid(byMonth[month] ?? 0)
        case .today:
            let monthSeconds = byMonth[month] ?? 0
            return paid(monthSeconds) - paid(monthSeconds - (days[today]?.azureSeconds ?? 0))
        }
    }

    /// Estimated Claude cost in US dollars.
    func claudeCost(_ day: Day) -> Double {
        day.claude.reduce(0) { sum, entry in
            let p = prices.claude(entry.key)
            return sum + Double(entry.value.inputTokens) / 1_000_000 * p.input + Double(entry.value.outputTokens) / 1_000_000 * p.output
        }
    }

    /// Editable prices: they change, and depend on the plan.
    struct Prices: Codable, Equatable {
        /// Azure Speech real-time transcription with speaker tags (about 1.00 + 0.30), US$ per audio hour.
        var azurePerHour = 1.3
        /// The key is on the free tier (F0): the first 5 hours each month cost nothing.
        var azureFreeTier = true
        /// US$ per million tokens.
        var opusInput = 4.0
        var opusOutput = 20.0
        var haikuInput = 0.10
        var haikuOutput = 0.50

        func claude(_ model: String) -> (input: Double, output: Double) {
            if model.contains("haiku") { return (haikuInput, haikuOutput) }
            if model == Proofreader.Model.opus.rawValue { return (opusInput, opusOutput) }
            // A fallback model answered: list prices of the earlier Opus and Sonnet models.
            if model.contains("sonnet") { return (2, 10) }
            if model.contains("opus") { return (5, 25) }
            return (opusInput, opusOutput)
        }

        private static let key = "usage_prices"
        static func load() -> Prices {
            UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(Prices.self, from: $0) } ?? Prices()
        }
        func save() {
            if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: Self.key) }
        }
    }
}
