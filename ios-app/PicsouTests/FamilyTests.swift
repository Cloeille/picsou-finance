import XCTest
@testable import Picsou

@MainActor
final class FamilyTests: XCTestCase {

    func testFamilyMemberDecoding() throws {
        let json = ##"[{"id":1,"displayName":"Chloé","avatarColor":"#6366F1","managed":false,"hasLogin":true,"activated":true,"loginName":"chloe","mfaEnabled":true}]"##
        let members = try JSONDecoder.picsou.decode([FamilyMember].self, from: Data(json.utf8))
        XCTAssertEqual(members.count, 1)
        XCTAssertEqual(members[0].displayName, "Chloé")
        XCTAssertTrue(members[0].mfaEnabled)
        XCTAssertFalse(members[0].managed)
    }

    func testDemoFamilyMembers_hasManagedAndIndependent() async throws {
        let members = try await DemoFamilyDataSource().members()
        XCTAssertTrue(members.contains { $0.managed })
        XCTAssertTrue(members.contains { !$0.managed })
    }
}
