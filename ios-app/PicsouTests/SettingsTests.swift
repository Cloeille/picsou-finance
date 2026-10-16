import XCTest
@testable import Picsou

@MainActor
final class SettingsTests: XCTestCase {

    func testSessionDecoding() throws {
        let json = #"[{"id":"1","kind":"REMEMBER_ME","userAgent":"Safari · macOS","ipPrefix":"192.168.1","createdAt":"2026-07-01T09:00:00.5Z","lastUsedAt":"2026-07-04T08:00:00.25Z","expiresAt":"2026-08-01T09:00:00Z","trustedFor2fa":true,"current":true}]"#
        let sessions = try JSONDecoder.picsou.decode([SessionInfo].self, from: Data(json.utf8))
        XCTAssertEqual(sessions.count, 1)
        XCTAssertTrue(sessions[0].current)
        XCTAssertEqual(sessions[0].id, "1")
        XCTAssertEqual(sessions[0].userAgent, "Safari · macOS")
    }

    func testChangePasswordRequestEncodes() throws {
        let data = try JSONEncoder.picsou.encode(ChangePasswordRequest(currentPassword: "old", newPassword: "newpass12"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(json["currentPassword"], "old")
        XCTAssertEqual(json["newPassword"], "newpass12")
    }

    func testDemoSettingsSessions_populatedWithCurrent() async throws {
        let sessions = try await DemoSettingsDataSource().sessions()
        XCTAssertFalse(sessions.isEmpty)
        XCTAssertTrue(sessions.contains { $0.current })
    }

    func testJWTPayloadDecodesClaims() {
        let payload = Data(#"{"sub":"chloe","role":"ADMIN"}"#.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let claims = JWT.payload(of: "aaa.\(payload).bbb")
        XCTAssertEqual(claims?["sub"] as? String, "chloe")
        XCTAssertEqual(claims?["role"] as? String, "ADMIN")
    }

    func testBankConnectionDecoding_ignoresAuditKeys() throws {
        let json = #"[{"id":1,"institutionName":"Crédit Agricole","institutionId":"ca_fr","status":"LINKED","authLink":null,"lastSyncedAt":"2026-07-04T07:30:00Z","createdAt":"2026-06-01T00:00:00Z"}]"#
        let connections = try JSONDecoder.picsou.decode([BankConnection].self, from: Data(json.utf8))
        XCTAssertEqual(connections.count, 1)
        XCTAssertEqual(connections[0].status, "LINKED")
        XCTAssertEqual(connections[0].institutionName, "Crédit Agricole")
    }

    func testDemoSyncConnections_hasLinkedAndFailed() async throws {
        let connections = try await DemoSyncDataSource().connections()
        XCTAssertTrue(connections.contains { $0.status == "LINKED" })
        XCTAssertTrue(connections.contains { $0.status == "FAILED" })
    }

    func testMfaStatusDecoding() throws {
        let json = #"{"enabled":true,"enrolledAt":"2026-06-01T10:00:00Z","remainingRecoveryCodes":6}"#
        let status = try JSONDecoder.picsou.decode(MfaStatus.self, from: Data(json.utf8))
        XCTAssertTrue(status.enabled)
        XCTAssertEqual(status.remainingRecoveryCodes, 6)
    }

    func testDemoMfaEnrollFlow_returnsSecretThenCodes() async throws {
        let ds = DemoSettingsDataSource()
        let status = try await ds.mfaStatus()
        XCTAssertFalse(status.enabled)
        let enroll = try await ds.mfaEnrollInit(password: "pw")
        XCTAssertFalse(enroll.secret.isEmpty)
        let codes = try await ds.mfaEnrollVerify(code: "123456")
        XCTAssertFalse(codes.isEmpty)
    }

    func testQRCodeGeneration_returnsImage() {
        XCTAssertNotNil(QRCode.image(from: "otpauth://totp/Picsou:chloe?secret=ABC&issuer=Picsou"))
    }
}
