import XCTest
@testable import Picsou

@MainActor
final class SignOutTests: XCTestCase {

    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    private let accessToken = SignOutTests.jwt(#"{"sub":"alice","aid":"auth-123"}"#)

    private func makeAppState(accessTokenExpiry: Date = Date().addingTimeInterval(3600)) -> (AppState, TokenStoring, ServerConfig) {
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        suite.set("https://test.local", forKey: ServerConfig.baseURLDefaultsKey)
        let serverConfig = ServerConfig(defaults: suite)

        let tokenStore = InMemoryTokenStore()
        tokenStore.save(TokenSet(accessToken: accessToken, refreshToken: "refresh-1", accessTokenExpiry: accessTokenExpiry))

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let appState = AppState(serverConfig: serverConfig, tokenStore: tokenStore, session: URLSession(configuration: config))
        return (appState, tokenStore, serverConfig)
    }

    func testSignOutRevokesThisDeviceSessionOnTheServer() async {
        let (appState, tokenStore, _) = makeAppState()
        let revoked = expectation(description: "session revoked")
        var revokeRequest: URLRequest?
        MockURLProtocol.handler = { request in
            revokeRequest = request
            revoked.fulfill()
            return MockURLProtocol.status(request, 204)
        }

        appState.signOut()

        XCTAssertEqual(appState.phase, .loggedOut)
        XCTAssertNil(tokenStore.load())
        await fulfillment(of: [revoked], timeout: 2)
        XCTAssertEqual(revokeRequest?.httpMethod, "DELETE")
        XCTAssertEqual(revokeRequest?.url?.absoluteString, "https://test.local/api/auth/sessions/auth-123")
        XCTAssertEqual(revokeRequest?.value(forHTTPHeaderField: "Authorization"), "Bearer \(accessToken)")
    }

    func testSignOutCompletesWhenTheServerCannotBeReached() async {
        let (appState, tokenStore, _) = makeAppState()
        let attempted = expectation(description: "revoke attempted")
        MockURLProtocol.handler = { _ in
            attempted.fulfill()
            throw URLError(.notConnectedToInternet)
        }

        appState.signOut()

        XCTAssertEqual(appState.phase, .loggedOut)
        XCTAssertNil(tokenStore.load())
        await fulfillment(of: [attempted], timeout: 2)
        XCTAssertEqual(appState.phase, .loggedOut)
    }

    func testExpiredAccessTokenIsRefreshedBeforeRevoking() async {
        let (appState, _, _) = makeAppState(accessTokenExpiry: Date().addingTimeInterval(-60))
        let fresh = Self.jwt(#"{"sub":"alice","aid":"auth-123","fresh":true}"#)
        let revoked = expectation(description: "session revoked")
        var bearer: String?
        MockURLProtocol.handler = { request in
            if request.url?.path == "/oauth2/token" {
                return MockURLProtocol.ok(request, json:
                    #"{"access_token":"\#(fresh)","refresh_token":"refresh-2","expires_in":900,"token_type":"Bearer"}"#)
            }
            bearer = request.value(forHTTPHeaderField: "Authorization")
            revoked.fulfill()
            return MockURLProtocol.status(request, 204)
        }

        appState.signOut()

        await fulfillment(of: [revoked], timeout: 2)
        XCTAssertEqual(bearer, "Bearer \(fresh)")
    }

    func testResetServerRevokesAgainstTheInstanceItForgets() async {
        let (appState, _, serverConfig) = makeAppState()
        let revoked = expectation(description: "session revoked")
        var revokeURL: String?
        MockURLProtocol.handler = { request in
            revokeURL = request.url?.absoluteString
            revoked.fulfill()
            return MockURLProtocol.status(request, 204)
        }

        appState.resetServer()

        XCTAssertEqual(appState.phase, .unconfigured)
        XCTAssertNil(serverConfig.baseURL)
        await fulfillment(of: [revoked], timeout: 2)
        XCTAssertEqual(revokeURL, "https://test.local/api/auth/sessions/auth-123")
    }

    private static func jwt(_ payload: String) -> String {
        func encode(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return "\(encode(#"{"alg":"HS256"}"#)).\(encode(payload)).signature"
    }
}
