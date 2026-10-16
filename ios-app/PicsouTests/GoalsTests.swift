import XCTest
@testable import Picsou

@MainActor
final class GoalsTests: XCTestCase {

    func testGoalRequestEncodesFields() throws {
        let request = GoalRequest(name: "Vacances", targetAmount: 5000, deadline: "2027-01-01", accountIds: [1, 3])
        let data = try JSONEncoder.picsou.encode(request)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["name"] as? String, "Vacances")
        XCTAssertEqual(json["deadline"] as? String, "2027-01-01")
        XCTAssertEqual((json["accountIds"] as? [Any])?.count, 2)
    }

    func testDemoGoalsCreate_returnsGoalFromRequest() async throws {
        let goal = try await DemoGoalsDataSource().create(
            GoalRequest(name: "Fonds", targetAmount: 8000, deadline: "2027-06-01", accountIds: [1]))
        XCTAssertEqual(goal.name, "Fonds")
        XCTAssertEqual(goal.targetAmount, 8000)
        XCTAssertEqual(goal.deadline, "2027-06-01")
    }

    func testDemoGoalsAccounts_isPopulated() async throws {
        let accounts = try await DemoGoalsDataSource().accounts()
        XCTAssertFalse(accounts.isEmpty)
    }

    func testGoalProgressDecodesLinkedAccounts() throws {
        let json = #"{"id":5,"name":"Voiture","targetAmount":20000,"currentTotal":5000,"percentComplete":25,"deadline":"2028-01-01","isOnTrack":true,"accounts":[{"id":3},{"id":7}]}"#
        let goal = try JSONDecoder.picsou.decode(GoalProgress.self, from: Data(json.utf8))
        XCTAssertEqual(goal.accountIds, [3, 7])
    }

    func testGoalProgressWithoutAccountsKey_decodesToEmpty() throws {
        let json = #"{"id":5,"name":"Voiture","targetAmount":20000,"currentTotal":5000,"percentComplete":25}"#
        let goal = try JSONDecoder.picsou.decode(GoalProgress.self, from: Data(json.utf8))
        XCTAssertTrue(goal.accountIds.isEmpty)
    }

    func testDemoGoalsUpdate_returnsUpdatedGoal() async throws {
        let goal = try await DemoGoalsDataSource().update(
            id: 5, GoalRequest(name: "Voiture", targetAmount: 20000, deadline: "2028-01-01", accountIds: [3]))
        XCTAssertEqual(goal.id, 5)
        XCTAssertEqual(goal.name, "Voiture")
    }
}
