import SwiftUI

/// Objectifs tab: a segmented Objectifs ⇄ Dettes view over the dashboard payload
/// (goals from `goalSummaries`, debts from `liabilities`).
struct GoalsView: View {
    @Environment(AppState.self) private var appState
    @State private var vm: DashboardViewModel?

    var body: some View {
        Group {
            if let vm {
                GoalsDebtsContent(vm: vm)
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

private enum GoalsSegment: Hashable { case goals, debts }

private struct GoalsDebtsContent: View {
    let vm: DashboardViewModel
    @Environment(AppState.self) private var appState
    @State private var segment: GoalsSegment = .goals
    @State private var showGoalForm = false

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
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Picker("", selection: $segment) {
                        Text("Objectifs").tag(GoalsSegment.goals)
                        Text("Dettes").tag(GoalsSegment.debts)
                    }
                    .pickerStyle(.segmented)

                    if segment == .goals {
                        goalsSection(data.goalSummaries)
                    } else {
                        debtsSection(data)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
                .padding(.bottom, 24)
            }
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showGoalForm) {
                GoalFormView(dataSource: appState.makeGoalsDataSource(),
                             onSaved: { Task { await vm.load() } },
                             onAuthExpired: { appState.signOut() })
            }
        }
    }

    // MARK: Goals

    @ViewBuilder
    private func goalsSection(_ goals: [GoalProgress]) -> some View {
        let totalSaved = goals.reduce(Decimal(0)) { $0 + ($1.currentTotal ?? 0) }
        Text("\(goals.count) objectif\(goals.count > 1 ? "s" : "") · \(Money.format(totalSaved)) épargnés")
            .font(Theme.font(14))
            .foregroundStyle(Theme.mutedForeground)
        ForEach(goals) { goal in
            NavigationLink {
                GoalDetailView(goal: goal, onChanged: { Task { await vm.load() } })
            } label: { GoalCard(goal: goal) }
                .buttonStyle(.plain)
        }
        Button { showGoalForm = true } label: { DashedAddCard(title: "Nouvel objectif") }
            .buttonStyle(.plain)
    }

    // MARK: Debts

    @ViewBuilder
    private func debtsSection(_ data: DashboardResponse) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Passif total".uppercased())
                .font(Theme.font(12, .semibold)).tracking(0.5)
                .foregroundStyle(.white.opacity(0.72))
            Text(Money.format(data.totalLiabilities))
                .font(Theme.font(38, .heavy)).tracking(Theme.tracking(38, em: -0.03)).monospacedDigit()
                .foregroundStyle(.white).padding(.top, 8)
            if let monthly = data.totalMonthlyPayment {
                Text("\(Money.format(monthly))/mois au total")
                    .font(Theme.font(13)).foregroundStyle(.white.opacity(0.8)).padding(.top, 12)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(Color(red: 0.11, green: 0.11, blue: 0.13),
                    in: RoundedRectangle(cornerRadius: Theme.Radius.hero, style: .continuous))
        .shadow(color: .black.opacity(0.28), radius: 20, x: 0, y: 14)

        if data.liabilities.isEmpty {
            Text("Aucune dette 🎉")
                .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                .frame(maxWidth: .infinity).padding(.vertical, 24)
        } else {
            HStack {
                SectionLabel("Dettes")
                Spacer()
                Text("\(data.liabilities.count)")
                    .font(Theme.font(13, .semibold)).foregroundStyle(Theme.mutedForeground)
            }
            .padding(.top, 4)
            VStack(spacing: 0) {
                ForEach(Array(data.liabilities.enumerated()), id: \.element.id) { index, loan in
                    if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                    NavigationLink {
                        if loan.type == .loan {
                            LoanScheduleView(accountId: loan.accountId, accountName: loan.name)
                        } else {
                            AccountDetailView(accountId: loan.accountId, accountName: loan.name)
                        }
                    } label: { loanRow(loan) }
                    .buttonStyle(.plain)
                }
            }
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(Theme.border, lineWidth: 1))
        }
    }

    private func loanRow(_ loan: LiabilityEntry) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 10) {
                Circle().fill(Color.account(loan.color)).frame(width: 10, height: 10)
                Text(loan.name).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Spacer(minLength: 8)
                Text(Money.format(loan.balanceEur))
                    .font(Theme.font(15, .bold)).monospacedDigit().foregroundStyle(Theme.foreground)
            }
            if let monthly = loan.monthlyPayment {
                Text("\(Money.format(monthly))/mois")
                    .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
                    .padding(.leading, 20)
            }
            if let due = loan.paymentDueAmountEur {
                Text(["\(Money.format(due, fractionDigits: 2)) à régler", DueDate.label(loan.paymentDueDate).map { "le \($0)" }]
                        .compactMap { $0 }.joined(separator: " "))
                    .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
                    .padding(.leading, 20)
            }
            if let paid = loan.percentPaid {
                HStack(spacing: 10) {
                    ProgressBar(value: paid / 100, height: 6)
                    Text("\(Int(paid))% payé")
                        .font(Theme.font(11.5, .bold)).monospacedDigit()
                        .foregroundStyle(Theme.mutedForeground)
                }
                .padding(.leading, 20)
            }
        }
        .padding(14)
    }
}

/// Dashed-border "add" affordance (new goal, etc.).
struct DashedAddCard: View {
    let title: String
    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "plus").font(.system(size: 15, weight: .semibold))
            Text(title).font(Theme.font(14.5, .semibold))
        }
        .foregroundStyle(Theme.mutedForeground)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .overlay(
            RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                .strokeBorder(Theme.border, style: StrokeStyle(lineWidth: 1.5, dash: [6]))
        )
    }
}
