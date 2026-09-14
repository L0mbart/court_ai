// ============================================================================
// AppSession.swift
// CourtAI — "Dompet" sesi login yang hidup selama app terbuka
// ----------------------------------------------------------------------------
// ALUR:
//   CourtAIApp membuat 1 AppSession → di-share ke semua layar (environmentObject).
//   Login sukses → save(user) → data disimpan di UserDefaults (tetap ada setelah tutup app).
//   Logout → hapus user dari memori + UserDefaults.
//
// Analogi: kartu anggota gym di dompet. Selama kartu ada, kamu "sudah login".
// ============================================================================

import Foundation
import SwiftUI

// MARK: - AppSession

/// Status global: siapa yang login, URL server, biometrik on/off.
/// `@MainActor` = semua update UI aman di thread utama.
@MainActor
final class AppSession: ObservableObject {
    @Published var user: AuthUser?                 // nil = belum login
    @Published var unlockedThisSession = false     // sudah lewat Face ID di sesi ini?
    @Published var serverURL: String {
        didSet { UserDefaults.standard.set(serverURL, forKey: "server_url") }
    }
    @Published var biometricEnabled: Bool {
        didSet { UserDefaults.standard.set(biometricEnabled, forKey: "biometric_enabled") }
    }

    private let userKey = "auth_user_json"

    /// Sudah ada user tersimpan?
    var isLoggedIn: Bool { user != nil }

    /// Perlu layar Face ID / Touch ID sebelum masuk Home?
    var needsBiometricGate: Bool {
        isLoggedIn && biometricEnabled && BiometricAuth.canUse && !unlockedThisSession
    }

    init() {
        // Baca pengaturan tersimpan; default server = localhost (PC/Mac di mesin yang sama)
        serverURL = UserDefaults.standard.string(forKey: "server_url") ?? "http://127.0.0.1:8080"
        biometricEnabled = UserDefaults.standard.bool(forKey: "biometric_enabled")
        if let data = UserDefaults.standard.data(forKey: userKey),
           let saved = try? JSONDecoder().decode(AuthUser.self, from: data) {
            user = saved
        }
    }

    /// Simpan user setelah login berhasil.
    func save(user: AuthUser) {
        self.user = user
        unlockedThisSession = true // login manual = sudah "unlock"
        if let data = try? JSONEncoder().encode(user) {
            UserDefaults.standard.set(data, forKey: userKey)
        }
    }

    /// Dipanggil setelah Face ID / Touch ID sukses.
    func markUnlocked() {
        unlockedThisSession = true
    }

    /// Keluar: hapus kartu anggota dari dompet.
    func logout() {
        user = nil
        unlockedThisSession = false
        UserDefaults.standard.removeObject(forKey: userKey)
    }

    /// URL dasar server (slash di ujung dibuang agar path API bersih).
    var baseURL: URL {
        URL(string: serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")))!
    }
}
