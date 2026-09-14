// ============================================================================
// UpdateService.swift
// CourtAI — Cek versi app & notifikasi update
// ----------------------------------------------------------------------------
// ALUR:
//   HomeView.task / tombol Update
//        ↓
//   fetch(api/version) → bandingkan versionCode
//        ↓
//   Jika lebih baru: tampilkan alert + (opsional) notifikasi lokal
//
// Analogi: cek "ada edisi majalah baru?" di toko, lalu beri tahu pembaca.
// ============================================================================

import Foundation
import UserNotifications
import UIKit

// MARK: - Versi yang sedang terpasang di app ini

/// Angka versi lokal. Naikkan `code` saat rilis baru agar server bisa membandingkan.
enum AppVersion {
    static let code = 6
    static let name = "1.1.4"
}

// MARK: - UpdateService

enum UpdateService {
    /// Ambil info versi terbaru dari server (`GET`-style via URLSession).
    static func fetch(base: URL) async throws -> VersionInfo {
        let url = base.appendingPathComponent("api/version")
        var req = URLRequest(url: url)
        req.timeoutInterval = 10
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.server("Gagal cek versi")
        }
        return try JSONDecoder().decode(VersionInfo.self, from: data)
    }

    /// Apakah versi remote lebih baru dari app ini?
    static func isNewer(_ remote: VersionInfo) -> Bool {
        remote.versionCode > AppVersion.code
    }

    /// Minta izin notifikasi (popup iOS sekali saja biasanya).
    static func requestNotificationPermission() async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    /// Kirim notifikasi lokal sekali per versionCode (agar tidak spam).
    static func notifyIfNeeded(_ remote: VersionInfo) async {
        let key = "last_notified_code"
        let last = UserDefaults.standard.integer(forKey: key)
        guard remote.versionCode > last else { return }

        let content = UNMutableNotificationContent()
        content.title = "Update CourtAI \(remote.versionName)"
        content.body = remote.changelog?.isEmpty == false
            ? (remote.changelog ?? "")
            : "Versi \(remote.versionName) tersedia. Buka app untuk update."
        content.sound = .default

        // Trigger 1 detik lagi (hampir segera)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let req = UNNotificationRequest(
            identifier: "courtai-update-\(remote.versionCode)",
            content: content,
            trigger: trigger
        )
        try? await UNUserNotificationCenter.current().add(req)
        UserDefaults.standard.set(remote.versionCode, forKey: key)
    }

    /// Pilih URL unduhan: IPA khusus → link iOS → halaman /download.
    static func downloadURL(for remote: VersionInfo, base: URL) -> URL? {
        if let ipa = remote.ipaUrl, let u = URL(string: ipa) { return u }
        if let ios = remote.iosUrl, let u = URL(string: ios) { return u }
        return base.appendingPathComponent("download")
    }
}
