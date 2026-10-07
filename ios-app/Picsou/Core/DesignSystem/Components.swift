import SwiftUI

/// Card surface: card fill + hairline border + continuous rounding (shadcn `Card`).
struct PicsouCard: ViewModifier {
    var padding: CGFloat = 16
    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                    .strokeBorder(Theme.border, lineWidth: 1)
            )
    }
}

extension View {
    func picsouCard(padding: CGFloat = 16) -> some View { modifier(PicsouCard(padding: padding)) }
}

/// Uppercase, tracked, muted section label ("ACTIFS", "OBJECTIF").
struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(Theme.font(12, .bold))
            .tracking(0.6)
            .foregroundStyle(Theme.mutedForeground)
    }
}

/// Circular initials/person avatar.
struct Avatar: View {
    var initials: String?
    var size: CGFloat = 38

    var body: some View {
        Group {
            if let initials {
                Text(initials).font(Theme.font(size * 0.37, .bold))
            } else {
                Image(systemName: "person.fill").font(.system(size: size * 0.42))
            }
        }
        .foregroundStyle(Theme.mutedForeground)
        .frame(width: size, height: size)
        .background(Theme.muted, in: Circle())
    }
}

/// Small muted pill naming an account type.
struct AccountTypeBadge: View {
    let type: AccountType
    var body: some View {
        Text(type.label)
            .font(Theme.font(11, .semibold))
            .foregroundStyle(Theme.mutedForeground)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Theme.muted, in: Capsule())
    }
}

/// Change chip: green on plain surfaces, translucent white on a colored (hero) surface.
struct DeltaPill: View {
    let amount: String
    var pct: String?
    var positive: Bool = true
    var onColor: Bool = false

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: positive ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill")
                .font(.system(size: 9))
            Text(amount).font(Theme.font(13, .semibold)).monospacedDigit()
            if let pct {
                Text("· \(pct)").font(Theme.font(13, .semibold)).opacity(0.85)
            }
        }
        .foregroundStyle(onColor ? Color.white : (positive ? Theme.positive : Theme.destructive))
        .padding(.horizontal, 10)
        .frame(height: 26)
        .background(
            onColor ? AnyShapeStyle(Color.white.opacity(0.22))
                    : AnyShapeStyle(positive ? Theme.positiveSurface : Theme.destructive.opacity(0.14)),
            in: Capsule()
        )
    }
}

/// Rounded track + tinted fill progress bar (value 0…1). Tint defaults to the brand color;
/// pass e.g. `Theme.destructive` for an over-budget envelope.
struct ProgressBar: View {
    let value: Double
    var height: CGFloat = 8
    var tint: Color = Theme.brand

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.muted)
                Capsule().fill(tint)
                    .frame(width: max(0, min(value, 1)) * geo.size.width)
            }
        }
        .frame(height: height)
    }
}

/// A category's color dot + name, matched to the width of a compact capsule label. Shared by
/// `AISuggestionChip`/`CategoryChip` rather than duplicating the dot+text pairing.
private struct CategoryLabel: View {
    let color: String
    let name: String
    var font: Font = Theme.font(12, .medium)

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(Color.account(color)).frame(width: 8, height: 8)
            Text(name).font(font).lineLimit(1).minimumScaleFactor(0.8)
        }
    }
}

/// Marks an AI category suggestion — distinct from a plain assigned category (`CategoryChip`) so
/// the user always knows which is which. Confidence is shown only when it's high enough to be
/// worth surfacing as a number (see `CategorizationInboxView`'s confidence-gating rule); below
/// that it reads as unhelpful noise, so the chip stays sparkles + name only.
struct AISuggestionChip: View {
    let categoryColor: String
    let categoryName: String
    var confidence: Int?

    private var confidenceLabel: String? {
        guard let confidence, confidence >= 60 else { return nil }
        return "· \(confidence)%"
    }

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "sparkles").font(.system(size: 11))
            CategoryLabel(color: categoryColor, name: categoryName, font: Theme.font(12, .semibold))
            if let confidenceLabel {
                Text(confidenceLabel).font(Theme.font(12, .semibold)).opacity(0.85)
            }
        }
        .foregroundStyle(Theme.brand)
        .padding(.horizontal, 10)
        .frame(height: 26)
        .background(Theme.brand.opacity(0.12), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Suggestion IA : \(categoryName), confiance \(confidenceBand)")
    }

    private var confidenceBand: String {
        guard let confidence else { return "inconnue" }
        if confidence >= 75 { return "élevée" }
        if confidence >= 60 { return "moyenne" }
        return "faible"
    }
}

/// A neutral, compact display of an already-assigned category (inbox cards post-accept,
/// spending drill-down rows, transaction detail). Same gauge as `AccountTypeBadge`.
struct CategoryChip: View {
    let color: String
    let name: String

    var body: some View {
        CategoryLabel(color: color, name: name)
            .foregroundStyle(Theme.secondaryForeground)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(Theme.muted, in: Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Catégorie : \(name)")
    }
}

/// Status of a recurring series. Color-coded but never color-only — the word always carries the
/// meaning (accessibility: color-blind users, and VoiceOver reads the label regardless).
struct StatusChip: View {
    enum Status {
        case confirmed, new, priceIncrease, ignored

        var label: String {
            switch self {
            case .confirmed: return "Confirmé"
            case .new: return "Nouveau"
            case .priceIncrease: return "Hausse de prix"
            case .ignored: return "Ignoré"
            }
        }
        var icon: String? {
            switch self {
            case .confirmed: return "checkmark.seal.fill"
            case .new: return nil
            case .priceIncrease: return "arrow.up.right"
            case .ignored: return "eye.slash"
            }
        }
        var foreground: Color {
            switch self {
            case .confirmed: return Theme.positive
            case .new: return Theme.brand
            case .priceIncrease: return Theme.destructive
            case .ignored: return Theme.mutedForeground
            }
        }
        var background: Color {
            switch self {
            case .confirmed: return Theme.positiveSurface
            case .new: return Theme.brand.opacity(0.12)
            case .priceIncrease: return Theme.destructive.opacity(0.14)
            case .ignored: return Theme.muted
            }
        }
    }

    let status: Status

    var body: some View {
        HStack(spacing: 4) {
            if let icon = status.icon {
                Image(systemName: icon).font(.system(size: 9))
            }
            Text(status.label).font(Theme.font(11, .semibold))
        }
        .foregroundStyle(status.foreground)
        .padding(.horizontal, 7).padding(.vertical, 2)
        .background(status.background, in: Capsule())
        .contentTransition(.opacity)
        .accessibilityLabel(status.label)
    }
}
