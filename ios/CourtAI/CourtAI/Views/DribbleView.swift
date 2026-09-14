// ============================================================================
// DribbleView.swift
// CourtAI — Layar latihan dribble "Pound The Rock"
// ----------------------------------------------------------------------------
// ALUR:
//   Kamera hidup → CameraModel.ball berubah tiap frame
//        ↓ (jika tracking = true)
//   DribbleEngine.onBall → hitung dribble / combo / skor
//        ↓ (Finish atau waktu habis)
//   CourtAPI.syncSessions → simpan ke server → tutup layar
//
// Analogi: game arcade — kamera = sensor, engine = mesin skor, timer 30 detik.
// ============================================================================

import SwiftUI
import UIKit

// MARK: - DribbleView

struct DribbleView: View {
    @EnvironmentObject var session: AppSession
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraModel()
    @State private var engine = DribbleEngine()
    @State private var tracking = false   // Start/Pause
    @State private var remaining = 30     // sisa detik
    @State private var startedAt: Date?
    @State private var timer: Timer?
    @State private var status = "Izinkan kamera, lalu Start."

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session)
                .ignoresSafeArea()

            // Overlay lingkaran oranye di posisi bola (jika ketemu)
            if let ball = camera.ball {
                GeometryReader { geo in
                    Circle()
                        .stroke(CourtTheme.orange, lineWidth: 3)
                        .background(Circle().fill(CourtTheme.orange.opacity(0.3)))
                        .frame(
                            width: max(36, ball.radius * min(geo.size.width, geo.size.height) * 2),
                            height: max(36, ball.radius * min(geo.size.width, geo.size.height) * 2)
                        )
                        // BallPoint 0...1 → pixel layar
                        .position(x: ball.x * geo.size.width, y: ball.y * geo.size.height)
                }
                .allowsHitTesting(false) // jangan blok tombol di bawah
            }

            VStack {
                topBar
                Spacer()
                Text("\(engine.score)")
                    .font(.system(size: 84, weight: .black))
                    .foregroundStyle(CourtTheme.amber)
                    .shadow(radius: 12)
                Text("POINTS")
                    .font(.caption.bold())
                    .foregroundStyle(CourtTheme.cream)
                    .tracking(2)
                Spacer()
                bottomBar
            }
        }
        .onAppear { camera.start(front: true) }
        .onDisappear {
            timer?.invalidate()
            camera.stop()
        }
        // Tiap posisi bola berubah → kirim ke engine (hanya saat tracking)
        .onChange(of: camera.ball) { _, ball in
            guard tracking else { return }
            _ = engine.onBall(ball)
        }
    }

    // MARK: - UI bar atas & bawah

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading) {
                    Text("POUND THE ROCK").font(.headline).foregroundStyle(CourtTheme.orange)
                    Text(status).font(.caption).foregroundStyle(CourtTheme.cream)
                }
                Spacer()
                Button("Close") { dismiss() }
                    .foregroundStyle(CourtTheme.cream)
            }
            HStack {
                metric("\(engine.dribbles)\nDRIBBLES", CourtTheme.mint)
                metric("\(engine.combo)\nCOMBO", CourtTheme.orange)
                metric("\(engine.lastSide)\nL\(engine.leftCount)/R\(engine.rightCount)", CourtTheme.cream)
                metric(String(format: "0:%02d\nTIME", remaining), CourtTheme.amber)
            }
        }
        .padding()
        .background(Color.black.opacity(0.55))
    }

    private var bottomBar: some View {
        HStack {
            Button(tracking ? "Pause" : "Start") { toggle() }
                .buttonStyle(PrimaryButtonStyle())
            Button(camera.usingFront ? "Front" : "Back") { camera.flip() }
                .buttonStyle(OutlineButtonStyle())
            Button("Finish") { Task { await finish() } }
                .buttonStyle(OutlineButtonStyle())
        }
        .padding()
        .background(Color.black.opacity(0.55))
    }

    private func metric(_ text: String, _ color: Color) -> some View {
        Text(text)
            .font(.caption.bold())
            .multilineTextAlignment(.center)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity)
    }

    // MARK: - Start / Pause / Finish

    /// Start: reset engine + timer 30 dtk. Pause: berhenti hitung.
    private func toggle() {
        tracking.toggle()
        if tracking {
            engine.reset()
            remaining = 30
            startedAt = Date()
            status = "Pound it!"
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
                guard let start = startedAt else { return }
                let left = max(0, 30 - Int(Date().timeIntervalSince(start)))
                remaining = left
                if left == 0 { Task { await finish() } }
            }
        } else {
            timer?.invalidate()
            status = "Paused"
        }
    }

    /// Kirim hasil sesi ke server lalu tutup layar.
    private func finish() async {
        tracking = false
        timer?.invalidate()
        guard let user = session.user else { dismiss(); return }
        let duration = Int(((startedAt.map { Date().timeIntervalSince($0) }) ?? 1) * 1000)
        let payload = SessionPayload(
            clientId: "\(user.id)_\(Int(Date().timeIntervalSince1970 * 1000))",
            userId: user.id,
            userName: user.name,
            title: "Pound The Rock (\(engine.score) pts)",
            drillId: "pound_the_rock",
            activityType: "dribble",
            activityLabel: "Dribble - Pound The Rock",
            makes: engine.dribbles,
            misses: 0,
            attempts: engine.dribbles,
            fgPercent: nil,
            score: engine.score,
            durationMs: max(1000, duration),
            createdAt: Int64(Date().timeIntervalSince1970 * 1000),
            deviceId: UIDevice.current.name,
            source: "ios"
        )
        try? await CourtAPI.syncSessions(base: session.baseURL, sessions: [payload])
        status = "Saved \(engine.dribbles) dribbles"
        dismiss()
    }
}
