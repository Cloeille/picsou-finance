import Foundation

/// Mirrors backend `AiJobStatus` — snapshot of the async AI-categorization job
/// (POST /api/transactions/categorize-ai, polled via GET .../categorize-ai/status).
struct AiJobStatus: Decodable, Equatable {
    let running: Bool
    let total: Int
    let processed: Int
    let applied: Int
    let suggested: Int
    let done: Bool
    let error: String?
}

/// Mirrors backend `CashflowResponse` (GET /api/cashflow?period=CYCLE) — the cycle's income/expense/net.
struct CashflowSummary: Decodable, Equatable {
    let from: String?
    let to: String?
    let income: Decimal
    let expense: Decimal
    let net: Decimal
}

/// Mirrors backend `BudgetResponse` (GET /api/budgets) — one category envelope for the current cycle.
struct BudgetEnvelope: Decodable, Identifiable, Equatable {
    let id: Int64
    let categoryId: Int64
    let categoryName: String
    let categoryColor: String?
    let categoryKind: String?
    let monthlyLimit: Decimal
    let spent: Decimal
    let remaining: Decimal
    let percent: Decimal
    let overBudget: Bool
    let cycleStart: String?
    let cycleEnd: String?
}

/// Body for POST/PUT /api/budgets.
struct BudgetRequest: Encodable {
    let categoryId: Int64
    let monthlyLimit: Decimal
}

/// Mirrors backend `CashflowPeriod` — which span a query covers.
enum CashflowPeriod: String, Encodable {
    case cycle = "CYCLE"
    case yearToDate = "YTD"
}

/// Mirrors backend `AiCategorizationMode`. A struct rather than an enum: settings are saved
/// read-modify-write, so a mode this build doesn't know must go back to the server unchanged.
struct AiCategorizationMode: RawRepresentable, Codable, Hashable {
    let rawValue: String
    init(rawValue: String) { self.rawValue = rawValue }

    static let suggest = AiCategorizationMode(rawValue: "SUGGEST")
    static let autoHighConfidence = AiCategorizationMode(rawValue: "AUTO_HIGH_CONFIDENCE")
    static let autoAll = AiCategorizationMode(rawValue: "AUTO_ALL")
}

/// Mirrors backend `BudgetSettingsResponse` (GET /api/budget/settings). The app only exposes
/// `cycleStartDay` and `aiCategorizationEnabled` in Settings UI (YAGNI: no AI-mode/threshold/logo
/// tuning in v1) — the other fields are carried through unchanged on save (read-modify-write).
struct BudgetSettings: Codable, Equatable {
    var cycleStartDay: Int
    var logoFetchEnabled: Bool
    var aiCategorizationEnabled: Bool
    var aiMode: AiCategorizationMode
    var aiConfidenceThreshold: Int
    let currentCycleStart: String?
    let currentCycleEnd: String?
}

/// Body for PUT /api/budget/settings — every field required by the backend, even the ones this
/// app's Settings screen doesn't expose (see `BudgetSettings`'s doc comment).
struct BudgetSettingsRequest: Encodable {
    let cycleStartDay: Int
    let logoFetchEnabled: Bool
    let aiCategorizationEnabled: Bool
    let aiMode: AiCategorizationMode
    let aiConfidenceThreshold: Int

    init(_ settings: BudgetSettings) {
        cycleStartDay = settings.cycleStartDay
        logoFetchEnabled = settings.logoFetchEnabled
        aiCategorizationEnabled = settings.aiCategorizationEnabled
        aiMode = settings.aiMode
        aiConfidenceThreshold = settings.aiConfidenceThreshold
    }
}
