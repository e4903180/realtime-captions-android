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
        if !trimmed.isEmpty { KeyVault.put(KeyVault.azureKey, trimmed) }
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
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("離線") { rows(EngineInfo.all.filter(\.offline)) }
                Section("雲端") {
                    rows(EngineInfo.all.filter { !$0.offline })
                    Button("雲端服務設定（金鑰）") { model.showCloudSettings = true }
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
