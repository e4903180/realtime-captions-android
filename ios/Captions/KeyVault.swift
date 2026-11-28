import Foundation
import Security

/// The user's own cloud API keys, kept in the iOS Keychain (this device only, never synced to
/// iCloud or backed up). Keys are never logged and only sent to the service they belong to.
enum KeyVault {
    static let azureKey = "azure_key"
    static let anthropicKey = "anthropic_key"
    private static let service = "tw.captions.keys"

    /// Returns the Keychain status (errSecSuccess when saved).
    @discardableResult
    static func put(_ name: String, _ value: String) -> OSStatus {
        remove(name)
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: Data(value.utf8),
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        setPresence(name, status == errSecSuccess)
        return status
    }

    /// Whether a key is stored, without a Keychain read after the first check (the app is the
    /// only writer, and `put` / `remove` keep this up to date).
    static func has(_ name: String) -> Bool {
        lock.lock()
        let known = presence[name]
        lock.unlock()
        if let known { return known }
        let found = get(name) != nil
        setPresence(name, found)
        return found
    }

    private static let lock = NSLock()
    private static var presence: [String: Bool] = [:]

    private static func setPresence(_ name: String, _ value: Bool) {
        lock.lock()
        presence[name] = value
        lock.unlock()
    }

    static func get(_ name: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func remove(_ name: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
        ]
        SecItemDelete(query as CFDictionary)
        setPresence(name, false)
    }

    /// "••••1a2b" for display; nil when nothing is stored.
    static func hint(_ name: String) -> String? { get(name).map { "••••" + String($0.suffix(4)) } }
}
