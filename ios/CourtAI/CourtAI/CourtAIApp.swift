// ============================================================================
// CourtAIApp.swift
// CourtAI — Pintu masuk app (titik @main)
// ----------------------------------------------------------------------------
// ALUR LAYAR:
//   Belum login          → LoginView
//   Login + biometrik ON → BiometricGateView (Face ID / Touch ID)
//   Sudah unlock         → HomeView
//
// Analogi: resepsionis gedung — cek kartu anggota, lalu kunci sidik jari,
// baru boleh masuk ke lobi (Home).
// ============================================================================

import SwiftUI

// MARK: - Entry point app

@main
struct CourtAIApp: App {
    /// Satu AppSession untuk seluruh app (dibagikan lewat environmentObject).
    @StateObject private var session = AppSession()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
        }
    }
}

// MARK: - RootView: "saklar" layar utama

struct RootView: View {
    @EnvironmentObject var session: AppSession

    var body: some View {
        Group {
            if !session.isLoggedIn {
                LoginView()
            } else if session.needsBiometricGate {
                BiometricGateView()
            } else {
                HomeView()
            }
        }
    }
}

// MARK: - BiometricGateView: kunci Face ID / Touch ID

struct BiometricGateView: View {
    @EnvironmentObject var session: AppSession
    @State private var status = "Unlock dengan Face ID / Touch ID"

    var body: some View {
        ZStack {
            CourtBackground()
            VStack(spacing: 16) {
                Text("COURTAI")
                    .font(.system(size: 36, weight: .black))
                    .foregroundStyle(CourtTheme.orange)
                Text("Welcome back, \(session.user?.name ?? "")")
                    .foregroundStyle(CourtTheme.cream)
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(CourtTheme.muted)
                Button("Unlock Biometric") {
                    Task {
                        let ok = await BiometricAuth.unlock(reason: "Masuk CourtAI")
                        if ok { session.markUnlocked() }
                        else { status = "Gagal / dibatalkan" }
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                Button("Logout") { session.logout() }
                    .buttonStyle(OutlineButtonStyle())
            }
            .padding(24)
        }
        // Coba unlock otomatis saat layar muncul
        .task {
            let ok = await BiometricAuth.unlock(reason: "Masuk CourtAI")
            if ok { session.markUnlocked() }
        }
    }
}
