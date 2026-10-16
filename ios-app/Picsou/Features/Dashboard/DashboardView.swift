import SwiftUI

/// Read-only dashboard — design "Variant B": greeting header, a featured blue net-worth card with a
/// sparkline, the top goal, and a condensed assets list, over a bottom tab bar.
struct DashboardView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: DashboardViewModel?
    var onSeeAllAccounts: () -> Void = {}

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            if let vm {
                // Observe the view model in a child that holds it non-optionally, so state changes
                // (loading → loaded) reliably re-render — an optional in the parent does not track.
                DashboardContent(vm: vm, isDemo: appState.isDemo, onSignOut: { appState.signOut() }, onSeeAllAccounts: onSeeAllAccounts)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .tint(Theme.brand)
        .task {
            if vm == nil {
                vm = DashboardViewModel(
                    dataSource: appState.makeDashboardDataSource(),
                    onAuthExpired: { appState.signOut() }
                )
            }
            await vm?.load()
        }
    }
}

private struct DashboardContent: View {
    let vm: DashboardViewModel
    let isDemo: Bool
    let onSignOut: () -> Void
    let onSeeAllAccounts: () -> Void

    var body: some View {
        switch vm.state {
        case .loading:
            ProgressView().controlSize(.large)
        case .loaded(let data):
            loaded(data)
        case .failed(let message):
            errorView(message)
        }
    }

    // MARK: Loaded

    @ViewBuilder
    private func loaded(_ data: DashboardResponse) -> some View {
        NavigationStack {
          ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header
                HeroNetWorthCard(
                    netWorth: data.totalNetWorth,
                    delta: monthDelta(data.netWorthHistory),
                    spark: data.netWorthHistory.map(\.totalDouble)
                )
                if let goal = data.goalSummaries.first {
                    VStack(alignment: .leading, spacing: 9) {
                        SectionLabel("Objectif")
                        GoalCard(goal: goal)
                    }
                }
                if !data.distribution.isEmpty {
                    VStack(alignment: .leading, spacing: 11) {
                        assetsHeader(count: data.distribution.count)
                        AssetsListCard(items: data.distribution)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
          }
          .refreshable { await vm.load() }
          .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(greetingName.map { "Bonjour, \($0)" } ?? "Bonjour")
                    .font(Theme.font(13))
                    .foregroundStyle(Theme.mutedForeground)
                Text("Patrimoine")
                    .font(Theme.font(21, .heavy))
                    .tracking(Theme.tracking(21, em: -0.01))
                    .foregroundStyle(Theme.foreground)
            }
            Spacer()
            Menu {
                if isDemo {
                    Label("Mode démo", systemImage: "sparkles")
                } else {
                    Button("Se déconnecter", role: .destructive, action: onSignOut)
                }
            } label: {
                Avatar(initials: initials, size: 40)
            }
        }
    }

    private func assetsHeader(count: Int) -> some View {
        HStack(spacing: 8) {
            SectionLabel("Actifs")
            Text("\(count)")
                .font(Theme.font(11, .bold))
                .foregroundStyle(Theme.mutedForeground)
                .padding(.horizontal, 6)
                .frame(minWidth: 20, minHeight: 20)
                .background(Theme.muted, in: Capsule())
            Spacer()
            Button(action: onSeeAllAccounts) {
                Text("Tout voir")
                    .font(Theme.font(13, .semibold))
                    .foregroundStyle(Theme.brand)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private func errorView(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(Theme.destructive)
            Text(message)
                .font(Theme.font(15))
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.mutedForeground)
            Button("Réessayer") { Task { await vm.load() } }
                .buttonStyle(.bordered)
                .tint(Theme.brand)
        }
        .padding(32)
    }

    // MARK: Helpers

    private var greetingName: String? { isDemo ? "Chloé" : nil }
    private var initials: String? { greetingName.map { String($0.prefix(1)).uppercased() } }

    private func monthDelta(_ history: [NetWorthPoint]) -> HeroNetWorthCard.DeltaValue? {
        guard history.count >= 2 else { return nil }
        let last = history[history.count - 1].total
        let previous = history[history.count - 2].total
        let diff = last - previous
        let previousD = previous.doubleValue
        let pctValue = previousD != 0 ? (diff.doubleValue / previousD * 100) : 0
        return HeroNetWorthCard.DeltaValue(
            amount: Money.formatSigned(diff),
            pct: String(format: "%+.1f%%", pctValue),
            positive: diff >= 0
        )
    }
}
