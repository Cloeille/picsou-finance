import Foundation

/// Body for POST/PUT /api/goals. `deadline` is a `yyyy-MM-dd` string in the future; `accountIds`
/// must be non-empty.
struct GoalRequest: Encodable {
    let name: String
    let targetAmount: Decimal
    let deadline: String
    let accountIds: [Int64]
}

/// Goals write-side + the account list for the linked-accounts picker.
protocol GoalsDataSource: Sendable {
    func accounts() async throws -> [Account]
    func create(_ request: GoalRequest) async throws -> GoalProgress
    func update(id: Int64, _ request: GoalRequest) async throws -> GoalProgress
    func delete(id: Int64) async throws
}

struct LiveGoalsDataSource: GoalsDataSource {
    let api: APIClient

    func accounts() async throws -> [Account] { try await api.get("api/accounts") }
    func create(_ request: GoalRequest) async throws -> GoalProgress { try await api.post("api/goals", body: request) }
    func update(id: Int64, _ request: GoalRequest) async throws -> GoalProgress { try await api.put("api/goals/\(id)", body: request) }
    func delete(id: Int64) async throws { _ = try await api.delete("api/goals/\(id)") }
}

struct DemoGoalsDataSource: GoalsDataSource {
    func accounts() async throws -> [Account] {
        try? await Task.sleep(nanoseconds: 150_000_000)
        return DemoData.accountsList()
    }
    func create(_ request: GoalRequest) async throws -> GoalProgress {
        DemoData.makeGoal(from: request, id: Int64.random(in: 100...999))
    }
    func update(id: Int64, _ request: GoalRequest) async throws -> GoalProgress {
        DemoData.makeGoal(from: request, id: id)
    }
    func delete(id: Int64) async throws {}
}
