import SwiftUI

/// Two-factor (TOTP) management: status, enrolment (QR + verify + recovery codes), disable,
/// regenerate codes. The user types their own password / authenticator code into the secure fields.
struct TwoFactorView: View {
    @Environment(AppState.self) private var appState
    @State private var status: MfaStatus?
    @State private var loading = true
    @State private var failed = false
    @State private var showEnroll = false
    @State private var showDisable = false
    @State private var showRegenerate = false

    private var dataSource: SettingsDataSource { appState.makeSettingsDataSource() }

    var body: some View {
        List {
            if loading {
                HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
            } else if failed {
                Text("Impossible de charger le statut 2FA.").foregroundStyle(Theme.mutedForeground)
            } else if let status {
                if status.enabled {
                    Section {
                        Label("Double authentification activée", systemImage: "checkmark.shield.fill")
                            .foregroundStyle(Theme.positive)
                    } footer: {
                        Text("\(status.remainingRecoveryCodes) code(s) de secours restant(s).")
                    }
                    Section {
                        Button("Régénérer les codes de secours") { showRegenerate = true }
                        Button("Désactiver la 2FA", role: .destructive) { showDisable = true }
                    }
                } else {
                    Section {
                        Label("Double authentification désactivée", systemImage: "shield.slash")
                            .foregroundStyle(Theme.mutedForeground)
                    } footer: {
                        Text("Protège ton compte avec une application d'authentification (TOTP).")
                    }
                    Section { Button("Activer la 2FA") { showEnroll = true } }
                }
            }
        }
        .navigationTitle("2FA")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.brand)
        .task { await load() }
        .sheet(isPresented: $showEnroll) {
            MfaEnrollSheet(dataSource: dataSource, username: appState.identity?.username ?? "picsou") {
                Task { await load() }
            }
        }
        .sheet(isPresented: $showDisable) {
            MfaCredentialSheet(title: "Désactiver la 2FA", actionLabel: "Désactiver", destructive: true,
                               action: { pwd, code in try await dataSource.mfaDisable(password: pwd, code: code); return nil },
                               onDone: { Task { await load() } })
        }
        .sheet(isPresented: $showRegenerate) {
            MfaCredentialSheet(title: "Régénérer les codes", actionLabel: "Régénérer", destructive: false,
                               action: { pwd, code in try await dataSource.mfaRegenerateCodes(password: pwd, code: code) },
                               onDone: { Task { await load() } })
        }
    }

    private func load() async {
        loading = true
        failed = false
        do { status = try await dataSource.mfaStatus() } catch { failed = true }
        loading = false
    }
}

private struct MfaEnrollSheet: View {
    let dataSource: SettingsDataSource
    let username: String
    var onEnrolled: () -> Void

    @Environment(\.dismiss) private var dismiss
    private enum Step { case password, scan, codes }
    @State private var step: Step = .password
    @State private var password = ""
    @State private var code = ""
    @State private var secret = ""
    @State private var recoveryCodes: [String] = []
    @State private var busy = false
    @State private var error: String?

    private var otpauthURL: String {
        "otpauth://totp/Picsou:\(username)?secret=\(secret)&issuer=Picsou&digits=6&period=30"
    }

    var body: some View {
        NavigationStack {
            Form {
                switch step {
                case .password:
                    Section("Mot de passe") { SecureField("Mot de passe actuel", text: $password) }
                    Section {
                        Button("Continuer") { initEnroll() }.disabled(busy || password.isEmpty)
                    }
                case .scan:
                    Section("Scanne ce QR code") {
                        if let image = QRCode.image(from: otpauthURL) {
                            Image(uiImage: image).resizable().interpolation(.none).scaledToFit()
                                .frame(height: 200).frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        Text(secret)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundStyle(Theme.mutedForeground).textSelection(.enabled)
                    }
                    Section("Code de vérification") {
                        TextField("123456", text: $code).keyboardType(.numberPad)
                        Button("Vérifier") { verify() }.disabled(busy || code.count != 6)
                    }
                case .codes:
                    Section {
                        ForEach(recoveryCodes, id: \.self) { Text($0).font(.system(.body, design: .monospaced)) }
                    } header: {
                        Text("Codes de secours")
                    } footer: {
                        Text("Garde ces codes en lieu sûr — ils permettent de te reconnecter sans ton téléphone.")
                    }
                    Section { Button("Terminé") { onEnrolled(); dismiss() } }
                }
                if let error { Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive) }
            }
            .navigationTitle("Activer la 2FA")
            .navigationBarTitleDisplayMode(.inline)
            .tint(Theme.brand)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Annuler") { dismiss() } } }
        }
    }

    private func initEnroll() {
        busy = true; error = nil
        Task {
            do { secret = try await dataSource.mfaEnrollInit(password: password).secret; step = .scan }
            catch {
                let apiError = error as? APIError
                self.error = apiError?.credentialMessage ?? apiError?.errorDescription ?? "Mot de passe incorrect."
            }
            busy = false
        }
    }

    private func verify() {
        busy = true; error = nil
        Task {
            do { recoveryCodes = try await dataSource.mfaEnrollVerify(code: code); step = .codes }
            catch { self.error = "Code invalide, réessaie." }
            busy = false
        }
    }
}

private struct MfaCredentialSheet: View {
    let title: String
    let actionLabel: String
    let destructive: Bool
    let action: (String, String) async throws -> [String]?
    var onDone: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @State private var resultCodes: [String]?

    var body: some View {
        NavigationStack {
            Form {
                if let resultCodes {
                    Section("Nouveaux codes de secours") {
                        ForEach(resultCodes, id: \.self) { Text($0).font(.system(.body, design: .monospaced)) }
                    }
                    Section { Button("Terminé") { onDone(); dismiss() } }
                } else {
                    Section("Mot de passe") { SecureField("Mot de passe actuel", text: $password) }
                    Section("Code 2FA") { TextField("123456", text: $code).keyboardType(.numberPad) }
                    Section {
                        Button(actionLabel, role: destructive ? .destructive : nil) { run() }
                            .disabled(busy || password.isEmpty || code.count != 6)
                    }
                    if let error { Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive) }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .tint(Theme.brand)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Annuler") { dismiss() } } }
        }
    }

    private func run() {
        busy = true; error = nil
        Task {
            do {
                if let codes = try await action(password, code) {
                    resultCodes = codes
                } else {
                    onDone(); dismiss()
                }
            } catch {
                self.error = (error as? APIError)?.credentialMessage ?? "Échec — vérifie le mot de passe et le code."
            }
            busy = false
        }
    }
}
