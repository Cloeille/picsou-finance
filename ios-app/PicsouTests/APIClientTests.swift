import XCTest
@testable import Picsou

@MainActor
final class APIClientTests: XCTestCase {

    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    private struct Ping: Decodable, Equatable { let ok: Bool }

    private func makeClient(tokens: TokenSet) -> (APIClient, TokenStoring) {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        suite.set("https://test.local", forKey: ServerConfig.baseURLDefaultsKey)
        let serverConfig = ServerConfig(defaults: suite)

        // In-memory store: hermetic, and avoids the Keychain (which needs a signed host).
        let tokenStore = InMemoryTokenStore()
        tokenStore.save(tokens)

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)

        let oauth = OAuthService(serverConfig: serverConfig, session: session)
        let api = APIClient(serverConfig: serverConfig, tokenStore: tokenStore, oauth: oauth, session: session)
        return (api, tokenStore)
    }

    func testRefreshesOn401ThenRetriesAndPersistsRotatedTokens() async throws {
        let (api, tokenStore) = makeClient(tokens: TokenSet(
            accessToken: "old",
            refreshToken: "refresh-1",
            accessTokenExpiry: Date().addingTimeInterval(3600)   // not near expiry: forces the 401 path
        ))

        MockURLProtocol.handler = { request in
            let url = request.url?.absoluteString ?? ""
            if url.contains("/oauth2/token") {
                return MockURLProtocol.ok(request, json:
                    #"{"access_token":"new","refresh_token":"refresh-2","expires_in":900,"token_type":"Bearer"}"#)
            }
            if url.contains("/api/ping") {
                let bearer = request.value(forHTTPHeaderField: "Authorization")
                return bearer == "Bearer new"
                    ? MockURLProtocol.ok(request, json: #"{"ok":true}"#)
                    : MockURLProtocol.status(request, 401)
            }
            return MockURLProtocol.status(request, 404)
        }

        let ping: Ping = try await api.get("api/ping")

        XCTAssertTrue(ping.ok)
        XCTAssertEqual(tokenStore.load()?.accessToken, "new")
        XCTAssertEqual(tokenStore.load()?.refreshToken, "refresh-2")   // rotation persisted
    }

    func testProactivelyRefreshesWhenAccessTokenNearlyExpired() async throws {
        let (api, tokenStore) = makeClient(tokens: TokenSet(
            accessToken: "old",
            refreshToken: "refresh-1",
            accessTokenExpiry: Date().addingTimeInterval(5)      // within the 60s skew → refresh up front
        ))

        MockURLProtocol.handler = { request in
            let url = request.url?.absoluteString ?? ""
            if url.contains("/oauth2/token") {
                return MockURLProtocol.ok(request, json:
                    #"{"access_token":"fresh","refresh_token":"refresh-2","expires_in":900,"token_type":"Bearer"}"#)
            }
            // Only ever accept the freshly-minted token — proves no request used "old".
            let bearer = request.value(forHTTPHeaderField: "Authorization")
            return bearer == "Bearer fresh"
                ? MockURLProtocol.ok(request, json: #"{"ok":true}"#)
                : MockURLProtocol.status(request, 401)
        }

        let ping: Ping = try await api.get("api/ping")
        XCTAssertTrue(ping.ok)
        XCTAssertEqual(tokenStore.load()?.accessToken, "fresh")
    }

    func testRefreshRefusedByServerSignsOutExactlyOnce() async {
        let (api, _) = makeClient(tokens: TokenSet(
            accessToken: "revoked",
            refreshToken: "refresh-1",
            accessTokenExpiry: Date().addingTimeInterval(3600)
        ))
        let lost = Counter()
        api.onAuthenticationLost = { lost.increment() }
        let tokenCalls = Counter()
        MockURLProtocol.handler = { request in
            if request.url?.path == "/oauth2/token" {
                tokenCalls.increment()
                return MockURLProtocol.status(request, 400, json: #"{"error":"invalid_grant"}"#)
            }
            return MockURLProtocol.status(request, 401)
        }

        do {
            let _: Ping = try await api.get("api/ping")
            XCTFail("expected the request to fail")
        } catch {
            XCTAssertEqual(error as? APIError, .unauthorized)
        }
        XCTAssertEqual(lost.value, 1)
        XCTAssertEqual(tokenCalls.value, 1)
    }

    func testRefreshRefusedByServerReturnsAppToLoginAndClearsTokens() async {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        suite.set("https://test.local", forKey: ServerConfig.baseURLDefaultsKey)
        let tokenStore = InMemoryTokenStore()
        tokenStore.save(TokenSet(accessToken: "revoked", refreshToken: "refresh-1",
                                 accessTokenExpiry: Date().addingTimeInterval(3600)))
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let appState = AppState(serverConfig: ServerConfig(defaults: suite), tokenStore: tokenStore,
                                session: URLSession(configuration: config))
        MockURLProtocol.handler = { request in
            request.url?.path == "/oauth2/token"
                ? MockURLProtocol.status(request, 401, json: #"{"error":"invalid_client"}"#)
                : MockURLProtocol.status(request, 401)
        }

        let _: Ping? = try? await appState.api.get("api/ping")
        for _ in 0..<50 where appState.phase != .loggedOut { await Task.yield() }

        XCTAssertEqual(appState.phase, .loggedOut)
        XCTAssertNil(tokenStore.load())
    }

    func testRefreshServerErrorKeepsTheUserSignedIn() async {
        let (api, tokenStore) = makeClient(tokens: TokenSet(
            accessToken: "old",
            refreshToken: "refresh-1",
            accessTokenExpiry: Date().addingTimeInterval(3600)
        ))
        let lost = Counter()
        api.onAuthenticationLost = { lost.increment() }
        MockURLProtocol.handler = { request in
            request.url?.path == "/oauth2/token"
                ? MockURLProtocol.status(request, 503)
                : MockURLProtocol.status(request, 401)
        }

        do {
            let _: Ping = try await api.get("api/ping")
            XCTFail("expected the request to fail")
        } catch {
            XCTAssertEqual(error as? APIError, .http(status: 503, body: ""))
        }
        XCTAssertEqual(lost.value, 0)
        XCTAssertEqual(tokenStore.load()?.refreshToken, "refresh-1")
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock(); defer { lock.unlock() }
        return count
    }

    func increment() {
        lock.lock(); defer { lock.unlock() }
        count += 1
    }
}

/// Hermetic token store for tests — no Keychain, no host-signing requirement.
final class InMemoryTokenStore: TokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: TokenSet?

    func load() -> TokenSet? {
        lock.lock(); defer { lock.unlock() }
        return tokens
    }

    func save(_ tokens: TokenSet) {
        lock.lock(); defer { lock.unlock() }
        self.tokens = tokens
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        tokens = nil
    }
}
