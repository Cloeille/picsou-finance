import Foundation

/// Mirrors backend `AccountResponse` (GET /api/accounts/{id}). Money as `Decimal`. The Java
/// `isManual` record field serializes as the JSON key `isManual` -- Jackson does NOT strip the
/// `is` prefix from a record component's own name (only from classic `getX()`/`isX()` accessor
/// *method* names on a plain bean), so this is the literal key on the wire. Verified live against
/// a running backend (POST/GET /api/accounts all return `"isManual"`, never `"manual"`) after this
/// exact drift broke every real account decode -- the old `manual` mapping only ever "worked"
/// because `PicsouTests` mocked its own (wrong) assumption instead of the real backend shape.
struct Account: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let accountType: String
    let provider: String?
    let currency: String?
    let currentBalance: Decimal?
    let currentBalanceEur: Decimal
    let lastSyncedAt: String?
    let manual: Bool
    let color: String?
    let ticker: String?
    let parentAccountId: Int64?
    let debt: DebtInfo?
    let hidden: Bool
    /// Credit cards only (`CREDIT_CARD`, synced from the card issuer); omitted everywhere else.
    var paymentDueAmount: Decimal? = nil
    var paymentDueDate: String? = nil
    var rewardPoints: Int64? = nil

    enum CodingKeys: String, CodingKey {
        case id, name
        case accountType = "type"
        case provider, currency, currentBalance, currentBalanceEur, lastSyncedAt
        case manual = "isManual"
        case color, ticker, parentAccountId, debt, hidden
        case paymentDueAmount, paymentDueDate, rewardPoints
    }

    var type: AccountType { AccountType(raw: accountType) }
    var lastSyncedDate: Date? { DateParsing.instant(lastSyncedAt) }
}

struct DebtInfo: Decodable, Equatable {
    let borrowedAmount: Decimal?
    let interestRate: Decimal?
    let monthlyPayment: Decimal?
    let lenderName: String?
    let startDate: String?
    let endDate: String?
}

/// Mirrors backend `HoldingResponse` (GET /api/accounts/{id}/holdings).
struct Holding: Decodable, Identifiable, Equatable {
    let ticker: String
    let name: String?
    let quantity: Decimal?
    let currentValueEur: Decimal?
    let pnlEur: Decimal?
    let pnlPercent: Decimal?
    let priceUpdatedAt: String?

    var id: String { ticker }
}
