import Foundation

/// The OAuth token set persisted between launches.
struct TokenSet: Codable, Equatable {
    var accessToken: String
    var refreshToken: String
    var accessTokenExpiry: Date
}

/// Storage for the current `TokenSet`. Abstracted so the API layer can be tested with an in-memory
/// double instead of the Keychain (which needs a signed host).
protocol TokenStoring: Sendable {
    func load() -> TokenSet?
    func save(_ tokens: TokenSet)
    func clear()
}

/// Keychain-backed storage for the current `TokenSet`. Thread-safe: the API layer may read tokens
/// off the main actor while the UI writes them.
final class TokenStore: TokenStoring, @unchecked Sendable {
    private let keychain: Keychain
    private let key = "oauth.tokens"
    private let lock = NSLock()

    init(keychain: Keychain = Keychain()) {
        self.keychain = keychain
    }

    func load() -> TokenSet? {
        lock.lock(); defer { lock.unlock() }
        guard let data = keychain.get(key) else { return nil }
        return try? JSONDecoder().decode(TokenSet.self, from: data)
    }

    func save(_ tokens: TokenSet) {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? JSONEncoder().encode(tokens) else { return }
        keychain.set(data, for: key)
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        keychain.delete(key)
    }
}
