import CaptionsCore
import Foundation

/// The shared Kotlin s2twp converter, reading the OpenCC tables bundled in the app (Vendor/opencc).
enum TaiwanChinese {
    static func makeConverter(bundle: Bundle = .main) -> ChineseScriptConverter {
        OpenCcTaiwanConverter.Companion.shared.s2twp { table($0, bundle) }
    }

    /// Traditional → Simplified per character, for offline hotwords (ADR-038).
    static func makeSimplifier(bundle: Bundle = .main) -> OpenCcSimplifier {
        OpenCcSimplifier(stCharacters: table("STCharacters.txt", bundle), twVariants: table("TWVariants.txt", bundle))
    }

    private static func table(_ name: String, _ bundle: Bundle) -> String {
        guard let url = bundle.url(forResource: (name as NSString).deletingPathExtension, withExtension: "txt", subdirectory: "opencc"),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { return "" }
        return text
    }
}
