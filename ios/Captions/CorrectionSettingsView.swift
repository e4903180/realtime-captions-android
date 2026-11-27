import Security
import CaptionsCore
import SwiftUI

/// Settings for correcting finished lines: the second pass (ADR-025) and AI proof-reading (ADR-026).
struct CorrectionSettingsView: View {
    @ObservedObject var store: SecondPassStore
    @State private var secondPass = SecondPassModel.isEnabled
    @State private var proofread = Proofreader.isEnabled
    @State private var model = Proofreader.model
    @State private var key = ""
    @State private var hint = KeyVault.hint(KeyVault.anthropicKey)
    @State private var testing = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Toggle("整句重聽（離線）", isOn: $secondPass)
                    .onChange(of: secondPass) { SecondPassModel.isEnabled = $0 }
                Text("一句說完後，用比較準的整句模型（\(SecondPassModel.name)）把那段聲音再聽一次，換掉錯字。不用網路，字幕照樣即時出現，修正約晚 1 秒。只用在「離線＋說話者」。")
                    .font(.callout).foregroundColor(.secondary)
                if !SecondPassModel.available {
                    Text("整句模型還在挑選中，下一版提供下載。").foregroundColor(.secondary)
                } else if store.isReady {
                    Button("移除整句模型", role: .destructive) { store.delete() }
                } else if let p = store.progress {
                    ProgressView(value: p) { Text("下載中… \(Int(p * 100))%") }
                } else {
                    Button("下載整句模型（約 \(max(1, SecondPassModel.remote.bytes / 1_000_000)) MB）") { Task { await store.download() } }
                }
                if let e = store.error { Text(e).foregroundColor(.red) }
            } header: { Text("兩段式辨識") }

            Section {
                Toggle("AI 校稿", isOn: $proofread)
                    .onChange(of: proofread) { Proofreader.isEnabled = $0 }
                Text("每隔幾句把最近的字幕交給 Claude，依上下文修同音錯字和標點。需要網路和你自己的 Anthropic 金鑰，按用量計費；字幕文字會傳到 Anthropic（不傳聲音）。")
                    .font(.callout).foregroundColor(.secondary)
                Picker("模型", selection: $model) {
                    ForEach(Proofreader.Model.allCases) { Text($0.label).tag($0) }
                }
                .onChange(of: model) { Proofreader.model = $0 }
                Text(hint.map { "目前金鑰：\($0)" } ?? "尚未設定金鑰")
                SecureField(hint == nil ? "Anthropic API 金鑰" : "換一組金鑰", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("儲存") { save() }.disabled(key.isEmpty)
                Button(testing ? "測試中…" : "測試金鑰") { Task { await test() } }
                    .disabled(testing || (key.isEmpty && hint == nil))
                if hint != nil {
                    Button("刪除金鑰", role: .destructive) {
                        KeyVault.remove(KeyVault.anthropicKey)
                        hint = nil
                        message = nil
                    }
                }
                if let message { Text(message).foregroundColor(message.hasPrefix("可以使用") || message == "已儲存" ? .green : .red) }
            } header: { Text("AI 校稿（雲端）") } footer: {
                Text("取得金鑰：到 console.anthropic.com 註冊，在「API Keys」建立一組金鑰貼到上面。")
            }
        }
        .navigationTitle("文字校正")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func save() {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard KeyVault.put(KeyVault.anthropicKey, trimmed) == errSecSuccess else { message = "無法存入鑰匙圈，請再試一次"; return }
        key = ""
        hint = KeyVault.hint(KeyVault.anthropicKey)
        message = "已儲存"
    }

    private func test() async {
        let k = key.isEmpty ? (KeyVault.get(KeyVault.anthropicKey) ?? "") : key.trimmingCharacters(in: .whitespacesAndNewlines)
        testing = true
        defer { testing = false }
        message = await Proofreader.testKey(k) ?? "可以使用"
    }
}
