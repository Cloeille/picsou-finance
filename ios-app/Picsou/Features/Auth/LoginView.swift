import SwiftUI

/// Sign-in screen (dark premium): instance badge, big wordmark + tagline, and a CTA that launches
/// the OAuth2 + PKCE web-auth (existing Picsou login + TOTP).
struct LoginView: View {
    @Environment(AppState.self) private var appState
    @State private var isLoggingIn = false
    @State private var errorMessage: String?

    private var instanceHost: String { appState.serverConfig.baseURL?.host ?? "votre instance" }

    var body: some View {
        ZStack {
            OnboardingBackground()
            VStack(spacing: 0) {
                HStack(spacing: 7) {
                    Image(systemName: "lock.fill").font(.system(size: 12, weight: .semibold))
                    Text(instanceHost).font(Theme.font(12.5, .medium))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 13).padding(.vertical, 7)
                .background(.white.opacity(0.12), in: Capsule())
                .overlay(Capsule().strokeBorder(.white.opacity(0.2)))
                .padding(.top, 8)

                Spacer()
                VStack(spacing: 22) {
                    PicsouWordmark(size: 52)
                    Text("Votre patrimoine, en sécurité.")
                        .font(Theme.font(15.5)).foregroundStyle(.white.opacity(0.72))
                }
                Spacer()

                if let errorMessage {
                    Text(errorMessage)
                        .font(Theme.font(13)).foregroundStyle(Color(red: 1, green: 0.5, blue: 0.5))
                        .multilineTextAlignment(.center).padding(.bottom, 8)
                }
                VStack(spacing: 12) {
                    OnboardingCTA(title: "Se connecter", loading: isLoggingIn, action: signIn)
                    HStack(spacing: 6) {
                        Image(systemName: "lock.fill").font(.system(size: 11))
                        Text("Ouvre une fenêtre sécurisée")
                    }
                    .font(Theme.font(12.5)).foregroundStyle(.white.opacity(0.6))
                    Button("Changer d'instance") { appState.resetServer() }
                        .font(Theme.font(14, .semibold)).foregroundStyle(.white.opacity(0.85))
                        .padding(.top, 6)
                }
            }
            .padding(.horizontal, 30).padding(.top, 24).padding(.bottom, 26)
        }
        .preferredColorScheme(.dark)
    }

    private func signIn() {
        errorMessage = nil
        isLoggingIn = true
        Task {
            do {
                try await appState.login()
            } catch {
                if (error as? APIError) != .unauthorized {
                    errorMessage = (error as? APIError)?.errorDescription ?? "La connexion a échoué. Réessayez."
                }
            }
            isLoggingIn = false
        }
    }
}
