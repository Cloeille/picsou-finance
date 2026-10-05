import XCTest
@testable import Picsou

/// Decoding against payloads shaped like the 1.1.0 backend actually writes them: Jackson with
/// `default-property-inclusion: non_null` (null fields are omitted, never `null`), records keeping
/// their `isX` component names, `Instant`s with fractional seconds. Each test pins one mismatch the
/// 1.1.0 audit found. The enum and scope tests read the Java sources next door, so they follow the
/// backend instead of a copy of it (they skip when the repository isn't on disk, e.g. on a device).
@MainActor
final class ApiContractTests: XCTestCase {

    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    // MARK: - Dates

    func testInstantWithFractionalSeconds_parses() throws {
        let date = try XCTUnwrap(DateParsing.instant("2026-07-04T08:00:00.123456Z"))
        XCTAssertEqual(date.timeIntervalSince1970, 1_783_152_000.123, accuracy: 0.001)
        XCTAssertNotNil(DateParsing.instant("2026-07-04T08:00:00Z"))
        XCTAssertNil(DateParsing.instant("not a date"))
    }

    // MARK: - Accounts

    func testAccountTypes_coverEveryBackendConstant() throws {
        let java = try backendSource("model/AccountType.java")
        let body = try XCTUnwrap(java.components(separatedBy: "public enum AccountType {").last?
            .components(separatedBy: ";").first)
        let constants = body.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.range(of: #"^[A-Z_]+,?$"#, options: .regularExpression) != nil }
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ",")) }
        XCTAssertGreaterThan(constants.count, 10)
        XCTAssertEqual(Set(constants), Set(AccountType.allCases.map(\.rawValue)))
        XCTAssertEqual(AccountType(raw: "SOMETHING_NEW"), .other)
    }

    func testCreditCardAccount_decodesPaymentDueAndPoints() throws {
        let json = """
        {"id":12,"name":"Amex Gold","type":"CREDIT_CARD","provider":"AMEX","currency":"EUR",
         "currentBalance":-812.4,"currentBalanceEur":-812.4,"lastSyncedAt":"2026-10-04T06:12:09.481233Z",
         "isManual":false,"color":"#6366f1","logoUrl":"https://logo.example/amex.png",
         "createdAt":"2026-08-11T10:00:00.5Z","paymentDueAmount":812.4,"paymentDueDate":"2026-10-20",
         "rewardPoints":13650,"hidden":false,"isOwner":true}
        """
        let account = try JSONDecoder.picsou.decode(Account.self, from: Data(json.utf8))
        XCTAssertEqual(account.type, .creditCard)
        XCTAssertTrue(account.type.isLiability)
        XCTAssertEqual(account.paymentDueAmount, Decimal(string: "812.4"))
        XCTAssertEqual(account.paymentDueDate, "2026-10-20")
        XCTAssertEqual(account.rewardPoints, 13650)
        XCTAssertNotNil(account.lastSyncedDate)
        XCTAssertEqual(DueDate.label(account.paymentDueDate), "20 oct. 2026")
    }

    func testAccountTypeGroups_matchTheWebFilters() {
        XCTAssertEqual(AccountType.livretA.category, "Épargne")
        XCTAssertEqual(AccountType.assuranceVie.category, "Investissement")
        XCTAssertEqual(AccountType.scpi.category, "Immobilier")
        XCTAssertEqual(AccountType.creditCard.category, "Dettes")
        XCTAssertTrue(AccountType.employeeSavings.holdsPositions)
        XCTAssertFalse(AccountType.scpi.holdsPositions)
    }

    // MARK: - Dashboard

    func testDashboardLiabilities_creditCardDueAndOmittedLoanFields() throws {
        let json = """
        {"totalNetWorth":120000.5,"totalLiabilities":150812.4,"totalMonthlyPayment":980.0,
         "netWorthHistory":[],"distribution":[],"goalSummaries":[],
         "liabilities":[
          {"accountId":9,"name":"Prêt immo","color":"#ef4444","balanceEur":150000,"percentage":99.46,
           "accountType":"LOAN","hasHoldings":false,"monthlyPayment":980.0,"percentPaid":31.2},
          {"accountId":12,"name":"Amex Gold","color":"#6366f1","balanceEur":812.4,"percentage":0.54,
           "accountType":"CREDIT_CARD","hasHoldings":false,"paymentDueAmountEur":812.4,"paymentDueDate":"2026-10-20"}]}
        """
        let dashboard = try JSONDecoder.picsou.decode(DashboardResponse.self, from: Data(json.utf8))
        let card = try XCTUnwrap(dashboard.liabilities.last)
        XCTAssertEqual(card.type, .creditCard)
        XCTAssertNil(card.monthlyPayment)
        XCTAssertNil(card.percentPaid)
        XCTAssertEqual(card.paymentDueAmountEur, Decimal(string: "812.4"))
        XCTAssertEqual(card.paymentDueDate, "2026-10-20")
        XCTAssertNil(dashboard.liabilities.first?.paymentDueAmountEur)
    }

    // MARK: - Budget

    func testRecurringCalendar_includesCardPaymentWithoutASeries() throws {
        let json = """
        [{"seriesId":4,"label":"Netflix","counterparty":"NETFLIX","expectedAmount":-13.49,"dueDate":"2026-10-12",
          "categoryId":7,"categoryName":"Abonnements","categoryColor":"#a855f7","categoryIcon":"tv","creditCardPayment":false},
         {"seriesId":-12,"label":"Amex Gold","counterparty":"Amex Gold","expectedAmount":-812.4,"dueDate":"2026-10-20",
          "rewardPoints":13650,"creditCardPayment":true}]
        """
        let occurrences = try JSONDecoder.picsou.decode([RecurringOccurrence].self, from: Data(json.utf8))
        XCTAssertFalse(occurrences[0].isCreditCardPayment)
        XCTAssertNil(occurrences[0].rewardPoints)
        XCTAssertTrue(occurrences[1].isCreditCardPayment)
        XCTAssertEqual(occurrences[1].rewardPoints, 13650)
        XCTAssertNil(occurrences[1].categoryColor)
        XCTAssertNotEqual(occurrences[0].id, occurrences[1].id)
    }

    func testUnknownEnumValues_doNotBreakDecoding() throws {
        let series = """
        [{"id":1,"label":"Gym","expectedAmount":-30,"cadence":"FORTNIGHTLY","status":"ARCHIVED",
          "variable":false,"autoConfirmed":false,"runtimeStatus":"PAUSED"}]
        """
        let decoded = try JSONDecoder.picsou.decode([RecurringSeries].self, from: Data(series.utf8))
        XCTAssertEqual(decoded[0].cadence, .unknown)
        XCTAssertEqual(decoded[0].status, .unknown)
        XCTAssertEqual(decoded[0].runtimeStatus, .unknown)

        let activity = """
        [{"seriesId":1,"label":"Gym","type":"CATEGORY_CHANGE","occurredOn":"2026-10-01","expectedAmount":-30,"cadence":"MONTHLY"}]
        """
        let entries = try JSONDecoder.picsou.decode([RecurringActivity].self, from: Data(activity.utf8))
        XCTAssertEqual(entries[0].type, RecurringActivityType.unknown)

        let categories = """
        [{"id":3,"name":"Épargne","kind":"SAVINGS","color":"#22c55e","isDefault":false,"archived":false,"sortOrder":4}]
        """
        let category = try JSONDecoder.picsou.decode([Picsou.Category].self, from: Data(categories.utf8))[0]
        XCTAssertEqual(category.kind, .unknown)
        XCTAssertFalse(category.pickable)
    }

    func testUnknownAiMode_survivesTheSettingsRoundTrip() throws {
        let json = #"{"cycleStartDay":5,"logoFetchEnabled":true,"aiCategorizationEnabled":true,"aiMode":"AUTO_SMART","aiConfidenceThreshold":70}"#
        let settings = try JSONDecoder.picsou.decode(BudgetSettings.self, from: Data(json.utf8))
        let body = try JSONEncoder.picsou.encode(BudgetSettingsRequest(settings))
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(sent["aiMode"] as? String, "AUTO_SMART")
        XCTAssertNil(settings.currentCycleStart)
    }

    // MARK: - Categorization suggestion

    func testAiSuggestion_isPinnedForAnUncategorizedTransaction() throws {
        let categories = try decodeCategories()
        let tx = try decodeTransaction(#""aiSuggestedCategoryId":2,"aiConfidence":87"#)
        let suggestion = try XCTUnwrap(CategorySuggestion(aiSuggestionFor: tx, in: categories))
        XCTAssertEqual(suggestion.category.name, "Courses")
        XCTAssertEqual(suggestion.confidence, 87)
    }

    func testAiSuggestion_isNilWithoutABackendSuggestionOrForAnUnpickableCategory() throws {
        let categories = try decodeCategories()
        XCTAssertNil(CategorySuggestion(aiSuggestionFor: try decodeTransaction(""), in: categories))
        XCTAssertNil(CategorySuggestion(aiSuggestionFor: try decodeTransaction(#""aiSuggestedCategoryId":3"#), in: categories))
        XCTAssertNil(CategorySuggestion(aiSuggestionFor: try decodeTransaction(#""aiSuggestedCategoryId":99"#), in: categories))
        XCTAssertNil(CategorySuggestion(
            aiSuggestionFor: try decodeTransaction(#""categoryId":2,"categoryName":"Courses","aiSuggestedCategoryId":2"#),
            in: categories))
    }

    // MARK: - Settings

    func testMcpScopes_matchTheBackendAllowlist() throws {
        let java = try backendSource("mcp/Scopes.java")
        let regex = try NSRegularExpression(pattern: #"public static final String [A-Z0-9_]+ = "([a-z0-9:-]+)";"#)
        let ids = regex.matches(in: java, range: NSRange(java.startIndex..., in: java)).compactMap {
            Range($0.range(at: 1), in: java).map { String(java[$0]) }
        }
        XCTAssertGreaterThanOrEqual(ids.count, 25)
        XCTAssertEqual(McpScope.all.map(\.id), ids)
        XCTAssertEqual(Set(McpScope.all.map(\.label)).count, McpScope.all.count)
        XCTAssertEqual(McpScope.label("budget:recurring-write"), "Abonnements récurrents — écriture")
    }

    func testSessions_decodeBrowserAndAppRows() throws {
        let json = """
        [{"id":"14","kind":"REMEMBER_ME","userAgent":"Mozilla/5.0 (Macintosh)","ipPrefix":"192.168.1",
          "createdAt":"2026-09-01T09:00:00.120Z","lastUsedAt":"2026-10-04T18:30:00.991Z","expiresAt":"2026-11-30T09:00:00Z",
          "trustedFor2fa":true,"current":false},
         {"id":"0b6f3c1e-4c1d-4a8e-9f0a-2d7e5b1c9a11","kind":"IOS_APP","createdAt":"2026-10-01T08:00:00.004Z",
          "lastUsedAt":"2026-10-05T07:45:12.31Z","expiresAt":"2026-10-31T08:00:00.004Z","trustedFor2fa":false,"current":true}]
        """
        let sessions = try JSONDecoder.picsou.decode([SessionInfo].self, from: Data(json.utf8))
        XCTAssertEqual(sessions.map(\.kind), [.rememberMe, .iosApp])
        XCTAssertNil(sessions[1].userAgent)
        XCTAssertEqual(SecurityView.title(of: sessions[1]), "App iPhone")
        XCTAssertNotNil(DateParsing.instant(sessions[1].lastUsedAt))
    }

    func testRevokeSession_sendsTheOpaqueId() async throws {
        var path: String?
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "DELETE")
            path = request.url?.path
            return MockURLProtocol.status(request, 204)
        }
        try await LiveSettingsDataSource(api: makeAPI()).revokeSession(id: "0b6f3c1e-4c1d-4a8e-9f0a-2d7e5b1c9a11")
        XCTAssertEqual(path, "/api/auth/sessions/0b6f3c1e-4c1d-4a8e-9f0a-2d7e5b1c9a11")
    }

    func testSidecarConnections_keepOnlyActiveOnes_andSurviveAMissingSidecar() async throws {
        MockURLProtocol.handler = { request in
            switch request.url?.path {
            case "/api/amex/status":
                return MockURLProtocol.ok(request, json:
                    #"{"isActive":true,"syncStatus":"SUCCESS","lastSyncStartedAt":"2026-10-05T06:00:00.1Z","lastSyncCompletedAt":"2026-10-05T06:01:02.913Z"}"#)
            case "/api/fortuneo/status":
                return MockURLProtocol.ok(request, json: #"{"isActive":true,"syncStatus":"FAILED","lastSyncError":"SESSION_EXPIRED"}"#)
            case "/api/sofidy/status":
                return MockURLProtocol.status(request, 404)
            default:
                return MockURLProtocol.ok(request, json: #"{"isActive":false,"syncStatus":"IDLE"}"#)
            }
        }
        let connections = await LiveSyncDataSource(api: makeAPI()).sidecarConnections()
        XCTAssertEqual(connections.map(\.connector), [.amex, .fortuneo])
        XCTAssertEqual(connections[1].status.syncStatus, "FAILED")
        XCTAssertNotNil(DateParsing.instant(connections[0].status.lastSyncCompletedAt))
    }

    // MARK: - Helpers

    private func backendSource(_ relativePath: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let file = root.appendingPathComponent("backend/src/main/java/com/picsou/").appendingPathComponent(relativePath)
        guard let source = try? String(contentsOf: file, encoding: .utf8) else {
            throw XCTSkip("backend sources not reachable from this test host: \(file.path)")
        }
        return source
    }

    private func decodeCategories() throws -> [Picsou.Category] {
        let json = """
        [{"id":2,"name":"Courses","kind":"EXPENSE","color":"#22c55e","icon":"cart","isDefault":true,"archived":false,"sortOrder":1},
         {"id":3,"name":"Ancien","kind":"EXPENSE","color":"#94a3b8","isDefault":false,"archived":true,"sortOrder":9}]
        """
        return try JSONDecoder.picsou.decode([Picsou.Category].self, from: Data(json.utf8))
    }

    private func decodeTransaction(_ extraFields: String) throws -> Transaction {
        let extra = extraFields.isEmpty ? "" : "," + extraFields
        let json = """
        {"id":31,"date":"2026-10-03","description":"CB CARREFOUR 0310","amount":-42.1,"type":"CARD",
         "nativeCurrency":"EUR","createdAt":"2026-10-03T21:04:11.5Z","isManual":false,"accountId":3,"accountName":"Compte courant"\(extra)}
        """
        return try JSONDecoder.picsou.decode(Transaction.self, from: Data(json.utf8))
    }

    private func makeAPI() -> APIClient {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        suite.set("https://test.local", forKey: ServerConfig.baseURLDefaultsKey)
        let serverConfig = ServerConfig(defaults: suite)
        let tokenStore = InMemoryTokenStore()
        tokenStore.save(TokenSet(accessToken: "tok", refreshToken: "r", accessTokenExpiry: Date().addingTimeInterval(3600)))
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)
        return APIClient(serverConfig: serverConfig, tokenStore: tokenStore,
                         oauth: OAuthService(serverConfig: serverConfig, session: session), session: session)
    }
}
