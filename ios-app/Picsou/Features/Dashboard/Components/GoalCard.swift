import SwiftUI

/// Goal progress inside a card: name, percent, brand progress bar, current / target.
struct GoalCard: View {
    let goal: GoalProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack {
                Text(goal.name)
                    .font(Theme.font(15, .semibold))
                    .foregroundStyle(Theme.foreground)
                Spacer()
                if let pct = goal.percentComplete {
                    Text(Percent.format(pct))
                        .font(Theme.font(14, .bold))
                        .foregroundStyle(Theme.brand)
                }
            }
            ProgressBar(value: (goal.percentComplete ?? 0) / 100)
            if let current = goal.currentTotal, let target = goal.targetAmount {
                HStack(spacing: 5) {
                    Text(Money.format(current))
                        .font(Theme.font(13, .semibold))
                        .foregroundStyle(Theme.foreground)
                    Text("/ \(Money.format(target))")
                        .font(Theme.font(13))
                        .foregroundStyle(Theme.mutedForeground)
                }
                .monospacedDigit()
            }
        }
        .picsouCard()
    }
}
