import Foundation

/// Mirrors the backend `Requisition` (GET /api/sync/status) — an Enable Banking bank connection.
struct BankConnection: Decodable, Identifiable, Equatable {
    let id: Int64
    let institutionName: String?
    let institutionId: String?
    let status: String          // CREATED | LINKED | EXPIRED | FAILED
    let authLink: String?
    let lastSyncedAt: String?
}

/// The browser/sidecar connectors that each expose `GET /api/<path>/status` with the same
/// `SessionStatusResponse` shape. Connecting one needs its web wizard (credentials, 2FA, identity
/// selection), so the app only shows their state.
enum SidecarConnector: String, CaseIterable {
    case amex, bourso, bourseDirect = "bourse-direct", amundi, fortuneo, corum, sofidy

    var name: String {
        switch self {
        case .amex: return "American Express"
        case .bourso: return "BoursoBank"
        case .bourseDirect: return "Bourse Direct"
        case .amundi: return "Amundi Épargne Salariale"
        case .fortuneo: return "Fortuneo"
        case .corum: return "CORUM"
        case .sofidy: return "Sofidy"
        }
    }

    var statusPath: String { "api/\(rawValue)/status" }
}

/// Mirrors a sidecar's `SessionStatusResponse`. `isActive` keeps its `is` prefix on the wire (a
/// record component, see `Account.manual`). Everything else is omitted until a session exists.
struct SidecarStatus: Decodable, Equatable {
    let isActive: Bool
    let syncStatus: String?             // IDLE | QUEUED | RUNNING | SUCCESS | FAILED
    let lastSyncCompletedAt: String?
}

struct SidecarConnection: Identifiable, Equatable {
    let connector: SidecarConnector
    let status: SidecarStatus
    var id: String { connector.rawValue }
}

/// Bank-connection listing + safe actions (retry a failed link, delete). Linking / reconnecting a
/// bank needs the web OAuth flow, so it's intentionally not offered here.
protocol SyncDataSource: Sendable {
    func connections() async throws -> [BankConnection]
    func retry(id: Int64) async throws
    func delete(id: Int64) async throws
    /// The connected sidecars only. A connector whose status call fails (sidecar not deployed,
    /// older server) is left out rather than failing the screen.
    func sidecarConnections() async -> [SidecarConnection]
}

struct LiveSyncDataSource: SyncDataSource {
    let api: APIClient

    func connections() async throws -> [BankConnection] { try await api.get("api/sync/status") }
    func retry(id: Int64) async throws { let _: [Account] = try await api.post("api/sync/\(id)/retry") }
    func delete(id: Int64) async throws { _ = try await api.delete("api/sync/\(id)") }

    func sidecarConnections() async -> [SidecarConnection] {
        await withTaskGroup(of: SidecarConnection?.self) { group in
            for connector in SidecarConnector.allCases {
                group.addTask {
                    guard let status: SidecarStatus = try? await api.get(connector.statusPath),
                          status.isActive else { return nil }
                    return SidecarConnection(connector: connector, status: status)
                }
            }
            var found: [SidecarConnection] = []
            for await connection in group { if let connection { found.append(connection) } }
            return SidecarConnector.allCases.compactMap { c in found.first { $0.connector == c } }
        }
    }
}

struct DemoSyncDataSource: SyncDataSource {
    func connections() async throws -> [BankConnection] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return DemoData.bankConnections()
    }
    func retry(id: Int64) async throws {}
    func delete(id: Int64) async throws {}
    func sidecarConnections() async -> [SidecarConnection] {
        [SidecarConnection(connector: .amex, status: SidecarStatus(
            isActive: true, syncStatus: "SUCCESS", lastSyncCompletedAt: "2026-07-04T07:30:00.412Z"))]
    }
}
