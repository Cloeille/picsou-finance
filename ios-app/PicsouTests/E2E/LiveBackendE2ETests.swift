import XCTest
@testable import Picsou

/// Exercises every `Live*DataSource` against a REAL running backend (not `MockURLProtocol`) — the
/// point is to catch a request/response contract drift between the iOS app and the backend that a
/// decode-a-canned-JSON unit test can't: a renamed field, a changed status code, a param the backend
/// no longer accepts, an endpoint that 500s on a real (if minimal) account.
///
/// Self-skips via `XCTSkip` when there's no backend to test against (see `LiveBackend` for how to
/// point this at one). The login/authorize/token-exchange handshake runs at most ONCE per test
/// process (`Self.session`, a memoized `Task`) — the backend rate-limits `/api/auth/login` to 5
/// attempts per 15 minutes, and this suite has more than 5 test methods.
@MainActor
final class LiveBackendE2ETests: XCTestCase {
    private static let session = Task { try await LiveBackend.makeClient() }

    private var api: APIClient!

    override func setUp() async throws {
        do {
            api = try await Self.session.value.api
        } catch let error as LiveBackend.Unavailable {
            throw XCTSkip(error.reason)
        }
    }

    // MARK: - Dashboard

    func testDashboard_decodesLiveResponse() async throws {
        let dashboard = try await LiveDashboardDataSource(api: api).fetch(range: .month)
        XCTAssertNotNil(dashboard.totalNetWorth)
        XCTAssertNotNil(dashboard.totalLiabilities)
    }

    // MARK: - Accounts + Goals (shares one manual checking account as fixture)

    func testAccountsAndGoals_liveCrudRoundTrip() async throws {
        let accountId = try await createManualAccount(name: "E2E Checking", type: "CHECKING", balance: 100)
        defer { Task { try? await deleteAccount(id: accountId) } }

        // Accounts: read the account we just created through the real DataSource.
        let accountsSource = LiveAccountsDataSource(api: api)
        let account = try await accountsSource.account(id: accountId)
        XCTAssertEqual(account.id, accountId)
        XCTAssertEqual(account.name, "E2E Checking")
        XCTAssertFalse(account.hidden)

        let holdingsBefore = try await accountsSource.holdings(id: accountId)
        XCTAssertTrue(holdingsBefore.isEmpty)

        let txBefore = try await accountsSource.transactions(id: accountId)
        XCTAssertTrue(txBefore.isEmpty)

        // Write path: add a manual cash transaction, then see it come back.
        let added = try await accountsSource.addCash(
            accountId: accountId,
            TransactionRequest(date: todayString(), description: "E2E test tx", amount: -12.34, txType: nil, currency: nil, categoryId: nil))
        XCTAssertEqual(added.description, "E2E test tx")

        let txAfter = try await accountsSource.transactions(id: accountId)
        XCTAssertEqual(txAfter.count, 1)

        // Goals: the account picker must see our account; then a full create/update/delete cycle.
        let goalsSource = LiveGoalsDataSource(api: api)
        let pickerAccounts = try await goalsSource.accounts()
        XCTAssertTrue(pickerAccounts.contains { $0.id == accountId })

        let deadline = futureDateString(daysFromNow: 400)
        let created = try await goalsSource.create(GoalRequest(
            name: "E2E Goal", targetAmount: 500, deadline: deadline, accountIds: [accountId]))
        XCTAssertEqual(created.name, "E2E Goal")
        XCTAssertEqual(created.accountIds, [accountId])

        let updated = try await goalsSource.update(id: created.id, GoalRequest(
            name: "E2E Goal Renamed", targetAmount: 600, deadline: deadline, accountIds: [accountId]))
        XCTAssertEqual(updated.name, "E2E Goal Renamed")

        try await goalsSource.delete(id: created.id)
    }

    // MARK: - Budget

