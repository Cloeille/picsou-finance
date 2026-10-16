import Foundation

/// Body for PUT /api/transactions/{id}/category. No rules-engine UI in this app (YAGNI) — always
/// `createRule: false`, no pattern/matchType/bulk-apply.
private struct CategorizeRequest: Encodable {
    let categoryId: Int64
    let createRule = false
}

/// Budget-tab data: cycle cashflow, envelopes, categorization (inbox + AI job), categories,
/// spending breakdown, recurring subscriptions, and budget settings. Live via the API, canned in
/// the demo build. One data source backs the whole Budget hub and everything it pushes to.
protocol BudgetDataSource: Sendable {
    func cashflow() async throws -> CashflowSummary
    func budgets() async throws -> [BudgetEnvelope]
    func createEnvelope(_ request: BudgetRequest) async throws -> BudgetEnvelope
    func updateEnvelope(id: Int64, _ request: BudgetRequest) async throws -> BudgetEnvelope
    func deleteEnvelope(id: Int64) async throws

    func categories() async throws -> [Category]

    func uncategorizedTransactions() async throws -> [Transaction]
    func categorize(transactionId: Int64, categoryId: Int64) async throws
    func startAiCategorization() async throws -> AiJobStatus
    func aiCategorizationStatus() async throws -> AiJobStatus

    func spendingByCategory(period: CashflowPeriod) async throws -> SpendingByCategory
    func spendingDetail(categoryId: Int64, period: CashflowPeriod) async throws -> SpendingDetail

    func recurringSeries() async throws -> [RecurringSeries]
    func recurringActivity() async throws -> [RecurringActivity]
    func recurringCalendar(horizonDays: Int) async throws -> [RecurringOccurrence]
    func confirmRecurring(id: Int64) async throws -> RecurringSeries
    func ignoreRecurring(id: Int64) async throws -> RecurringSeries
    func undoRecurring(id: Int64) async throws -> RecurringSeries

    func budgetSettings() async throws -> BudgetSettings
    func updateBudgetSettings(_ settings: BudgetSettings) async throws -> BudgetSettings
}

struct LiveBudgetDataSource: BudgetDataSource {
    let api: APIClient

    func cashflow() async throws -> CashflowSummary {
        try await api.get("api/cashflow", query: [URLQueryItem(name: "period", value: "CYCLE")])
    }
    func budgets() async throws -> [BudgetEnvelope] { try await api.get("api/budgets") }
    func createEnvelope(_ request: BudgetRequest) async throws -> BudgetEnvelope {
        try await api.post("api/budgets", body: request)
    }
    func updateEnvelope(id: Int64, _ request: BudgetRequest) async throws -> BudgetEnvelope {
        try await api.put("api/budgets/\(id)", body: request)
    }
    func deleteEnvelope(id: Int64) async throws { _ = try await api.delete("api/budgets/\(id)") }

    func categories() async throws -> [Category] { try await api.get("api/categories") }

    func uncategorizedTransactions() async throws -> [Transaction] {
        try await api.get("api/transactions/uncategorized")
    }
    func categorize(transactionId: Int64, categoryId: Int64) async throws {
        try await api.putVoid("api/transactions/\(transactionId)/category",
                              body: CategorizeRequest(categoryId: categoryId))
    }
    func startAiCategorization() async throws -> AiJobStatus {
        try await api.post("api/transactions/categorize-ai")
    }
    func aiCategorizationStatus() async throws -> AiJobStatus {
        try await api.get("api/transactions/categorize-ai/status")
    }

    func spendingByCategory(period: CashflowPeriod) async throws -> SpendingByCategory {
        try await api.get("api/spending/by-category", query: [URLQueryItem(name: "period", value: period.rawValue)])
    }
    func spendingDetail(categoryId: Int64, period: CashflowPeriod) async throws -> SpendingDetail {
        try await api.get("api/spending/category/\(categoryId)",
                          query: [URLQueryItem(name: "period", value: period.rawValue)])
    }

