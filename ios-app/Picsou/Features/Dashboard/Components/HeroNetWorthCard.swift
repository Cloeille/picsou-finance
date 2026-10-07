import SwiftUI

/// The featured net-worth card (design "Variant B"): brand-blue surface, white value, delta chip,
/// and a corner sparkline drawn from the net-worth history.
struct HeroNetWorthCard: View {
    let netWorth: Decimal
    let delta: DeltaValue?
    let spark: [Double]

    struct DeltaValue {
        let amount: String
        let pct: String
        let positive: Bool
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if spark.count >= 2 {
                Sparkline(values: spark)
                    .frame(width: 132, height: 54)
                    .padding(.trailing, 6)
                    .padding(.bottom, 4)
                    .allowsHitTesting(false)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text("Valeur nette".uppercased())
                    .font(Theme.font(12, .semibold))
                    .tracking(0.5)
                    .foregroundStyle(.white.opacity(0.82))
                Text(Money.format(netWorth))
                    .font(Theme.font(38, .heavy))
                    .tracking(Theme.tracking(38, em: -0.03))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .padding(.top, 8)
                HStack(spacing: 9) {
                    if let delta {
                        DeltaPill(amount: delta.amount, positive: delta.positive, onColor: true)
                    }
                    Text("ce mois-ci")
                        .font(Theme.font(13))
                        .foregroundStyle(.white.opacity(0.85))
                }
                .padding(.top, 15)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(20)
        .background(Theme.brand, in: RoundedRectangle(cornerRadius: Theme.Radius.hero, style: .continuous))
        .shadow(color: .black.opacity(0.28), radius: 20, x: 0, y: 14)
    }
}

/// Lightweight area+line sparkline in white (mirrors the design's corner SVG).
struct Sparkline: View {
    let values: [Double]

    var body: some View {
        GeometryReader { geo in
            let pts = points(in: geo.size)
            ZStack {
                areaPath(pts, height: geo.size.height)
                    .fill(LinearGradient(
                        colors: [.white.opacity(0.34), .white.opacity(0)],
                        startPoint: .top, endPoint: .bottom))
                linePath(pts)
                    .stroke(.white.opacity(0.92),
                            style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
        }
    }

    private func points(in size: CGSize) -> [CGPoint] {
        guard values.count >= 2 else { return [] }
        let minV = values.min() ?? 0
        let maxV = values.max() ?? 1
        let span = max(maxV - minV, 0.0001)
        let stepX = size.width / CGFloat(values.count - 1)
        let topPad: CGFloat = 4, botPad: CGFloat = 4
        let usable = size.height - topPad - botPad
        return values.enumerated().map { i, v in
            let x = CGFloat(i) * stepX
            let y = topPad + usable * (1 - CGFloat((v - minV) / span))
            return CGPoint(x: x, y: y)
        }
    }

    private func linePath(_ pts: [CGPoint]) -> Path {
        var path = Path()
        guard let first = pts.first else { return path }
        path.move(to: first)
        pts.dropFirst().forEach { path.addLine(to: $0) }
        return path
    }

    private func areaPath(_ pts: [CGPoint], height: CGFloat) -> Path {
        var path = linePath(pts)
        guard let last = pts.last, let first = pts.first else { return path }
        path.addLine(to: CGPoint(x: last.x, y: height))
        path.addLine(to: CGPoint(x: first.x, y: height))
        path.closeSubpath()
        return path
    }
}
