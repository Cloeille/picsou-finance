import Foundation

/// Mirrors backend `SpendingByCategoryResponse` (GET /api/spending/by-category) — the ranked
/// expense breakdown behind the Spending screen.
struct SpendingByCategory: Decodable, Equatable {
    let period: String
    let from: String?
    let to: String?
    let totalExpense: Decimal
    let categories: [CategorySpend]
}

/// One leaf category's expense total (`SpendingByCategoryResponse.CategorySpend`). `categoryId`
/// is nil for the "uncategorized" bucket. No parent/child rollup UI in v1 (YAGNI) — `parentId`
/// etc. are decoded but unused.
struct CategorySpend: Decodable, Identifiable, Equatable {
    let categoryId: Int64?
    let slug: String?
    let name: String
    let color: String?
    let icon: String?
    let amount: Decimal
    let count: Int
    let share: Decimal
    let parentId: Int64?
    let parentName: String?
    let parentColor: String?

    var id: Int64 { categoryId ?? -1 }
}

/// Mirrors backend `SpendingDetailResponse` (GET /api/spending/category/{id}) — the drill-down.
/// `children` (parent-category rollup) is intentionally not decoded — no category-tree UI in v1.
struct SpendingDetail: Decodable, Equatable {
    let categoryId: Int64?
    let slug: String?
    let name: String
    let color: String?
    let icon: String?
    let period: String
    let from: String?
    let to: String?
    let total: Decimal
    let count: Int
    let transactions: [Transaction]
}
