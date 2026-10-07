import Foundation

/// Mirrors backend `CategoryKind` — drives cashflow/envelope inclusion. `TRANSFER` categories
/// exist but are never offered in the categorization picker (see `Category.pickable`).
enum CategoryKind: String, LenientEnum {
    case income = "INCOME"
    case expense = "EXPENSE"
    case transfer = "TRANSFER"
    case unknown = "UNKNOWN"
}

/// Mirrors backend `CategoryResponse` (GET /api/categories). Read-only in the app — no
/// category-tree management (create/rename/archive stays on the web), only picking one.
struct Category: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let kind: CategoryKind
    let color: String
    let icon: String?
    let isDefault: Bool
    let archived: Bool
    let sortOrder: Int
    let parentId: Int64?

    /// Offered in the categorization picker: not archived, not a transfer bucket.
    var pickable: Bool { !archived && (kind == .income || kind == .expense) }
}
