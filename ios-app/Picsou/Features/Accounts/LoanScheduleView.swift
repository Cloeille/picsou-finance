import SwiftUI

/// Full loan amortization schedule (GET /api/accounts/{id}/loan-summary): a summary header plus the
/// per-month installment table (capital / interest / remaining balance).
struct LoanScheduleView: View {
    @Environment(AppState.self) private var appState
    let accountId: Int64
    let accountName: String
    @State private var schedule: LoanSchedule?
    @State private var failed = false

    var body: some View {
        Group {
            if let schedule {
                content(schedule)
            } else if failed {
                Text("Impossible de charger l'échéancier.")
                    .font(Theme.font(15)).foregroundStyle(Theme.mutedForeground).padding(32)
            } else {
                ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Échéancier")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do { schedule = try await appState.makeAccountsDataSource().loanSummary(id: accountId) }
            catch { failed = true }
        }
    }

    private func content(_ schedule: LoanSchedule) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                summaryCard(schedule.summary)
                VStack(spacing: 0) {
                    tableRow("MOIS", "CAPITAL", "INTÉRÊT", "RESTANT", header: true)
                    ForEach(schedule.schedule) { row in
                        Rectangle().fill(Theme.border).frame(height: 1)
                        tableRow(monthLabel(row.day), Money.format(row.capital),
                                 Money.format(row.interest), Money.format(row.remainingBalance))
                    }
                }
                .cardOutline()
            }
            .padding(16)
        }
    }

    private func summaryCard(_ summary: LoanSummary) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(accountName).font(Theme.font(17, .bold)).foregroundStyle(Theme.foreground)
            HStack {
                stat("Mensualité", summary.monthlyPayment.map { Money.format($0) } ?? "—")
                Spacer()
                stat("Capital restant", summary.remainingBalance.map { Money.format($0) } ?? "—")
            }
            if let pct = summary.capitalRepaidPct {
                ProgressBar(value: pct.doubleValue / 100)
                Text("\(Int(pct.doubleValue)) % remboursé · \(summary.paidInstallments ?? 0)/\(summary.totalInstallments ?? 0) échéances")
                    .font(Theme.font(12)).foregroundStyle(Theme.mutedForeground)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16).cardOutline()
    }

    private func tableRow(_ month: String, _ capital: String, _ interest: String, _ remaining: String, header: Bool = false) -> some View {
        HStack(spacing: 6) {
            Text(month).frame(maxWidth: .infinity, alignment: .leading)
            Text(capital).frame(width: 72, alignment: .trailing)
            Text(interest).frame(width: 62, alignment: .trailing).foregroundStyle(header ? Theme.mutedForeground : Theme.mutedForeground)
            Text(remaining).frame(width: 78, alignment: .trailing)
        }
        .font(header ? Theme.font(10, .bold) : Theme.font(12))
        .tracking(header ? 0.3 : 0)
        .monospacedDigit()
        .foregroundStyle(header ? Theme.mutedForeground : Theme.foreground)
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label.uppercased()).font(Theme.font(10, .bold)).tracking(0.3).foregroundStyle(Theme.mutedForeground)
            Text(value).font(Theme.font(16, .heavy)).monospacedDigit().foregroundStyle(Theme.foreground)
        }
    }

    private func monthLabel(_ date: Date?) -> String {
        guard let date else { return "" }
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "MMM yyyy"
        return f.string(from: date)
    }
}
