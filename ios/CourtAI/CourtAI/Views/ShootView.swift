import SwiftUI
import UIKit

struct ShootView: View {
    @EnvironmentObject var session: AppSession
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraModel()
    @State private var engine = ShotEngine()
    @State private var tracking = false
    @State private var makes = 0
    @State private var misses = 0
    @State private var startedAt: Date?
    @State private var elapsed = "00:00"
    @State private var timer: Timer?
    @State private var status = "Geser kotak rim, lalu Start."
    @State private var rimNorm = CGRect(x: 0.35, y: 0.18, width: 0.30, height: 0.12)

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session).ignoresSafeArea()

            GeometryReader { geo in
                if let ball = camera.ball {
                    Circle()
                        .stroke(CourtTheme.orange, lineWidth: 3)
                        .background(Circle().fill(CourtTheme.orange.opacity(0.28)))
                        .frame(
                            width: max(36, ball.radius * min(geo.size.width, geo.size.height) * 2),
                            height: max(36, ball.radius * min(geo.size.width, geo.size.height) * 2)
                        )
                        .position(x: ball.x * geo.size.width, y: ball.y * geo.size.height)
                }

                RoundedRectangle(cornerRadius: 4)
                    .stroke(CourtTheme.amber, lineWidth: 3)
                    .background(RoundedRectangle(cornerRadius: 4).fill(CourtTheme.amber.opacity(0.18)))
                    .frame(width: rimNorm.width * geo.size.width, height: rimNorm.height * geo.size.height)
                    .position(
                        x: rimNorm.midX * geo.size.width,
                        y: rimNorm.midY * geo.size.height
                    )
                    .gesture(
                        DragGesture()
                            .onChanged { value in
                                let w = rimNorm.width
                                let h = rimNorm.height
                                var x = value.location.x / geo.size.width - w / 2
                                var y = value.location.y / geo.size.height - h / 2
                                x = min(max(0, x), 1 - w)
                                y = min(max(0, y), 1 - h)
                                rimNorm = CGRect(x: x, y: y, width: w, height: h)
                                engine.rim = rimNorm
                            }
                    )
            }

            VStack {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text("SHOT TRACKER").font(.headline).foregroundStyle(CourtTheme.cream)
                            Text(status).font(.caption).foregroundStyle(CourtTheme.amber)
                        }
                        Spacer()
                        Button("Close") { dismiss() }.foregroundStyle(CourtTheme.cream)
                    }
                    HStack {
                        Text("\(makes) MAKE").foregroundStyle(CourtTheme.mint).bold()
                        Spacer()
                        Text("\(misses) MISS").foregroundStyle(Color.red).bold()
                        Spacer()
                        Text(fgText).foregroundStyle(CourtTheme.amber).bold()
                        Spacer()
                        Text(elapsed).foregroundStyle(CourtTheme.cream).bold()
                    }
                    .font(.caption)
                }
                .padding()
                .background(Color.black.opacity(0.55))

                Spacer()

                HStack {
                    Button(tracking ? "Pause" : "Start") { toggle() }
                        .buttonStyle(PrimaryButtonStyle())
                    Button("+ Make") { if tracking { makes += 1 } }
                        .buttonStyle(OutlineButtonStyle())
                    Button("+ Miss") { if tracking { misses += 1 } }
                        .buttonStyle(OutlineButtonStyle())
                    Button(camera.usingFront ? "Front" : "Back") { camera.flip() }
                        .buttonStyle(OutlineButtonStyle())
                    Button("Finish") { Task { await finish() } }
                        .buttonStyle(OutlineButtonStyle())
                }
                .padding()
                .background(Color.black.opacity(0.55))
            }
        }
        .onAppear {
            camera.start(front: false)
            engine.rim = rimNorm
        }
        .onDisappear {
            timer?.invalidate()
            camera.stop()
        }
        .onChange(of: camera.ball) { _, ball in
            guard tracking else { return }
            if let event = engine.onBall(ball) {
                switch event {
                case .make: makes += 1; status = "Auto make"
                case .miss: misses += 1; status = "Auto miss"
                }
            }
        }
    }

    private var fgText: String {
        let a = makes + misses
        return a == 0 ? "0%" : "\(makes * 100 / a)%"
    }

    private func toggle() {
        tracking.toggle()
        if tracking {
            engine.reset()
            engine.rim = rimNorm
            startedAt = Date()
            status = "Tracking..."
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
                guard let start = startedAt else { return }
                let s = Int(Date().timeIntervalSince(start))
                elapsed = String(format: "%02d:%02d", s / 60, s % 60)
            }
        } else {
            timer?.invalidate()
            status = "Paused"
        }
    }

    private func finish() async {
        tracking = false
        timer?.invalidate()
        guard let user = session.user else { dismiss(); return }
        let duration = Int(((startedAt.map { Date().timeIntervalSince($0) }) ?? 1) * 1000)
        let attempts = makes + misses
        let fg = attempts == 0 ? nil : Double(makes) * 100 / Double(attempts)
        let payload = SessionPayload(
            clientId: "\(user.id)_\(Int(Date().timeIntervalSince1970 * 1000))",
            userId: user.id,
            userName: user.name,
            title: "iOS Shot Session",
            drillId: "freestyle",
            activityType: "shoot",
            activityLabel: "Shoot - Freestyle",
            makes: makes,
            misses: misses,
            attempts: attempts,
            fgPercent: fg,
            score: nil,
            durationMs: max(1000, duration),
            createdAt: Int64(Date().timeIntervalSince1970 * 1000),
            deviceId: UIDevice.current.name,
            source: "ios"
        )
        try? await CourtAPI.syncSessions(base: session.baseURL, sessions: [payload])
        dismiss()
    }
}
