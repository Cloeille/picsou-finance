import SwiftUI

@MainActor
@Observable
final class AccountDetailViewModel {
    enum State {
        case loading
        case loaded(Account, [Holding], [Transaction], LoanSchedule?)
        case failed(String)
    }

    private(set) var state: State = .loading
    private let dataSource: AccountsDataSource
    private let id: Int64
    private let onAuthExpired: () -> Void

    init(id: Int64, dataSource: AccountsDataSource, onAuthExpired: @escaping () -> Void) {
        self.id = id
        self.dataSource = dataSource
        self.onAuthExpired = onAuthExpired
    }

    func load() async {
        do {
            let account = try await dataSource.account(id: id)
            let holdings = account.type.holdsPositions ? try await dataSource.holdings(id: id) : []
            let txs = try await dataSource.transactions(id: id)
            let loan = account.type == .loan ? try? await dataSource.loanSummary(id: id) : nil
            state = .loaded(account, holdings, txs, loan)
        } catch {
            if (error as? APIError) == .unauthorized { onAuthExpired(); return }
            state = .failed((error as? APIError)?.errorDescription ?? "Impossible de charger ce compte.")
        }
    }
}

struct AccountDetailView: View {
    @Environment(AppState.self) private var appState
    let accountId: Int64
    let accountName: String
    @State private var vm: AccountDetailViewModel?
    @State private var showAddCash = false

    var body: some View {
        Group {
            if let vm {
                AccountDetailContent(vm: vm)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .navigationTitle(accountName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddCash = true } label: { Image(systemName: "plus") }
            }
        }
        .task {
            if vm == nil {
                vm = AccountDetailViewModel(id: accountId,
                                            dataSource: appState.makeAccountsDataSource(),
                                            onAuthExpired: { appState.signOut() })
            }
            await vm?.load()
        }
        .sheet(isPresented: $showAddCash) {
            AddCashView(accountId: accountId, dataSource: appState.makeAccountsDataSource()) {
                Task { await vm?.load() }
            }
        }
    }
}

private struct AccountDetailContent: View {
    let vm: AccountDetailViewModel

    var body: some View {
        switch vm.state {
        case .loading:
            ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded(let account, let holdings, let txs, let loan):
            loaded(account, holdings, txs, loan)
        case .failed(let message):
            Text(message).font(Theme.font(15)).foregroundStyle(Theme.mutedForeground).padding(32)
        }
    }

    private func loaded(_ account: Account, _ holdings: [Holding], _ txs: [Transaction], _ loan: LoanSchedule?) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header(account)
                if !holdings.isEmpty { holdingsSection(holdings) }
                if account.type == .creditCard { creditCardSection(account) }
                if let loan { loanSection(account, loan) }
                transactionsSection(account, txs)
            }
            .padding(16)
        }
    }

    private func header(_ account: Account) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                AccountTypeBadge(type: account.type)
                if let provider = account.provider {
                    Text(provider).font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
                }
            }
            Text(Money.format(account.currentBalanceEur))
                .font(Theme.font(36, .heavy)).tracking(Theme.tracking(36, em: -0.03)).monospacedDigit()
                .foregroundStyle(Theme.foreground)
            Text(account.manual ? "Compte manuel" : syncedLabel(account.lastSyncedDate))
                .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .picsouCard()
    }

    private func holdingsSection(_ holdings: [Holding]) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            SectionLabel("Positions")
            VStack(spacing: 0) {
                ForEach(Array(holdings.enumerated()), id: \.element.id) { index, h in
                    if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(h.name ?? h.ticker).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                            Text(h.ticker).font(Theme.font(12)).foregroundStyle(Theme.mutedForeground)
                        }
                        Spacer(minLength: 8)
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(Money.format(h.currentValueEur ?? 0)).font(Theme.font(15, .bold)).monospacedDigit().foregroundStyle(Theme.foreground)
                            if let pnl = h.pnlPercent {
                                let up = pnl >= 0
                                Text("\(up ? "+" : "")\(Percent.format(pnl.doubleValue))")
                                    .font(Theme.font(12, .semibold)).foregroundStyle(up ? Theme.positive : Theme.destructive)
                            }
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 12)
                }
            }
            .cardOutline()
        }
    }

    private func creditCardSection(_ account: Account) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            SectionLabel("Carte de crédit")
            VStack(alignment: .leading, spacing: 12) {
                statRow("Montant dû", account.paymentDueAmount.map { Money.format($0, fractionDigits: 2) } ?? "—")
                statRow("Échéance", DueDate.label(account.paymentDueDate) ?? "—")
                if let points = account.rewardPoints {
                    statRow("Points", points.formatted(.number.locale(Locale(identifier: "fr_FR"))))
                }
            }
            .padding(16)
            .cardOutline()
        }
    }

    private func loanSection(_ account: Account, _ loan: LoanSchedule) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            SectionLabel("Prêt")
            VStack(alignment: .leading, spacing: 12) {
                statRow("Mensualité", loan.summary.monthlyPayment.map { Money.format($0) } ?? "—")
                statRow("Capital restant", loan.summary.remainingBalance.map { Money.format($0) } ?? "—")
                if let pct = loan.summary.capitalRepaidPct {
                    ProgressBar(value: pct.doubleValue / 100)
                    Text("\(Int(pct.doubleValue)) % remboursé")
                        .font(Theme.font(12)).foregroundStyle(Theme.mutedForeground)
                }
                Divider().background(Theme.border)
                NavigationLink {
                    LoanScheduleView(accountId: account.id, accountName: account.name)
                } label: {
                    HStack {
                        Text("Voir l'échéancier complet").font(Theme.font(14, .semibold)).foregroundStyle(Theme.brand)
                        Spacer()
                        Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.mutedForeground)
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(16)
            .cardOutline()
        }
    }

    private func transactionsSection(_ account: Account, _ txs: [Transaction]) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                SectionLabel("Transactions")
                Spacer()
                if txs.count > 6 {
                    NavigationLink {
                        TransactionsListView(accountId: account.id, accountName: account.name)
                    } label: {
                        Text("Tout voir").font(Theme.font(13, .semibold)).foregroundStyle(Theme.brand)
                    }
                }
            }
            if txs.isEmpty {
                Text("Aucune transaction.").font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                    .frame(maxWidth: .infinity).padding(.vertical, 20)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(txs.prefix(6).enumerated()), id: \.element.id) { index, tx in
                        if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                        TransactionRow(tx: tx)
                    }
                }
                .cardOutline()
            }
        }
    }

    // MARK: helpers

    private func statRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
            Spacer()
            Text(value).font(Theme.font(15, .semibold)).monospacedDigit().foregroundStyle(Theme.foreground)
        }
    }

    private func syncedLabel(_ date: Date?) -> String {
        guard let date else { return "—" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "fr_FR")
        return "Synchronisé \(f.localizedString(for: date, relativeTo: Date()))"
    }

    private func monthLabel(_ date: Date?) -> String {
        guard let date else { return "" }
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "MMM yyyy"
        return f.string(from: date)
    }
}

/// Card border + rounding for grouped row lists.
extension View {
    func cardOutline() -> some View {
        self
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(Theme.border, lineWidth: 1))
    }
}
