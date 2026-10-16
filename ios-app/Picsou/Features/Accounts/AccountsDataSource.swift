import Foundation

/// Account-detail data: live via the API, or canned in the demo build. Mirrors the DashboardDataSource
/// pattern (live/demo selected by `AppState.isDemo`).
protocol AccountsDataSource: Sendable {
    func account(id: Int64) async throws -> Account
    func holdings(id: Int64) async throws -> [Holding]
    func transactions(id: Int64) async throws -> [Transaction]
    func loanSummary(id: Int64) async throws -> LoanSchedule
    func addCash(accountId: Int64, _ request: TransactionRequest) async throws -> Transaction
}

struct LiveAccountsDataSource: AccountsDataSource {
    let api: APIClient

    func account(id: Int64) async throws -> Account {
        try await api.get("api/accounts/\(id)")
    }
    func holdings(id: Int64) async throws -> [Holding] {
        try await api.get("api/accounts/\(id)/holdings")
    }
    func transactions(id: Int64) async throws -> [Transaction] {
        try await api.get("api/accounts/\(id)/transactions")
    }
    func loanSummary(id: Int64) async throws -> LoanSchedule {
        try await api.get("api/accounts/\(id)/loan-summary")
    }
    func addCash(accountId: Int64, _ request: TransactionRequest) async throws -> Transaction {
        try await api.post("api/accounts/\(accountId)/transactions", body: request)
    }
}

struct DemoAccountsDataSource: AccountsDataSource {
    func account(id: Int64) async throws -> Account {
        try? await Task.sleep(nanoseconds: 250_000_000)
        return DemoData.account(id: id)
    }
    func holdings(id: Int64) async throws -> [Holding] { DemoData.holdings(id: id) }
    func transactions(id: Int64) async throws -> [Transaction] {
        try? await Task.sleep(nanoseconds: 250_000_000)
        return DemoData.transactions(id: id)
    }
    func loanSummary(id: Int64) async throws -> LoanSchedule { DemoData.loanSchedule() }
    func addCash(accountId: Int64, _ request: TransactionRequest) async throws -> Transaction {
        DemoData.makeTransaction(from: request, accountId: accountId)
    }
}
