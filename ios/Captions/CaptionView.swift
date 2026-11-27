import SwiftUI

/// A color with a dark-mode and a light-mode value; follows the system appearance live.
private func adaptive(_ dark: Color, _ light: Color) -> Color {
    Color(UIColor { $0.userInterfaceStyle == .light ? UIColor(light) : UIColor(dark) })
}

private let backgroundColor = adaptive(.black, .white)
private let textColor = adaptive(.white, .black)
private let stableColor = adaptive(Color(red: 1.0, green: 0.96, blue: 0.62), Color(red: 0.55, green: 0.36, blue: 0.0))  // settled
private let tentativeColor = adaptive(Color(white: 0.62), Color(white: 0.42))                                          // may still change
private let errorColor = adaptive(Color(red: 1.0, green: 0.54, blue: 0.5), Color(red: 0.75, green: 0.1, blue: 0.1))
private let barColor = adaptive(Color(white: 0.1), Color(white: 0.93))
private let meterColor = adaptive(Color(white: 0.19), Color(white: 0.82))
/// Speaker tags: black text on a light tag in dark mode, white text on a deep tag in light mode.
private let speakerTextColor = adaptive(.black, .white)
private let speakerColors: [Color] = [
    adaptive(Color(red: 0.5, green: 0.85, blue: 1.0), Color(red: 0.0, green: 0.42, blue: 0.7)),
    adaptive(Color(red: 1.0, green: 0.67, blue: 0.57), Color(red: 0.75, green: 0.27, blue: 0.13)),
    adaptive(Color(red: 0.73, green: 0.96, blue: 0.79), Color(red: 0.1, green: 0.5, blue: 0.22)),
    adaptive(Color(red: 0.92, green: 0.5, blue: 0.99), Color(red: 0.55, green: 0.18, blue: 0.7)),
    adaptive(Color(red: 1.0, green: 0.9, blue: 0.5), Color(red: 0.55, green: 0.4, blue: 0.0)),
]

private func color(for speaker: String?) -> Color {
    guard let s = speaker, let c = s.unicodeScalars.first else { return textColor }
    let i = Int(c.value) - Int(UnicodeScalar("A").value)
    return speakerColors[((i % speakerColors.count) + speakerColors.count) % speakerColors.count]
}

struct CaptionView: View {
    @ObservedObject var model: CaptionViewModel
    @ObservedObject private var models: ModelStore

    init(model: CaptionViewModel) {
        self.model = model
        _models = ObservedObject(wrappedValue: model.models)
    }

