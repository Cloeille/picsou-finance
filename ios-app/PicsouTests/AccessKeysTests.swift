import XCTest
@testable import Picsou

@MainActor
final class AccessKeysTests: XCTestCase {

    func testAccessKeyDecoding() throws {
        let json = #"[{"id":1,"name":"Claude","keyPrefix":"pk_abc","scopes":["accounts:read","goals:read"],"lastUsedAt":null,"expiresAt":null,"revokedAt":null,"createdAt":"2026-06-01T00:00:00Z"}]"#
        let keys = try JSONDecoder.picsou.decode([AccessKey].self, from: Data(json.utf8))
        XCTAssertEqual(keys[0].scopes.count, 2)
        XCTAssertFalse(keys[0].isRevoked)
    }

    func testDemoAccessKeysCreate_returnsSecretOnce() async throws {
        let created = try await DemoAccessKeysDataSource().create(name: "Test", scopes: ["accounts:read"])
        XCTAssertFalse(created.secret.isEmpty)
        XCTAssertEqual(created.key.name, "Test")
        XCTAssertEqual(created.key.scopes, ["accounts:read"])
    }

    func testDemoAccessKeysList_includesRevoked() async throws {
        let keys = try await DemoAccessKeysDataSource().list()
        XCTAssertFalse(keys.isEmpty)
        XCTAssertTrue(keys.contains { $0.isRevoked })
    }
}
