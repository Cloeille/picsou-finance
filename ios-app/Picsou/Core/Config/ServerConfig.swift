import Foundation

/// Stores and validates the self-hosted instance URL entered on first launch.
final class ServerConfig: @unchecked Sendable {
    static let baseURLDefaultsKey = "picsou.instanceBaseURL"

    private let defaults: UserDefaults
    private let key = ServerConfig.baseURLDefaultsKey

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var baseURL: URL? {
        guard let s = defaults.string(forKey: key) else { return nil }
        return URL(string: s)
    }

    /// Normalize a user-entered URL, confirm the instance answers `/actuator/health`, and persist it.
    func validateAndSave(_ raw: String, session: URLSession) async throws {
        guard let base = Self.normalize(raw) else { throw APIError.invalidURL }

        let health = base.appendingPathComponent("actuator/health")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(from: health)
        } catch {
            throw APIError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.network("No response") }
        guard http.statusCode == 200 else {
            throw APIError.http(status: http.statusCode, body: nil)
        }
        // Actuator health returns {"status":"UP"} — reject anything else so an unrelated 200 page
        // (e.g. a captive portal) can't masquerade as a Picsou instance.
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let status = obj["status"] as? String, status != "UP" {
            throw APIError.http(status: 503, body: status)
        }

        defaults.set(base.absoluteString, forKey: key)
    }

    func clear() {
        defaults.removeObject(forKey: key)
    }

    /// Accepts "example.com", "example.com/", "https://example.com" → an http(s) URL, no trailing slash.
    static func normalize(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if !s.contains("://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        guard let url = URL(string: s),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host != nil else { return nil }
        return url
    }
}
