import XCTest
@testable import Picsou

@MainActor
final class BudgetTests: XCTestCase {

    func testCashflowDecoding() throws {
        let json = #"{"period":"CYCLE","from":"2026-07-01","to":"2026-07-31","income":2450,"expense":1684,"net":766,"series":[]}"#
        let cashflow = try JSONDecoder.picsou.decode(CashflowSummary.self, from: Data(json.utf8))
        XCTAssertEqual(cashflow.income, 2450)
        XCTAssertEqual(cashflow.net, 766)
    }

    func testBudgetEnvelopeDecoding_ignoresExtraKeysAndReadsOverBudget() throws {
        let json = ##"[{"id":1,"categoryId":9,"categoryName":"Alimentation","categoryKind":"EXPENSE","categoryColor":"#10B981","categoryIcon":null,"monthlyLimit":500,"spent":543,"remaining":-43,"percent":108.6,"overBudget":true,"rollup":false,"cycleStart":"2026-07-01","cycleEnd":"2026-07-31"}]"##
        let envelopes = try JSONDecoder.picsou.decode([BudgetEnvelope].self, from: Data(json.utf8))
        XCTAssertEqual(envelopes.count, 1)
        XCTAssertTrue(envelopes[0].overBudget)
        XCTAssertEqual(envelopes[0].categoryName, "Alimentation")
    }

    func testDemoBudget_isPopulated() async throws {
        let ds = DemoBudgetDataSource(store: DemoBudgetStore())
        let cashflow = try await ds.cashflow()
        XCTAssertGreaterThan(cashflow.income, 0)
        let budgets = try await ds.budgets()
        XCTAssertFalse(budgets.isEmpty)
        XCTAssertTrue(budgets.contains { $0.overBudget })
    }

    // MARK: - Categorization (real backend JSON shapes, per docs/briefs/2026-07-22-budget-ios-redesign-design.md)

    func testCategoryDecoding_matchesBackendCategoryResponse() throws {
        let json = ##"{"id":9,"name":"Revenus","kind":"INCOME","color":"#22C55E","icon":null,"isDefault":true,"archived":false,"sortOrder":0,"parentId":null}"##
        let category = try JSONDecoder.picsou.decode(Category.self, from: Data(json.utf8))
        XCTAssertEqual(category.name, "Revenus")
        XCTAssertEqual(category.kind, .income)
        XCTAssertTrue(category.isDefault)
        XCTAssertTrue(category.pickable)
    }

    func testCategory_transferKindIsNotPickable() throws {
        let json = ##"{"id":1,"name":"Virement interne","kind":"TRANSFER","color":"#888888","icon":null,"isDefault":true,"archived":false,"sortOrder":0,"parentId":null}"##
        let category = try JSONDecoder.picsou.decode(Category.self, from: Data(json.utf8))
        XCTAssertFalse(category.pickable)
    }

    func testTransactionDecoding_decodesCategorizationFields() throws {
        // Real TransactionResponse shape: categoryId is present alongside categoryName, and
        // aiSuggestedCategoryId/aiConfidence are set for an unreviewed AI suggestion.
        let json = ##"{"id":1,"date":"2026-07-01","description":"Amazon","amount":-34.99,"isManual":false,"categoryId":null,"categoryName":null,"merchantLabel":"Amazon","merchantBrandId":null,"counterparty":null,"accountId":3,"accountName":"Compte courant","aiSuggestedCategoryId":6,"aiConfidence":88}"##
        let tx = try JSONDecoder.picsou.decode(Transaction.self, from: Data(json.utf8))
        XCTAssertFalse(tx.isCategorized)
        XCTAssertEqual(tx.aiSuggestedCategoryId, 6)
        XCTAssertEqual(tx.aiConfidence, 88)
    }

    func testTransaction_categorized_clearsAiSuggestionAndSetsCategory() throws {
        let json = ##"{"id":1,"date":"2026-07-01","description":"Amazon","amount":-34.99,"isManual":false,"categoryId":null,"categoryName":null,"merchantLabel":"Amazon","merchantBrandId":null,"counterparty":null,"accountId":3,"accountName":"Compte courant","aiSuggestedCategoryId":6,"aiConfidence":88}"##
        let tx = try JSONDecoder.picsou.decode(Transaction.self, from: Data(json.utf8))
        let updated = tx.categorized(as: 6, name: "Shopping")
        XCTAssertTrue(updated.isCategorized)
        XCTAssertEqual(updated.categoryId, 6)
        XCTAssertEqual(updated.categoryName, "Shopping")
        XCTAssertNil(updated.aiSuggestedCategoryId)
        XCTAssertNil(updated.aiConfidence)
    }

