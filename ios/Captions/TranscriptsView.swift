import SwiftUI

/// 逐字稿紀錄: earlier transcripts, newest first, with search, rename, delete and export.
struct TranscriptsView: View {
    @ObservedObject var store = TranscriptStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var keep = TranscriptStore.isEnabled
    @State private var confirmDeleteAll = false

    private var shown: [Transcript] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return store.all }
        return store.all.filter { t in
            t.displayTitle.localizedCaseInsensitiveContains(q) || t.lines.contains { $0.text.localizedCaseInsensitiveContains(q) }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("自動保存逐字稿", isOn: $keep)
                        .onChange(of: keep) { TranscriptStore.isEnabled = $0 }
                } footer: {
                    Text("每次按「開始」到「停止」存成一份。只存在這支手機，不存聲音；按「清除」只清畫面，不會刪紀錄。")
                }
                if shown.isEmpty {
                    Text(query.isEmpty ? "還沒有逐字稿" : "找不到「\(query)」").foregroundColor(.secondary)
                }
                ForEach(shown) { t in
                    NavigationLink { TranscriptDetailView(id: t.id, highlight: query) } label: { TranscriptRow(t: t, query: query) }
                }
                .onDelete { offsets in
                    for i in offsets { store.delete(shown[i].id) }
                }
                if !store.all.isEmpty {
                    Section {
                        Button("全部刪除", role: .destructive) { confirmDeleteAll = true }
                    }
                }
            }
            .searchable(text: $query, prompt: "搜尋逐字稿")
            .navigationTitle("逐字稿紀錄")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .confirmationDialog("刪除全部 \(store.all.count) 份逐字稿？", isPresented: $confirmDeleteAll, titleVisibility: .visible) {
                Button("全部刪除", role: .destructive) { store.deleteAll() }
            }
        }
    }
}

private struct TranscriptRow: View {
    let t: Transcript
    let query: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(t.displayTitle).font(.headline)
            Text("\(t.title == nil ? "" : Transcript.dateFormat.string(from: t.started) + "・")\(Transcript.minutes(t.duration))・\(t.lines.count) 句・\(t.engine)")
                .font(.caption).foregroundColor(.secondary)
            Text(preview).font(.subheadline).foregroundColor(.secondary).lineLimit(2)
        }
        .padding(.vertical, 2)
    }

    private var preview: String {
        let q = query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty, let hit = t.lines.first(where: { $0.text.localizedCaseInsensitiveContains(q) }) { return hit.text }
        return t.lines.first?.text ?? ""
    }
}

struct TranscriptDetailView: View {
    let id: UUID
    var highlight = ""
    @ObservedObject var store = TranscriptStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var confirmDelete = false

    var body: some View {
        if let t = store.all.first(where: { $0.id == id }) {
            List {
                Section {
                    Text("\(Transcript.dateFormat.string(from: t.started))・\(Transcript.minutes(t.duration))・\(t.engine)")
                        .font(.footnote).foregroundColor(.secondary)
                }
                Section {
                    ForEach(Array(t.lines.enumerated()), id: \.offset) { _, line in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(clock(t.started.addingTimeInterval(line.start))).font(.caption.monospacedDigit()).foregroundColor(.secondary)
                                if let s = line.speaker { Text(s).font(.caption.bold()).foregroundColor(.accentColor) }
                            }
                            Text(line.text)
                                .font(.body)
                                .background(matches(line.text) ? Color.yellow.opacity(0.3) : Color.clear)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
            .navigationTitle(t.displayTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        ShareLink(item: store.exportFile(t, srt: false)) { Label("下載文字檔（.txt）", systemImage: "doc.text") }
                        ShareLink(item: store.exportFile(t, srt: true)) { Label("下載字幕檔（.srt）", systemImage: "captions.bubble") }
                        Button { UIPasteboard.general.string = t.plainText } label: { Label("複製全文", systemImage: "doc.on.doc") }
                        Button { newTitle = t.title ?? ""; renaming = true } label: { Label("改標題", systemImage: "pencil") }
                        Button(role: .destructive) { confirmDelete = true } label: { Label("刪除", systemImage: "trash") }
                    } label: { Image(systemName: "square.and.arrow.up") }
                }
            }
            .alert("改標題", isPresented: $renaming) {
                TextField("例如：週三家庭聚餐", text: $newTitle)
                Button("取消", role: .cancel) {}
                Button("儲存") { store.rename(id, to: newTitle) }
            }
            .confirmationDialog("刪除這份逐字稿？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("刪除", role: .destructive) { store.delete(id); dismiss() }
            }
        } else {
            Text("這份逐字稿已刪除").foregroundColor(.secondary)
        }
    }

    private func matches(_ text: String) -> Bool {
        let q = highlight.trimmingCharacters(in: .whitespaces)
        return !q.isEmpty && text.localizedCaseInsensitiveContains(q)
    }

    private func clock(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: d)
    }
}
