import Foundation
@testable import Picsou

/// Drives the real OAuth2 Authorization Code + PKCE handshake against a *running* Picsou backend,
/// reusing the app's own production code (`ServerConfig.validateAndSave`, `OAuthService.authorizeURL`
/// / `.exchange`, `APIClient`) for everything except the interactive web-view step
/// (`ASWebAuthenticationSession`), which XCTest can't drive headlessly — that step is replaced here
/// by a direct cookie-authenticated GET, mirroring `backend/scripts/verify-oauth-pkce.sh`.
///
/// Self-skips (via `Unavailable`) when there's no backend to test against, exactly like the
/// backend's own `@Testcontainers(disabledWithoutDocker = true)` convention — see
/// `docs/conventions/testing.md`. To run for real:
///
///   PICSOU_E2E_BASE_URL=http://localhost:8080   (optional, this is the default)
///   PICSOU_E2E_USERNAME=e2e_admin                (optional, this is the default)
///   PICSOU_E2E_PASSWORD=...                       (required — the seeded admin's password)
///
/// A fresh backend seeds that admin automatically via APP_USERNAME/APP_PASSWORD_HASH (see
/// .env.example — "Only set these to skip the wizard (CI, automated provisioning)").
@MainActor
enum LiveBackend {
    struct Unavailable: Error, CustomStringConvertible {
        let reason: String
        var description: String { reason }
    }

    static let baseURLString = ProcessInfo.processInfo.environment["PICSOU_E2E_BASE_URL"] ?? "http://localhost:8080"
    static let username = ProcessInfo.processInfo.environment["PICSOU_E2E_USERNAME"] ?? "e2e_admin"
    static let password = ProcessInfo.processInfo.environment["PICSOU_E2E_PASSWORD"]

    /// Everything a test needs: `api` for the DataSource-level tests, `oauth`/`tokenStore` for tests
    /// that exercise the refresh grant directly.
    struct Session {
        let api: APIClient
        let oauth: OAuthService
        let tokenStore: TokenStoring
        let baseURL: URL
    }

    /// Logs in, completes the PKCE authorize+token exchange through the app's real `OAuthService`,
    /// and returns a `Session` wired to the real backend with a real, valid access token.
    static func makeClient() async throws -> Session {
        guard let password, !password.isEmpty else {
            throw Unavailable(reason: "PICSOU_E2E_PASSWORD not set -- skipping live-backend e2e tests")
        }
        guard let base = ServerConfig.normalize(baseURLString) else {
            throw Unavailable(reason: "invalid PICSOU_E2E_BASE_URL: \(baseURLString)")
        }

        let defaults = UserDefaults(suiteName: "picsou-e2e-\(UUID().uuidString)")!
        let serverConfig = ServerConfig(defaults: defaults)

        let config = URLSessionConfiguration.ephemeral
        config.httpCookieAcceptPolicy = .always
        let redirectDelegate = NoRedirectDelegate()
        let session = URLSession(configuration: config, delegate: redirectDelegate, delegateQueue: nil)

        do {
            try await serverConfig.validateAndSave(baseURLString, session: session)
        } catch {
            throw Unavailable(reason: "backend not reachable at \(baseURLString): \(error)")
        }

        try await login(base: base, session: session)

        let oauth = OAuthService(serverConfig: serverConfig, session: session)
        let pkce = PKCE.generate()
        let state = PKCE.randomVerifier()
        let authorizeURL = try oauth.authorizeURL(base: base, challenge: pkce.challenge, state: state)
        let code = try await authorizationCode(for: authorizeURL, session: session, delegate: redirectDelegate, expectedState: state)
        let tokens = try await oauth.exchange(base: base, code: code, verifier: pkce.verifier)

        let tokenStore = InMemoryTokenStore()
        tokenStore.save(tokens)
        let api = APIClient(serverConfig: serverConfig, tokenStore: tokenStore, oauth: oauth, session: session)
        return Session(api: api, oauth: oauth, tokenStore: tokenStore, baseURL: base)
    }

    private static func login(base: URL, session: URLSession) async throws {
        var request = URLRequest(url: base.appendingPathComponent("api/auth/login"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "username": username, "password": password!, "rememberMe": false,
        ])
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw Unavailable(reason: "login request failed: \(error)")
        }
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw Unavailable(reason: "login failed (\((response as? HTTPURLResponse)?.statusCode ?? -1)): \(String(data: data, encoding: .utf8) ?? "")")
        }
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           obj["mfaRequired"] as? Bool == true {
            throw Unavailable(reason: "the seeded e2e admin has MFA enabled -- this fixture doesn't drive TOTP")
        }
    }

    private static func authorizationCode(
        for url: URL, session: URLSession, delegate: NoRedirectDelegate, expectedState: String
    ) async throws -> String {
        delegate.capturedLocation = nil
        _ = try? await session.data(from: url)
        guard let location = delegate.capturedLocation,
              let items = URLComponents(string: location)?.queryItems else {
            throw Unavailable(reason: "no redirect Location from /oauth2/authorize -- is the login cookie valid?")
        }
        if let error = items.first(where: { $0.name == "error" })?.value {
            throw Unavailable(reason: "authorize rejected: \(error)")
        }
        guard items.first(where: { $0.name == "state" })?.value == expectedState else {
            throw Unavailable(reason: "state mismatch on authorize redirect")
        }
        guard let code = items.first(where: { $0.name == "code" })?.value else {
            throw Unavailable(reason: "no code in redirect: \(location)")
        }
        return code
    }
}

/// Captures a 3xx response's `Location` header instead of following it: `picsou://callback` isn't a
/// scheme `URLSession` can load anyway, so this mirrors `curl -D - --max-redirs 0`.
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    var capturedLocation: String?

    func urlSession(
        _ session: URLSession, task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        capturedLocation = response.value(forHTTPHeaderField: "Location")
        completionHandler(nil)
    }
}
