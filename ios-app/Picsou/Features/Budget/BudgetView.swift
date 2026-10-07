import SwiftUI

@MainActor
@Observable
final class BudgetViewModel {
    enum State { case loading; case loaded(CashflowSummary, [BudgetEnvelope], Int); case failed(String) }
    private(set) var state: State = .loading
    let dataSource: BudgetDataSource
    private let onAuthExpired: () -> Void

    init(dataSource: BudgetDataSource, onAuthExpired: @escaping () -> Void) {
        self.dataSource = dataSource
        self.onAuthExpired = onAuthExpired
    }

    func load() async {
        do {
            async let cashflowTask = dataSource.cashflow()
            async let budgetsTask = dataSource.budgets()
            async let uncategorizedTask = dataSource.uncategorizedTransactions()
            let (cashflow, budgets, uncategorized) = try await (cashflowTask, budgetsTask, uncategorizedTask)
            state = .loaded(cashflow, budgets, uncategorized.count)
        } catch {
            if (error as? APIError) == .unauthorized { onAuthExpired(); return }
            state = .failed((error as? APIError)?.errorDescription ?? "Impossible de charger le budget.")
        }
    }
}

/// Budget tab: a hub over the cycle's cashflow + envelopes, with pushes to the categorization
/// inbox, spending breakdown, recurring subscriptions, and budget settings — see
/// docs/briefs/2026-07-22-budget-ios-redesign-design.md for the why behind this shape.
struct BudgetView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: BudgetViewModel?

    var body: some View {
        Group {
            if let vm {
                BudgetContent(vm: vm)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .task {
            if vm == nil {
                vm = BudgetViewModel(dataSource: appState.makeBudgetDataSource(),
                                     onAuthExpired: { appState.signOut() })
            }
            await vm?.load()
        }
    }
}

private struct BudgetContent: View {
    let vm: BudgetViewModel
    @Environment(AppState.self) private var appState
    @State private var envelopeSheet: EnvelopeSheetContext?

    private enum EnvelopeSheetContext: Identifiable {
        case new
        case edit(BudgetEnvelope)
        var id: String { switch self { case .new: return "new"; case .edit(let e): return "edit-\(e.id)" } }
    }

    var body: some View {
        NavigationStack {
            Group {
                switch vm.state {
                case .loading:
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
                case .loaded(let cashflow, let budgets, let uncategorizedCount):
                    loaded(cashflow, budgets, uncategorizedCount)
                case .failed(let message):
                    ScrollView {
                        Text(message).font(Theme.font(15)).foregroundStyle(Theme.mutedForeground)
                            .frame(maxWidth: .infinity).padding(.top, 100)
                    }
                    .refreshable { await vm.load() }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(item: $envelopeSheet) { context in
                switch context {
                case .new:
                    EnvelopeFormView(dataSource: vm.dataSource, envelope: nil, onSaved: { Task { await vm.load() } })
                case .edit(let envelope):
                    EnvelopeFormView(dataSource: vm.dataSource, envelope: envelope, onSaved: { Task { await vm.load() } })
                }
            }
        }
    }

    private func loaded(_ cashflow: CashflowSummary, _ budgets: [BudgetEnvelope], _ uncategorizedCount: Int) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header

                cashflowCard(cashflow, budgets)

                categorizeBanner(uncategorizedCount)

                VStack(alignment: .leading, spacing: 11) {
                    SectionLabel("Enveloppes")
                    if budgets.isEmpty {
                        Text("Aucune enveloppe pour l'instant. Crée-en une pour suivre une catégorie de dépenses.")
                            .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.bottom, 4)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(budgets.enumerated()), id: \.element.id) { index, env in
                                if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                                Button { envelopeSheet = .edit(env) } label: { envelopeRow(env) }
                                    .buttonStyle(.plain)
                            }
                        }
                        .cardOutline()
                    }
                    Button { envelopeSheet = .new } label: { DashedAddCard(title: "Nouvelle enveloppe") }
                        .buttonStyle(.plain)
                }

                NavigationLink { SpendingView(dataSource: vm.dataSource) } label: {
                    navRow(icon: "chart.pie.fill", title: "Dépenses par catégorie", subtitle: "Où part l'argent ce cycle")
                }.buttonStyle(.plain)

                NavigationLink { RecurringView(dataSource: vm.dataSource) } label: {
                    navRow(icon: "arrow.triangle.2.circlepath", title: "Abonnements", subtitle: "Séries détectées et échéances")
                }.buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
        .refreshable { await vm.load() }
    }

    private var header: some View {
        HStack {
            Text("Budget")
                .font(Theme.font(32, .heavy)).tracking(Theme.tracking(32))
                .foregroundStyle(Theme.foreground)
            Spacer()
            NavigationLink { BudgetSettingsView(dataSource: vm.dataSource) } label: {
                Image(systemName: "gearshape.fill").font(.system(size: 18)).foregroundStyle(Theme.mutedForeground)
            }
        }
    }

    private func cashflowCard(_ cashflow: CashflowSummary, _ budgets: [BudgetEnvelope]) -> some View {
        let overCount = budgets.filter(\.overBudget).count
        return VStack(alignment: .leading, spacing: 14) {
            if let label = cycleLabel(cashflow.from, cashflow.to) {
                Text(label.uppercased())
                    .font(Theme.font(11, .bold)).tracking(0.4).foregroundStyle(Theme.mutedForeground)
            }
            HStack(spacing: 0) {
                stat("Revenus", cashflow.income, Theme.positive)
                Divider().frame(height: 34).overlay(Theme.border)
                stat("Dépenses", cashflow.expense, Theme.foreground)
                Divider().frame(height: 34).overlay(Theme.border)
                stat("Net", cashflow.net, cashflow.net >= 0 ? Theme.positive : Theme.destructive)
            }
            Divider().overlay(Theme.border)
            HStack(spacing: 6) {
                if overCount > 0 {
                    Circle().fill(Theme.destructive).frame(width: 6, height: 6)
                    Text("\(overCount) enveloppe\(overCount > 1 ? "s" : "") dépassée\(overCount > 1 ? "s" : "")")
                        .font(Theme.font(12, .semibold)).foregroundStyle(Theme.destructive)
                } else {
                    Text("Tout est dans les clous")
                        .font(Theme.font(12, .semibold)).foregroundStyle(Theme.mutedForeground)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .picsouCard()
    }

    @ViewBuilder
    private func categorizeBanner(_ count: Int) -> some View {
        if count > 0 {
            NavigationLink { CategorizationInboxView(dataSource: vm.dataSource, onChanged: { Task { await vm.load() } }) } label: {
                HStack(spacing: 10) {
                    Image(systemName: "tray.full.fill").font(.system(size: 16)).foregroundStyle(Theme.brand)
                    Text("\(count) à catégoriser").font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.mutedForeground)
                }
                .padding(14)
                .picsouCard()
            }
            .buttonStyle(.plain)
        } else {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 16)).foregroundStyle(Theme.positive)
                Text("Tout est catégorisé").font(Theme.font(14, .medium)).foregroundStyle(Theme.mutedForeground)
            }
            .padding(14)
            .picsouCard()
        }
    }

    private func navRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 16)).foregroundStyle(Theme.brand).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Text(subtitle).font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.mutedForeground)
        }
        .padding(14)
        .picsouCard()
    }

    private func stat(_ label: String, _ amount: Decimal, _ color: Color) -> some View {
        VStack(spacing: 4) {
            Text(label.uppercased())
                .font(Theme.font(10, .bold)).tracking(0.3).foregroundStyle(Theme.mutedForeground)
            Text(Money.format(amount))
                .font(Theme.font(17, .heavy)).monospacedDigit().foregroundStyle(color)
                .minimumScaleFactor(0.7).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    private func envelopeRow(_ env: BudgetEnvelope) -> some View {
        let pct = min(1.0, max(0, env.percent.doubleValue / 100))
        return VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 10) {
                Circle().fill(Color.account(env.categoryColor ?? Theme.fallbackColorHex)).frame(width: 10, height: 10)
                Text(env.categoryName).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer(minLength: 8)
                Text("\(Money.format(env.spent)) / \(Money.format(env.monthlyLimit))")
                    .font(Theme.font(13, .semibold)).monospacedDigit()
                    .foregroundStyle(env.overBudget ? Theme.destructive : Theme.mutedForeground)
            }
            ProgressBar(value: pct, height: 6, tint: env.overBudget ? Theme.destructive : Theme.brand)
        }
        .padding(14)
        .contentShape(Rectangle())
    }

    private func cycleLabel(_ from: String?, _ to: String?) -> String? {
        guard let from, let to,
              let start = DateParsing.localDate.date(from: from),
              let end = DateParsing.localDate.date(from: to) else { return nil }
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "d MMM"
        return "Cycle \(f.string(from: start)) – \(f.string(from: end))"
    }
}
