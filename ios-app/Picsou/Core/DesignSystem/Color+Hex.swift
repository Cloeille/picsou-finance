import SwiftUI

extension Color {
    /// Parses a "#RRGGBB" (or "RRGGBB") hex string, as used by the backend's per-account colors.
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let value = UInt64(s, radix: 16) else { return nil }
        self.init(
            .sRGB,
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }

    /// Non-failable convenience with a neutral fallback for malformed colors.
    static func account(_ hex: String) -> Color {
        Color(hex: hex) ?? .gray
    }
}
