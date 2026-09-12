import Foundation

enum APIError: LocalizedError {
    case badURL
    case server(String)
    case decode

    var errorDescription: String? {
        switch self {
        case .badURL: return "Server URL tidak valid"
        case .server(let msg): return msg
        case .decode: return "Response tidak bisa dibaca"
        }
    }
}

enum CourtAPI {
    static func login(base: URL, identifier: String, password: String) async throws -> AuthUser {
        let url = base.appendingPathComponent("api/auth/login")
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 12
        let body: [String: String] = ["identifier": identifier, "password": password]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw APIError.server("No response") }
        if !(200...299).contains(http.statusCode) {
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let detail = obj["detail"] as? String {
                throw APIError.server(detail)
            }
            throw APIError.server("Login gagal (\(http.statusCode))")
        }
        let decoded = try JSONDecoder().decode(LoginResponse.self, from: data)
        return AuthUser(
            id: decoded.user.id,
            name: decoded.user.name,
            email: decoded.user.email ?? "",
            phone: decoded.user.phone ?? "",
            token: decoded.token
        )
    }

    static func syncSessions(base: URL, sessions: [SessionPayload]) async throws {
        let url = base.appendingPathComponent("api/sessions/sync")
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 15
        req.httpBody = try JSONEncoder().encode(SyncBody(sessions: sessions))
        let (_, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.server("Sync gagal")
        }
    }
}
