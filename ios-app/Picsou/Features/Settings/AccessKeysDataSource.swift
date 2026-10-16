import Foundation

/// Mirrors backend `AccessKeyResponse` (GET /api/access-keys). The secret is never returned here.
struct AccessKey: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let keyPrefix: String
    let scopes: [String]
    let lastUsedAt: String?
    let expiresAt: String?
    let revokedAt: String?
    let createdAt: String?

    var isRevoked: Bool { revokedAt != nil }
}

/// Mirrors `AccessKeyCreatedResponse` — the one-time plaintext secret plus the created key.
struct AccessKeyCreated: Decodable, Equatable {
    let secret: String
    let key: AccessKey
}

struct AccessKeyCreateRequest: Encodable {
    let name: String
    let scopes: [String]
    let expiresAt: String?
}

/// The MCP scope allowlist, in the order of backend `mcp.Scopes.ALL` / frontend `ALL_SCOPES`. Labels
/// are the web's `accessKeys.scopes.*.label` (fr.json) plus the read/write side, which the web shows
/// as a group heading instead. `McpScopeTests` checks the ids against `Scopes.java`.
enum McpScope {
    static let all: [(id: String, label: String)] = [
        ("accounts:read", "Comptes — lecture"),
        ("transactions:read", "Transactions — lecture"),
        ("goals:read", "Objectifs — lecture"),
        ("dashboard:read", "Tableau de bord — lecture"),
        ("prices:read", "Cours — lecture"),
        ("family:read", "Famille — lecture"),
        ("sync:read", "État des synchronisations — lecture"),
        ("analysis:read", "Analyse du patrimoine — lecture"),
        ("budget:categories-read", "Catégories budgétaires — lecture"),
        ("budget:rules-read", "Règles de catégorisation — lecture"),
        ("budget:transactions-read", "Transactions budgétées — lecture"),
        ("budget:recurring-read", "Abonnements récurrents — lecture"),
        ("budget:envelopes-read", "Enveloppes budgétaires — lecture"),
        ("budget:dashboard-read", "Tableau de bord budget — lecture"),
        ("oauth2:discover", "Découverte OAuth2"),
        ("oauth2:session-status", "Statut de session"),
        ("accounts:write", "Comptes — écriture"),
        ("transactions:write", "Transactions — écriture"),
        ("goals:write", "Objectifs — écriture"),
        ("sync:trigger", "Synchronisation — déclencher"),
        ("budget:categories-write", "Catégories budgétaires — écriture"),
        ("budget:rules-write", "Règles de catégorisation — écriture"),
        ("budget:transactions-write", "Transactions budgétées — écriture"),
        ("budget:recurring-write", "Abonnements récurrents — écriture"),
        ("budget:envelopes-write", "Enveloppes budgétaires — écriture"),
    ]
    static func label(_ id: String) -> String { all.first { $0.id == id }?.label ?? id }
}

protocol AccessKeysDataSource: Sendable {
    func list() async throws -> [AccessKey]
    func create(name: String, scopes: [String]) async throws -> AccessKeyCreated
    func revoke(id: Int64) async throws
}

struct LiveAccessKeysDataSource: AccessKeysDataSource {
    let api: APIClient

    func list() async throws -> [AccessKey] { try await api.get("api/access-keys") }
    func create(name: String, scopes: [String]) async throws -> AccessKeyCreated {
        try await api.post("api/access-keys", body: AccessKeyCreateRequest(name: name, scopes: scopes, expiresAt: nil))
    }
    func revoke(id: Int64) async throws { _ = try await api.delete("api/access-keys/\(id)") }
}

struct DemoAccessKeysDataSource: AccessKeysDataSource {
    func list() async throws -> [AccessKey] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return DemoData.accessKeys()
    }
    func create(name: String, scopes: [String]) async throws -> AccessKeyCreated {
        let key = AccessKey(id: Int64.random(in: 100...999), name: name, keyPrefix: "pk_demo1234",
                            scopes: scopes, lastUsedAt: nil, expiresAt: nil, revokedAt: nil,
                            createdAt: "2026-07-05T00:00:00Z")
        return AccessKeyCreated(secret: "sk_live_demo_\(UUID().uuidString.prefix(16))", key: key)
    }
    func revoke(id: Int64) async throws {}
}
