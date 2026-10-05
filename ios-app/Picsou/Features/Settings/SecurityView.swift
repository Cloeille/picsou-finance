import SwiftUI

/// Security settings: 2FA, then the active sessions (GET /api/auth/sessions): browsers kept signed in
/// with "Remember Me" and iOS app sign-ins, this device included. Any row can be revoked; revoking
/// this device signs it out. "Log out everywhere else" keeps only this one.
struct SecurityView: View {
    @Environment(AppState.self) private var appState
    @State private var sessions: [SessionInfo] = []
    @State private var loading = true
    @State private var failed = false
    @State private var confirmingSelfRevoke: SessionInfo?

    private var dataSource: SettingsDataSource { appState.makeSettingsDataSource() }

    var body: some View {
        List {
            Section("Double authentification") {
                NavigationLink {
                    TwoFactorView()
                } label: {
                    Label("2FA (TOTP)", systemImage: "lock.shield.fill")
                }
            }
            Section("Sessions actives") {
                if loading {
                    HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                } else if failed {
                    Text("Impossible de charger les sessions.").foregroundStyle(Theme.mutedForeground)
                } else {
                    ForEach(sessions) { session in
                        sessionRow(session)
                            .swipeActions {
                                Button(role: .destructive) {
                                    if session.current { confirmingSelfRevoke = session } else { revoke(session) }
                                } label: {
                                    Label(session.current ? "Déconnecter" : "Révoquer", systemImage: "trash")
                                }
                            }
                    }
                    if sessions.contains(where: { !$0.current }) {
                        Button(role: .destructive) { revokeOthers() } label: {
                            Text("Déconnecter les autres appareils")
                        }
                    }
                }
            }
        }
        .confirmationDialog("Déconnecter cet iPhone ?", isPresented: Binding(
            get: { confirmingSelfRevoke != nil }, set: { if !$0 { confirmingSelfRevoke = nil } }
        ), titleVisibility: .visible) {
            Button("Déconnecter", role: .destructive) {
                if let session = confirmingSelfRevoke { revoke(session) }
            }
        } message: {
            Text("Tu devras te reconnecter pour utiliser l'app.")
        }
        .navigationTitle("Sécurité")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .task { await load() }
    }

    private func sessionRow(_ session: SessionInfo) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(Self.title(of: session))
                    .font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                if session.current {
                    Text("Actuelle").font(Theme.font(11, .bold))
                        .foregroundStyle(Theme.positive)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.positive.opacity(0.14), in: Capsule())
                }
            }
            Text([session.ipPrefix, "vu \(relative(session.lastUsedAt))"].compactMap { $0 }.joined(separator: " · "))
                .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
        }
        .padding(.vertical, 2)
    }

    private func load() async {
        loading = true
        failed = false
        do { sessions = try await dataSource.sessions() }
        catch { failed = true }
        loading = false
    }

    static func title(of session: SessionInfo) -> String {
        switch session.kind {
        case .iosApp: return "App iPhone"
        case .rememberMe, .unknown: return session.userAgent ?? "Appareil inconnu"
        }
    }

    private func revoke(_ session: SessionInfo) {
        Task {
            do {
                try await dataSource.revokeSession(id: session.id)
                if session.current { appState.signOut(); return }
            } catch {
                if (error as? APIError) == .unauthorized { appState.signOut(); return }
            }
            await load()
        }
    }

    private func revokeOthers() {
        Task {
            try? await dataSource.revokeOtherSessions()
            await load()
        }
    }

    private func relative(_ iso: String?) -> String {
        guard let date = DateParsing.instant(iso) else { return "—" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "fr_FR")
        return f.localizedString(for: date, relativeTo: Date())
    }
}
