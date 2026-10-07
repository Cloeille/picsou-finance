import SwiftUI

@MainActor
@Observable
final class TransactionsViewModel {
    enum State { case loading; case loaded([Transaction]); case failed(String) }
    private(set) var state: State = .loading
    private let dataSource: AccountsDataSource
    private let accountId: Int64
    private let onAuthExpired: () -> Void

    init(accountId: Int64, dataSource: AccountsDataSource, onAuthExpired: @escaping () -> Void) {
        self.accountId = accountId
        self.dataSource = dataSource
        self.onAuthExpired = onAuthExpired
    }

    func load() async {
        do {
            state = .loaded(try await dataSource.transactions(id: accountId))
        } catch {
            if (error as? APIError) == .unauthorized { onAuthExpired(); return }
            state = .failed((error as? APIError)?.errorDescription ?? "Impossible de charger les transactions.")
        }
    }
}

struct TransactionsListView: View {
    @Environment(AppState.self) private var appState
    let accountId: Int64
    let accountName: String
    @State private var vm: TransactionsViewModel?
    @State private var selected: Transaction?
    @State private var showAddCash = false

    var body: some View {
        Group {
            if let vm {
                TransactionsContent(vm: vm) { selected = $0 }
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .navigationTitle("Transactions")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddCash = true } label: { Image(systemName: "plus") }
            }
        }
        .task {
            if vm == nil {
                vm = TransactionsViewModel(accountId: accountId,
                                           dataSource: appState.makeAccountsDataSource(),
                                           onAuthExpired: { appState.signOut() })
            }
            await vm?.load()
        }
        .sheet(item: $selected) { tx in
            TransactionDetailSheet(tx: tx, categorization: appState.makeBudgetDataSource()) { _ in
                Task { await vm?.load() }
            }
        }
        .sheet(isPresented: $showAddCash) {
            AddCashView(accountId: accountId, dataSource: appState.makeAccountsDataSource()) {
                Task { await vm?.load() }
            }
        }
    }
}

private struct TransactionsContent: View {
    let vm: TransactionsViewModel
    let onSelect: (Transaction) -> Void

    var body: some View {
        switch vm.state {
        case .loading:
            ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded(let txs):
            if txs.isEmpty {
                Text("Aucune transaction.").font(Theme.font(15)).foregroundStyle(Theme.mutedForeground)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                list(txs)
            }
        case .failed(let message):
            Text(message).font(Theme.font(15)).foregroundStyle(Theme.mutedForeground).padding(32)
        }
    }

    private func list(_ txs: [Transaction]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ForEach(grouped(txs), id: \.0) { month, monthTxs in
                    VStack(alignment: .leading, spacing: 9) {
                        SectionLabel(month)
                        VStack(spacing: 0) {
                            ForEach(Array(monthTxs.enumerated()), id: \.element.id) { index, tx in
                                if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                                Button { onSelect(tx) } label: { TransactionRow(tx: tx) }
                                    .buttonStyle(.plain)
                            }
                        }
                        .cardOutline()
                    }
                }
            }
            .padding(16)
        }
    }

    private func grouped(_ txs: [Transaction]) -> [(String, [Transaction])] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "MMMM yyyy"
        var order: [String] = []
        var map: [String: [Transaction]] = [:]
        for tx in txs {
            let key = tx.day.map { formatter.string(from: $0).capitalized } ?? "—"
            if map[key] == nil { order.append(key) }
            map[key, default: []].append(tx)
        }
        return order.map { ($0, map[$0] ?? []) }
    }
}

/// One transaction line: merchant tile · label + category · signed amount.
struct TransactionRow: View {
    let tx: Transaction

    var body: some View {
        HStack(spacing: 12) {
            MerchantAvatar(label: tx.displayLabel, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(tx.displayLabel).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground).lineLimit(1)
                if let category = tx.categoryName {
                    Text(category).font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
                }
            }
            Spacer(minLength: 8)
            Text(signedAmount(tx.amount))
                .font(Theme.font(15, .bold)).monospacedDigit()
                .foregroundStyle(tx.isExpense ? Theme.foreground : Theme.positive)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
    }
}

func signedAmount(_ amount: Decimal) -> String {
    let base = Money.format(amount, fractionDigits: 2)
    return amount >= 0 ? "+\(base)" : base
}

/// Transaction detail. The "Catégorie" row is always shown (not just when set) and tappable —
/// pushes `CategoryPickerView` within this sheet's own `NavigationStack` (never a nested sheet,
/// per docs/briefs/2026-07-22-budget-ios-redesign-design.md §0.3) and updates optimistically.
struct TransactionDetailSheet: View {
    let categorization: BudgetDataSource
    var onChanged: (Transaction) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var tx: Transaction
    @State private var categories: [Category] = []
    @State private var categorizationError: String?

    init(tx: Transaction, categorization: BudgetDataSource, onChanged: @escaping (Transaction) -> Void) {
        self._tx = State(initialValue: tx)
        self.categorization = categorization
        self.onChanged = onChanged
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    MerchantAvatar(label: tx.displayLabel, size: 64)
                    Text(tx.displayLabel).font(Theme.font(20, .bold)).foregroundStyle(Theme.foreground)
                        .multilineTextAlignment(.center)
                    Text(signedAmount(tx.amount))
                        .font(Theme.font(28, .heavy)).monospacedDigit()
                        .foregroundStyle(tx.isExpense ? Theme.foreground : Theme.positive)

                    VStack(spacing: 0) {
                        detailRow("Date", formattedDate)
                        detailRow("Compte", tx.accountName ?? "—")
                        categoryRow
                        if let type = tx.txType { detailRow("Type", type) }
                        detailRow("Source", tx.manual ? "Manuelle" : "Synchronisée")
                    }
                    .cardOutline()
                    .padding(.top, 8)

                    if let categorizationError {
                        Text(categorizationError).font(Theme.font(13)).foregroundStyle(Theme.destructive)
                    }
                }
                .padding(16)
            }
            .navigationTitle("").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Fermer") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .task { categories = (try? await categorization.categories()) ?? [] }
    }

    private var categoryRow: some View {
        NavigationLink {
            CategoryPickerView(categories: categories, current: tx.categoryId,
                               suggestion: CategorySuggestion(aiSuggestionFor: tx, in: categories)) { picked in
                recategorize(to: picked)
            }
        } label: {
            HStack {
                Text("Catégorie").font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                Spacer()
                if let name = tx.categoryName {
                    Text(name).font(Theme.font(14, .semibold)).foregroundStyle(Theme.foreground)
                } else {
                    Text("Non catégorisée").font(Theme.font(14, .semibold)).foregroundStyle(Theme.mutedForeground)
                }
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.mutedForeground)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
        }
        .buttonStyle(.plain)
    }

    private func recategorize(to category: Category) {
        let previous = tx
        tx = tx.categorized(as: category.id, name: category.name)
        categorizationError = nil
        Task {
            do {
                try await categorization.categorize(transactionId: tx.id, categoryId: category.id)
                onChanged(tx)
            } catch {
                tx = previous
                categorizationError = "Impossible de changer la catégorie. Réessaie."
            }
        }
    }

    private var formattedDate: String {
        guard let day = tx.day else { return tx.date }
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateStyle = .long
        return f.string(from: day)
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text(label).font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                Spacer()
                Text(value).font(Theme.font(14, .semibold)).foregroundStyle(Theme.foreground)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
        }
    }
}