    @State private var showPicker = false
    @State private var showTranscripts = false
    @AppStorage(Proofreader.enabledKey) private var proofreadOn = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            captions
            status
            controls
        }
        .background(backgroundColor.ignoresSafeArea())
        .sheet(isPresented: Binding(
            get: { model.showCloudSettings && !showPicker },
            set: { model.showCloudSettings = $0 }
        )) { CloudSettingsView() }
        .alert("雲端用量已達上限", isPresented: Binding(
            get: { model.budgetAlert != nil },
            set: { if !$0 { model.budgetAlert = nil } }
        )) {
            Button("好", role: .cancel) {}
            Button("繼續用雲端") { model.continueWithCloud() }
        } message: {
            Text((model.budgetAlert ?? "") + "上限可以在「辨識引擎 > 雲端用量與費用」改。")
        }
    }

    private var topBar: some View {
        let engine = EngineInfo.byId(model.engineId)
        return Button { showPicker = true } label: {
            HStack(spacing: 6) {
                Image(systemName: engine.offline ? "iphone" : "cloud")
                Text(engine.name).font(.system(size: 17, weight: .semibold))
                Image(systemName: "chevron.down").font(.system(size: 13))
                if model.budgetFallback {
                    Text("已改用離線（雲端達上限）").font(.system(size: 14)).foregroundColor(.orange)
                }
                Spacer()
            }
            .foregroundColor(textColor)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .accessibilityLabel("辨識引擎：\(engine.name)，點一下更換")
        .sheet(isPresented: $showPicker) { EnginePickerView(model: model) }
        .sheet(isPresented: $showTranscripts) { TranscriptsView() }
    }

    private var captions: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // A plain VStack: LazyVStack estimates the height of lines it has not laid out,
                // which made the list jump while scrolling to the newest line (at most 200 lines).
                VStack(alignment: .leading, spacing: 14) {
                    if model.lines.isEmpty && model.stableText.isEmpty && model.tentativeText.isEmpty {
                        Text(model.running ? "正在聽…" : "按下「開始」，說話會即時顯示在這裡")
                            .font(.system(size: 24))
                            .foregroundColor(tentativeColor)
                            .padding(.top, 24)
                    }
                    ForEach(model.lines) { line in
                        let continued = model.continuesLastLine && line.id == model.lines.last?.id
                        lineView(line.text, speaker: line.speaker, current: continued)
                    }
                    if !model.continuesLastLine && (!model.stableText.isEmpty || !model.tentativeText.isEmpty) {
                        lineView(nil, speaker: model.currentSpeaker, current: true)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            // One scroll per change, without animation, so the text does not bounce.
            .onChange(of: scrollKey) { _ in
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) { proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
    }

    /// Changes whenever the bottom of the captions may have moved.
    private var scrollKey: String {
        "\(model.lines.count)|\(model.lines.last?.text.count ?? 0)|\(model.stableText.count + model.tentativeText.count)|\(model.continuesLastLine)"
    }

    @ViewBuilder
    private func lineView(_ text: String?, speaker: String?, current: Bool) -> some View {
        let size = CGFloat(model.fontSize)
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if let speaker {
                Text(speaker)
                    .font(.system(size: size * 0.6, weight: .bold))
                    .foregroundColor(speakerTextColor)
                    .padding(.horizontal, 8)
                    .background(color(for: speaker), in: Capsule())
            }
            Group {
                if current {
                    // A continued line shows its finished text, then the utterance still being heard.
                    let done = text.map { $0 + Self.joiner($0, model.stableText + model.tentativeText) } ?? ""
                    Text(done).foregroundColor(textColor)
                        + Text(model.stableText).foregroundColor(stableColor)
                        + Text(model.tentativeText).foregroundColor(tentativeColor)
                } else {
                    Text(text ?? "").foregroundColor(textColor)
                }
            }
            .font(.system(size: size, weight: .medium))
            .lineSpacing(size * 0.25)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Same joining rule as the shared CaptionAssembler: a comma between Chinese, a space between words.
    static func joiner(_ a: String, _ b: String) -> String {
        guard let x = a.last, let y = b.first else { return "" }
        if !x.isASCII && !y.isASCII && x.isLetter && y.isLetter { return "，" }
        if x.isASCII && y.isASCII && (x.isLetter || x.isNumber) && (y.isLetter || y.isNumber) { return " " }
        return ""
    }

    private var status: some View {
        VStack(spacing: 6) {
            if let progress = models.progress {
                ProgressView(value: progress) {
                    Text("第一次使用，正在下載離線模型（約 \(ModelStore.totalBytes / 1_000_000) MB）… \(Int(progress * 100))%")
                        .font(.system(size: 16)).foregroundColor(textColor)
                }
                .tint(.green)
            } else if model.preparing {
                Text("準備中…").font(.system(size: 16)).foregroundColor(textColor)
            }
            if let hint = model.hint {
                Text(hint).font(.system(size: 18)).foregroundColor(stableColor)
            }
            if proofreadOn, let notice = model.proofreadNotice {
                Text(notice).font(.system(size: 16)).foregroundColor(stableColor.opacity(0.8))
            }
            if let error = model.error {
                Text(error).font(.system(size: 18)).foregroundColor(errorColor)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    private var controls: some View {
        HStack(spacing: 12) {
            LevelMeter(level: model.level, active: model.running)
            Button("A−") { model.changeFontSize(by: -4) }.font(.system(size: 18))
            Button("A+") { model.changeFontSize(by: 4) }.font(.system(size: 22))
            Button("清除") { model.clear() }.font(.system(size: 18))
            Button("紀錄") { showTranscripts = true }.font(.system(size: 18))
            Spacer()
            Button(action: model.toggle) {
                Text(model.running ? "停止" : "開始")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 12)
                    .background(model.running ? Color(red: 0.78, green: 0.16, blue: 0.16) : Color(red: 0.18, green: 0.49, blue: 0.2), in: Capsule())
            }
            .disabled(model.preparing)
        }
        .tint(textColor)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(barColor)
    }
}

private struct LevelMeter: View {
    let level: Float
    let active: Bool

    var body: some View {
        ZStack {
            Circle().fill(meterColor)
            Circle()
                .fill(Color(red: 0.4, green: 0.73, blue: 0.42))
                .scaleEffect(active ? CGFloat(0.3 + 0.7 * level) : 0)
                .animation(.easeOut(duration: 0.1), value: level)
        }
        .frame(width: 36, height: 36)
        .accessibilityLabel(active ? "收音中" : "未收音")
    }
}
