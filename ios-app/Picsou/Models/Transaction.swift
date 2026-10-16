import Foundation

/// Mirrors backend `TransactionResponse`. `amount` is signed (negative = expense). The Java
/// `isManual` record field serializes as the JSON key `isManual` (see `Account.manual`'s doc
/// comment for why) -- verified live against a running backend.
struct Transaction: Decodable, Identifiable, Equatable {
    let id: Int64
    let date: String
    let description: String
    let amount: Decimal
    let nativeCurrency: String?
    let manual: Bool
    let txType: String?
    let categoryId: Int64?
    let categoryName: String?
    let merchantLabel: String?
    let merchantBrandId: Int64?
    let counterparty: String?
    let accountId: Int64?
    let accountName: String?
    /// Populated only while the transaction is uncategorized and the AI job produced a
    /// suggestion instead of auto-applying it (see `AiJobStatus`/`AiCategorizationMode`).
    let aiSuggestedCategoryId: Int64?
    /// 0-100. Nil unless `aiSuggestedCategoryId` is set.
    let aiConfidence: Int?

    enum CodingKeys: String, CodingKey {
        case id, date, description, amount, nativeCurrency
        case manual = "isManual"
        case txType, categoryId, categoryName, merchantLabel, merchantBrandId, counterparty
        case accountId, accountName, aiSuggestedCategoryId, aiConfidence
    }

    var displayLabel: String { merchantLabel ?? description }
    var isExpense: Bool { amount < 0 }
    var day: Date? { DateParsing.localDate.date(from: date) }
    var isCategorized: Bool { categoryId != nil }

    /// Same instance with its category reassigned (optimistic local update after a PUT).
    func categorized(as categoryId: Int64, name: String) -> Transaction {
        Transaction(id: id, date: date, description: description, amount: amount,
                    nativeCurrency: nativeCurrency, manual: manual, txType: txType,
                    categoryId: categoryId, categoryName: name, merchantLabel: merchantLabel,
                    merchantBrandId: merchantBrandId, counterparty: counterparty,
                    accountId: accountId, accountName: accountName,
                    aiSuggestedCategoryId: nil, aiConfidence: nil)
    }
}

/// Body for POST /api/accounts/{id}/transactions (manual cash tx). `amount` sign encodes direction.
struct TransactionRequest: Encodable {
    let date: String
    let description: String
    let amount: Decimal
    var txType: String?
    var currency: String?
    var categoryId: Int64?
}

/// Mirrors `LoanScheduleResponse` (GET /api/accounts/{id}/loan-summary).
struct LoanSchedule: Decodable, Equatable {
    let summary: LoanSummary
    let schedule: [LoanInstallment]
}

struct LoanSummary: Decodable, Equatable {
    let monthlyPayment: Decimal?
    let remainingBalance: Decimal?
    let capitalRepaidPct: Decimal?
    let endDate: String?
    let paidInstallments: Int?
    let totalInstallments: Int?
}

struct LoanInstallment: Decodable, Identifiable, Equatable {
    let number: Int
    let date: String
    let capital: Decimal
    let interest: Decimal
    let totalPayment: Decimal
    let remainingBalance: Decimal

    var id: Int { number }
    var day: Date? { DateParsing.localDate.date(from: date) }
}
