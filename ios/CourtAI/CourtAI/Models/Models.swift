// ============================================================================
// Models.swift
// CourtAI — Cetakan data (struct) yang dipakai app ↔ server
// ----------------------------------------------------------------------------
// ALUR:
//   Server kirim/terima JSON → Codable mengubahnya jadi struct Swift (dan sebaliknya).
//
// Analogi: formulir isian. Setiap struct = satu jenis formulir dengan kolom tetap.
// `Codable` = bisa diubah ke/dari JSON otomatis.
// ============================================================================

import Foundation

// MARK: - User yang sudah login (disimpan di HP)

/// Data atlet setelah login. `token` = "tiket" untuk request terotentikasi.
struct AuthUser: Codable, Equatable {
    let id: String
    let name: String
    let email: String
    let phone: String
    var token: String = ""
}

// MARK: - Bentuk respons login dari server

struct LoginResponse: Codable {
    let ok: Bool
    let token: String
    let user: AuthUserDTO
}

/// User dari JSON server (email/phone boleh null → Optional).
struct AuthUserDTO: Codable {
    let id: String
    let name: String
    let email: String?
    let phone: String?
}

// MARK: - Satu sesi latihan yang dikirim ke server

/// Catatan 1 kali training (shoot / dribble) untuk disimpan di dashboard.
struct SessionPayload: Codable {
    let clientId: String       // ID unik dari sisi HP (hindari duplikat)
    let userId: String
    let userName: String
    let title: String
    let drillId: String        // mis. "pound_the_rock", "freestyle"
    let activityType: String   // "dribble" / "shoot"
    let activityLabel: String
    let makes: Int
    let misses: Int
    let attempts: Int
    let fgPercent: Double?     // field goal % (boleh nil untuk dribble)
    let score: Int?            // skor dribble (boleh nil untuk shoot)
    let durationMs: Int        // lama sesi dalam milidetik
    let createdAt: Int64       // timestamp
    let deviceId: String
    let source: String         // "ios"
}

/// Bungkus array sesi untuk endpoint sync.
struct SyncBody: Codable {
    let sessions: [SessionPayload]
}

// MARK: - Info versi dari server (untuk update)

struct VersionInfo: Codable {
    let versionCode: Int       // angka banding (lebih besar = lebih baru)
    let versionName: String    // teks yang dibaca manusia, mis. "1.1.4"
    let changelog: String?     // catatan perubahan
    let forceUpdate: Bool?     // jika true, user wajib update
    let apkUrl: String?        // link Android (tidak dipakai iOS)
    let iosUrl: String?
    let ipaUrl: String?
}
