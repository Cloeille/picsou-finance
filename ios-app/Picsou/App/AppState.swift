import Foundation
import Observation

/// Top-level app coordinator and finite-state machine.
///
/// `unconfigured → loggedOut → locked → ready`
/// - **unconfigured**: no instance URL saved yet (first launch).
/// - **loggedOut**: instance known, but no tokens in the Keychain.
/// - **locked**: tokens present, awaiting a Face ID unlock.
/// - **ready**: unlocked and authenticated — screens may call the API.
@MainActor
@Observable
final class AppState {

    enum Phase: Equatable {
        case unconfigured
        case loggedOut
        case locked
        case ready
    }

    private(set) var phase: Phase

    /// True in the "Picsou Demo" build — server config, auth and Face ID are skipped, and the
    /// dashboard reads mock data.
    let isDemo: Bool

    let serverConfig: ServerConfig
    let tokenStore: TokenStoring
    let oauth: OAuthService
    let api: APIClient

    /// Non-nil while a transient error should be surfaced (e.g. a failed unlock).
    var lastError: String?

    private let biometric: BiometricGate
    private let session: URLSession

    init(
        serverConfig: ServerConfig = ServerConfig(),
        tokenStore: TokenStoring = TokenStore(),
        biometric: BiometricGate = BiometricGate(),
        session: URLSession = .cookieless
    ) {
        self.serverConfig = serverConfig
        self.tokenStore = tokenStore
        self.biometric = biometric
        self.session = session
        self.oauth = OAuthService(serverConfig: serverConfig, session: session)
        self.api = APIClient(serverConfig: serverConfig, tokenStore: tokenStore, oauth: oauth, session: session)
        self.isDemo = AppConfig.isDemo

        if isDemo {
            phase = .ready                 // straight into the mock dashboard
        } else if serverConfig.baseURL == nil {
            phase = .unconfigured
        } else if tokenStore.load() == nil {
            phase = .loggedOut
        } else {
            phase = .locked
        }

        // When a refresh ultimately fails, the API layer asks us to send the user back to login.
        api.onAuthenticationLost = { [weak self] in
            Task { @MainActor in self?.signOut() }
        }
    }

    /// The dashboard's data source: mock data in the demo build, the live API otherwise.
    func makeDashboardDataSource() -> DashboardDataSource {
        isDemo ? DemoDashboardDataSource() : LiveDashboardDataSource(api: api)
    }

    /// Account-detail data source: mock in the demo build, the live API otherwise.
    func makeAccountsDataSource() -> AccountsDataSource {
        isDemo ? DemoAccountsDataSource() : LiveAccountsDataSource(api: api)
    }

    /// Goals write-side data source: mock in the demo build, the live API otherwise.
    func makeGoalsDataSource() -> GoalsDataSource {
        isDemo ? DemoGoalsDataSource() : LiveGoalsDataSource(api: api)
    }

    /// Profile + sessions data source: mock in the demo build, the live API otherwise.
    func makeSettingsDataSource() -> SettingsDataSource {
        isDemo ? DemoSettingsDataSource() : LiveSettingsDataSource(api: api)
    }

    /// One demo store per app launch so create/edit/delete/categorize actions stay consistent
    /// across every screen that requests a Budget data source (hub, inbox, spending, recurring…).
    /// `@ObservationIgnored` because `@Observable` would otherwise turn this into a computed
    /// property, which can't be `lazy`; SwiftUI doesn't need to observe this reference itself.
    @ObservationIgnored private lazy var demoBudgetStore = DemoBudgetStore()

    /// Budget-tab data source: mock in the demo build, the live API otherwise.
    func makeBudgetDataSource() -> BudgetDataSource {
        isDemo ? DemoBudgetDataSource(store: demoBudgetStore) : LiveBudgetDataSource(api: api)
    }

    /// Bank-sync data source: mock in the demo build, the live API otherwise.
    func makeSyncDataSource() -> SyncDataSource {
        isDemo ? DemoSyncDataSource() : LiveSyncDataSource(api: api)
    }

    /// MCP access-keys data source: mock in the demo build, the live API otherwise.
    func makeAccessKeysDataSource() -> AccessKeysDataSource {
        isDemo ? DemoAccessKeysDataSource() : LiveAccessKeysDataSource(api: api)
    }

    /// Family data source: mock in the demo build, the live API otherwise.
    func makeFamilyDataSource() -> FamilyDataSource {
        isDemo ? DemoFamilyDataSource() : LiveFamilyDataSource(api: api)
    }

    struct Identity { let username: String; let role: String }

    /// Current user identity for display — a demo constant, else decoded (unverified) from the
    /// access-token JWT (`sub` = username, `role`). There is no `/api/me` endpoint.
    var identity: Identity? {
        if isDemo { return Identity(username: "chloe", role: "Admin") }
        guard let token = tokenStore.load()?.accessToken, let claims = JWT.payload(of: token) else { return nil }
        let username = (claims["sub"] as? String) ?? "—"
        let role = (claims["role"] as? String) == "ADMIN" ? "Admin" : "Membre"
        return Identity(username: username, role: role)
    }

    /// Validate and persist the instance URL, then advance out of `.unconfigured`.
    func configureServer(_ raw: String) async throws {
        try await serverConfig.validateAndSave(raw, session: session)
        phase = tokenStore.load() == nil ? .loggedOut : .locked
    }

    /// Run the OAuth2 + PKCE login and, on success, become `.ready`.
    func login() async throws {
        let tokens = try await oauth.login()
        tokenStore.save(tokens)
        phase = .ready
    }

    /// Ask for Face ID. On success become `.ready`; a user cancel silently stays `.locked`.
    func unlock() async {
        lastError = nil
        do {
            try await biometric.authenticate(reason: "Unlock Picsou to view your finances")
            phase = .ready
        } catch BiometricGate.Failure.canceled {
            // Stay locked, no error banner.
        } catch {
            lastError = "Face ID failed — try again."
        }
    }

    /// Called when the app is backgrounded; drops a ready session back behind the lock.
    func lockIfNeeded() {
        if phase == .ready { phase = .locked }
    }

    /// Clear tokens and return to the login screen (keeps the configured instance).
    func signOut() {
        revokeOnServer()
        tokenStore.clear()
        phase = .loggedOut
    }

    /// Forget the instance entirely (Settings action, not wired into Phase 1 UI yet).
    func resetServer() {
        revokeOnServer()
        tokenStore.clear()
        serverConfig.clear()
        phase = .unconfigured
    }

    /// Best-effort `DELETE /api/auth/sessions/{aid}`, so signing out also ends this device's
    /// authorization and its refresh token on the server. Captures the tokens and instance URL
    /// before the caller clears them; runs detached, ignores every failure, so sign-out never waits
    /// on the network. Bypasses `APIClient`, whose lost-auth callback would sign out again.
    private func revokeOnServer() {
        guard let tokens = tokenStore.load(), let base = serverConfig.baseURL else { return }
        Task { [oauth, session] in
            var accessToken = tokens.accessToken
            if tokens.accessTokenExpiry.timeIntervalSinceNow < 60,
               let fresh = try? await oauth.refresh(tokens.refreshToken, base: base) {
                accessToken = fresh.accessToken
            }
            guard let aid = JWT.payload(of: accessToken)?["aid"] as? String else { return }
            var request = URLRequest(url: base.appendingPathComponent("api/auth/sessions/\(aid)"), timeoutInterval: 5)
            request.httpMethod = "DELETE"
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
            _ = try? await session.data(for: request)
        }
    }
}
