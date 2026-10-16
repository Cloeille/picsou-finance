import SwiftUI

/// Shared look for the onboarding / auth flow: a premium dark canvas with a blue "aurora" glow.
/// These screens are always dark (independent of system appearance).
struct OnboardingBackground: View {
    var body: some View {
        // Base gradient fills the proposed size; the oversized glow is overlaid + clipped so it
        // never drives layout width (a fixed-size child would otherwise stretch the whole screen).
        LinearGradient(
            colors: [Color(red: 0.043, green: 0.047, blue: 0.063),
                     Color(red: 0.031, green: 0.035, blue: 0.047)],
            startPoint: .top, endPoint: .bottom)
        .overlay(alignment: .top) {
            Circle()
                .fill(Color(red: 0.23, green: 0.44, blue: 0.95).opacity(0.30))
                .frame(width: 560, height: 320)
                .blur(radius: 70)
                .offset(y: -150)
        }
        .overlay {
            LinearGradient(
                colors: [.clear, Color(red: 0.027, green: 0.031, blue: 0.043).opacity(0.86)],
                startPoint: .center, endPoint: .bottom)
        }
        .clipped()
        .ignoresSafeArea()
    }
}

/// Full-width white pill CTA with a trailing arrow (design primary button on the dark canvas).
struct OnboardingCTA: View {
    let title: String
    var loading: Bool = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if loading { ProgressView().tint(.black).controlSize(.small) }
                Text(title).font(Theme.font(16, .bold))
                Image(systemName: "arrow.right").font(.system(size: 15, weight: .semibold))
            }
            .foregroundStyle(Color(red: 0.043, green: 0.043, blue: 0.094))
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(.white, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
            .shadow(color: .black.opacity(0.45), radius: 18, y: 12)
        }
        .disabled(loading)
    }
}

/// Icon-in-a-rounded-square + title + description row used on the intro screen.
struct FeatureRow: View {
    let systemImage: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white.opacity(0.92))
                .frame(width: 46, height: 46)
                .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).strokeBorder(.white.opacity(0.12)))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Theme.font(15.5, .bold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(Theme.font(13))
                    .foregroundStyle(.white.opacity(0.6))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }
}

/// Text wordmark stand-in for the Picsou logo (the SVG asset can replace this later).
struct PicsouWordmark: View {
    /// Rendered height of the wordmark.
    var size: CGFloat = 30
    var body: some View {
        Image("PicsouWordmark")
            .resizable()
            .scaledToFit()
            .frame(height: size)
            .accessibilityLabel("Picsou")
    }
}
