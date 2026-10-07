import SwiftUI

/// First-run intro: wordmark, promise, three feature rows, and a "Get started" CTA.
struct IntroView: View {
    var onContinue: () -> Void

    var body: some View {
        ZStack {
            OnboardingBackground()
            VStack(spacing: 0) {
                PicsouWordmark(size: 34).padding(.top, 8)

                VStack(spacing: 12) {
                    Text("Vos finances, enfin réunies.")
                        .font(Theme.font(27, .heavy))
                        .tracking(Theme.tracking(27))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                    Text("Picsou rassemble tout votre patrimoine en un seul endroit — privé et auto-hébergé.")
                        .font(Theme.font(14.5))
                        .foregroundStyle(.white.opacity(0.64))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 28)
                .padding(.horizontal, 6)

                VStack(spacing: 16) {
                    FeatureRow(systemImage: "square.stack.3d.up.fill",
                               title: "Patrimoine unifié",
                               subtitle: "Comptes, épargne, crypto et immobilier au même endroit.")
                    FeatureRow(systemImage: "lock.shield.fill",
                               title: "100 % auto-hébergé",
                               subtitle: "Vos données restent sur votre serveur. Jamais les nôtres.")
                    FeatureRow(systemImage: "target",
                               title: "Objectifs & budgets",
                               subtitle: "Suivez vos objectifs d'épargne et vos dépenses.")
                }
                .padding(.top, 34)

                Spacer(minLength: 24)
                OnboardingCTA(title: "Commencer", action: onContinue)
            }
            .padding(.horizontal, 26)
            .padding(.top, 24)
            .padding(.bottom, 24)
        }
        .preferredColorScheme(.dark)
    }
}

/// Unconfigured entry flow: intro → server setup.
struct OnboardingFlow: View {
    @State private var showSetup = false

    var body: some View {
        if showSetup {
            ServerSetupView()
        } else {
            IntroView { showSetup = true }
        }
    }
}
