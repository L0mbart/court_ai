import Foundation
import CoreGraphics

struct BallPoint: Equatable {
    let x: CGFloat // 0...1
    let y: CGFloat
    let radius: CGFloat
    let conf: CGFloat
}

enum ShotEvent {
    case make, miss
}

final class DribbleEngine {
    private(set) var dribbles = 0
    private(set) var score = 0
    private(set) var combo = 0
    private(set) var leftCount = 0
    private(set) var rightCount = 0
    private(set) var lastSide = "—"

    private var goingDown = false
    private var peakY: CGFloat = 1
    private var valleyY: CGFloat = 0
    private var armed = false
    private var lastAt: TimeInterval = 0
    private var prevY: CGFloat?

    func reset() {
        dribbles = 0; score = 0; combo = 0
        leftCount = 0; rightCount = 0; lastSide = "—"
        goingDown = false; peakY = 1; valleyY = 0; armed = false
        lastAt = 0; prevY = nil
    }

    func onBall(_ point: BallPoint?, now: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        guard let p = point, p.conf >= 0.22 else {
            if combo > 0 && now - lastAt > 0.9 { combo = 0 }
            return false
        }
        defer { prevY = p.y }
        guard let prev = prevY else { return false }
        let dy = p.y - prev
        if dy > 0.004 {
            if !goingDown { peakY = prev; goingDown = true; armed = true }
            valleyY = p.y
        } else if dy < -0.004 && goingDown {
            let amp = valleyY - peakY
            if armed && amp >= 0.035 && now - lastAt >= 0.12 {
                register(x: p.x, amp: amp, now: now)
                goingDown = false; armed = false
                return true
            }
            goingDown = false; armed = false
        }
        if combo > 0 && now - lastAt > 1.0 { combo = 0 }
        return false
    }

    private func register(x: CGFloat, amp: CGFloat, now: TimeInterval) {
        dribbles += 1
        combo += 1
        lastAt = now
        if x < 0.42 { lastSide = "LEFT"; leftCount += 1 }
        else if x > 0.58 { lastSide = "RIGHT"; rightCount += 1 }
        else { lastSide = "—" }
        var pts = 1
        if amp >= 0.09 { pts += 1 }
        if combo >= 5 { pts += 1 }
        if combo >= 10 { pts += 1 }
        score += pts
    }
}

final class ShotEngine {
    var rim = CGRect(x: 0.35, y: 0.18, width: 0.30, height: 0.12)
    private var wasAbove = false
    private var crossed = false
    private var peakY: CGFloat = 1
    private var lastEvent: TimeInterval = 0
    private var trail: [BallPoint] = []

    func reset() {
        wasAbove = false; crossed = false; peakY = 1
        lastEvent = 0; trail.removeAll()
    }

    func onBall(_ point: BallPoint?, now: TimeInterval = Date().timeIntervalSince1970) -> ShotEvent? {
        guard let p = point, p.conf >= 0.25 else { return nil }
        trail.append(p)
        if trail.count > 18 { trail.removeFirst() }
        let rimCy = rim.midY
        if p.y < rim.minY { wasAbove = true; peakY = min(peakY, p.y) }
        let inX = p.x >= rim.minX && p.x <= rim.maxX
        let nearY = p.y >= rim.minY - 0.04 && p.y <= rim.maxY + 0.10
        if wasAbove && inX && nearY { crossed = true }
        if wasAbove && crossed && p.y > rimCy && trail.count >= 4 {
            if now - lastEvent < 0.9 {
                clear(); return nil
            }
            let centered = p.x >= rim.minX + 0.04 && p.x <= rim.maxX - 0.04
            let event: ShotEvent = (centered && peakY < rim.minY - 0.02) ? .make : .miss
            lastEvent = now
            clear()
            return event
        }
        if wasAbove && p.y > rim.maxY + 0.18 && (p.x < rim.minX - 0.05 || p.x > rim.maxX + 0.05) {
            if now - lastEvent < 0.9 { clear(); return nil }
            lastEvent = now
            clear()
            return .miss
        }
        return nil
    }

    private func clear() {
        wasAbove = false; crossed = false; peakY = 1; trail.removeAll()
    }
}

enum BallDetector {
    static func detect(rgba: UnsafePointer<UInt8>, width: Int, height: Int, bytesPerRow: Int, step: Int = 6) -> BallPoint? {
        var sumX = 0.0, sumY = 0.0, count = 0
        var minX = width, minY = height, maxX = 0, maxY = 0
        var y = 0
        while y < height {
            var x = 0
            while x < width {
                let o = y * bytesPerRow + x * 4
                let r = Int(rgba[o]), g = Int(rgba[o + 1]), b = Int(rgba[o + 2])
                if isOrange(r: r, g: g, b: b) {
                    sumX += Double(x); sumY += Double(y); count += 1
                    minX = min(minX, x); minY = min(minY, y)
                    maxX = max(maxX, x); maxY = max(maxY, y)
                }
                x += step
            }
            y += step
        }
        guard count >= 10 else { return nil }
        let cx = CGFloat(sumX / Double(count) / Double(width))
        let cy = CGFloat(sumY / Double(count) / Double(height))
        let radius = max(CGFloat(maxX - minX) / CGFloat(width), CGFloat(maxY - minY) / CGFloat(height)) / 2
        guard radius >= 0.01 && radius <= 0.3 else { return nil }
        return BallPoint(x: cx, y: cy, radius: radius, conf: min(1, CGFloat(count) / 120))
    }

    private static func isOrange(r: Int, g: Int, b: Int) -> Bool {
        guard r >= 110, r > g + 25, r > b + 35 else { return false }
        guard g >= 35 && g <= 180, b <= 130 else { return false }
        let sat = Double(r - min(g, b)) / Double(max(1, r))
        return sat >= 0.28
    }
}
