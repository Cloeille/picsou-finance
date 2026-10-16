import Foundation

/// Where the dashboard gets its data. Mirrors the web app's demo interceptor: the UI is identical;
/// only the source swaps between the live API and canned mocks.
protocol DashboardDataSource: Sendable {
    func fetch(range: TimeRange) async throws -> DashboardResponse
}

/// Real source — a single `GET /api/dashboard?range=` through the authenticated API client.
struct LiveDashboardDataSource: DashboardDataSource {
    let api: APIClient

    func fetch(range: TimeRange) async throws -> DashboardResponse {
        try await api.get("api/dashboard", query: [URLQueryItem(name: "range", value: range.rawValue)])
    }
}

/// Demo source — returns mock data after a short delay, so the loading state is still exercised.
struct DemoDashboardDataSource: DashboardDataSource {
    func fetch(range: TimeRange) async throws -> DashboardResponse {
        try? await Task.sleep(nanoseconds: 350_000_000)
        return DemoData.dashboard(range: range)
    }
}
