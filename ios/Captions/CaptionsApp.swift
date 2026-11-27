import SwiftUI

@main
struct CaptionsApp: App {
    @StateObject private var model = CaptionViewModel()
    /// 外觀: "system" (default), "dark" or "light".
    @AppStorage("appearance") private var appearance = "system"

    var body: some Scene {
        WindowGroup {
            CaptionView(model: model)
                .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
        }
    }
}
