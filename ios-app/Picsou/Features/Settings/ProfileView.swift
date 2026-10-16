import SwiftUI

/// Profile settings: change username (PATCH /api/auth/username) and password
/// (POST /api/auth/change-password). The user types their own credentials into the secure fields.
struct ProfileView: View {
    @Environment(AppState.self) private var appState
    @State private var username = ""
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var savingUsername = false
    @State private var savingPassword = false
    @State private var message: String?
    @State private var isError = false

    private var dataSource: SettingsDataSource { appState.makeSettingsDataSource() }

    var body: some View {
        Form {
            Section("Nom d'utilisateur") {
                TextField("Nom d'utilisateur", text: $username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button { saveUsername() } label: {
                    HStack { Text("Enregistrer le nom"); if savingUsername { Spacer(); ProgressView() } }
                }
                .disabled(savingUsername || username.trimmingCharacters(in: .whitespaces).count < 3)
            }
            Section("Mot de passe") {
                SecureField("Mot de passe actuel", text: $currentPassword)
                SecureField("Nouveau mot de passe (8+)", text: $newPassword)
                Button { savePassword() } label: {
                    HStack { Text("Changer le mot de passe"); if savingPassword { Spacer(); ProgressView() } }
                }
                .disabled(savingPassword || currentPassword.isEmpty || newPassword.count < 8)
            }
            if let message {
                Section {
                    Text(message).font(Theme.font(13)).foregroundStyle(isError ? Theme.destructive : Theme.positive)
                }
            }
        }
        .navigationTitle("Profil")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .onAppear { if username.isEmpty { username = appState.identity?.username ?? "" } }
    }

    private func saveUsername() {
        savingUsername = true
        message = nil
        Task {
            do {
                let updated = try await dataSource.changeUsername(username.trimmingCharacters(in: .whitespaces))
                username = updated
                isError = false
                message = "Nom d'utilisateur mis à jour."
            } catch {
                isError = true
                message = (error as? APIError)?.errorDescription ?? "Échec — ce nom est peut-être déjà pris."
            }
            savingUsername = false
        }
    }

    private func savePassword() {
        savingPassword = true
        message = nil
        Task {
            do {
                try await dataSource.changePassword(current: currentPassword, new: newPassword)
                currentPassword = ""
                newPassword = ""
                isError = false
                message = "Mot de passe changé. Une reconnexion peut être nécessaire."
            } catch {
                isError = true
                let apiError = error as? APIError
                message = apiError?.credentialMessage ?? apiError?.errorDescription ?? "Échec du changement de mot de passe."
            }
            savingPassword = false
        }
    }
}