    func recurringSeries() async throws -> [RecurringSeries] { try await api.get("api/recurring") }
    func recurringActivity() async throws -> [RecurringActivity] { try await api.get("api/recurring/activity") }
    func recurringCalendar(horizonDays: Int) async throws -> [RecurringOccurrence] {
        try await api.get("api/recurring/calendar", query: [URLQueryItem(name: "horizonDays", value: String(horizonDays))])
    }
    func confirmRecurring(id: Int64) async throws -> RecurringSeries { try await api.post("api/recurring/\(id)/confirm") }
    func ignoreRecurring(id: Int64) async throws -> RecurringSeries { try await api.post("api/recurring/\(id)/ignore") }
    func undoRecurring(id: Int64) async throws -> RecurringSeries { try await api.post("api/recurring/\(id)/undo") }

    func budgetSettings() async throws -> BudgetSettings { try await api.get("api/budget/settings") }
    func updateBudgetSettings(_ settings: BudgetSettings) async throws -> BudgetSettings {
        try await api.put("api/budget/settings", body: BudgetSettingsRequest(settings))
    }
}

/// In-memory demo backing store so create/edit/delete/categorize actions in a Demo-scheme run
/// feel real across a session (not just canned reads) without a server. Owned by `AppState` (one
/// instance per app launch, shared by every `DemoBudgetDataSource` the app constructs) so state
/// stays consistent across screens — NOT a global singleton, so tests get an isolated store by
/// simply not passing one in (each `DemoBudgetDataSource()` default-constructs its own).
@MainActor
final class DemoBudgetStore {
    lazy var envelopes = DemoData.budgetEnvelopes()
    lazy var uncategorized = DemoData.transactions(id: 3).filter { !$0.isCategorized }
    lazy var recurring = DemoData.recurringSeries()
    lazy var settings = DemoData.budgetSettings()
    var nextEnvelopeId: Int64 = 100

    init() {}
}

struct DemoBudgetDataSource: BudgetDataSource {
    let store: DemoBudgetStore
    init(store: DemoBudgetStore) { self.store = store }

    func cashflow() async throws -> CashflowSummary {
        try? await Task.sleep(nanoseconds: 250_000_000)
        return DemoData.cashflow()
    }
    @MainActor func budgets() async throws -> [BudgetEnvelope] { store.envelopes }
    @MainActor func createEnvelope(_ request: BudgetRequest) async throws -> BudgetEnvelope {
        let c = DemoData.categories().first { $0.id == request.categoryId }
        let env = BudgetEnvelope(id: store.nextEnvelopeId, categoryId: request.categoryId,
                                 categoryName: c?.name ?? "Catégorie", categoryColor: c?.color,
                                 categoryKind: c?.kind.rawValue, monthlyLimit: request.monthlyLimit,
                                 spent: 0, remaining: request.monthlyLimit, percent: 0, overBudget: false,
                                 cycleStart: "2026-07-01", cycleEnd: "2026-07-31")
        store.nextEnvelopeId += 1
        store.envelopes.append(env)
        return env
    }
    @MainActor func updateEnvelope(id: Int64, _ request: BudgetRequest) async throws -> BudgetEnvelope {
        guard let index = store.envelopes.firstIndex(where: { $0.id == id }) else { throw APIError.http(status: 404, body: nil) }
        let old = store.envelopes[index]
        let c = DemoData.categories().first { $0.id == request.categoryId }
        let updated = BudgetEnvelope(id: id, categoryId: request.categoryId, categoryName: c?.name ?? old.categoryName,
                                     categoryColor: c?.color ?? old.categoryColor, categoryKind: c?.kind.rawValue,
                                     monthlyLimit: request.monthlyLimit, spent: old.spent,
                                     remaining: request.monthlyLimit - old.spent,
                                     percent: request.monthlyLimit > 0 ? (old.spent as NSDecimalNumber).dividing(by: request.monthlyLimit as NSDecimalNumber).multiplying(by: 100) as Decimal : 0,
                                     overBudget: old.spent > request.monthlyLimit,
                                     cycleStart: old.cycleStart, cycleEnd: old.cycleEnd)
        store.envelopes[index] = updated
        return updated
    }
    @MainActor func deleteEnvelope(id: Int64) async throws {
        store.envelopes.removeAll { $0.id == id }
    }

