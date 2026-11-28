import Security
import SwiftUI

/// The user's own cloud keys (same wording as the Android app's CloudSettings screen).
struct CloudSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var region = AzureEngine.savedRegion
    @State private var hint = KeyVault.hint(KeyVault.azureKey)
    @State private var testing = false
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("使用你自己的雲端帳號。金鑰存在這支手機的鑰匙圈裡，只會傳給它所屬的服務，不會上傳到其他地方。")
                        .font(.callout).foregroundColor(.secondary)
                }
                Section("Azure 語音服務") {
                    Text("台灣腔最準（錯字率約 5%），會標示說話者。按使用時間計費，約 US$1.3／小時；免費方案每月 5 小時。")
                        .font(.callout).foregroundColor(.secondary)
                    Text(hint.map { "目前金鑰：\($0)（\(regionName(region))）" } ?? "尚未設定金鑰")
                    SecureField(hint == nil ? "金鑰（KEY 1）" : "換一組金鑰（KEY 1）", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Picker("區域（要和建立資源時選的一樣）", selection: $region) {
                        ForEach(AzureEngine.regions, id: \.id) { Text($0.name).tag($0.id) }
                    }
                    Button("儲存") { save() }
                        .disabled(key.isEmpty && (hint == nil || region == AzureEngine.savedRegion))
                    Button(testing ? "測試中…" : "測試連線") { Task { await test() } }
                        .disabled(testing || (key.isEmpty && hint == nil))
                    if hint != nil {
                        Button("刪除金鑰", role: .destructive) {
                            KeyVault.remove(KeyVault.azureKey)
                            hint = nil
                            message = nil
                        }
                    }
                    if let message {
                        Text(message).foregroundColor(message.hasPrefix("可以使用") ? .green : .red)
                    }
                }
                Section("怎麼取得金鑰") {
                    Text("1. 到 portal.azure.com 註冊（有免費試用）。\n2. 建立「Speech 服務」資源：區域選 East Asia，定價層選 Free F0。\n3. 打開資源 →「金鑰與端點」，複製 KEY 1 貼到上面。")
                        .font(.callout)
                }
            }
            .navigationTitle("雲端服務設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }

    private func regionName(_ id: String) -> String { AzureEngine.regions.first { $0.id == id }?.name ?? id }

    private func save() {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty, KeyVault.put(KeyVault.azureKey, trimmed) != errSecSuccess { message = "無法存入鑰匙圈，請再試一次"; return }
        UserDefaults.standard.set(region, forKey: AzureEngine.regionDefaultsKey)
        key = ""
        hint = KeyVault.hint(KeyVault.azureKey)
        message = "已儲存"
    }

    private func test() async {
        let k = key.isEmpty ? (KeyVault.get(KeyVault.azureKey) ?? "") : key.trimmingCharacters(in: .whitespacesAndNewlines)
        testing = true
        defer { testing = false }
        if let problem = await AzureEngine.testKey(k, region: region) {
            message = problem
        } else {
            message = "可以使用（\(regionName(region))）"
        }
    }
}

/// Engine list: one line of explanation per engine.
struct EnginePickerView: View {
    @ObservedObject var model: CaptionViewModel
    @ObservedObject private var models: ModelStore
    @ObservedObject private var second: SecondPassStore
    @State private var confirmRemove: String?
    @State private var words = ""

