import Foundation

/// EUR currency formatting (fr_FR grouping, e.g. "12 345 €").
enum Money {
    static let eur0 = currencyFormatter(fractionDigits: 0)
    static let eur2 = currencyFormatter(fractionDigits: 2)

    private static func currencyFormatter(fractionDigits: Int) -> NumberFormatter {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = "EUR"
        f.locale = Locale(identifier: "fr_FR")
        f.minimumFractionDigits = fractionDigits
        f.maximumFractionDigits = fractionDigits
        return f
    }

    static func format(_ value: Decimal, fractionDigits: Int = 0) -> String {
        let formatter = fractionDigits >= 2 ? eur2 : eur0
        return formatter.string(from: NSDecimalNumber(decimal: value)) ?? "—"
    }

    /// Signed variant for deltas (leading "+" on non-negative values).
    static func formatSigned(_ value: Decimal) -> String {
        let base = format(value)
        return value >= 0 ? "+\(base)" : base
    }
}

/// Whole-number percent from a 0–100 value.
enum Percent {
    static func format(_ value: Double) -> String {
        "\(Int(value.rounded()))%"
    }
}

/// Parses the backend's `LocalDate` strings ("yyyy-MM-dd") and `Instant` strings.
enum DateParsing {
    static let localDate: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone(identifier: "UTC")
        df.dateFormat = "yyyy-MM-dd"
        return df
    }()

    /// Jackson writes an `Instant` with its fractional seconds ("2026-07-04T08:00:00.123456Z") as soon
    /// as it has any, which every Postgres timestamp does; a plain `ISO8601DateFormatter` rejects that.
    static func instant(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        return withFraction.date(from: iso) ?? withoutFraction.date(from: iso)
    }

    private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let withoutFraction = ISO8601DateFormatter()
}

/// "12 nov. 2026" from a backend `LocalDate`.
enum DueDate {
    static func label(_ localDate: String?) -> String? {
        guard let localDate, let date = DateParsing.localDate.date(from: localDate) else { return nil }
        return formatter.string(from: date)
    }

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "d MMM yyyy"
        return f
    }()
}

/// A backend enum decoded leniently: a value the server added after this build lands on `unknown`
/// instead of failing the whole payload.
protocol LenientEnum: RawRepresentable, Decodable where RawValue == String {
    static var unknown: Self { get }
}

extension LenientEnum {
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = Self(rawValue: raw) ?? .unknown
    }
}

extension Decimal {
    var doubleValue: Double { NSDecimalNumber(decimal: self).doubleValue }
}
