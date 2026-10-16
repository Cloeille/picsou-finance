import SwiftUI

/// Family sharing: the household members (GET /api/family/members). Read-only — creating members and
/// activation links stay on the web admin.
struct FamilyView: View {
    @Environment(AppState.self) private var appState
    @State private var members: [FamilyMember] = []
    @State private var loading = true
    @State private var failed = false

    private var dataSource: FamilyDataSource { appState.makeFamilyDataSource() }

    var body: some View {
        List {
            Section {
                if loading {
                    HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                } else if failed {
                    Text("Membres réservés à l'administrateur du foyer.").foregroundStyle(Theme.mutedForeground)
                } else if members.isEmpty {
                    Text("Aucun membre.").foregroundStyle(Theme.mutedForeground)
                } else {
                    ForEach(members) { member in memberRow(member) }
                }
            } header: {
                Text("Membres du foyer")
            } footer: {
                Text("Gère les membres et le partage depuis l'app web.")
            }
        }
        .navigationTitle("Famille")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .task { await load() }
    }

    private func memberRow(_ member: FamilyMember) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.account(member.avatarColor ?? Theme.fallbackColorHex))
                .frame(width: 38, height: 38)
                .overlay(
                    Text(initials(member.displayName))
                        .font(Theme.font(14, .bold)).foregroundStyle(.white)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(member.displayName).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Text(roleLabel(member)).font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            }
            Spacer(minLength: 8)
            if member.mfaEnabled {
                Image(systemName: "lock.shield.fill").font(.system(size: 13)).foregroundStyle(Theme.positive)
            }
        }
        .padding(.vertical, 2)
    }

    private func initials(_ name: String) -> String {
        let parts = name.split(separator: " ")
        let first = parts.first?.first.map(String.init) ?? "?"
        let second = parts.dropFirst().first?.first.map(String.init) ?? ""
        return (first + second).uppercased()
    }

    private func roleLabel(_ member: FamilyMember) -> String {
        if !member.managed { return member.activated ? "Compte indépendant" : "Invitation en attente" }
        return "Profil géré"
    }

    private func load() async {
        loading = true
        failed = false
        do { members = try await dataSource.members() } catch { failed = true }
        loading = false
    }
}
