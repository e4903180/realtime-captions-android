import SwiftUI

/// 雲端用量: what this phone has sent to Azure and Claude, and what it probably costs.
struct UsageView: View {
    @ObservedObject var log = UsageLog.shared
    @State private var range: UsageLog.Range = .month
    @State private var confirmReset = false

    var body: some View {
        let day = log.total(range)
        let azure = log.azureCost(range)
        let claude = log.claudeCost(day)
        List {
            Section {
                Picker("期間", selection: $range) {
                    ForEach(UsageLog.Range.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                HStack {
                    Text("估計費用")
                    Spacer()
                    Text(Self.usd(azure + claude)).font(.title3.bold()).monospacedDigit()
                }
            } footer: {
                Text("只算這支手機用的量，是估計值。實際帳單請看 Azure 入口網站的「成本管理」和 Anthropic 主控台的 Usage。")
            }

            Section {
                row("收音時間", Self.duration(day.azureSeconds))
                row("目前估計費用", Self.usd(azure))
            } header: { Text("Azure 語音") } footer: {
                Text("計費標準：照送出的聲音時間計費，即時轉錄加說話者標籤每小時約 \(Self.usd(log.prices.azurePerHour))。"
                     + (log.prices.azureFreeTier ? "免費方案（F0）每月前 \(Int(UsageLog.azureFreeHours)) 小時免費，超過的部分才算錢。" : ""))
            }

            Section {
                row("請求次數", "\(day.claudeRequests) 次")
                if day.claudeFailures > 0 { row("失敗次數", "\(day.claudeFailures) 次") }
                ForEach(day.claude.keys.sorted(), id: \.self) { model in
                    let c = day.claude[model]!
                    if c.requests > 0 {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model).font(.subheadline)
                            Text("輸入 \(Self.tokens(c.inputTokens))・輸出 \(Self.tokens(c.outputTokens)) token")
                                .font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
                row("目前估計費用", Self.usd(claude))
            } header: { Text("Claude AI 校稿") } footer: {
                Text("計費標準：照 token 計費，每百萬 token Opus 輸入 \(Self.usd(log.prices.opusInput))、輸出 \(Self.usd(log.prices.opusOutput))；Haiku 輸入 \(Self.usd(log.prices.haikuInput))、輸出 \(Self.usd(log.prices.haikuOutput))。")
            }

            Section {
                Toggle("設定每月上限", isOn: Binding(
                    get: { log.monthlyLimit > 0 },
                    set: { log.monthlyLimit = $0 ? 5 : 0 }
                ))
                if log.monthlyLimit > 0 {
                    price("每月上限（美元）", $log.monthlyLimit)
                    row("本月已用", "\(Self.usd(log.monthCost)) / \(Self.usd(log.monthlyLimit))")
                }
            } header: { Text("用量上限") } footer: {
                Text("本月估計費用（Azure＋AI 校稿）超過上限時會跳出提醒，這次收音改用離線辨識並暫停 AI 校稿；想繼續的話可以選「繼續用雲端」。每月 1 號重新計算。")
            }

            Section {
                Toggle("Azure 金鑰是免費方案（F0）", isOn: $log.prices.azureFreeTier)
                price("Azure 每小時", $log.prices.azurePerHour)
                price("Opus 輸入（每百萬 token）", $log.prices.opusInput)
                price("Opus 輸出（每百萬 token）", $log.prices.opusOutput)
                price("Haiku 輸入（每百萬 token）", $log.prices.haikuInput)
                price("Haiku 輸出（每百萬 token）", $log.prices.haikuOutput)
            } header: { Text("單價（美元）") } footer: {
                Text("價格會變，也跟方案有關，可以自己改。每月 1 號起「本月」自動從 0 開始算。")
            }

            Section {
                Button("全部歸零", role: .destructive) { confirmReset = true }
            }
        }
        .navigationTitle("雲端用量")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("清除所有用量紀錄？", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("歸零", role: .destructive) { log.reset() }
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack { Text(title); Spacer(); Text(value).foregroundColor(.secondary).monospacedDigit() }
    }

    private func price(_ title: String, _ value: Binding<Double>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", value: value, format: .number.precision(.fractionLength(0...4)))
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 90)
        }
    }

    static func usd(_ v: Double) -> String {
        v > 0 && v < 0.01 ? "< US$0.01" : String(format: "US$%.2f", v)
    }

    static func duration(_ seconds: Double) -> String {
        let m = Int(seconds / 60)
        return m >= 60 ? "\(m / 60) 小時 \(m % 60) 分" : (m > 0 ? "\(m) 分" : "\(Int(seconds)) 秒")
    }

    static func tokens(_ n: Int) -> String {
        n >= 10_000 ? String(format: "%.1f 萬", Double(n) / 10_000) : "\(n)"
    }
}
