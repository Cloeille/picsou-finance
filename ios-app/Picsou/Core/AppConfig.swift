import Foundation

/// Build-time configuration. `isDemo` is the iOS analogue of the web app's `VITE_DEMO_MODE`: the
/// "Picsou Demo" scheme defines the `DEMO` compilation condition, which boots the app straight into
/// a mock-data dashboard with no server or login.
enum AppConfig {
    static var isDemo: Bool {
        #if DEMO
        return true
        #else
        return false
        #endif
    }
}
