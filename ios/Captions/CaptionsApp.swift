import SwiftUI

@main
struct CaptionsApp: App {
    @StateObject private var model = CaptionViewModel()

    var body: some Scene {
        WindowGroup {
            CaptionView(model: model)
                .preferredColorScheme(.dark)
        }
    }
}
