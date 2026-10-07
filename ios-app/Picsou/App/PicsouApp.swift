import SwiftUI

@main
struct PicsouApp: App {
    @State private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("appearanceMode") private var appearanceRaw = AppearanceMode.system.rawValue

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appState)
                .preferredColorScheme(AppearanceMode(rawValue: appearanceRaw)?.colorScheme)
        }
        .onChange(of: scenePhase) { _, newPhase in
            // Re-lock behind Face ID when the app leaves the foreground.
            if newPhase == .background { appState.lockIfNeeded() }
        }
    }
}
