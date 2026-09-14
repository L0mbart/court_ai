// ============================================================================
// TrackingEngines.swift
// CourtAI — Otak penghitung bola (tracking)
// ----------------------------------------------------------------------------
// ALUR SEDERHANA (bayangkan seperti wasit + kamera):
//   1. Kamera kirim gambar → BallDetector cari warna oranye (bola).
//   2. Kalau ketemu, hasilnya BallPoint (posisi bola di layar).
//   3. DribbleEngine: lihat bola naik-turun → hitung dribble + skor.
//   4. ShotEngine: lihat bola lewat "kotak rim" → putuskan MAKE atau MISS.
//
// Koordinat x,y memakai angka 0...1 (bukan pixel).
//   Analogi: peta kota. 0 = kiri/atas, 1 = kanan/bawah layar.
//   Jadi kode tetap jalan di iPhone kecil maupun besar.
// ============================================================================

import Foundation
import CoreGraphics

// MARK: - BallPoint: "titik bola" di layar

/// Satu snapshot posisi bola.
/// Analogi: pin lokasi di Google Maps, tapi di layar kamera.
struct BallPoint: Equatable {
    let x: CGFloat // 0 = kiri, 1 = kanan
    let y: CGFloat // 0 = atas, 1 = bawah
    let radius: CGFloat // seberapa besar bola terlihat (0...1)
    let conf: CGFloat // confidence = seberapa yakin detektor (0...1)
}

// MARK: - ShotEvent: hasil lemparan

/// Hasil deteksi tembakan: masuk (make) atau meleset (miss).
enum ShotEvent {
    case make, miss
}

// ============================================================================
// MARK: - DribbleEngine
// Analogi: sensor di lantai gym yang menghitung pantulan bola.
// Bola turun (ke bawah layar = y naik) lalu naik lagi = 1 dribble.
// ============================================================================

final class DribbleEngine {
    // --- Hasil yang ditampilkan ke pemain ---
    private(set) var dribbles = 0      // total pantulan terdeteksi
    private(set) var score = 0         // poin (bisa > dribbles jika combo bagus)
    private(set) var combo = 0         // pantulan beruntun tanpa jeda lama
    private(set) var leftCount = 0     // dribble di sisi kiri layar
    private(set) var rightCount = 0    // dribble di sisi kanan layar
    private(set) var lastSide = "—"    // LEFT / RIGHT / —

    // --- Status internal (mesin pantulan) ---
    // Analogi gelombang: puncak (atas) → lembah (bawah) → naik lagi = 1 hitungan.
    private var goingDown = false      // sedang bergerak ke bawah?
    private var peakY: CGFloat = 1     // titik tertinggi (y kecil = lebih atas)
    private var valleyY: CGFloat = 0   // titik terendah pantulan
    private var armed = false          // siap mencatat dribble?
    private var lastAt: TimeInterval = 0 // waktu dribble terakhir (cegah double-count)
    private var prevY: CGFloat?        // posisi y frame sebelumnya

    /// Reset semua angka — dipanggil saat Start / sesi baru.
    func reset() {
        dribbles = 0; score = 0; combo = 0
        leftCount = 0; rightCount = 0; lastSide = "—"
        goingDown = false; peakY = 1; valleyY = 0; armed = false
        lastAt = 0; prevY = nil
    }

    /// Dipanggil tiap frame kamera punya (atau tidak punya) bola.
    /// Return `true` jika satu dribble baru baru saja terhitung.
    func onBall(_ point: BallPoint?, now: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        // Bola tidak jelas / hilang → kalau lama, combo putus
        guard let p = point, p.conf >= 0.22 else {
            if combo > 0 && now - lastAt > 0.9 { combo = 0 }
            return false
        }
        defer { prevY = p.y } // simpan y untuk frame berikutnya
        guard let prev = prevY else { return false } // butuh 2 frame dulu

        let dy = p.y - prev // positif = turun di layar, negatif = naik

        // --- Fase turun (bola menuju lantai) ---
        if dy > 0.004 {
            if !goingDown { peakY = prev; goingDown = true; armed = true }
            valleyY = p.y
        }
        // --- Fase naik lagi: pantulan selesai? ---
        else if dy < -0.004 && goingDown {
            let amp = valleyY - peakY // amplitudo = seberapa dalam pantulan
            // Syarat: sudah "armed", pantulan cukup dalam, jeda antar hitungan OK
            if armed && amp >= 0.035 && now - lastAt >= 0.12 {
                register(x: p.x, amp: amp, now: now)
                goingDown = false; armed = false
                return true
            }
            goingDown = false; armed = false
        }

        // Combo hilang jika berhenti > 1 detik
        if combo > 0 && now - lastAt > 1.0 { combo = 0 }
        return false
    }

    /// Mencatat 1 dribble: sisi kiri/kanan + hitung poin bonus.
    private func register(x: CGFloat, amp: CGFloat, now: TimeInterval) {
        dribbles += 1
        combo += 1
        lastAt = now
        // Sisi layar ≈ tangan mana yang memantul (kasar, dari posisi bola)
        if x < 0.42 { lastSide = "LEFT"; leftCount += 1 }
        else if x > 0.58 { lastSide = "RIGHT"; rightCount += 1 }
        else { lastSide = "—" }
        // Poin dasar 1; bonus jika pantulan dalam / combo panjang
        var pts = 1
        if amp >= 0.09 { pts += 1 }
        if combo >= 5 { pts += 1 }
        if combo >= 10 { pts += 1 }
        score += pts
    }
}

