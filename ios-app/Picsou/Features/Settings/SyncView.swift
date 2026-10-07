import SwiftUI

/// Bank-sync settings: the list of bank connections (GET /api/sync/status) with status, last-synced,
/// retry (for a failed link) and swipe-to-delete, then the connected sidecars (Amex, BoursoBank,
/// Fortuneo, CORUM, Sofidy…), read-only. Adding / reconnecting either kind uses the web.
struct SyncView: View {
    @Environment(AppState.self) private var appState
    @State private var connections: [BankConnection] = []
    @State private var sidecars: [SidecarConnection] = []
    @State private var loading = true
    @State private var failed = false
    @State private var retrying: Int64?

    private var dataSource: SyncDataSource { appState.makeSyncDataSource() }

    var body: some View {
        List {
            Section {
                if loading {
                    HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                } else if failed {
                    Text("Impossible de charger les connexions.").foregroundStyle(Theme.mutedForeground)
                } else if connections.isEmpty {
                    Text("Aucune connexion bancaire.").foregroundStyle(Theme.mutedForeground)
                } else {
                    ForEach(connections) { connection in
                        connectionRow(connection)
                            .swipeActions {
                                Button(role: .destructive) { delete(connection.id) } label: {
                                    Label("Supprimer", systemImage: "trash")
                                }
                            }
                    }
                }
            } header: {
                Text("Connexions bancaires")
            } footer: {
                Text("Pour ajouter ou reconnecter une banque, utilise l'app web (redirection sécurisée de la banque).")
            }
            if !sidecars.isEmpty {
                Section {
                    ForEach(sidecars) { sidecarRow($0) }
                } header: {
                    Text("Autres connexions")
                } footer: {
                    Text("Ces connexions se configurent depuis l'app web (identifiants et validation sur ton téléphone).")
                }
            }
        }
        .navigationTitle("Synchronisation")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .task { await load() }
    }

    private func connectionRow(_ connection: BankConnection) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                Text(connection.institutionName ?? "Banque")
                    .font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer()
                statusBadge(connection.status)
            }
            Text("Synchronisé \(relative(connection.lastSyncedAt))")
                .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            if connection.status == "FAILED" {
                Button { retry(connection.id) } label: {
                    HStack(spacing: 6) {
                        if retrying == connection.id { ProgressView() }
                        Text("Réessayer").font(Theme.font(13, .semibold))
                    }
                }
                .disabled(retrying == connection.id)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 2)
    }

    private func sidecarRow(_ connection: SidecarConnection) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                Text(connection.connector.name)
                    .font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer()
                sidecarBadge(connection.status.syncStatus)
            }
            Text("Synchronisé \(relative(connection.status.lastSyncCompletedAt))")
                .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            if connection.status.syncStatus == "FAILED" {
                Text("La dernière synchronisation a échoué. Relance-la depuis l'app web.")
                    .font(Theme.font(12)).foregroundStyle(Theme.destructive)
            }
        }
        .padding(.vertical, 2)
    }

    private func sidecarBadge(_ status: String?) -> some View {
        let (label, color): (String, Color) = switch status {
        case "FAILED": ("Échec", Theme.destructive)
        case "QUEUED", "RUNNING": ("Synchro…", Theme.brand)
        default: ("Connectée", Theme.positive)
        }
        return badge(label, color)
    }

    private func statusBadge(_ status: String) -> some View {
        let (label, color): (String, Color) = switch status {
        case "LINKED": ("Connectée", Theme.positive)
        case "FAILED": ("Échec", Theme.destructive)
        case "EXPIRED": ("Expirée", Color(hex: "#F59E0B") ?? .orange)
        default: ("En attente", Theme.mutedForeground)
        }
        return badge(label, color)
    }

    private func badge(_ label: String, _ color: Color) -> some View {
        Text(label)
            .font(Theme.font(11, .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.14), in: Capsule())
    }

    private func load() async {
        loading = true
        failed = false
        do { connections = try await dataSource.connections() }
        catch { failed = true }
        sidecars = await dataSource.sidecarConnections()
        loading = false
    }

    private func retry(_ id: Int64) {
        retrying = id
        Task {
            try? await dataSource.retry(id: id)
            retrying = nil
            await load()
        }
    }

    private func delete(_ id: Int64) {
        Task {
            try? await dataSource.delete(id: id)
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
