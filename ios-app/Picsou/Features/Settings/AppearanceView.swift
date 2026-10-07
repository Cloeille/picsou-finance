import SwiftUI

/// Local appearance preference, applied at the app root via `.preferredColorScheme`.
enum AppearanceMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Automatique"
        case .light: "Clair"
        case .dark: "Sombre"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct AppearanceView: View {
    @AppStorage("appearanceMode") private var mode = AppearanceMode.system.rawValue

    var body: some View {
        Form {
            Section {
                Picker("Thème", selection: $mode) {
                    ForEach(AppearanceMode.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.inline)
            } footer: {
                Text("« Automatique » suit le réglage clair/sombre de ton iPhone.")
            }
        }
        .navigationTitle("Apparence")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
    }
}
