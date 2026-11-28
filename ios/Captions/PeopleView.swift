import SwiftUI

/// 認得的人: rename, merge (rename to a name already in the list) and forget remembered voices.
struct PeopleView: View {
    @ObservedObject var model: CaptionViewModel
    @ObservedObject var store = VoiceStore.shared
    @State private var editing: VoiceStore.Person?
    @State private var newName = ""
    @State private var merging: VoiceStore.Person?
    @State private var confirmForgetAll = false

    var body: some View {
        List {
            Section {
                if store.people.isEmpty {
                    Text("還沒有認得的人").foregroundColor(.secondary)
                }
                ForEach(store.people) { p in
                    Button { newName = p.name; editing = p } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.name).font(.headline).foregroundColor(.primary)
                            Text("學了 \(p.samples) 句・\(Self.date.string(from: p.added)) 加入").font(.caption).foregroundColor(.secondary)
                        }
                    }
                    .swipeActions {
                        Button("忘記", role: .destructive) { store.forget(p.id) }
                        if store.people.count > 1 { Button("合併") { merging = p }.tint(.blue) }
                    }
                }
            } footer: {
                Text("在字幕上長按一句，選「這是誰」並勾「記住這個聲音」就會加到這裡。同一個人可以多記幾句，會越認越準。改名成清單裡已有的名字，兩個會合併成同一個人。聲紋只存在這支手機，不存聲音。只用在離線引擎。")
            }
            if !store.people.isEmpty {
                Section {
                    Button("全部忘記", role: .destructive) { confirmForgetAll = true }
                }
            }
        }
        .navigationTitle("認得的人")
        .navigationBarTitleDisplayMode(.inline)
        .alert("改名", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("名字", text: $newName)
            Button("取消", role: .cancel) {}
            Button("儲存") {
                if let p = editing, !newName.trimmingCharacters(in: .whitespaces).isEmpty {
                    store.rename(p.id, to: newName)
                    model.renameSpeaker(p.name, to: newName.trimmingCharacters(in: .whitespacesAndNewlines))
                }
            }
        }
        .confirmationDialog("把「\(merging?.name ?? "")」合併到…", isPresented: Binding(get: { merging != nil }, set: { if !$0 { merging = nil } }), titleVisibility: .visible) {
            ForEach(store.people.filter { $0.id != merging?.id }) { other in
                Button(other.name) {
                    if let from = merging {
                        store.merge(from.id, into: other.id)
                        model.renameSpeaker(from.name, to: other.name)
                    }
                }
            }
        }
        .confirmationDialog("忘記所有認得的人？", isPresented: $confirmForgetAll, titleVisibility: .visible) {
            Button("全部忘記", role: .destructive) { store.forgetAll() }
        }
    }

    static let date: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "M/d"
        return f
    }()
}

/// 「這句話是誰說的？」 after a long-press on a caption line.
struct NameSpeakerView: View {
    @ObservedObject var model: CaptionViewModel
    let line: Line
    @ObservedObject var store = VoiceStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var wholeSpeaker = true
    @State private var remember = true

    var body: some View {
        let hasVoice = model.hasVoice(line.id)
        NavigationStack {
            Form {
                Section {
                    Text(line.text).lineLimit(3).foregroundColor(.secondary)
                }
                Section("是誰") {
                    TextField("名字，例如 小明", text: $name)
                    if !store.people.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(store.people) { p in
                                    Button(p.name) { name = p.name }
                                        .buttonStyle(.bordered)
                                        .tint(name == p.name ? .accentColor : .secondary)
                                }
                            }
                        }
                    }
                }
                Section {
                    if line.speaker != nil {
                        Toggle("「\(line.speaker!)」的其他句子也一起改", isOn: $wholeSpeaker)
                    }
                    Toggle("記住這個聲音", isOn: $remember).disabled(!hasVoice)
                } footer: {
                    Text(hasVoice
                         ? "記住後，之後收音時聽到這個聲音會自動顯示名字。聲紋只存在這支手機。"
                         : "這句沒有聲紋（雲端引擎或句子太短），只會改名字，不會記住聲音。")
                }
            }
            .navigationTitle("這句話是誰說的？")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("確定") {
                        model.nameSpeaker(of: line.id, as: name, wholeSpeaker: wholeSpeaker, remember: remember && hasVoice)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
