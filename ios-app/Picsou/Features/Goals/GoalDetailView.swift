import SwiftUI

/// Goal detail (from the "Objectifs & Dettes" template): a big progress ring, an on-track badge,
/// and a Reste / Par mois / Échéance stats grid, plus a short projection note.
struct GoalDetailView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    let goal: GoalProgress
    var onChanged: () -> Void = {}
    @State private var showEdit = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .firstTextBaseline) {
                    Text(goal.name)
                        .font(Theme.font(23, .heavy))
                        .tracking(Theme.tracking(23, em: -0.01))
                        .foregroundStyle(Theme.foreground)
                    Spacer()
                    if let onTrack = goal.isOnTrack {
                        OnTrackBadge(onTrack: onTrack)
                    }
                }

                GoalRing(
                    progress: (goal.percentComplete ?? 0) / 100,
                    percentLabel: Percent.format(goal.percentComplete ?? 0),
                    amounts: amountsLabel
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)

                HStack(spacing: 9) {
                    stat("Reste", goal.remaining.map { Money.format($0) } ?? "—")
                    stat("Par mois", goal.monthlyNeeded.map { Money.format($0) } ?? "—")
                    stat("Échéance", goal.deadlineLabel ?? "—")
                }

                if let monthly = goal.monthlyNeeded {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionLabel("Projection")
                        Text(projection(monthly: monthly))
                            .font(Theme.font(13))
                            .foregroundStyle(Theme.mutedForeground)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .picsouCard()
                }
            }
            .padding(16)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Modifier") { showEdit = true }.foregroundStyle(Theme.brand)
            }
        }
        .sheet(isPresented: $showEdit) {
            GoalFormView(dataSource: appState.makeGoalsDataSource(), editing: goal,
                         onSaved: { onChanged(); dismiss() },
                         onAuthExpired: { appState.signOut() })
        }
    }

    private var amountsLabel: String {
        let current = goal.currentTotal.map { Money.format($0) } ?? "—"
        let target = goal.targetAmount.map { Money.format($0) } ?? "—"
        return "\(current) / \(target)"
    }

    private func projection(monthly: Decimal) -> String {
        let months = goal.monthsLeft ?? 0
        let pace = "Au rythme actuel (~\(Money.format(monthly))/mois)"
        if goal.isOnTrack == true {
            return "\(pace), l'objectif est atteint dans les temps — il reste ~\(months) mois."
        }
        return "\(pace), il manque un peu : il reste ~\(months) mois pour y arriver."
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 5) {
            Text(label.uppercased())
                .font(Theme.font(11, .bold)).tracking(0.4)
                .foregroundStyle(Theme.mutedForeground)
            Text(value)
                .font(Theme.font(16, .heavy)).monospacedDigit()
                .foregroundStyle(Theme.foreground)
                .minimumScaleFactor(0.7).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12).padding(.horizontal, 6)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.border, lineWidth: 1))
    }
}

/// Circular progress ring with a centered percent + amounts.
struct GoalRing: View {
    let progress: Double
    let percentLabel: String
    let amounts: String

    var body: some View {
        ZStack {
            Circle().stroke(Theme.muted, lineWidth: 13)
            Circle()
                .trim(from: 0, to: max(0, min(progress, 1)))
                .stroke(Theme.brand, style: StrokeStyle(lineWidth: 13, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 3) {
                Text(percentLabel)
                    .font(Theme.font(30, .heavy)).tracking(Theme.tracking(30)).monospacedDigit()
                    .foregroundStyle(Theme.foreground)
                Text(amounts)
                    .font(Theme.font(13)).monospacedDigit()
                    .foregroundStyle(Theme.mutedForeground)
            }
        }
        .frame(width: 190, height: 190)
    }
}

/// Green "on track" / red "behind" pill.
struct OnTrackBadge: View {
    let onTrack: Bool
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(onTrack ? Theme.positive : Theme.destructive).frame(width: 7, height: 7)
            Text(onTrack ? "En bonne voie" : "En retard").font(Theme.font(12.5, .bold))
        }
        .foregroundStyle(onTrack ? Theme.positive : Theme.destructive)
        .padding(.horizontal, 11).padding(.vertical, 4)
        .background((onTrack ? Theme.positive : Theme.destructive).opacity(0.14), in: Capsule())
    }
}
