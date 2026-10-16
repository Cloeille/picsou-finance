import SwiftUI

/// Accounts screen (Actifs tab): the instance's accounts grouped by category, each group a card of
/// rows with a subtotal. Reuses the dashboard payload (its `distribution` is the account list).
struct AccountsView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: DashboardViewModel?

    var body: some View {
        Group {
            if let vm {
                AccountsContent(vm: vm)
            } else {
                ProgressView().controlSize(.large)
            }
        }
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

private struct AccountsContent: View {
    let vm: DashboardViewModel

    var body: some View {
        switch vm.state {
        case .loading:
            ProgressView().controlSize(.large)
        case .loaded(let data):
            loaded(data)
        case .failed(let message):
            Text(message)
                .font(Theme.font(15))
                .foregroundStyle(Theme.mutedForeground)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func loaded(_ data: DashboardResponse) -> some View {
        let groups = grouped(data.distribution)
        let total = data.distribution.reduce(Decimal(0)) { $0 + $1.balanceEur }
        return NavigationStack {
          ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Text("Comptes")
                        .font(Theme.font(32, .heavy))
                        .tracking(Theme.tracking(32))
                        .foregroundStyle(Theme.foreground)
                    Spacer()
                    Avatar(initials: nil, size: 36)
                }
                (Text("Total des actifs · ").foregroundColor(Theme.mutedForeground)
                    + Text(Money.format(total)).foregroundColor(Theme.foreground).fontWeight(.bold))
                    .font(Theme.font(14))
                    .monospacedDigit()

                ForEach(groups) { group in
                    VStack(alignment: .leading, spacing: 9) {
                        HStack {
                            SectionLabel(group.name)
                            Spacer()
                            Text(Money.format(group.subtotal))
                                .font(Theme.font(13, .semibold))
                                .foregroundStyle(Theme.mutedForeground)
                                .monospacedDigit()
                        }
                        AssetsListCard(items: group.items)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
          }
          .toolbar(.hidden, for: .navigationBar)
        }
    }

    private struct AccountGroup: Identifiable {
        let id: String
        let name: String
        let rank: Int
        let items: [DistributionItem]
        let subtotal: Decimal
    }

    private func grouped(_ items: [DistributionItem]) -> [AccountGroup] {
        Dictionary(grouping: items) { $0.type.category }
            .map { category, groupItems in
                AccountGroup(
                    id: category,
                    name: category,
                    rank: groupItems.first?.type.categoryRank ?? 99,
                    items: groupItems.sorted { $0.balanceEur > $1.balanceEur },
                    subtotal: groupItems.reduce(Decimal(0)) { $0 + $1.balanceEur }
                )
            }
            .sorted { $0.rank < $1.rank }
    }
}