    init(model: CaptionViewModel) {
        self.model = model
        _models = ObservedObject(wrappedValue: model.models)
        _second = ObservedObject(wrappedValue: model.secondPassStore)
        _words = State(initialValue: model.vocabulary.joined(separator: "\n"))
    }
    @AppStorage("appearance") private var appearance = "system"
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("離線") { rows(EngineInfo.all.filter(\.offline)) }
                Section("雲端") {
                    rows(EngineInfo.all.filter { !$0.offline })
                    Button("雲端服務設定（金鑰）") { model.showCloudSettings = true }
                    NavigationLink("雲端用量與費用") { UsageView() }
                }
                Section {
                    TextField("一行一個詞，例如人名、地名、專有名詞", text: $words, axis: .vertical)
                        .lineLimit(3...6)
                        .autocorrectionDisabled()
                    HStack {
                        Button("儲存") { model.setVocabulary(words); words = model.vocabulary.joined(separator: "\n") }
                            .disabled(words == model.vocabulary.joined(separator: "\n"))
                        Spacer()
                        Text("目前 \(model.vocabulary.count) 個詞").foregroundColor(.secondary)
                    }
                    .buttonStyle(.borderless)
                } header: { Text("自訂詞彙") } footer: {
                    Text("離線和 Azure 雲端都會優先辨識成這些詞，下次按「開始」生效。離線用詞彙時會多用一點電。")
                }
                Section {
                    Toggle("說話者辨識", isOn: $model.speakersOn)
                    if model.speakersOn {
                        NavigationLink("認得的人（\(VoiceStore.shared.people.count)）") { PeopleView(model: model) }
                        Picker("說話人數", selection: $model.speakerCount) {
                            Text("自動").tag(0)
                            ForEach(2...6, id: \.self) { Text("\($0) 人").tag($0) }
                        }
                    }
                } header: { Text("說話者") } footer: {
                    Text(model.speakersOn
                         ? "分出不同的人，標 A、B、C；長按字幕可以替說話者取名字。知道有幾個人在講話時選人數，離線分人會比較準。"
                         : "不分人、不顯示標籤，只照停頓換行；離線時比較省電。")
                }
                Section {
                    Picker("背景過濾", selection: $model.backgroundFilter) {
                        ForEach(CaptionViewModel.BackgroundFilter.allCases) { Text($0.label).tag($0) }
                    }
                } header: { Text("背景聲音") } footer: {
                    Text("把電視、隔壁桌這類比較小聲的說話聲，以及有「認得的人」之後不認得的聲音，當成背景淡化或隱藏。安靜時也可能誤判小聲的人，所以預設關。只用在離線引擎，看電視模式時不作用。")
                }
                Section {
                    if models.isReady {
                        HStack {
                            Text("離線辨識模型（約 \(ModelStore.totalBytes / 1_000_000) MB）")
                            Spacer()
                            Button("移除", role: .destructive) { confirmRemove = "offline" }
                        }
                    }
                    if second.isReady {
                        HStack {
                            Text("整句重聽模型")
                            Spacer()
                            Button("移除", role: .destructive) { confirmRemove = "second" }
                        }
                    }
                    if !models.isReady && !second.isReady {
                        Text("目前沒有下載的模型").foregroundColor(.secondary)
                    }
                } header: { Text("已下載的模型") } footer: {
                    Text("移除後可以釋放空間；之後要用離線辨識時會再自動下載。")
                }
                Section("外觀") {
                    Picker("背景", selection: $appearance) {
                        Text("跟隨系統").tag("system")
                        Text("深色").tag("dark")
                        Text("淺色").tag("light")
                    }
                }
                Section("文字校正") {
                    NavigationLink("整句重聽、AI 校稿") { CorrectionSettingsView(store: model.secondPassStore) }
                }
            }
            .confirmationDialog("移除這個模型？", isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }), titleVisibility: .visible) {
                Button("移除", role: .destructive) {
                    if confirmRemove == "offline" { model.removeOfflineModels() } else { model.removeSecondPassModel() }
                }
            }
            .navigationTitle("辨識引擎")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .sheet(isPresented: $model.showCloudSettings) { CloudSettingsView() }
        }
    }

    @ViewBuilder
    private func rows(_ engines: [EngineInfo]) -> some View {
        ForEach(engines) { e in
            Button {
                model.selectEngine(e.id)
            } label: {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(e.name).font(.headline).foregroundColor(.primary)
                            if e.recommended { Text("推薦").font(.caption).padding(.horizontal, 6).background(Color.green.opacity(0.3), in: Capsule()) }
                            if e.needsKey { Text(KeyVault.get(KeyVault.azureKey) == nil ? "需要金鑰" : "已設定金鑰").font(.caption).foregroundColor(.secondary) }
                        }
                        Text(e.subtitle).font(.subheadline).foregroundColor(.secondary)
                    }
                    Spacer()
                    if model.engineId == e.id { Image(systemName: "checkmark").foregroundColor(.green) }
                }
            }
        }
    }
}
