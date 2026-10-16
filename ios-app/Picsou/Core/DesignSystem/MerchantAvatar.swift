import SwiftUI

/// Rounded initials tile for a transaction/merchant, colored deterministically from the label.
struct MerchantAvatar: View {
    let label: String
    var size: CGFloat = 40

    private static let palette: [Color] = [
        Color(hex: "#2563EB") ?? .blue,
        Color(hex: "#10B981") ?? .green,
        Color(hex: "#8B5CF6") ?? .purple,
        Color(hex: "#F59E0B") ?? .orange,
        Color(hex: "#EF4444") ?? .red,
        Color(hex: "#0EA5E9") ?? .teal,
    ]

    private var initials: String {
        let parts = label.split(separator: " ")
        let first = parts.first?.first.map(String.init) ?? "?"
        let second = parts.dropFirst().first?.first.map(String.init) ?? ""
        return (first + second).uppercased()
    }

    private var color: Color {
        let sum = label.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        return Self.palette[sum % Self.palette.count]
    }

    var body: some View {
        Text(initials)
            .font(Theme.font(size * 0.34, .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color.opacity(0.9), in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
    }
}
