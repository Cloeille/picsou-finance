import XCTest
@testable import Picsou

final class DemoDashboardDataSourceTests: XCTestCase {

    func testDemoDashboardPopulatesEverySection() async throws {
        let dashboard = try await DemoDashboardDataSource().fetch(range: .year)

        XCTAssertGreaterThan(dashboard.totalNetWorth.doubleValue, 0)
        XCTAssertGreaterThanOrEqual(dashboard.netWorthHistory.count, 2)
        XCTAssertFalse(dashboard.distribution.isEmpty)
        XCTAssertFalse(dashboard.liabilities.isEmpty)      // a loan, so the Passifs section shows
        XCTAssertFalse(dashboard.goalSummaries.isEmpty)

        // Net worth is assets minus liabilities.
        let assets = dashboard.distribution.reduce(Decimal(0)) { $0 + $1.balanceEur }
        XCTAssertEqual(
            (assets - dashboard.totalLiabilities).doubleValue,
            dashboard.totalNetWorth.doubleValue,
            accuracy: 0.01
        )
    }

    func testRangeControlsHistoryLength() async throws {
        let source = DemoDashboardDataSource()
        let short = try await source.fetch(range: .month)
        let long = try await source.fetch(range: .year)

        XCTAssertLessThanOrEqual(short.netWorthHistory.count, long.netWorthHistory.count)
        XCTAssertEqual(long.netWorthHistory.count, 12)
    }

    func testAppConfigIsNotDemoInTestBuild() {
        // The DEMO condition is only set for the "Picsou Demo" scheme; tests run the normal build.
        XCTAssertFalse(AppConfig.isDemo)
    }
}
