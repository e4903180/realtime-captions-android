import CaptionsCore
import Foundation
import os

/// 認得的人 (ADR-034): people whose voice the app remembers, so their lines show their name.
/// Voice prints (speaker embeddings) are kept only on the phone, never audio.
@MainActor
final class VoiceStore: ObservableObject {
    struct Person: Codable, Identifiable, Equatable {
        var id: Int32
        var name: String
        var profile: [Float]
        /// How many lines were used to learn this voice.
        var samples: Int
        var added: Date
    }

    static let shared = VoiceStore()

    /// Read by the recognizer's thread; changed here only.
    let registry = VoiceRegistry(threshold: 0.5)
    @Published private(set) var people: [Person] = []

    private let file: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("voices.json")
    }()

    private init() {
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([Person].self, from: data) {
            people = saved
            syncRegistry()
            // Saved by an earlier version, before voice prints were kept out of backups.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var existing = file
            do { try existing.setResourceValues(values) } catch {
                FileWriter.log.error("voices backup flag: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// Learns [voice] as [name]'s; with the same name again, refines that person's voice.
    func remember(name: String, voice: KotlinFloatArray) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let p = registry.add(name: name, embedding: voice)
        let profile = FloatArrays.toSwift(p.profile)
        if let i = people.firstIndex(where: { $0.id == p.id }) {
            people[i].profile = profile
            people[i].samples += 1
        } else {
            people.append(Person(id: p.id, name: name, profile: profile, samples: 1, added: Date()))
        }
        save()
    }

    func rename(_ id: Int32, to name: String) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, let i = people.firstIndex(where: { $0.id == id }) else { return }
        // Renaming to someone already known: they are the same person.
        if let other = people.first(where: { $0.name == name && $0.id != id }) {
            merge(id, into: other.id)
            return
        }
        people[i].name = name
        registry.rename(id: id, name: name)
        save()
    }

    /// The same person registered twice ("小明" and "阿明"): keep [into] with both voices.
    func merge(_ from: Int32, into: Int32) {
        guard let f = people.firstIndex(where: { $0.id == from }), let t = people.firstIndex(where: { $0.id == into }) else { return }
        registry.merge(fromId: from, intoId: into)
        people[t].samples += people[f].samples
        if let merged = registry.people.first(where: { $0.id == into }) { people[t].profile = FloatArrays.toSwift(merged.profile) }
        people.remove(at: f)
        save()
    }

    func forget(_ id: Int32) {
        people.removeAll { $0.id == id }
        registry.remove(id: id)
        save()
    }

    func forgetAll() {
        people = []
        registry.clear()
        save()
    }

    private func syncRegistry() {
        registry.restore(saved: people.map {
            VoiceRegistry.Person(id: $0.id, name: $0.name, profile: FloatArrays.toKotlin($0.profile))
        })
    }

    /// Voice prints stay on this phone: never in iCloud or computer backups.
    private let writer = FileWriter(label: "tw.captions.voices", excludeFromBackup: true)

    private func save() {
        do {
            writer.write(try JSONEncoder().encode(people), to: file)
        } catch {
            FileWriter.log.error("encode voices: \(error.localizedDescription, privacy: .public)")
        }
    }
}
