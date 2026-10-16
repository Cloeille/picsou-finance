import Foundation

enum APIError: Error, LocalizedError, Equatable {
    case notConfigured
    case invalidURL
    case network(String)
    case unauthorized
    case http(status: Int, body: String?)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "No server configured."
        case .invalidURL: return "That doesn't look like a valid URL."
        case .network(let message): return "Network error: \(message)"
        case .unauthorized: return "Your session has expired. Please sign in again."
        case .http(let status, _): return "The server returned an error (HTTP \(status))."
        case .decoding: return "The server sent an unexpected response."
        }
    }

    /// The `detail` of the ProblemDetail body of an `.http` error, when the server sent one.
    var problemDetail: String? {
        guard case .http(_, let body) = self else { return nil }
        return ProblemDetail(body: body)?.detail
    }
}

/// RFC 9457 body of a backend error response.
struct ProblemDetail: Decodable {
    /// `type` of the 401 the backend's security entry point sends when the access token is
    /// missing, expired or revoked (`SecurityConfig.AUTHENTICATION_REQUIRED_TYPE`). A 401 without
    /// it comes from a credential check (wrong current password) and is an ordinary error.
    static let authenticationRequiredType = "urn:picsou:problem:authentication-required"

    let type: String?
    let detail: String?

    init?(body: String?) {
        guard let data = body?.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(ProblemDetail.self, from: data) else { return nil }
        self = decoded
    }
}
