import Foundation
import LocalAuthentication

/// Face ID / Touch ID / passcode gate for app entry.
final class BiometricGate {
    enum Failure: Error { case canceled, unavailable, failed }

    func authenticate(reason: String) async throws {
        let context = LAContext()
        context.localizedFallbackTitle = "Use Passcode"

        var policyError: NSError?
        // `.deviceOwnerAuthentication` allows a passcode fallback when biometrics are unavailable.
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) else {
            throw Failure.unavailable
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, error in
                if success {
                    continuation.resume()
                } else if let laError = error as? LAError {
                    switch laError.code {
                    case .userCancel, .appCancel, .systemCancel:
                        continuation.resume(throwing: Failure.canceled)
                    default:
                        continuation.resume(throwing: Failure.failed)
                    }
                } else {
                    continuation.resume(throwing: Failure.failed)
                }
            }
        }
    }
}
