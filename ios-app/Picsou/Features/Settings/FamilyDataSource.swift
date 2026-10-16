import Foundation

/// Mirrors backend `FamilyMemberResponse` (GET /api/family/members, admin only).
struct FamilyMember: Decodable, Identifiable, Equatable {
    let id: Int64
    let displayName: String
    let avatarColor: String?
    let managed: Bool
    let hasLogin: Bool
    let activated: Bool
    let loginName: String?
    let mfaEnabled: Bool
}

protocol FamilyDataSource: Sendable {
    func members() async throws -> [FamilyMember]
}

struct LiveFamilyDataSource: FamilyDataSource {
    let api: APIClient
    func members() async throws -> [FamilyMember] { try await api.get("api/family/members") }
}

struct DemoFamilyDataSource: FamilyDataSource {
    func members() async throws -> [FamilyMember] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return DemoData.familyMembers()
    }
}
