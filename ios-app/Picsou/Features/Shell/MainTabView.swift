import SwiftUI

/// Authenticated app shell: hosts the tab destinations over a shared bottom tab bar.
struct MainTabView: View {
    @State private var tab: PicsouTab = .home

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            switch tab {
            case .home: DashboardView(onSeeAllAccounts: { tab = .assets })
            case .assets: AccountsView()
            case .goals: GoalsView()
            case .settings: SettingsView()
            case .budget: BudgetView()
            }
        }
        .tint(Theme.brand)
        .safeAreaInset(edge: .bottom) { PicsouTabBar(selection: $tab) }
    }
}

/// Placeholder for tabs that arrive in later phases.
struct ComingSoonView: View {
    let tab: PicsouTab

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: tab.icon)
                .font(.system(size: 40))
                .foregroundStyle(Theme.mutedForeground)
            Text(tab.label)
                .font(Theme.font(20, .bold))
                .foregroundStyle(Theme.foreground)
            Text("Bientôt disponible")
                .font(Theme.font(14))
                .foregroundStyle(Theme.mutedForeground)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
