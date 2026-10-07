import SwiftUI
import UIKit

extension Color {
    /// Build a Color from OKLCH (L in 0…1, C chroma, H hue degrees) — the color space the Picsou
    /// design tokens are authored in (CSS `oklch()`). Converts OKLCH → OKLab → linear sRGB → sRGB
    /// with gamut clamping, so values match the web design system exactly.
    init(oklch l: Double, _ c: Double, _ h: Double, opacity: Double = 1) {
        let hr = h * .pi / 180
        let a = c * cos(hr)
        let b = c * sin(hr)

        let l_ = l + 0.3963377774 * a + 0.2158037573 * b
        let m_ = l - 0.1055613458 * a - 0.0638541728 * b
        let s_ = l - 0.0894841775 * a - 1.2914855480 * b
        let lc = l_ * l_ * l_, mc = m_ * m_ * m_, sc = s_ * s_ * s_

        let r = 4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc
        let g = -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc
        let bl = -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc

        func toSRGB(_ x: Double) -> Double {
            let v = min(max(x, 0), 1)
            return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1 / 2.4) - 0.055
        }
        self.init(.sRGB, red: toSRGB(r), green: toSRGB(g), blue: toSRGB(bl), opacity: opacity)
    }

    /// A dynamic color that resolves to `light` or `dark` based on the current appearance.
    init(light: Color, dark: Color) {
        self.init(uiColor: UIColor { traits in
            UIColor(traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}