    func testAiJobStatusDecoding() throws {
        let json = #"{"running":true,"total":23,"processed":8,"applied":3,"suggested":5,"done":false,"error":null}"#
        let status = try JSONDecoder.picsou.decode(AiJobStatus.self, from: Data(json.utf8))
        XCTAssertTrue(status.running)
        XCTAssertEqual(status.total, 23)
        XCTAssertFalse(status.done)
    }

    func testSpendingByCategoryDecoding() throws {
        let json = ##"{"period":"CYCLE","from":"2026-07-01","to":"2026-07-31","totalExpense":1684,"categories":[{"categoryId":1,"slug":"alimentation","name":"Alimentation","color":"#10B981","icon":null,"amount":543,"count":12,"share":0.32,"parentId":null,"parentName":null,"parentColor":null}]}"##
        let breakdown = try JSONDecoder.picsou.decode(SpendingByCategory.self, from: Data(json.utf8))
        XCTAssertEqual(breakdown.categories.count, 1)
        XCTAssertEqual(breakdown.categories[0].name, "Alimentation")
    }

    func testSpendingDetailDecoding_reusesTransactionResponseShape() throws {
        let json = ##"{"categoryId":1,"slug":"alimentation","name":"Alimentation","color":"#10B981","icon":null,"period":"CYCLE","from":"2026-07-01","to":"2026-07-31","total":-543,"count":1,"transactions":[{"id":1,"date":"2026-07-01","description":"Carrefour","amount":-54.30,"isManual":false,"categoryId":1,"categoryName":"Alimentation","merchantLabel":"Carrefour","merchantBrandId":null,"counterparty":null,"accountId":3,"accountName":"Compte courant","aiSuggestedCategoryId":null,"aiConfidence":null}],"children":[]}"##
        let detail = try JSONDecoder.picsou.decode(SpendingDetail.self, from: Data(json.utf8))
        XCTAssertEqual(detail.transactions.count, 1)
        XCTAssertEqual(detail.transactions[0].categoryName, "Alimentation")
    }

    func testRecurringSeriesDecoding_matchesBackendResponse() throws {
        let json = ##"{"id":1,"label":"Netflix","counterparty":"NETFLIX.COM","expectedAmount":-15.49,"cadence":"MONTHLY","status":"CONFIRMED","nextDueDate":"2026-08-05","lastSeenDate":"2026-07-05","categoryId":4,"categoryName":"Abonnements","categoryColor":"#6366F1","categoryIcon":null,"confidence":96,"amountMin":null,"amountMax":null,"variable":false,"previousAmount":-13.49,"priceChangedAt":"2026-07-05","autoConfirmed":false,"runtimeStatus":"SCHEDULED"}"##
        let series = try JSONDecoder.picsou.decode(RecurringSeries.self, from: Data(json.utf8))
        XCTAssertEqual(series.status, .confirmed)
        XCTAssertEqual(series.cadence, .monthly)
        XCTAssertEqual(series.runtimeStatus, .scheduled)
        XCTAssertTrue(series.priceIncreased)
    }

    func testRecurringActivityDecoding_synthesizesStableId() throws {
        let json = ##"{"seriesId":1,"label":"Netflix","type":"PRICE_CHANGE","occurredOn":"2026-07-05","expectedAmount":-15.49,"previousAmount":-13.49,"cadence":"MONTHLY","categoryId":4,"categoryName":"Abonnements","categoryColor":"#6366F1"}"##
        let activity = try JSONDecoder.picsou.decode(RecurringActivity.self, from: Data(json.utf8))
        XCTAssertEqual(activity.type, .priceChange)
        XCTAssertEqual(activity.id, "1-PRICE_CHANGE-2026-07-05")
    }

