import XCTest
@testable import Picsou

final class DashboardDecodingTests: XCTestCase {

    /// A representative payload: null totalMonthlyPayment, a NetWorthPoint carrying the extra
    /// `accounts` map (which the model omits and must ignore), and one of each collection.
    private let sample = """
    {
      "totalNetWorth": 152340.55,
      "totalLiabilities": 18000.00,
      "totalMonthlyPayment": null,
      "netWorthHistory": [
        {"date":"2026-06-01","total":150000.00,"invested":120000.00,"pnl":3000.00,
         "accounts":{"1":{"total":1.0,"invested":1.0,"pnl":0.0}}},
        {"date":"2026-07-01","total":152340.55,"invested":121000.00,"pnl":3500.25}
      ],
      "distribution": [
        {"accountId":1,"name":"Livret A","color":"#4F46E5","balanceEur":10000.00,
         "percentage":40.0,"accountType":"SAVINGS","hasHoldings":false},
        {"accountId":2,"name":"PEA","color":"#10B981","balanceEur":15000.00,
         "percentage":60.0,"accountType":"PEA","hasHoldings":true}
      ],
      "liabilities": [
        {"accountId":3,"name":"Prêt immo","color":"#EF4444","balanceEur":18000.00,
         "percentage":100.0,"accountType":"LOAN","hasHoldings":false,
         "monthlyPayment":650.00,"percentPaid":42.5}
      ],
      "goalSummaries": [
        {"id":7,"name":"Vacances","targetAmount":5000.00,"currentTotal":1500.00,"percentComplete":30.0}
      ]
    }
    """

    func testDecodesFullDashboard() throws {
        let data = Data(sample.utf8)
        let dashboard = try JSONDecoder.picsou.decode(DashboardResponse.self, from: data)

        XCTAssertEqual(dashboard.totalNetWorth.doubleValue, 152340.55, accuracy: 0.01)
        XCTAssertNil(dashboard.totalMonthlyPayment)

        // Extra keys on the first history point (`accounts`) are ignored, both points decode.
        XCTAssertEqual(dashboard.netWorthHistory.count, 2)
        XCTAssertEqual(dashboard.netWorthHistory.last?.pnl.doubleValue ?? 0, 3500.25, accuracy: 0.01)
        XCTAssertNotNil(dashboard.netWorthHistory.first?.day)

        XCTAssertEqual(dashboard.distribution.count, 2)
        XCTAssertEqual(dashboard.distribution.first?.type, .savings)
        XCTAssertEqual(dashboard.distribution.last?.type, .pea)

        XCTAssertEqual(dashboard.liabilities.count, 1)
        XCTAssertEqual(dashboard.liabilities.first?.type, .loan)
        XCTAssertEqual(dashboard.liabilities.first?.percentPaid ?? 0, 42.5, accuracy: 0.01)

        XCTAssertEqual(dashboard.goalSummaries.first?.name, "Vacances")
        XCTAssertEqual(dashboard.goalSummaries.first?.percentComplete ?? 0, 30, accuracy: 0.01)
    }

    func testUnknownAccountTypeFallsBackToOther() {
        XCTAssertEqual(AccountType(raw: "SOMETHING_NEW"), .other)
        XCTAssertEqual(AccountType(raw: "PEA"), .pea)
    }
}
