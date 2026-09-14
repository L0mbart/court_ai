// ============================================================================
// HomeView.swift
// CourtAI — Layar beranda setelah login
// ----------------------------------------------------------------------------
// ALUR:
//   Login / biometric OK → HomeView
//     • Lihat profil + statistik ringkas
//     • Tombol Shot Tracker → ShootView (full screen)
//     • Tombol Pound The Rock → DribbleView
//     • Sync / Update / Server → layanan terkait
//
// Analogi: lobi gym — pilih mesin latihan, cek papan skor, atur alamat klub.
// ============================================================================

import SwiftUI

// MARK: - HomeView

struct HomeView: View {
    @EnvironmentObject var session: AppSession
    // Statistik lokal di beranda (bisa diisi dari sesi sebelumnya nanti)
    @State private var makes = 0
    @State private var attempts = 0
    @State private var fgText = "—"
    @State private var showShoot = false
    @State private var showDribble = false
    @State private var status = ""
    @State private var showServer = false
    @State private var tempServer = ""
    @State private var updateInfo: VersionInfo?
    @State private var showUpdateAlert = false

    var body: some View {
        NavigationStack {
            ZStack {
                CourtBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // --- Header: merek + sapaan + logout ---
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("COURTAI")
                                    .font(.system(size: 28, weight: .black))
                                    .foregroundStyle(CourtTheme.orange)
                                    .tracking(3)
                                Text("Let's go, \(firstName)")
                                    .font(.headline)
                                    .foregroundStyle(CourtTheme.cream)
                                Text("v\(AppVersion.name) (\(AppVersion.code))")
                                    .font(.caption2)
                                    .foregroundStyle(CourtTheme.muted)
                            }
                            Spacer()
                            Button("Logout") { session.logout() }
                                .buttonStyle(OutlineButtonStyle())
                                .frame(width: 96)
                        }

                        // --- Kartu profil atlet ---
                        VStack(alignment: .leading, spacing: 6) {
                            Text("ATHLETE PROFILE")
                                .font(.caption)
                                .foregroundStyle(CourtTheme.muted)
                                .tracking(1)
                            Text(session.user?.name ?? "—")
                                .font(.title2.bold())
                                .foregroundStyle(CourtTheme.cream)
                            Text("ID: \(session.user?.id ?? "—") | \(contact)")
                                .font(.caption)
                                .foregroundStyle(CourtTheme.amber)
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(CourtTheme.navy.opacity(0.9))
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

                        // --- Ringkasan angka (makes / attempts / FG%) ---
                        HStack(spacing: 10) {
                            statCard("MAKES", "\(makes)", CourtTheme.mint)
                            statCard("ATTEMPTS", "\(attempts)", CourtTheme.cream)
                            statCard("FG%", fgText, CourtTheme.amber)
                        }

                        Text("TRAINING FLOOR")
                            .font(.caption.bold())
                            .foregroundStyle(CourtTheme.muted)
                            .tracking(1)
                            .padding(.top, 8)

                        // Buka mode tembak
                        Button {
                            showShoot = true
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("SHOT TRACKER")
                                Text("AI make / miss")
                                    .font(.caption)
                                    .opacity(0.9)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                        }
                        .buttonStyle(PrimaryButtonStyle())

                        // Buka mode dribble
                        Button {
                            showDribble = true
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("POUND THE ROCK")
                                Text("Dribble challenge")
                                    .font(.caption)
                                    .opacity(0.85)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                        }
                        .buttonStyle(PrimaryButtonStyle(color: CourtTheme.amber, foreground: CourtTheme.ink))

                        HStack(spacing: 10) {
                            Button("Sync") { Task { await sync() } }
                                .buttonStyle(OutlineButtonStyle())
                            Button("Update") { Task { await checkUpdate(manual: true) } }
                                .buttonStyle(OutlineButtonStyle())
                            Button("Server") {
                                tempServer = session.serverURL
                                showServer = true
                            }
                            .buttonStyle(OutlineButtonStyle())
                        }

                        if !status.isEmpty {
                            Text(status)
                                .font(.footnote)
                                .foregroundStyle(CourtTheme.muted)
                        }

                        Text("Track every rep. Own every session.")
                            .font(.footnote)
                            .foregroundStyle(CourtTheme.muted)
                            .padding(.top, 8)
                    }
                    .padding(20)
                }
            }
            .navigationBarHidden(true)
            .fullScreenCover(isPresented: $showShoot) { ShootView() }
            .fullScreenCover(isPresented: $showDribble) { DribbleView() }
            .alert("Server URL", isPresented: $showServer) {
                TextField("http://192.168.x.x:8080", text: $tempServer)
                Button("Simpan") { session.serverURL = tempServer }
                Button("Batal", role: .cancel) {}
            }
            .alert(
                "Update \(updateInfo?.versionName ?? "")",
                isPresented: $showUpdateAlert
            ) {
                Button("Update sekarang") { openUpdate() }
                // Jika forceUpdate, tombol "Nanti" disembunyikan
                if updateInfo?.forceUpdate != true {
                    Button("Nanti", role: .cancel) {}
                }
            } message: {
                Text(updateInfo?.changelog?.isEmpty == false
                    ? (updateInfo?.changelog ?? "")
                    : "Versi baru CourtAI tersedia.")
            }
            // Saat layar muncul: minta izin notifikasi + cek update diam-diam
            .task {
                await UpdateService.requestNotificationPermission()
                await checkUpdate(manual: false)
            }
        }
    }

    // MARK: - Helper tampilan

    private var firstName: String {
        session.user?.name.split(separator: " ").first.map(String.init) ?? "Athlete"
    }

    private var contact: String {
        if let e = session.user?.email, !e.isEmpty { return e }
        if let p = session.user?.phone, !p.isEmpty { return p }
        return "—"
    }

    private func statCard(_ title: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2).foregroundStyle(CourtTheme.muted)
            Text(value).font(.title2.bold()).foregroundStyle(color)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CourtTheme.navy.opacity(0.9))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - Aksi tombol

    private func sync() async {
        guard let user = session.user else { return }
        status = "Siap sync sebagai \(user.name). Selesaikan sesi training untuk kirim data."
    }

    /// Cek update. `manual: true` = user tekan tombol (tampilkan pesan jika sudah terbaru).
    private func checkUpdate(manual: Bool) async {
        guard let base = URL(string: session.serverURL) else {
            if manual { status = "Server URL tidak valid" }
            return
        }
        do {
            let remote = try await UpdateService.fetch(base: base)
            if UpdateService.isNewer(remote) {
                await UpdateService.notifyIfNeeded(remote)
                updateInfo = remote
                showUpdateAlert = true
                status = "Update \(remote.versionName) tersedia"
            } else if manual {
                status = "Sudah versi terbaru (v\(AppVersion.name))"
            }
        } catch {
            if manual { status = "Cek update gagal: \(error.localizedDescription)" }
        }
    }

    /// Buka link unduhan di Safari / browser sistem.
    private func openUpdate() {
        guard let remote = updateInfo,
              let base = URL(string: session.serverURL),
              let url = UpdateService.downloadURL(for: remote, base: base) else { return }
        UIApplication.shared.open(url)
    }
}
