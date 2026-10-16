import XCTest
@testable import Picsou

/// `LiveBudgetDataSource` against `MockURLProtocol` — the real HTTP request shapes (method, path,
/// query params, body fields), not just decode-from-JSON or demo-store round-trips. This is the
/// exact class of test the project has been bitten by skipping twice already this session (a JSON
/// key mismatch, a broken OAuth refresh grant) — see TODO.md's 2026-07-22 entry and
/// docs/briefs/2026-07-22-budget-ios-redesign-design.md.
@MainActor
final class LiveBudgetDataSourceTests: XCTestCase {

    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    private func makeDataSource() -> LiveBudgetDataSource {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        suite.set("https://test.local", forKey: ServerConfig.baseURLDefaultsKey)
        let serverConfig = ServerConfig(defaults: suite)

        let tokenStore = InMemoryTokenStore()
        tokenStore.save(TokenSet(accessToken: "tok", refreshToken: "r", accessTokenExpiry: Date().addingTimeInterval(3600)))

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)

        let oauth = OAuthService(serverConfig: serverConfig, session: session)
        let api = APIClient(serverConfig: serverConfig, tokenStore: tokenStore, oauth: oauth, session: session)
        return LiveBudgetDataSource(api: api)
    }

    private func jsonBody(_ request: URLRequest) -> [String: Any] {
        guard let data = request.httpBodyStream.map(Data.init(reading:)) ?? request.httpBody,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }

    // MARK: - Categorization

    func testCategorize_putsCorrectPathAndBody() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.status(request, 204)
        }
        try await ds.categorize(transactionId: 42, categoryId: 7)

        XCTAssertEqual(captured?.httpMethod, "PUT")
        XCTAssertEqual(captured?.url?.path, "/api/transactions/42/category")
        let body = jsonBody(captured!)
        XCTAssertEqual(body["categoryId"] as? Int, 7)
        // No rules-engine UI in this app (YAGNI) -- must always be false, never omitted (the
        // backend field is a non-optional boolean, so a missing key would 400).
        XCTAssertEqual(body["createRule"] as? Bool, false)
    }

    func testUncategorizedTransactions_getsCorrectPath() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: "[]")
        }
        _ = try await ds.uncategorizedTransactions()
        XCTAssertEqual(captured?.httpMethod, "GET")
        XCTAssertEqual(captured?.url?.path, "/api/transactions/uncategorized")
    }

    func testStartAiCategorization_postsCorrectPath() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: #"{"running":true,"total":0,"processed":0,"applied":0,"suggested":0,"done":false,"error":null}"#)
        }
        _ = try await ds.startAiCategorization()
        XCTAssertEqual(captured?.httpMethod, "POST")
        XCTAssertEqual(captured?.url?.path, "/api/transactions/categorize-ai")
    }

    // MARK: - Envelopes

    func testCreateEnvelope_postsCorrectBody() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: ##"{"id":1,"categoryId":7,"categoryName":"x","categoryKind":"EXPENSE","categoryColor":null,"monthlyLimit":50,"spent":0,"remaining":50,"percent":0,"overBudget":false,"cycleStart":null,"cycleEnd":null}"##)
        }
        _ = try await ds.createEnvelope(BudgetRequest(categoryId: 7, monthlyLimit: 50))
        XCTAssertEqual(captured?.httpMethod, "POST")
        XCTAssertEqual(captured?.url?.path, "/api/budgets")
        let body = jsonBody(captured!)
        XCTAssertEqual(body["categoryId"] as? Int, 7)
        XCTAssertEqual(body["monthlyLimit"] as? Double, 50)
    }

    func testDeleteEnvelope_deletesCorrectPath() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.status(request, 204)
        }
        try await ds.deleteEnvelope(id: 9)
        XCTAssertEqual(captured?.httpMethod, "DELETE")
        XCTAssertEqual(captured?.url?.path, "/api/budgets/9")
    }

    // MARK: - Budget settings (the carry-through case: the UI only exposes 2 of 5 fields)

    func testUpdateBudgetSettings_carriesThroughEveryField() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: #"{"cycleStartDay":15,"logoFetchEnabled":false,"aiCategorizationEnabled":true,"aiMode":"AUTO_ALL","aiConfidenceThreshold":92,"currentCycleStart":null,"currentCycleEnd":null}"#)
        }
        let settings = BudgetSettings(cycleStartDay: 15, logoFetchEnabled: false, aiCategorizationEnabled: true,
                                      aiMode: .autoAll, aiConfidenceThreshold: 92, currentCycleStart: nil, currentCycleEnd: nil)
        _ = try await ds.updateBudgetSettings(settings)

        XCTAssertEqual(captured?.httpMethod, "PUT")
        XCTAssertEqual(captured?.url?.path, "/api/budget/settings")
        let body = jsonBody(captured!)
        // Every field the backend's BudgetSettingsRequest requires, even the 3 this app's
        // Settings screen never lets the user touch -- a missing one would 400.
        XCTAssertEqual(body["cycleStartDay"] as? Int, 15)
        XCTAssertEqual(body["logoFetchEnabled"] as? Bool, false)
        XCTAssertEqual(body["aiCategorizationEnabled"] as? Bool, true)
        XCTAssertEqual(body["aiMode"] as? String, "AUTO_ALL")
        XCTAssertEqual(body["aiConfidenceThreshold"] as? Int, 92)
    }

    // MARK: - Spending

    func testSpendingByCategory_sendsPeriodQueryParam() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: #"{"period":"YTD","from":null,"to":null,"totalExpense":0,"categories":[]}"#)
        }
        _ = try await ds.spendingByCategory(period: .yearToDate)
        XCTAssertEqual(captured?.url?.path, "/api/spending/by-category")
        XCTAssertTrue(captured?.url?.query?.contains("period=YTD") ?? false)
    }

    func testSpendingDetail_getsCategoryIdInPath() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: #"{"categoryId":3,"slug":null,"name":"x","color":null,"icon":null,"period":"CYCLE","from":null,"to":null,"total":0,"count":0,"transactions":[]}"#)
        }
        _ = try await ds.spendingDetail(categoryId: 3, period: .cycle)
        XCTAssertEqual(captured?.url?.path, "/api/spending/category/3")
        XCTAssertTrue(captured?.url?.query?.contains("period=CYCLE") ?? false)
    }

    // MARK: - Recurring

    func testRecurringCalendar_sendsHorizonDaysQueryParam() async throws {
        let ds = makeDataSource()
        var captured: URLRequest?
        MockURLProtocol.handler = { request in
            captured = request
            return MockURLProtocol.ok(request, json: "[]")
        }
        _ = try await ds.recurringCalendar(horizonDays: 60)
        XCTAssertEqual(captured?.url?.path, "/api/recurring/calendar")
        XCTAssertTrue(captured?.url?.query?.contains("horizonDays=60") ?? false)
    }

    func testConfirmIgnoreUndoRecurring_postToCorrectActionPaths() async throws {
        let ds = makeDataSource()
        let seriesJSON = ##"{"id":5,"label":"x","counterparty":null,"expectedAmount":-1,"cadence":"MONTHLY","status":"CONFIRMED","nextDueDate":null,"lastSeenDate":null,"categoryId":null,"categoryName":null,"categoryColor":null,"categoryIcon":null,"confidence":null,"amountMin":null,"amountMax":null,"variable":false,"previousAmount":null,"priceChangedAt":null,"autoConfirmed":false,"runtimeStatus":"SCHEDULED"}"##
        var paths: [String] = []
        MockURLProtocol.handler = { request in
            paths.append(request.url?.path ?? "")
            return MockURLProtocol.ok(request, json: seriesJSON)
        }
        _ = try await ds.confirmRecurring(id: 5)
        _ = try await ds.ignoreRecurring(id: 5)
        _ = try await ds.undoRecurring(id: 5)
        XCTAssertEqual(paths, ["/api/recurring/5/confirm", "/api/recurring/5/ignore", "/api/recurring/5/undo"])
    }
}

private extension Data {
    init(reading input: InputStream) {
        self.init()
        input.open()
        defer { input.close() }
        let bufferSize = 4096
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while input.hasBytesAvailable {
            let read = input.read(&buffer, maxLength: bufferSize)
            if read > 0 { append(buffer, count: read) } else { break }
        }
    }
}
