import Foundation

/// Mirrors backend `SessionResponse.Kind`.
enum SessionKind: String, LenientEnum {
    case rememberMe = "REMEMBER_ME"
    case iosApp = "IOS_APP"
    case unknown = "UNKNOWN"
}

/// Mirrors backend `SessionResponse` (GET /api/auth/sessions): a browser "Remember Me" session or
/// an iOS app sign-in. `id` is opaque; it goes back unchanged to `DELETE /api/auth/sessions/{id}`.
struct SessionInfo: Decodable, Identifiable, Equatable {
    let id: String
    let kind: SessionKind
    let userAgent: String?
    let ipPrefix: String?
    let createdAt: String?
    let lastUsedAt: String?
    let expiresAt: String?
    let trustedFor2fa: Bool
    let current: Bool
}

struct ChangeUsernameRequest: Encodable { let newUsername: String }
struct ChangePasswordRequest: Encodable { let currentPassword: String; let newPassword: String }

/// Mirrors backend `MfaStatusResponse` (GET /api/auth/mfa/status).
struct MfaStatus: Decodable, Equatable {
    let enabled: Bool
    let enrolledAt: String?
    let remainingRecoveryCodes: Int
}

/// Mirrors backend `EnrollInitResponse` (POST /api/auth/mfa/enroll/init).
struct MfaEnrollInit: Decodable, Equatable {
    let qrCodeDataUri: String?
    let secret: String
}

fileprivate struct RecoveryCodesResponse: Decodable { let recoveryCodes: [String] }
fileprivate struct EnrollInitBody: Encodable { let currentPassword: String }
fileprivate struct EnrollVerifyBody: Encodable { let code: String }
fileprivate struct DisableMfaBody: Encodable { let currentPassword: String; let code: String }
fileprivate struct RegenerateBody: Encodable { let currentPassword: String; let code: String }

/// Profile + session + 2FA settings (auth endpoints).
protocol SettingsDataSource: Sendable {
    func sessions() async throws -> [SessionInfo]
    func revokeSession(id: String) async throws
    func revokeOtherSessions() async throws
    func changeUsername(_ newUsername: String) async throws -> String
    func changePassword(current: String, new: String) async throws
    func mfaStatus() async throws -> MfaStatus
    func mfaEnrollInit(password: String) async throws -> MfaEnrollInit
    func mfaEnrollVerify(code: String) async throws -> [String]
    func mfaDisable(password: String, code: String) async throws
    func mfaRegenerateCodes(password: String, code: String) async throws -> [String]
}

struct LiveSettingsDataSource: SettingsDataSource {
    let api: APIClient

    func sessions() async throws -> [SessionInfo] { try await api.get("api/auth/sessions") }
    func revokeSession(id: String) async throws { _ = try await api.delete("api/auth/sessions/\(id)") }
    func revokeOtherSessions() async throws { _ = try await api.delete("api/auth/sessions") }

    func changeUsername(_ newUsername: String) async throws -> String {
        let response: [String: String] = try await api.patch("api/auth/username", body: ChangeUsernameRequest(newUsername: newUsername))
        return response["username"] ?? newUsername
    }
    func changePassword(current: String, new: String) async throws {
        let _: [String: String] = try await api.post("api/auth/change-password",
                                                      body: ChangePasswordRequest(currentPassword: current, newPassword: new))
    }

    func mfaStatus() async throws -> MfaStatus { try await api.get("api/auth/mfa/status") }
    func mfaEnrollInit(password: String) async throws -> MfaEnrollInit {
        try await api.post("api/auth/mfa/enroll/init", body: EnrollInitBody(currentPassword: password))
    }
    func mfaEnrollVerify(code: String) async throws -> [String] {
        let response: RecoveryCodesResponse = try await api.post("api/auth/mfa/enroll/verify", body: EnrollVerifyBody(code: code))
        return response.recoveryCodes
    }
    func mfaDisable(password: String, code: String) async throws {
        try await api.postVoid("api/auth/mfa/disable", body: DisableMfaBody(currentPassword: password, code: code))
    }
    func mfaRegenerateCodes(password: String, code: String) async throws -> [String] {
        let response: RecoveryCodesResponse = try await api.post("api/auth/mfa/recovery-codes/regenerate",
                                                                  body: RegenerateBody(currentPassword: password, code: code))
        return response.recoveryCodes
    }
}

struct DemoSettingsDataSource: SettingsDataSource {
    func sessions() async throws -> [SessionInfo] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return [
            SessionInfo(id: "0b6f3c1e-4c1d-4a8e-9f0a-2d7e5b1c9a11", kind: .iosApp, userAgent: nil, ipPrefix: nil,
                        createdAt: "2026-07-01T09:00:00Z", lastUsedAt: "2026-07-04T08:00:00Z",
                        expiresAt: "2026-08-01T09:00:00Z", trustedFor2fa: false, current: true),
            SessionInfo(id: "2", kind: .rememberMe, userAgent: "Safari · macOS", ipPrefix: "192.168.1.x",
                        createdAt: "2026-06-20T10:00:00Z", lastUsedAt: "2026-07-02T19:30:00Z",
                        expiresAt: "2026-07-20T10:00:00Z", trustedFor2fa: false, current: false),
            SessionInfo(id: "3", kind: .rememberMe, userAgent: "Chrome · Windows", ipPrefix: "10.0.0.x",
                        createdAt: "2026-06-10T14:00:00Z", lastUsedAt: "2026-06-28T11:00:00Z",
                        expiresAt: "2026-07-10T14:00:00Z", trustedFor2fa: false, current: false),
        ]
    }
    func revokeSession(id: String) async throws {}
    func revokeOtherSessions() async throws {}
    func changeUsername(_ newUsername: String) async throws -> String { newUsername }
    func changePassword(current: String, new: String) async throws {}

    func mfaStatus() async throws -> MfaStatus {
        try? await Task.sleep(nanoseconds: 150_000_000)
        return MfaStatus(enabled: false, enrolledAt: nil, remainingRecoveryCodes: 0)
    }
    func mfaEnrollInit(password: String) async throws -> MfaEnrollInit {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return MfaEnrollInit(qrCodeDataUri: nil, secret: "JBSWY3DPEHPK3PXP")
    }
    func mfaEnrollVerify(code: String) async throws -> [String] { Self.demoRecoveryCodes }
    func mfaDisable(password: String, code: String) async throws {}
    func mfaRegenerateCodes(password: String, code: String) async throws -> [String] { Self.demoRecoveryCodes }

    private static let demoRecoveryCodes = [
        "A1B2-C3D4", "E5F6-G7H8", "J9K0-L1M2", "N3P4-Q5R6",
        "S7T8-U9V0", "W1X2-Y3Z4", "B5C6-D7E8", "F9G0-H1J2",
    ]
}
