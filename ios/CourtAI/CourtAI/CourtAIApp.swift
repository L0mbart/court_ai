import SwiftUI

@main
struct CourtAIApp: App {
    @StateObject private var session = AppSession()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
        }
    }
}

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
        .task {
            let ok = await BiometricAuth.unlock(reason: "Masuk CourtAI")
            if ok { session.markUnlocked() }
        }
    }
}
