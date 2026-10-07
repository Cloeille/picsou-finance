import Foundation

enum RecurringCadence: String, LenientEnum {
    case weekly = "WEEKLY"
    case biweekly = "BIWEEKLY"
    case monthly = "MONTHLY"
    case quarterly = "QUARTERLY"
    case yearly = "YEARLY"
    case unknown = "UNKNOWN"

    var label: String {
        switch self {
        case .weekly: return "Hebdomadaire"
        case .biweekly: return "Toutes les 2 semaines"
        case .monthly: return "Mensuel"
        case .quarterly: return "Trimestriel"
        case .yearly: return "Annuel"
        case .unknown: return "Périodique"
        }
    }
}

enum RecurringStatus: String, LenientEnum {
    case suggested = "SUGGESTED"
    case confirmed = "CONFIRMED"
    case ignored = "IGNORED"
    case unknown = "UNKNOWN"
}

/// Urgency of a series' next due date, computed server-side at read time (never stored).
enum RecurringRuntimeStatus: String, LenientEnum {
    case stale = "STALE"
    case late = "LATE"
    case dueSoon = "DUE_SOON"
    case scheduled = "SCHEDULED"
    case unknown = "UNKNOWN"
}

enum RecurringActivityType: String, LenientEnum {
    case autoConfirmed = "AUTO_CONFIRMED"
    case priceChange = "PRICE_CHANGE"
    case unknown = "UNKNOWN"
}

/// Mirrors backend `RecurringSeriesResponse` (GET /api/recurring).
struct RecurringSeries: Decodable, Identifiable, Equatable {
    let id: Int64
    let label: String
    let counterparty: String?
    let expectedAmount: Decimal
    let cadence: RecurringCadence
    let status: RecurringStatus
    let nextDueDate: String?
    let lastSeenDate: String?
    let categoryId: Int64?
    let categoryName: String?
    let categoryColor: String?
    let confidence: Decimal?
    let variable: Bool
    let amountMin: Decimal?
    let amountMax: Decimal?
    let previousAmount: Decimal?
    let priceChangedAt: String?
    let autoConfirmed: Bool
    let runtimeStatus: RecurringRuntimeStatus

    var priceIncreased: Bool { previousAmount != nil && priceChangedAt != nil }
}

/// Mirrors backend `RecurringActivityResponse` (GET /api/recurring/activity) — the "what changed"
/// feed. No `id` field on the wire; synthesized from series id + occurrence date (unique per feed).
struct RecurringActivity: Decodable, Identifiable, Equatable {
    let seriesId: Int64
    let label: String
    let type: RecurringActivityType
    let occurredOn: String
    let expectedAmount: Decimal
    let previousAmount: Decimal?
    let cadence: RecurringCadence
    let categoryId: Int64?
    let categoryName: String?
    let categoryColor: String?

    var id: String { "\(seriesId)-\(type.rawValue)-\(occurredOn)" }
}

/// Mirrors backend `RecurringOccurrenceResponse` (GET /api/recurring/calendar) — one projected
/// charge. No `id` field on the wire; synthesized from series id + due date. A credit card's
/// statement payment is listed too, with `creditCardPayment: true` and `seriesId` = minus the card's
/// account id (there is no series behind it).
struct RecurringOccurrence: Decodable, Identifiable, Equatable {
    let seriesId: Int64
    let label: String
    let counterparty: String?
    let expectedAmount: Decimal
    let dueDate: String
    let categoryId: Int64?
    let categoryName: String?
    let categoryColor: String?
    var rewardPoints: Int64? = nil
    /// Optional on the wire: servers before 1.1.0 don't send it.
    var creditCardPayment: Bool? = nil

    var isCreditCardPayment: Bool { creditCardPayment == true }

    var id: String { "\(seriesId)-\(dueDate)" }
    var day: Date? { DateParsing.localDate.date(from: dueDate) }
}
