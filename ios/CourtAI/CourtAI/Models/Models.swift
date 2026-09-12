import Foundation

struct AuthUser: Codable, Equatable {
    let id: String
    let name: String
    let email: String
    let phone: String
    var token: String = ""
}

struct LoginResponse: Codable {
    let ok: Bool
    let token: String
    let user: AuthUserDTO
}

struct AuthUserDTO: Codable {
    let id: String
    let name: String
    let email: String?
    let phone: String?
}

struct SessionPayload: Codable {
    let clientId: String
    let userId: String
    let userName: String
    let title: String
    let drillId: String
    let activityType: String
    let activityLabel: String
    let makes: Int
    let misses: Int
    let attempts: Int
    let fgPercent: Double?
    let score: Int?
    let durationMs: Int
    let createdAt: Int64
    let deviceId: String
    let source: String
}

struct SyncBody: Codable {
    let sessions: [SessionPayload]
}

struct VersionInfo: Codable {
    let versionCode: Int
    let versionName: String
    let changelog: String?
    let forceUpdate: Bool?
    let apkUrl: String?
    let iosUrl: String?
    let ipaUrl: String?
}
