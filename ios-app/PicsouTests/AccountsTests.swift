import XCTest
@testable import Picsou

@MainActor
final class AccountsTests: XCTestCase {

    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    func testDecodeAccount_mapsManualKeyAndType() throws {
        let json = """
        {"id":7,"name":"Livret A","type":"SAVINGS","provider":"CA","currency":"EUR",
         "currentBalance":12000.0,"currentBalanceEur":12000.0,"lastSyncedAt":"2026-07-03T08:05:00Z",
         "isManual":false,"color":"#4F46E5","ticker":null,"parentAccountId":null,"debt":null,"hidden":false}
        """
        let account = try JSONDecoder.picsou.decode(Account.self, from: Data(json.utf8))
        XCTAssertEqual(account.id, 7)
        XCTAssertEqual(account.type, .savings)
        XCTAssertFalse(account.manual)                       // JSON key `isManual` maps to `manual`
        XCTAssertEqual(account.currentBalanceEur.doubleValue, 12000, accuracy: 0.01)
        XCTAssertNotNil(account.lastSyncedDate)
    }

    func testDecodeTransactions_signedAmountsAndMerchantFallback() throws {
        let json = """
        [{"id":1,"date":"2026-07-01","description":"CARREFOUR 4213","amount":-54.30,
          "nativeCurrency":"EUR","isManual":false,"txType":"WITHDRAWAL","categoryName":"Alimentation",
          "merchantLabel":"Carrefour","counterparty":"CARREFOUR","accountId":3,"accountName":"Compte courant"},
         {"id":2,"date":"2026-07-02","description":"VIR SALAIRE","amount":2450.00,
          "nativeCurrency":"EUR","isManual":false,"txType":"DEPOSIT","categoryName":"Revenus",
          "merchantLabel":null,"counterparty":null,"accountId":3,"accountName":"Compte courant"}]
        """
        let txs = try JSONDecoder.picsou.decode([Transaction].self, from: Data(json.utf8))
        XCTAssertEqual(txs.count, 2)
        XCTAssertEqual(txs[0].displayLabel, "Carrefour")     // merchantLabel wins
        XCTAssertTrue(txs[0].isExpense)
        XCTAssertEqual(txs[1].displayLabel, "VIR SALAIRE")   // falls back to description
        XCTAssertFalse(txs[1].isExpense)
    }

    func testDemoAccountsDataSource_isPopulated() async throws {
        let ds = DemoAccountsDataSource()
        let account = try await ds.account(id: 2)
        XCTAssertEqual(account.type, .pea)
        let holdings = try await ds.holdings(id: 2)
        XCTAssertFalse(holdings.isEmpty)
        let txs = try await ds.transactions(id: 2)
        XCTAssertFalse(txs.isEmpty)
        let loan = try await ds.loanSummary(id: 99)
        XCTAssertFalse(loan.schedule.isEmpty)
    }

    func testDemoAddCash_isSignedManualTx() async throws {
        let ds = DemoAccountsDataSource()
        let tx = try await ds.addCash(accountId: 3, TransactionRequest(
            date: "2026-07-04", description: "Test", amount: -12.5,
            txType: "WITHDRAWAL", currency: "EUR", categoryId: nil))
        XCTAssertTrue(tx.manual)
        XCTAssertTrue(tx.isExpense)
        XCTAssertEqual(tx.accountId, 3)
    }

    func testApiPost_decodesResponseBody() async throws {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        suite.set("https://test.local", forKey: ServerConfig.baseURLDefaultsKey)
        let serverConfig = ServerConfig(defaults: suite)
        let tokenStore = InMemoryTokenStore()
        tokenStore.save(TokenSet(accessToken: "tok", refreshToken: "r",
                                 accessTokenExpiry: Date().addingTimeInterval(3600)))
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)
        let oauth = OAuthService(serverConfig: serverConfig, session: session)
        let api = APIClient(serverConfig: serverConfig, tokenStore: tokenStore, oauth: oauth, session: session)

        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            return MockURLProtocol.status(request, 201, json:
                #"{"id":42,"date":"2026-07-04","description":"Test","amount":-12.5,"isManual":true,"accountId":3,"accountName":"Compte courant"}"#)
        }

        let request = TransactionRequest(date: "2026-07-04", description: "Test", amount: -12.5,
                                         txType: "WITHDRAWAL", currency: "EUR", categoryId: nil)
        let tx: Transaction = try await api.post("api/accounts/3/transactions", body: request)
        XCTAssertEqual(tx.id, 42)
        XCTAssertTrue(tx.manual)
        XCTAssertTrue(tx.isExpense)
    }
}
