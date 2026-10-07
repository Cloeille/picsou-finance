import SwiftUI

/// The Picsou design-system tokens, ported 1:1 from the web design system (`styles.css` / the
/// "Picsou Design System" Claude Design project). Colors are the exact OKLCH values, resolved
/// per light/dark appearance. Type uses SF Pro (the native iOS analogue of the design's Geist),
/// kept behind one `Theme.font(...)` so it can be swapped for bundled Geist later.
enum Theme {

    // MARK: Semantic colors (light / dark)

    static let background      = Color(light: Color(oklch: 1, 0, 0),                dark: Color(oklch: 0.145, 0, 0))
    static let foreground      = Color(light: Color(oklch: 0.145, 0, 0),           dark: Color(oklch: 0.985, 0, 0))
    static let card            = Color(light: Color(oklch: 1, 0, 0),               dark: Color(oklch: 0.205, 0, 0))
    static let cardForeground  = Color(light: Color(oklch: 0.145, 0, 0),           dark: Color(oklch: 0.985, 0, 0))
    static let primary         = Color(light: Color(oklch: 0.488, 0.243, 264.376), dark: Color(oklch: 0.424, 0.199, 265.638))
    static let primaryForeground = Color(oklch: 0.97, 0.014, 254.604)
    static let secondary       = Color(light: Color(oklch: 0.967, 0.001, 286.375), dark: Color(oklch: 0.274, 0.006, 286.033))
    static let secondaryForeground = Color(light: Color(oklch: 0.21, 0.006, 285.885), dark: Color(oklch: 0.985, 0, 0))
    static let muted           = Color(light: Color(oklch: 0.97, 0, 0),            dark: Color(oklch: 0.269, 0, 0))
    static let mutedForeground = Color(light: Color(oklch: 0.45, 0, 0),            dark: Color(oklch: 0.708, 0, 0))
    static let destructive     = Color(light: Color(oklch: 0.577, 0.245, 27.325),  dark: Color(oklch: 0.704, 0.191, 22.216))
    static let border          = Color(light: Color(oklch: 0.922, 0, 0),           dark: Color.white.opacity(0.10))
    static let input           = Color(light: Color(oklch: 0.922, 0, 0),           dark: Color.white.opacity(0.15))
    static let ring            = Color(light: Color(oklch: 0.708, 0, 0),           dark: Color(oklch: 0.556, 0, 0))

    /// Chart ramp (emerald), light == dark. chart1 lightest → chart5 darkest.
    static let chart1 = Color(oklch: 0.845, 0.143, 164.978)
    static let chart2 = Color(oklch: 0.696, 0.17, 162.48)
    static let chart3 = Color(oklch: 0.596, 0.145, 163.225)
    static let chart4 = Color(oklch: 0.508, 0.118, 165.612)
    static let chart5 = Color(oklch: 0.432, 0.095, 166.913)

    /// Brand accent used across the app for links, the hero card and the active tab (#2563eb, blue-600).
    static let brand = Color(oklch: 0.546, 0.245, 262.881)
    /// Positive delta (emerald-600 #059669) and its translucent chip surface.
    static let positive = Color(oklch: 0.596, 0.145, 163.225)
    static let positiveSurface = Color(oklch: 0.696, 0.17, 162.48, opacity: 0.14)

    /// Fallback hex for a category/account dot when the server didn't send a color (indigo-500,
    /// matches the web app's own fallback). Pass to `Color.account(_:)` — kept as a hex string,
    /// not a `Color`, since every call site already does `Color.account(x ?? Theme.fallbackHex)`.
    static let fallbackColorHex = "#6366f1"

    // MARK: Radii (from --radius = 10px, matched to the templates)

    enum Radius {
        static let field: CGFloat = 16
        static let control: CGFloat = 15
        static let card: CGFloat = 16
        static let hero: CGFloat = 22
        static let sm: CGFloat = 10
        static let badge: CGFloat = 6
    }

    // MARK: Typography

    /// SF Pro (system). Central seam for a future swap to bundled Geist.
    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight)
    }

    /// Tight tracking for large headings (approx the design's -0.02em / -0.03em letter-spacing).
    static func tracking(_ size: CGFloat, em: CGFloat = -0.02) -> CGFloat {
        size * em
    }
}
