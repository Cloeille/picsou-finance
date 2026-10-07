import Foundation

/// Minimal, unverified JWT payload reader — used only to display identity (username/role) from the
/// access token. Does NOT verify the signature; never use it for authorization decisions.
enum JWT {
    static func payload(of token: String) -> [String: Any]? {
        let segments = token.split(separator: ".")
        guard segments.count >= 2, let data = base64URLDecode(String(segments[1])) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func base64URLDecode(_ value: String) -> Data? {
        var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }
}
