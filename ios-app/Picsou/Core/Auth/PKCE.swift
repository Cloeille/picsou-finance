import Foundation
import CryptoKit

/// RFC 7636 PKCE parameters. The `verifier` never leaves the app until the token exchange; only the
/// derived `challenge` is put on the authorization request.
struct PKCE: Equatable {
    let verifier: String
    let challenge: String

    static func generate() -> PKCE {
        let verifier = randomVerifier()
        return PKCE(verifier: verifier, challenge: codeChallenge(for: verifier))
    }

    /// 32 random bytes → base64url (43 chars), within RFC 7636's 43–128 range.
    static func randomVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URL(Data(bytes))
    }

    /// S256 transform: base64url(SHA256(verifier)).
    static func codeChallenge(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return base64URL(Data(digest))
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
