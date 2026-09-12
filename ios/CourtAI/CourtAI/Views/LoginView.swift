import SwiftUI

struct LoginView: View {
    @EnvironmentObject var session: AppSession
    @State private var identifier = ""
    @State private var password = ""
    @State private var status = ""
    @State private var loading = false
    @State private var showServer = false
    @State private var tempServer = ""

    var body: some View {
        ZStack {
            CourtBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("COURTAI")
                        .font(.system(size: 40, weight: .black))
                        .foregroundStyle(CourtTheme.orange)
                        .padding(.top, 48)
                    Text("Athlete login")
                        .font(.title3.bold())
                        .foregroundStyle(CourtTheme.cream)
                    Text("Masuk dengan email atau nomor telepon. Akun dibuat admin di dashboard web.")
                        .font(.subheadline)
                        .foregroundStyle(CourtTheme.muted)

                    TextField("Email atau nomor telepon", text: $identifier)
                        .textFieldStyle(CourtFieldStyle())
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()

                    SecureField("Password", text: $password)
                        .textFieldStyle(CourtFieldStyle())
                        .textContentType(.password)

                    Toggle("Aktifkan biometrik setelah login", isOn: $session.biometricEnabled)
                        .foregroundStyle(CourtTheme.cream)
                        .tint(CourtTheme.orange)

                    Button(loading ? "Logging in..." : "Login") {
                        Task { await login() }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(loading)

                    Button("Atur Server URL") {
                        tempServer = session.serverURL
                        showServer = true
                    }
                    .buttonStyle(OutlineButtonStyle())

                    if !status.isEmpty {
                        Text(status)
                            .font(.footnote)
                            .foregroundStyle(CourtTheme.amber)
                    }
                }
                .padding(24)
            }
        }
        .alert("Server URL", isPresented: $showServer) {
            TextField("http://192.168.x.x:8080", text: $tempServer)
            Button("Simpan") { session.serverURL = tempServer }
            Button("Batal", role: .cancel) {}
        } message: {
            Text("IP Mac/PC yang menjalankan dashboard.")
        }
    }

    private func login() async {
        loading = true
        status = "Menghubungkan ke server..."
        defer { loading = false }
        do {
            let user = try await CourtAPI.login(
                base: session.baseURL,
                identifier: identifier,
                password: password
            )
            session.save(user: user)
            status = "Welcome, \(user.name)"
        } catch {
            status = error.localizedDescription
        }
    }
}

private struct CourtFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(14)
            .background(CourtTheme.navy)
            .foregroundStyle(CourtTheme.cream)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.white.opacity(0.12), lineWidth: 1)
            )
    }
}
