import SwiftUI

/// Condensed assets list (design "Variant B"): one card, a divider-separated row per account
/// (color dot · name + type badge · balance).
struct AssetsListCard: View {
    let items: [DistributionItem]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                if index > 0 {
                    Rectangle().fill(Theme.border).frame(height: 1)
                }
                NavigationLink {
                    AccountDetailView(accountId: item.accountId, accountName: item.name)
                } label: {
                    row(item)
                }
                .buttonStyle(.plain)
            }
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
                .strokeBorder(Theme.border, lineWidth: 1)
        )
    }

    private func row(_ item: DistributionItem) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.account(item.color))
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(Theme.font(15, .semibold))
                    .foregroundStyle(Theme.foreground)
                    .lineLimit(1)
                AccountTypeBadge(type: item.type)
            }
            Spacer(minLength: 8)
            Text(Money.format(item.balanceEur))
                .font(Theme.font(15, .bold))
                .monospacedDigit()
                .foregroundStyle(Theme.foreground)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
    }
}