// ============================================================================
// MARK: - ShotEngine
// Analogi: wasit yang melihat apakah bola lewat "jendela rim" dari atas ke bawah.
// Rim = kotak virtual di layar (bisa digeser user di ShootView).
// ============================================================================

final class ShotEngine {
    /// Kotak rim normalisasi (0...1). Default kira-kira tengah-atas layar.
    var rim = CGRect(x: 0.35, y: 0.18, width: 0.30, height: 0.12)

    private var wasAbove = false       // pernah di atas rim?
    private var crossed = false        // pernah masuk zona rim?
    private var peakY: CGFloat = 1     // titik tertinggi lintasan bola
    private var lastEvent: TimeInterval = 0 // cooldown antar event
    private var trail: [BallPoint] = [] // jejak posisi (seperti garis asap)

    func reset() {
        wasAbove = false; crossed = false; peakY = 1
        lastEvent = 0; trail.removeAll()
    }

    /// Analisis 1 frame. Return `.make` / `.miss` / `nil` (belum ada keputusan).
    func onBall(_ point: BallPoint?, now: TimeInterval = Date().timeIntervalSince1970) -> ShotEvent? {
        guard let p = point, p.conf >= 0.25 else { return nil }
        trail.append(p)
        if trail.count > 18 { trail.removeFirst() } // simpan max ~18 titik

        let rimCy = rim.midY
        // Bola masih di atas rim → tandai dan catat puncak
        if p.y < rim.minY { wasAbove = true; peakY = min(peakY, p.y) }

        let inX = p.x >= rim.minX && p.x <= rim.maxX
        let nearY = p.y >= rim.minY - 0.04 && p.y <= rim.maxY + 0.10
        if wasAbove && inX && nearY { crossed = true }

        // Pola MAKE-ish: pernah atas + lewat zona rim + sudah turun melewati tengah rim
        if wasAbove && crossed && p.y > rimCy && trail.count >= 4 {
            if now - lastEvent < 0.9 {
                clear(); return nil // terlalu cepat = abaikan (anti double)
            }
            // MAKE jika lintasan cukup tengah + sempat naik cukup tinggi
            let centered = p.x >= rim.minX + 0.04 && p.x <= rim.maxX - 0.04
            let event: ShotEvent = (centered && peakY < rim.minY - 0.02) ? .make : .miss
            lastEvent = now
            clear()
            return event
        }

        // Jelas MISS: sudah jauh di bawah rim dan keluar ke samping
        if wasAbove && p.y > rim.maxY + 0.18 && (p.x < rim.minX - 0.05 || p.x > rim.maxX + 0.05) {
            if now - lastEvent < 0.9 { clear(); return nil }
            lastEvent = now
            clear()
            return .miss
        }
        return nil
    }

    /// Bersihkan status setelah 1 event (siap tembakan berikutnya).
    private func clear() {
        wasAbove = false; crossed = false; peakY = 1; trail.removeAll()
    }
}

// ============================================================================
// MARK: - BallDetector
// Analogi: cari "titik oranye" di foto — seperti highlight marker di kertas.
// Scan piksel dengan langkah `step` (tidak setiap piksel) agar lebih cepat.
// ============================================================================

enum BallDetector {
    /// Cari bola oranye di buffer RGBA. Return posisi normalisasi atau nil.
    static func detect(rgba: UnsafePointer<UInt8>, width: Int, height: Int, bytesPerRow: Int, step: Int = 6) -> BallPoint? {
        var sumX = 0.0, sumY = 0.0, count = 0
        var minX = width, minY = height, maxX = 0, maxY = 0
        var y = 0
        while y < height {
            var x = 0
            while x < width {
                // Setiap piksel: 4 byte R,G,B,A berderet
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
        guard count >= 10 else { return nil } // terlalu sedikit = bukan bola
        // Rata-rata posisi → pusat bola (dibagi lebar/tinggi → 0...1)
        let cx = CGFloat(sumX / Double(count) / Double(width))
        let cy = CGFloat(sumY / Double(count) / Double(height))
        let radius = max(CGFloat(maxX - minX) / CGFloat(width), CGFloat(maxY - minY) / CGFloat(height)) / 2
        guard radius >= 0.01 && radius <= 0.3 else { return nil } // terlalu kecil/besar
        return BallPoint(x: cx, y: cy, radius: radius, conf: min(1, CGFloat(count) / 120))
    }

    /// Apakah warna RGB ini "cukup oranye" seperti bola basket?
    private static func isOrange(r: Int, g: Int, b: Int) -> Bool {
        guard r >= 110, r > g + 25, r > b + 35 else { return false }
        guard g >= 35 && g <= 180, b <= 130 else { return false }
        // saturasi kasar: merah jauh lebih kuat dari hijau/biru
        let sat = Double(r - min(g, b)) / Double(max(1, r))
        return sat >= 0.28
    }
}
