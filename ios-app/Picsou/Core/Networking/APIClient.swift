import Foundation

extension URLSessionConfiguration {
    /// For every API and token call. The backend answers a username or password change with web
    /// `access_token` cookies; a stored copy would ride along on later requests long after it
    /// expired or after another account signed in, so the app never keeps or sends cookies.
    static var cookieless: URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        return config
    }
}

extension URLSession {
    static let cookieless = URLSession(configuration: .cookieless)
}

/// Thin JSON client for the Picsou REST API. Injects the Bearer access token, refreshes it once when
/// the server rejects it (and proactively when it's about to expire), and asks `AppState` to sign out
/// when refresh ultimately fails.
final class APIClient: @unchecked Sendable {
    private let serverConfig: ServerConfig
    private let tokenStore: TokenStoring
    private let refresher: TokenRefresher
    private let session: URLSession

    /// Invoked (possibly off the main actor) when a request can no longer be authenticated.
    var onAuthenticationLost: (@Sendable () -> Void)?

    init(serverConfig: ServerConfig, tokenStore: TokenStoring, oauth: OAuthService, session: URLSession = .cookieless) {
        self.serverConfig = serverConfig
        self.tokenStore = tokenStore
        self.session = session
        self.refresher = TokenRefresher(oauth: oauth, tokenStore: tokenStore)
    }

    func get<T: Decodable>(_ path: String, query: [URLQueryItem] = []) async throws -> T {
        try decode(try await requestData(path: path, query: query, method: "GET", body: nil))
    }

    func post<T: Decodable, B: Encodable>(_ path: String, body: B) async throws -> T {
        try decode(try await requestData(path: path, query: [], method: "POST", body: encode(body)))
    }

    /// POST with no request body (e.g. action endpoints like `/api/sync/{id}/retry`).
    func post<T: Decodable>(_ path: String) async throws -> T {
        try decode(try await requestData(path: path, query: [], method: "POST", body: nil))
    }

    /// POST a body to an endpoint that returns no content (204), ignoring the response.
    @discardableResult
    func postVoid<B: Encodable>(_ path: String, body: B) async throws -> Data {
        try await requestData(path: path, query: [], method: "POST", body: encode(body))
    }

    func put<T: Decodable, B: Encodable>(_ path: String, body: B) async throws -> T {
        try decode(try await requestData(path: path, query: [], method: "PUT", body: encode(body)))
    }

    /// PUT a body to an endpoint that returns no content (204), ignoring the response.
    @discardableResult
    func putVoid<B: Encodable>(_ path: String, body: B) async throws -> Data {
        try await requestData(path: path, query: [], method: "PUT", body: encode(body))
    }

    func patch<T: Decodable, B: Encodable>(_ path: String, body: B) async throws -> T {
        try decode(try await requestData(path: path, query: [], method: "PATCH", body: encode(body)))
    }

    @discardableResult
    func delete(_ path: String) async throws -> Data {
        try await requestData(path: path, query: [], method: "DELETE", body: nil)
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder.picsou.decode(T.self, from: data) }
        catch { throw APIError.decoding(String(describing: error)) }
    }

    private func encode<B: Encodable>(_ body: B) throws -> Data {
        do { return try JSONEncoder.picsou.encode(body) }
        catch { throw APIError.decoding(String(describing: error)) }
    }

    private func requestData(path: String, query: [URLQueryItem], method: String, body: Data?) async throws -> Data {
        guard let base = serverConfig.baseURL else { throw APIError.notConfigured }
        guard var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw APIError.invalidURL
        }
        if !query.isEmpty { comps.queryItems = query }
        guard let url = comps.url else { throw APIError.invalidURL }

        do {
            let token = try await validAccessToken()
            let (data, response) = try await send(url: url, bearer: token, method: method, body: body)
            if Self.isTokenRejection(data, response) {
                let fresh = try await refresher.forceRefresh()
                let (retryData, retryResponse) = try await send(url: url, bearer: fresh.accessToken, method: method, body: body)
                return try validate(retryData, retryResponse)
            }
            return try validate(data, response)
        } catch let error as APIError {
            if case .unauthorized = error { onAuthenticationLost?() }
            throw error
        }
    }

    private func send(url: URL, bearer: String, method: String, body: Data?) async throws -> (Data, URLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        do {
            return try await session.data(for: request)
        } catch {
            throw APIError.network(error.localizedDescription)
        }
    }

    private func validate(_ data: Data, _ response: URLResponse) throws -> Data {
        guard let http = response as? HTTPURLResponse else { throw APIError.network("No HTTP response") }
        if (200...299).contains(http.statusCode) { return data }
        if Self.isTokenRejection(data, response) { throw APIError.unauthorized }
        throw APIError.http(status: http.statusCode, body: String(data: data, encoding: .utf8))
    }

    /// The server rejected the Bearer itself (missing, expired or revoked). Other 401s, such as a
    /// wrong current password on change-password, carry no such type and surface as `.http`.
    private static func isTokenRejection(_ data: Data, _ response: URLResponse) -> Bool {
        (response as? HTTPURLResponse)?.statusCode == 401
            && ProblemDetail(body: String(data: data, encoding: .utf8))?.type == ProblemDetail.authenticationRequiredType
    }

    /// A non-expired access token, refreshing proactively when within 60s of expiry.
    private func validAccessToken() async throws -> String {
        guard let tokens = tokenStore.load() else { throw APIError.unauthorized }
        if tokens.accessTokenExpiry.timeIntervalSinceNow > 60 {
            return tokens.accessToken
        }
        return try await refresher.forceRefresh().accessToken
    }
}

/// Serializes refresh so concurrent 401s trigger a single token refresh (single-flight).
actor TokenRefresher {
    private let oauth: OAuthService
    private let tokenStore: TokenStoring
    private var inFlight: Task<TokenSet, Error>?

    init(oauth: OAuthService, tokenStore: TokenStoring) {
        self.oauth = oauth
        self.tokenStore = tokenStore
    }

    func forceRefresh() async throws -> TokenSet {
        if let inFlight { return try await inFlight.value }

        let task = Task { [oauth, tokenStore] () throws -> TokenSet in
            guard let refreshToken = tokenStore.load()?.refreshToken, !refreshToken.isEmpty else {
                throw APIError.unauthorized
            }
            let fresh: TokenSet
            do {
                fresh = try await oauth.refresh(refreshToken)
            } catch let APIError.http(status, body) where Self.isRefusal(status: status, body: body) {
                throw APIError.unauthorized
            }
            tokenStore.save(fresh)
            return fresh
        }
        inFlight = task
        defer { inFlight = nil }
        return try await task.value
    }

    /// The server rejected the refresh token itself (revoked from the web, reuse detected, absolute
    /// lifetime over): retrying can never succeed, so the caller must sign out. A network error or a
    /// 5xx is transient and keeps the tokens.
    static func isRefusal(status: Int, body: String?) -> Bool {
        guard status == 400 || status == 401,
              let data = body?.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = json["error"] as? String else { return false }
        return code == "invalid_grant" || code == "invalid_client"
    }
}
