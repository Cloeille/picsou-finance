import SwiftUI

enum PicsouTab: CaseIterable {
    case home, budget, assets, goals, settings

    var icon: String {
        switch self {
        case .home: return "house.fill"
        case .budget: return "chart.pie.fill"
        case .assets: return "creditcard.fill"
        case .goals: return "target"
        case .settings: return "gearshape.fill"
        }
    }

    var label: String {
        switch self {
        case .home: return "Accueil"
        case .budget: return "Budget"
        case .assets: return "Actifs"
        case .goals: return "Objectifs"
        case .settings: return "Réglages"
        }
    }
}

/// Bottom tab bar. On iOS 26+ it renders as a floating **Liquid Glass** pill (as in the design
/// template); on older systems it falls back to a standard material bar with a top hairline.
struct PicsouTabBar: View {
    @Binding var selection: PicsouTab

    var body: some View {
        if #available(iOS 26.0, *) {
            glassBar
        } else {
            standardBar
        }
    }

    private var items: some View {
        HStack(spacing: 0) {
            ForEach(PicsouTab.allCases, id: \.self) { tab in
                Button { selection = tab } label: { item(tab) }
                    .buttonStyle(.plain)
            }
        }
    }

    private var standardBar: some View {
        items
            .padding(.top, 10)
            .padding(.horizontal, 4)
            .background(.ultraThinMaterial)
            .overlay(alignment: .top) { Rectangle().fill(Theme.border).frame(height: 0.5) }
    }

    @available(iOS 26.0, *)
    private var glassBar: some View {
        items
            .padding(.vertical, 9)
            .padding(.horizontal, 6)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .padding(.horizontal, 14)
            .padding(.bottom, 6)
    }

    private func item(_ tab: PicsouTab) -> some View {
        VStack(spacing: 4) {
            Image(systemName: tab.icon).font(.system(size: 21))
            Text(tab.label).font(Theme.font(10, .semibold))
        }
        .foregroundStyle(selection == tab ? Theme.brand : Theme.mutedForeground)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }
}
