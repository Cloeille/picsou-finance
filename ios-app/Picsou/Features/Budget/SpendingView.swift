import SwiftUI

/// Ranked expense-by-category breakdown for the cycle or year, with a drill-down per category
/// (transactions + inline re-categorization). Pushed from the Budget hub.
struct SpendingView: View {
    let dataSource: BudgetDataSource

    @State private var period: CashflowPeriod = .cycle
    @State private var breakdown: SpendingByCategory?
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("", selection: $period) {
                    Text("Cycle").tag(CashflowPeriod.cycle)
                    Text("Année").tag(CashflowPeriod.yearToDate)
                }
                .pickerStyle(.segmented)

                if isLoading {
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity).padding(.top, 40)
                } else if let errorMessage {
                    Text(errorMessage).font(Theme.font(14)).foregroundStyle(Theme.mutedForeground).padding(.top, 40)
                } else if let breakdown, breakdown.categories.isEmpty {
                    Text(period == .cycle ? "Aucune dépense sur ce cycle." : "Aucune dépense cette année.")
                        .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground).padding(.top, 40)
                } else if let breakdown {
                    VStack(spacing: 0) {
                        ForEach(Array(breakdown.categories.enumerated()), id: \.element.id) { index, item in
                            if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                            destination(for: item) { row(item) }
                        }
                    }
                    .cardOutline()
                }
            }
            .padding(16)
        }
        .navigationTitle("Dépenses")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task(id: period) { await load() }
    }

    /// The backend has no "uncategorized" detail endpoint (`GET /api/spending/category/{id}`
    /// needs a real category id) -- routing that bucket to the categorization inbox is both the
    /// fix for what would otherwise be a dead drill-down and the more useful destination anyway:
    /// "uncategorized spending" and "the inbox" are the same underlying transactions.
    @ViewBuilder
    private func destination<Label: View>(for item: CategorySpend, @ViewBuilder label: () -> Label) -> some View {
        if let categoryId = item.categoryId {
            NavigationLink {
                SpendingDetailView(dataSource: dataSource, categoryId: categoryId, categoryName: item.name, period: period)
            } label: { label() }
            .buttonStyle(.plain)
        } else {
            NavigationLink {
                CategorizationInboxView(dataSource: dataSource, onChanged: { Task { await load() } })
            } label: { label() }
            .buttonStyle(.plain)
        }
    }

    private func row(_ item: CategorySpend) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Color.account(item.color ?? Theme.fallbackColorHex)).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                ProgressBar(value: (item.share as NSDecimalNumber).doubleValue, height: 4, tint: Color.account(item.color ?? Theme.fallbackColorHex))
                    .frame(width: 120)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(Money.format(item.amount)).font(Theme.font(14, .semibold)).monospacedDigit().foregroundStyle(Theme.foreground)
                Text("\(item.count) tx").font(Theme.font(11.5)).foregroundStyle(Theme.mutedForeground)
            }
        }
        .padding(14)
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            breakdown = try await dataSource.spendingByCategory(period: period)
        } catch {
            errorMessage = "Impossible de charger les dépenses."
        }
        isLoading = false
    }
}

private struct SpendingDetailView: View {
    let dataSource: BudgetDataSource
    let categoryId: Int64
    let categoryName: String
    let period: CashflowPeriod

    @State private var detail: SpendingDetail?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var selected: Transaction?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let detail {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Money.format(abs(detail.total)))
                            .font(Theme.font(28, .heavy)).monospacedDigit().foregroundStyle(Theme.foreground)
                        Text("\(detail.count) transaction\(detail.count > 1 ? "s" : "")")
                            .font(Theme.font(13)).foregroundStyle(Theme.mutedForeground)
                    }
                    if detail.transactions.isEmpty {
                        Text("Aucune transaction.").font(Theme.font(14)).foregroundStyle(Theme.mutedForeground).padding(.top, 20)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(detail.transactions.enumerated()), id: \.element.id) { index, tx in
                                if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                                Button { selected = tx } label: { transactionRow(tx) }
                                    .buttonStyle(.plain)
                            }
                        }
                        .cardOutline()
                    }
                } else if isLoading {
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity).padding(.top, 40)
                } else if let errorMessage {
                    Text(errorMessage).font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                        .frame(maxWidth: .infinity).padding(.top, 40)
                }
            }
            .padding(16)
        }
        .navigationTitle(categoryName)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: $selected) { tx in
            TransactionDetailSheet(tx: tx, categorization: dataSource) { updated in
                selected = nil
                Task { await load() }
            }
        }
    }

    private func transactionRow(_ tx: Transaction) -> some View {
        HStack(spacing: 10) {
            MerchantAvatar(label: tx.displayLabel, size: 32)
            Text(tx.displayLabel).font(Theme.font(14, .medium)).foregroundStyle(Theme.foreground).lineLimit(1)
            Spacer()
            Text(Money.format(abs(tx.amount))).font(Theme.font(14, .semibold)).monospacedDigit().foregroundStyle(Theme.foreground)
        }
        .padding(14)
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            detail = try await dataSource.spendingDetail(categoryId: categoryId, period: period)
        } catch {
            errorMessage = "Impossible de charger le détail. Tire pour réessayer."
        }
        isLoading = false
    }
}
