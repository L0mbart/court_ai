import LocalAuthentication
import Foundation

enum BiometricAuth {
    static var canUse: Bool {
        let ctx = LAContext()
        var err: NSError?
        return ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
    }

    static func unlock(reason: String) async -> Bool {
        let ctx = LAContext()
        do {
            return try await ctx.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: reason
            )
        } catch {
            return false
        }
    }
}