    func categories() async throws -> [Category] { DemoData.categories() }

    @MainActor func uncategorizedTransactions() async throws -> [Transaction] { store.uncategorized }
    @MainActor func categorize(transactionId: Int64, categoryId: Int64) async throws {
        store.uncategorized.removeAll { $0.id == transactionId }
    }
    @MainActor func startAiCategorization() async throws -> AiJobStatus {
        let total = store.uncategorized.count
        return AiJobStatus(running: true, total: total, processed: 0, applied: 0, suggested: 0, done: false, error: nil)
    }
    @MainActor func aiCategorizationStatus() async throws -> AiJobStatus {
        // Demo: the job "completes" instantly with the pre-seeded suggestions already baked into
        // DemoData.transactions(id:) — nothing left to simulate progressing.
        let total = store.uncategorized.count
        return AiJobStatus(running: false, total: total, processed: total, applied: 0,
                           suggested: store.uncategorized.filter { $0.aiSuggestedCategoryId != nil }.count,
                           done: true, error: nil)
    }

    func spendingByCategory(period: CashflowPeriod) async throws -> SpendingByCategory { DemoData.spendingByCategory() }
    func spendingDetail(categoryId: Int64, period: CashflowPeriod) async throws -> SpendingDetail {
        DemoData.spendingDetail(categoryId: categoryId, period: period)
    }

    @MainActor func recurringSeries() async throws -> [RecurringSeries] { store.recurring }
    func recurringActivity() async throws -> [RecurringActivity] { DemoData.recurringActivity() }
    func recurringCalendar(horizonDays: Int) async throws -> [RecurringOccurrence] { DemoData.recurringCalendar() }
    @MainActor func confirmRecurring(id: Int64) async throws -> RecurringSeries { try setRecurringStatus(id: id, .confirmed) }
    @MainActor func ignoreRecurring(id: Int64) async throws -> RecurringSeries { try setRecurringStatus(id: id, .ignored) }
    @MainActor func undoRecurring(id: Int64) async throws -> RecurringSeries { try setRecurringStatus(id: id, .suggested) }

    @MainActor private func setRecurringStatus(id: Int64, _ status: RecurringStatus) throws -> RecurringSeries {
        guard let index = store.recurring.firstIndex(where: { $0.id == id }) else { throw APIError.http(status: 404, body: nil) }
        let old = store.recurring[index]
        let updated = RecurringSeries(id: old.id, label: old.label, counterparty: old.counterparty,
                                      expectedAmount: old.expectedAmount, cadence: old.cadence, status: status,
                                      nextDueDate: old.nextDueDate, lastSeenDate: old.lastSeenDate,
                                      categoryId: old.categoryId, categoryName: old.categoryName,
                                      categoryColor: old.categoryColor, confidence: old.confidence,
                                      variable: old.variable, amountMin: old.amountMin, amountMax: old.amountMax,
                                      previousAmount: old.previousAmount,
                                      priceChangedAt: old.priceChangedAt, autoConfirmed: old.autoConfirmed,
                                      runtimeStatus: old.runtimeStatus)
        store.recurring[index] = updated
        return updated
    }

    @MainActor func budgetSettings() async throws -> BudgetSettings { store.settings }
    @MainActor func updateBudgetSettings(_ settings: BudgetSettings) async throws -> BudgetSettings {
        store.settings = settings
        return settings
    }
}
