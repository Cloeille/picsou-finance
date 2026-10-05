import SwiftUI
import UIKit

/// MCP access keys: list, create (the secret is shown exactly once), and revoke.
struct AccessKeysView: View {
    @Environment(AppState.self) private var appState
    @State private var keys: [AccessKey] = []
    @State private var loading = true
    @State private var failed = false
    @State private var showCreate = false

    private var dataSource: AccessKeysDataSource { appState.makeAccessKeysDataSource() }

    var body: some View {
        List {
            Section {
                if loading {
                    HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                } else if failed {
                    Text("Impossible de charger les clés.").foregroundStyle(Theme.mutedForeground)
                } else if keys.isEmpty {
                    Text("Aucune clé d'accès.").foregroundStyle(Theme.mutedForeground)
                } else {
                    ForEach(keys) { key in
                        keyRow(key)
                            .swipeActions {
                                if !key.isRevoked {
                                    Button(role: .destructive) { revoke(key.id) } label: {
                                        Label("Révoquer", systemImage: "trash")
                                    }
                                }
                            }
                    }
                }
            } header: {
                Text("Clés d'accès MCP")
            } footer: {
                Text("Permettent à un client MCP d'accéder à ton Picsou selon les scopes accordés.")
            }
        }
        .navigationTitle("Clés MCP")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showCreate = true } label: { Image(systemName: "plus") }
            }
        }
        .task { await load() }
        .sheet(isPresented: $showCreate) {
            AccessKeyCreateSheet(dataSource: dataSource) { Task { await load() } }
        }
    }

    private func keyRow(_ key: AccessKey) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(key.name).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer()
                if key.isRevoked {
                    Text("Révoquée").font(Theme.font(11, .bold)).foregroundStyle(Theme.destructive)
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(Theme.destructive.opacity(0.14), in: Capsule())
                }
            }
            Text(key.keyPrefix).font(.system(.footnote, design: .monospaced)).foregroundStyle(Theme.mutedForeground)
            Text("\(key.scopes.count) scope\(key.scopes.count > 1 ? "s" : "") · \(usageLabel(key))")
                .font(Theme.font(12)).foregroundStyle(Theme.mutedForeground)
        }
        .padding(.vertical, 2)
    }

    private func usageLabel(_ key: AccessKey) -> String {
        if let date = DateParsing.instant(key.lastUsedAt) {
            let f = RelativeDateTimeFormatter(); f.locale = Locale(identifier: "fr_FR")
            return "utilisée \(f.localizedString(for: date, relativeTo: Date()))"
        }
        return "jamais utilisée"
    }

    private func load() async {
        loading = true; failed = false
        do { keys = try await dataSource.list() } catch { failed = true }
        loading = false
    }

    private func revoke(_ id: Int64) {
        Task { try? await dataSource.revoke(id: id); await load() }
    }
}

private struct AccessKeyCreateSheet: View {
    let dataSource: AccessKeysDataSource
    var onCreated: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var selected: Set<String> = []
    @State private var busy = false
    @State private var error: String?
    @State private var createdSecret: String?

    var body: some View {
        NavigationStack {
            Form {
                if let secret = createdSecret {
                    Section {
                        Text(secret).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                    } header: {
                        Text("Secret — copie-le maintenant")
                    } footer: {
                        Text("Il ne sera plus jamais affiché.")
                    }
                    Section {
                        Button("Copier le secret") { UIPasteboard.general.string = secret }
                        Button("Terminé") { dismiss() }
                    }
                } else {
                    Section("Nom") { TextField("ex. Claude Desktop", text: $name) }
                    Section("Scopes") {
                        ForEach(McpScope.all, id: \.id) { scope in
                            Button { toggle(scope.id) } label: {
                                HStack {
                                    Text(scope.label).foregroundStyle(Theme.foreground)
                                    Spacer()
                                    if selected.contains(scope.id) {
                                        Image(systemName: "checkmark").foregroundStyle(Theme.brand).fontWeight(.semibold)
                                    }
                                }
                            }
                        }
                    }
                    if let error { Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive) }
                }
            }
            .navigationTitle("Nouvelle clé")
            .navigationBarTitleDisplayMode(.inline)
            .tint(Theme.brand)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Annuler") { dismiss() } }
                if createdSecret == nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Créer") { create() }
                            .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty || selected.isEmpty)
                            .fontWeight(.semibold)
                    }
                }
            }
        }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }

    private func create() {
        busy = true; error = nil
        Task {
            do {
                let created = try await dataSource.create(name: name.trimmingCharacters(in: .whitespaces), scopes: Array(selected))
                createdSecret = created.secret
                onCreated()
            } catch {
                self.error = (error as? APIError)?.errorDescription ?? "Échec de la création."
            }
            busy = false
        }
    }
}
