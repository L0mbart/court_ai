import Foundation
import SwiftUI

@MainActor
final class AppSession: ObservableObject {
    @Published var user: AuthUser?
    @Published var unlockedThisSession = false
    @Published var serverURL: String {
        didSet { UserDefaults.standard.set(serverURL, forKey: "server_url") }
    }
    @Published var biometricEnabled: Bool {
        didSet { UserDefaults.standard.set(biometricEnabled, forKey: "biometric_enabled") }
    }

    private let userKey = "auth_user_json"

    var isLoggedIn: Bool { user != nil }

    var needsBiometricGate: Bool {
        isLoggedIn && biometricEnabled && BiometricAuth.canUse && !unlockedThisSession
    }

    init() {
        serverURL = UserDefaults.standard.string(forKey: "server_url") ?? "http://127.0.0.1:8080"
        biometricEnabled = UserDefaults.standard.bool(forKey: "biometric_enabled")
        if let data = UserDefaults.standard.data(forKey: userKey),
           let saved = try? JSONDecoder().decode(AuthUser.self, from: data) {
            user = saved
        }
    }

    func save(user: AuthUser) {
        self.user = user
        unlockedThisSession = true
        if let data = try? JSONEncoder().encode(user) {
            UserDefaults.standard.set(data, forKey: userKey)
        }
    }

    func markUnlocked() {
        unlockedThisSession = true
    }

    func logout() {
        user = nil
        unlockedThisSession = false
        UserDefaults.standard.removeObject(forKey: userKey)
    }

    var baseURL: URL {
        URL(string: serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")))!
    }
}
