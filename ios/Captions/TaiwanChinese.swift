import CaptionsCore
import Foundation

/// The shared Kotlin s2twp converter, reading the OpenCC tables bundled in the app (Vendor/opencc).
enum TaiwanChinese {
    static func makeConverter(bundle: Bundle = .main) -> ChineseScriptConverter {
        OpenCcTaiwanConverter.Companion.shared.s2twp { name in
            guard let url = bundle.url(forResource: (name as NSString).deletingPathExtension, withExtension: "txt", subdirectory: "opencc"),
                  let text = try? String(contentsOf: url, encoding: .utf8)
            else { return "" }
            return text
        }
    }
}
