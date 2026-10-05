import Foundation
import AuthenticationServices
#if canImport(UIKit)
import UIKit
#endif

/// Drives OAuth2 Authorization Code + PKCE against the user's Picsou instance.
///
/// `login()` opens the system web-auth sheet on the instance's `/oauth2/authorize` endpoint — which
/// reuses the existing web login (password + TOTP) — captures the auth code from the `picsou://`
/// callback, and exchanges it for tokens. `refresh(_:)` performs the refresh-token grant.
@MainActor
final class OAuthService {

    private let serverConfig: ServerConfig
    private let session: URLSession
    private let presentationProvider = WebAuthPresentationProvider()
    private var webAuthSession: ASWebAuthenticationSession?

    private let clientId = "picsou-ios"
    private let redirectURI = "picsou://callback"
    private let callbackScheme = "picsou"
    /// Requested for documentation purposes only: the resource server's `type=access` validation
    /// path (`JwtTokenAuthenticator.authenticate`) never reads the `scope` claim — only the
    /// `mcp`-typed tokens minted for `/mcp` clients are scope-checked. The app's own read/write
    /// calls are gated by role, not by this value.
    private let scope = "read"

    init(serverConfig: ServerConfig, session: URLSession = .cookieless) {
        self.serverConfig = serverConfig
        self.session = session
    }

    // MARK: - Public flows

    func login() async throws -> TokenSet {
        guard let base = serverConfig.baseURL else { throw APIError.notConfigured }
        let pkce = PKCE.generate()
        let state = PKCE.randomVerifier()   // opaque CSRF state

        let authorizeURL = try authorizeURL(base: base, challenge: pkce.challenge, state: state)
        let callback = try await presentWebAuth(url: authorizeURL)
        let code = try authorizationCode(from: callback, expectedState: state)
        return try await exchange(base: base, code: code, verifier: pkce.verifier)
    }

    func refresh(_ refreshToken: String) async throws -> TokenSet {
        guard let base = serverConfig.baseURL else { throw APIError.notConfigured }
        return try await refresh(refreshToken, base: base)
    }

    func refresh(_ refreshToken: String, base: URL) async throws -> TokenSet {
        try await postToken(base: base, form: [
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
            "client_id": clientId,
        ], previousRefreshToken: refreshToken)
    }

    // MARK: - Authorize

    /// Internal (not `private`) so `@testable import` can reuse the exact production URL-building
    /// and token-exchange code from an e2e test that supplies its own authorization code — see
    /// `PicsouTests/E2E/LiveBackend.swift`.
    func authorizeURL(base: URL, challenge: String, state: String) throws -> URL {
        var comps = URLComponents(url: base.appendingPathComponent("oauth2/authorize"),
                                  resolvingAgainstBaseURL: false)
        comps?.queryItems = [
            .init(name: "response_type", value: "code"),
            .init(name: "client_id", value: clientId),
            .init(name: "redirect_uri", value: redirectURI),
            .init(name: "scope", value: scope),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "state", value: state),
        ]
        guard let url = comps?.url else { throw APIError.invalidURL }
        return url
    }

    private func presentWebAuth(url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let authSession = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
                self?.webAuthSession = nil
                if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else if let error {
                    continuation.resume(throwing: Self.mapWebAuthError(error))
                } else {
                    continuation.resume(throwing: APIError.unauthorized)
                }
            }
            authSession.presentationContextProvider = presentationProvider
            // Non-ephemeral: a still-valid "Remember Me" web session can skip re-typing the password.
            authSession.prefersEphemeralWebBrowserSession = false
            webAuthSession = authSession   // retain until the callback fires
            if !authSession.start() {
                webAuthSession = nil
                continuation.resume(throwing: APIError.network("Could not start the login session"))
            }
        }
    }

    private func authorizationCode(from callback: URL, expectedState: String) throws -> String {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let oauthError = items.first(where: { $0.name == "error" })?.value {
            throw APIError.http(status: 400, body: oauthError)
        }
        guard items.first(where: { $0.name == "state" })?.value == expectedState else {
            throw APIError.unauthorized
        }
        guard let code = items.first(where: { $0.name == "code" })?.value else {
            throw APIError.unauthorized
        }
        return code
    }

    // MARK: - Token endpoint

    func exchange(base: URL, code: String, verifier: String) async throws -> TokenSet {
        try await postToken(base: base, form: [
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirectURI,
            "client_id": clientId,
            "code_verifier": verifier,
        ], previousRefreshToken: nil)
    }

    private func postToken(base: URL, form: [String: String], previousRefreshToken: String?) async throws -> TokenSet {
        var request = URLRequest(url: base.appendingPathComponent("oauth2/token"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Self.formURLEncode(form).data(using: .utf8)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.network("No HTTP response") }
        guard http.statusCode == 200 else {
            throw APIError.http(status: http.statusCode, body: String(data: data, encoding: .utf8))
        }

        let token: TokenResponse
        do {
            token = try JSONDecoder().decode(TokenResponse.self, from: data)
        } catch {
            throw APIError.decoding(String(describing: error))
        }
        return TokenSet(
            accessToken: token.accessToken,
            refreshToken: token.refreshToken ?? previousRefreshToken ?? "",
            accessTokenExpiry: Date().addingTimeInterval(TimeInterval(token.expiresIn ?? 900))
        )
    }

    private struct TokenResponse: Decodable {
        let accessToken: String
        let refreshToken: String?
        let expiresIn: Int?
        let tokenType: String?

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token"
            case refreshToken = "refresh_token"
            case expiresIn = "expires_in"
            case tokenType = "token_type"
        }
    }

    // MARK: - Helpers

    static func formURLEncode(_ params: [String: String]) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return params.map { key, value in
            let k = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let v = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(k)=\(v)"
        }.joined(separator: "&")
    }

    static func mapWebAuthError(_ error: Error) -> APIError {
        if let asError = error as? ASWebAuthenticationSessionError, asError.code == .canceledLogin {
            return .unauthorized
        }
        return .network(error.localizedDescription)
    }
}

/// Supplies the window the system login sheet anchors to.
final class WebAuthPresentationProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        #if canImport(UIKit)
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        return window ?? ASPresentationAnchor()
        #else
        return ASPresentationAnchor()
        #endif
    }
}
