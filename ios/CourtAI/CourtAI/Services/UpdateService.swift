import Foundation
import UserNotifications
import UIKit

enum AppVersion {
    static let code = 5
    static let name = "1.1.3"
}

enum UpdateService {
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

    static func isNewer(_ remote: VersionInfo) -> Bool {
        remote.versionCode > AppVersion.code
    }

    static func requestNotificationPermission() async {
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

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

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let req = UNNotificationRequest(
            identifier: "courtai-update-\(remote.versionCode)",
            content: content,
            trigger: trigger
        )
        try? await UNUserNotificationCenter.current().add(req)
        UserDefaults.standard.set(remote.versionCode, forKey: key)
    }

    static func downloadURL(for remote: VersionInfo, base: URL) -> URL? {
        if let ipa = remote.ipaUrl, let u = URL(string: ipa) { return u }
        if let ios = remote.iosUrl, let u = URL(string: ios) { return u }
        return base.appendingPathComponent("download")
    }
}
