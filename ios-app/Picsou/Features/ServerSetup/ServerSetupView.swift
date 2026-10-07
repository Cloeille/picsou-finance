import SwiftUI

/// First-launch screen (dark premium): enter the self-hosted instance URL, with a live
/// encrypted/unencrypted indicator.
struct ServerSetupView: View {
    @Environment(AppState.self) private var appState
    @State private var urlText = ""
    @State private var isChecking = false
    @State private var errorMessage: String?

    private var isSecure: Bool {
        let u = urlText.trimmingCharacters(in: .whitespaces).lowercased()
        return u.isEmpty || !u.hasPrefix("http://")
    }

    var body: some View {
        ZStack {
            OnboardingBackground()
            VStack(spacing: 0) {
                PicsouWordmark(size: 32).padding(.top, 8)

                VStack(spacing: 10) {
                    Text("BIENVENUE")
                        .font(Theme.font(12.5, .semibold)).tracking(0.7)
                        .foregroundStyle(.white.opacity(0.9))
                    Text("Connectez votre instance")
                        .font(Theme.font(29, .heavy)).tracking(Theme.tracking(29))
                        .foregroundStyle(.white).multilineTextAlignment(.center)
                    Text("Renseignez l'adresse de votre serveur Picsou auto-hébergé pour commencer.")
                        .font(Theme.font(15)).foregroundStyle(.white.opacity(0.64))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 40).padding(.horizontal, 6)

                VStack(alignment: .leading, spacing: 9) {
                    Text("Adresse de l'instance")
                        .font(Theme.font(11.5, .semibold)).tracking(0.3)
                        .foregroundStyle(.white.opacity(0.5))
                    HStack(spacing: 11) {
                        Image(systemName: "globe")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(.white.opacity(0.85))
                        TextField("", text: $urlText,
                                  prompt: Text("https://picsou.exemple.fr").foregroundStyle(.white.opacity(0.35)))
                            .textInputAutocapitalization(.never).keyboardType(.URL)
                            .autocorrectionDisabled().textContentType(.URL)
                            .foregroundStyle(.white).font(Theme.font(16, .medium)).tint(.white)
                            .submitLabel(.go).onSubmit(submit)
                        Image(systemName: isSecure ? "checkmark" : "exclamationmark.triangle.fill")
                            .font(.system(size: isSecure ? 14 : 15, weight: .bold))
                            .foregroundStyle(.white.opacity(0.92))
                    }
                    .padding(.horizontal, 15).frame(height: 56)
                    .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.white.opacity(0.14)))

                    HStack(spacing: 7) {
                        Image(systemName: isSecure ? "lock.fill" : "lock.open.fill")
                            .font(.system(size: 11, weight: .semibold))
                        Text(isSecure ? "Connexion chiffrée de bout en bout" : "Connexion non chiffrée — HTTP")
                            .font(Theme.font(12.5))
                    }
                    .foregroundStyle(.white.opacity(0.85))

                    if let errorMessage {
                        Text(errorMessage).font(Theme.font(12.5))
                            .foregroundStyle(Color(red: 1, green: 0.5, blue: 0.5))
                    }
                }
                .padding(.top, 36)

                Spacer(minLength: 24)
                VStack(spacing: 16) {
                    HStack(spacing: 6) {
                        Text("Vous hébergez Picsou vous-même ?").foregroundStyle(.white.opacity(0.55))
                        Text("Guide d'installation").foregroundStyle(.white.opacity(0.92)).fontWeight(.semibold)
                    }
                    .font(Theme.font(13))
                    OnboardingCTA(title: "Continuer", loading: isChecking, action: submit)
                }
            }
            .padding(.horizontal, 26).padding(.top, 24).padding(.bottom, 24)
        }
        .preferredColorScheme(.dark)
    }

    private func submit() {
        guard !isChecking, !urlText.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        errorMessage = nil
        isChecking = true
        Task {
            do {
                try await appState.configureServer(urlText)
            } catch {
                errorMessage = (error as? APIError)?.errorDescription ?? "Impossible de joindre ce serveur."
            }
            isChecking = false
        }
    }
}