    func testRecurringOccurrenceDecoding_synthesizesStableId() throws {
        let json = ##"{"seriesId":1,"label":"Netflix","counterparty":"NETFLIX.COM","expectedAmount":-15.49,"dueDate":"2026-08-05","categoryId":4,"categoryName":"Abonnements","categoryColor":"#6366F1"}"##
        let occurrence = try JSONDecoder.picsou.decode(RecurringOccurrence.self, from: Data(json.utf8))
        XCTAssertEqual(occurrence.id, "1-2026-08-05")
        XCTAssertNotNil(occurrence.day)
    }

    func testBudgetSettingsDecoding() throws {
        let json = #"{"cycleStartDay":1,"logoFetchEnabled":true,"aiCategorizationEnabled":true,"aiMode":"AUTO_HIGH_CONFIDENCE","aiConfidenceThreshold":80,"currentCycleStart":"2026-07-01","currentCycleEnd":"2026-07-31"}"#
        let settings = try JSONDecoder.picsou.decode(BudgetSettings.self, from: Data(json.utf8))
        XCTAssertEqual(settings.cycleStartDay, 1)
        XCTAssertEqual(settings.aiMode, .autoHighConfidence)
    }

    // MARK: - Demo data source coverage (the new methods, per DataSource protocol extension)

    func testDemoBudget_categoriesAndUncategorizedTransactions() async throws {
        let ds = DemoBudgetDataSource(store: DemoBudgetStore())
        let categories = try await ds.categories()
        XCTAssertFalse(categories.isEmpty)
        let uncategorized = try await ds.uncategorizedTransactions()
        XCTAssertFalse(uncategorized.isEmpty, "demo data must seed at least one uncategorized transaction for the inbox to be demoable")
        XCTAssertTrue(uncategorized.contains { $0.aiSuggestedCategoryId != nil }, "at least one demo transaction should carry an AI suggestion")
    }

    func testDemoBudget_envelopeCrudRoundTrip() async throws {
        let ds = DemoBudgetDataSource(store: DemoBudgetStore())
        let before = try await ds.budgets().count
        let created = try await ds.createEnvelope(BudgetRequest(categoryId: 8, monthlyLimit: 60))
        let afterCreate = try await ds.budgets().count
        XCTAssertEqual(afterCreate, before + 1)
        let updated = try await ds.updateEnvelope(id: created.id, BudgetRequest(categoryId: 8, monthlyLimit: 90))
        XCTAssertEqual(updated.monthlyLimit, 90)
        try await ds.deleteEnvelope(id: created.id)
        let afterDelete = try await ds.budgets().count
        XCTAssertEqual(afterDelete, before)
    }

    func testDemoBudget_categorizeRemovesFromInbox() async throws {
        let ds = DemoBudgetDataSource(store: DemoBudgetStore())
        let before = try await ds.uncategorizedTransactions()
        guard let first = before.first else { return XCTFail("demo inbox should not be empty") }
        try await ds.categorize(transactionId: first.id, categoryId: 1)
        let after = try await ds.uncategorizedTransactions()
        XCTAssertEqual(after.count, before.count - 1)
        XCTAssertFalse(after.contains { $0.id == first.id })
    }

    func testDemoBudget_recurringConfirmIgnoreUndo() async throws {
        let ds = DemoBudgetDataSource(store: DemoBudgetStore())
        let series = try await ds.recurringSeries()
        guard let suggested = series.first(where: { $0.status == .suggested }) else { return XCTFail("demo data needs a SUGGESTED series") }
        let confirmed = try await ds.confirmRecurring(id: suggested.id)
        XCTAssertEqual(confirmed.status, .confirmed)
        let undone = try await ds.undoRecurring(id: suggested.id)
        XCTAssertEqual(undone.status, .suggested)
    }

    func testDemoBudget_settingsRoundTrip() async throws {
        let ds = DemoBudgetDataSource(store: DemoBudgetStore())
        var settings = try await ds.budgetSettings()
        settings.cycleStartDay = 15
        let saved = try await ds.updateBudgetSettings(settings)
        XCTAssertEqual(saved.cycleStartDay, 15)
        let reloaded = try await ds.budgetSettings()
        XCTAssertEqual(reloaded.cycleStartDay, 15)
    }
}
