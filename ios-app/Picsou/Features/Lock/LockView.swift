import SwiftUI

/// Face ID gate (dark premium). Prompts automatically on appear; a pulsing glow behind the glyph.
struct LockView: View {
    @Environment(AppState.self) private var appState
    @State private var pulse = false

    var body: some View {
        ZStack {
            OnboardingBackground()
            VStack(spacing: 0) {
                Spacer()
                ZStack {
                    Circle()
                        .fill(Color(red: 0.23, green: 0.44, blue: 0.95).opacity(0.35))
                        .frame(width: 150, height: 150)
                        .blur(radius: 32)
                        .scaleEffect(pulse ? 1.18 : 0.9)
                    Image(systemName: "faceid")
                        .font(.system(size: 92, weight: .light))
                        .foregroundStyle(.white)
                }
                .onAppear {
                    withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) { pulse = true }
                }

                Text("Déverrouillez Picsou")
                    .font(Theme.font(23, .heavy)).tracking(Theme.tracking(23, em: -0.01))
                    .foregroundStyle(.white).padding(.top, 32)
                Text("Regardez votre iPhone pour continuer")
                    .font(Theme.font(14.5)).foregroundStyle(.white.opacity(0.6)).padding(.top, 9)
                if let error = appState.lastError {
                    Text(error).font(Theme.font(12.5))
                        .foregroundStyle(Color(red: 1, green: 0.5, blue: 0.5)).padding(.top, 10)
                }

                Spacer()
                Button("Utiliser le code") { Task { await appState.unlock() } }
                    .font(Theme.font(15, .semibold)).foregroundStyle(.white.opacity(0.9))
                Button("Se déconnecter") { appState.signOut() }
                    .font(Theme.font(13)).foregroundStyle(.white.opacity(0.5)).padding(.top, 12)
            }
            .padding(.horizontal, 30).padding(.top, 24).padding(.bottom, 30)
        }
        .preferredColorScheme(.dark)
        .task { await appState.unlock() }
    }
}
