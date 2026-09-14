// ============================================================================
// BiometricAuth.swift
// CourtAI — Face ID / Touch ID (kunci biometrik)
// ----------------------------------------------------------------------------
// ALUR:
//   RootView cek needsBiometricGate → BiometricGateView
//        ↓
//   BiometricAuth.unlock → iOS tampilkan popup Face ID / Touch ID
//        ↓
//   sukses → AppSession.markUnlocked() → masuk HomeView
//
// Analogi: kunci sidik jari di pintu kamar — hanya pemilik yang boleh masuk.
// ============================================================================

import LocalAuthentication
import Foundation

// MARK: - BiometricAuth

/// Pembungkus sederhana LocalAuthentication (API Apple untuk biometrik).
enum BiometricAuth {
    /// Apakah perangkat mendukung Face ID / Touch ID dan sudah dikonfigurasi?
    static var canUse: Bool {
        let ctx = LAContext()
        var err: NSError?
        return ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
    }

    /// Tampilkan prompt biometrik. Return true jika user berhasil unlock.
    static func unlock(reason: String) async -> Bool {
        let ctx = LAContext()
        do {
            return try await ctx.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: reason // teks yang dilihat user di popup
            )
        } catch {
            return false // gagal / dibatalkan / tidak tersedia
        }
    }
}
