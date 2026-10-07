import XCTest
@testable import Picsou

final class PKCETests: XCTestCase {

    /// RFC 7636, Appendix B: the canonical verifier → S256 challenge vector.
    func testCodeChallenge_matchesRFC7636Vector() {
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        let challenge = PKCE.codeChallenge(for: verifier)
        XCTAssertEqual(challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    func testGenerate_producesBase64URLVerifierOfExpectedLength() {
        let pkce = PKCE.generate()
        // 32 bytes → 43 base64url chars (no padding).
        XCTAssertEqual(pkce.verifier.count, 43)
        XCTAssertFalse(pkce.verifier.contains("+"))
        XCTAssertFalse(pkce.verifier.contains("/"))
        XCTAssertFalse(pkce.verifier.contains("="))
    }

    func testGenerate_challengeIsDerivedFromVerifier() {
        let pkce = PKCE.generate()
        XCTAssertEqual(pkce.challenge, PKCE.codeChallenge(for: pkce.verifier))
        XCTAssertFalse(pkce.challenge.contains("="))
    }

    func testVerifiers_areUnique() {
        XCTAssertNotEqual(PKCE.randomVerifier(), PKCE.randomVerifier())
    }
}