    func testBudget_cashflowAndEnvelopesDecodeLive() async throws {
        let budgetSource = LiveBudgetDataSource(api: api)
        let cashflow = try await budgetSource.cashflow()
        XCTAssertNotNil(cashflow.net)
        _ = try await budgetSource.budgets()   // decodes to [] on a fresh member -- just must not throw
    }

    // MARK: - Access keys (MCP)

    func testAccessKeys_liveCreateListRevoke() async throws {
        let source = LiveAccessKeysDataSource(api: api)
        let created = try await source.create(name: "E2E key", scopes: ["dashboard:read", "goals:read"])
        XCTAssertFalse(created.secret.isEmpty)
        XCTAssertEqual(Set(created.key.scopes), Set(["dashboard:read", "goals:read"]))

        let list = try await source.list()
        XCTAssertTrue(list.contains { $0.id == created.key.id })

        try await source.revoke(id: created.key.id)
        let afterRevoke = try await source.list()
        XCTAssertTrue(afterRevoke.first { $0.id == created.key.id }?.isRevoked ?? false)
    }

    // MARK: - Family

    func testFamily_membersDecodeLive() async throws {
        let members = try await LiveFamilyDataSource(api: api).members()
        // The admin itself is a family member on a fresh instance.
        XCTAssertFalse(members.isEmpty)
    }

    // MARK: - Sync

    func testSync_connectionsDecodeLive() async throws {
        _ = try await LiveSyncDataSource(api: api).connections()   // [] on a fresh member -- must not throw
    }

    // MARK: - Settings / auth

    func testSettings_sessionsAndMfaStatusDecodeLive() async throws {
        let source = LiveSettingsDataSource(api: api)
        // This fixture signed in through the OAuth2 AS, so its own authorization is listed as the
        // current IOS_APP session (Remember Me rows, if any, come from other browsers).
        let sessions = try await source.sessions()
        XCTAssertTrue(sessions.contains { $0.kind == .iosApp && $0.current })

        let mfa = try await source.mfaStatus()
        XCTAssertFalse(mfa.enabled)
    }

    // MARK: - OAuth refresh grant (the bug this whole suite exists to catch)

    func testOAuthRefresh_rotatesTokensAndNewAccessTokenWorks() async throws {
        let liveSession = try await Self.session.value
        guard let initial = liveSession.tokenStore.load() else {
            return XCTFail("no tokens in the shared session's token store")
        }

        let refreshed = try await liveSession.oauth.refresh(initial.refreshToken)
        XCTAssertNotEqual(refreshed.accessToken, initial.accessToken)
        XCTAssertNotEqual(refreshed.refreshToken, initial.refreshToken, "reuseRefreshTokens(false) -- must rotate")

        // Persist the rotated pair so later tests in this run (and the still-in-flight shared
        // session) keep using a live refresh token instead of the one just consumed.
        liveSession.tokenStore.save(refreshed)

        let dashboardSource = LiveDashboardDataSource(api: liveSession.api)
        _ = try await dashboardSource.fetch(range: .month)   // proves the rotated access token actually works
    }

    // MARK: - Helpers (raw APIClient calls for fixtures no DataSource exposes -- account create/delete)

    private struct AccountCreateRequest: Encodable {
        let name: String
        let type: String
        let provider: String?
        let currency: String
        let currentBalance: Decimal
        let isManual: Bool
        let color: String
        let ticker: String?
    }

    private func createManualAccount(name: String, type: String, balance: Decimal) async throws -> Int64 {
        let created: Account = try await api.post("api/accounts", body: AccountCreateRequest(
            name: name, type: type, provider: nil, currency: "EUR",
            currentBalance: balance, isManual: true, color: "#2563EB", ticker: nil))
        return created.id
    }

    private func deleteAccount(id: Int64) async throws {
        _ = try await api.delete("api/accounts/\(id)")
    }

    private func todayString() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: Date())
    }

    private func futureDateString(daysFromNow: Int) -> String {
        let date = Calendar(identifier: .gregorian).date(byAdding: .day, value: daysFromNow, to: Date())!
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: date)
    }
}
