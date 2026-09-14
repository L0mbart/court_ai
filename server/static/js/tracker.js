/**
 * ============================================================
 * FILE: tracker.js — "mata" CourtAI untuk mengikuti bola basket
 * ============================================================
 * Apa file ini?
 *   Kode yang mencari bola oranye di layar kamera, merapikan
 *   gerakannya supaya tidak gemetar, lalu menghitung dribble
 *   dan tembakan (MAKE / MISS).
 *
 * Alur kerja singkat:
 *   1. detect()  → cari gumpalan warna oranye (bola) di gambar.
 *   2. TrackerFilter → ratakan posisi agar garis bola tidak goyang.
 *   3. DribbleEngine / ShotEngine → baca gerak naik-turun / lewat
 *      ring, lalu tambah skor dribble atau catat MAKE/MISS.
 *
 * Dipakai halaman web CourtAI lewat: window.BallTracker
 * Bahasa: komentar untuk kelas 1 SMA / orang awam.
 * ============================================================
 */
window.BallTracker = (() => {
  /* ========== BAGIAN: Deteksi Warna Oranye ========== */
  /**
   * APA: Mengecek apakah satu piksel (titik warna) termasuk "oranye bola".
   * KAPAN: Dipanggil berkali-kali saat scan gambar di fungsi detect().
   * PARAMETER: r, g, b = merah, hijau, biru (0–255).
   * RETURN: true jika mirip warna bola basket, false jika bukan.
   *
   * ANALOGI: Seperti memilah jeruk di keranjang buah — kita lihat
   *   "seberapa oranye" warnanya (hue), "seberapa cerah" (saturasi),
   *   dan pastikan tidak terlalu gelap / terlalu biru.
   */
  // --- color: basketball orange in RGB approx HSV ---
  function isOrange(r, g, b) {
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    // Terlalu gelap = bukan bola yang terlihat jelas
    if (max < 70) return false;
    // Saturasi rendah = warna "kusam" / abu-abu, bukan oranye kuat
    const sat = max === 0 ? 0 : (max - min) / max;
    if (sat < 0.22) return false;
    // Hitung "hue" (sudut warna di pelangi) dari RGB
    let hue = 0;
    const d = max - min || 1;
    if (max === r) hue = ((g - b) / d) % 6;
    else if (max === g) hue = (b - r) / d + 2;
    else hue = (r - g) / d + 4;
    hue *= 60;
    if (hue < 0) hue += 360;
    // orange ~8..45 deg, allow reddish basketball
    // Oranye bola biasanya di sekitar sudut 5–55 derajat
    if (hue < 5 || hue > 55) return false;
    if (r < 90) return false;       // merah harus cukup kuat
    if (r < g) return false;        // oranye: merah > hijau
    if (b > r * 0.85) return false; // biru tidak boleh mendominasi
    return true;
  }

  /* ========== BAGIAN: Deteksi Bola ========== */
  /**
   * APA: Mencari pusat bola oranye di canvas (gambar frame kamera).
   * KAPAN: Setiap frame video saat latihan berjalan.
   * PARAMETER:
   *   ctx  = "kuas" 2D canvas (bisa baca piksel gambar),
   *   w, h = lebar & tinggi gambar,
   *   step = loncatan piksel (lebih besar = lebih cepat, sedikit kurang teliti).
   * RETURN: { x, y, radius, conf } posisi dinormalisasi 0–1, atau null jika tidak ketemu.
   *
   * ANALOGI: Seperti mencari titik oranye terpadat di peta kota:
   *   1) bagi layar jadi kotak-kotak besar (grid kasar),
   *   2) pilih kotak paling banyak titik oranye,
   *   3) zoom sekitar situ untuk hitung pusat & ukuran bola lebih rapi.
   */
  /**
   * Find densest orange blob via coarse grid, then refine.
   */
  function detect(ctx, w, h, step = 4) {
    const img = ctx.getImageData(0, 0, w, h);
    const d = img.data;
    // Grid kasar: layar dibagi ~24 bagian di setiap sisi
    const gw = Math.max(8, Math.floor(w / 24));
    const gh = Math.max(8, Math.floor(h / 24));
    const grid = new Float32Array(gw * gh);   // jumlah piksel oranye per sel
    const gxSum = new Float32Array(gw * gh);  // jumlah koordinat X di sel itu
    const gySum = new Float32Array(gw * gh);  // jumlah koordinat Y di sel itu

    // Pass 1: scan kasar — hitung mana sel paling "penuh oranye"
    for (let y = 0; y < h; y += step) {
      for (let x = 0; x < w; x += step) {
        const i = (y * w + x) * 4; // indeks RGBA di array data
        if (!isOrange(d[i], d[i + 1], d[i + 2])) continue;
        const cx = Math.min(gw - 1, Math.floor((x / w) * gw));
        const cy = Math.min(gh - 1, Math.floor((y / h) * gh));
        const gi = cy * gw + cx;
        grid[gi] += 1;
        gxSum[gi] += x;
        gySum[gi] += y;
      }
    }

    // Cari sel dengan skor tertinggi
    let best = -1;
    let bestI = -1;
    for (let i = 0; i < grid.length; i++) {
      if (grid[i] > best) {
        best = grid[i];
        bestI = i;
      }
    }
    // Terlalu sedikit titik oranye → anggap tidak ada bola
    if (best < 3 || bestI < 0) return null;

    const cellX = bestI % gw;
    const cellY = Math.floor(bestI / gw);
    // expand neighborhood ±1 cell
    // Gabungkan juga 8 tetangga (3×3) supaya pusat lebih stabil
    let sumX = 0, sumY = 0, count = 0;
    let minX = w, minY = h, maxX = 0, maxY = 0;
    for (let dy = -1; dy <= 1; dy++) {
      for (let dx = -1; dx <= 1; dx++) {
        const nx = cellX + dx;
        const ny = cellY + dy;
        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
        const gi = ny * gw + nx;
        if (grid[gi] <= 0) continue;
        sumX += gxSum[gi];
        sumY += gySum[gi];
        count += grid[gi];
      }
    }
    if (count < 4) return null;

    // refine bbox with a second pass around estimated center
    // Pass 2: zoom di sekitar pusat kasar, hitung ulang lebih teliti
    const roughX = sumX / count;
    const roughY = sumY / count;
    const searchR = Math.max(24, Math.min(w, h) * 0.18);
    let rSumX = 0, rSumY = 0, rCount = 0;
    minX = w; minY = h; maxX = 0; maxY = 0;
    const x0 = Math.max(0, Math.floor(roughX - searchR));
    const x1 = Math.min(w - 1, Math.floor(roughX + searchR));
    const y0 = Math.max(0, Math.floor(roughY - searchR));
    const y1 = Math.min(h - 1, Math.floor(roughY + searchR));
    const fine = Math.max(2, Math.floor(step / 2));
    for (let y = y0; y <= y1; y += fine) {
      for (let x = x0; x <= x1; x += fine) {
        const i = (y * w + x) * 4;
        if (!isOrange(d[i], d[i + 1], d[i + 2])) continue;
        rSumX += x; rSumY += y; rCount++;
        // Perluas kotak pembatas (bounding box) bola
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
      }
    }
    if (rCount < 5) return null;

    // Normalisasi ke 0–1 supaya tidak tergantung resolusi layar
    const cx = rSumX / rCount / w;
    const cy = rSumY / rCount / h;
    const bw = (maxX - minX) / w;
    const bh = (maxY - minY) / h;
    const radius = Math.max(bw, bh) / 2;
    const aspect = bh < 0.001 ? 99 : bw / bh;
    // Filter bentuk aneh: terlalu kecil/besar, atau terlalu pipih
    if (radius < 0.008 || radius > 0.38) return null;
    if (aspect < 0.35 || aspect > 2.8) return null;

    return {
      x: cx,
      y: cy,
      radius,
      conf: Math.min(1, rCount / 80), // conf = seberapa yakin (0–1)
    };
  }

  /* ========== BAGIAN: Filter Halus (EMA) ========== */
  /**
   * APA: Merapikan posisi bola supaya tidak "loncat-loncat" tiap frame.
   * KAPAN: Setelah detect() — hasil mentah masuk ke update().
   *
   * ANALOGI EMA (Exponential Moving Average):
   *   Seperti mencampur posisi lama + posisi baru dengan resep tetap.
   *   Misal: 80% posisi lama + 20% posisi baru → gerak lebih lembut,
   *   seperti bola digambar dengan kuas basah, bukan titik-titik kasar.
   *
   * Jika deteksi hilang sebentar, kita "tahan" / prediksi posisi singkat
   * (agar UI tidak blink), tapi setelah ~260 ms kita lepaskan.
   */
  /** EMA smoother + brief hold when detection drops. */
  class TrackerFilter {
    constructor() {
      this.x = null;
      this.y = null;
      this.radius = 0.05;
      this.conf = 0;
      this.lostSince = 0; // kapan terakhir bola "hilang"
      this.vx = 0;        // kecepatan perkiraan di sumbu X
      this.vy = 0;        // kecepatan perkiraan di sumbu Y
    }
    /** APA: Reset semua ke kondisi awal (belum ada bola). */
    reset() {
      this.x = this.y = null;
      this.conf = 0;
      this.lostSince = 0;
      this.vx = this.vy = 0;
    }
    /**
     * APA: Masukkan hasil deteksi mentah; keluar posisi yang sudah dihaluskan.
     * PARAMETER: raw = hasil detect() atau null; now = waktu sekarang (ms).
     * RETURN: objek posisi (bisa predicted:true) atau null.
     */
    update(raw, now = Date.now()) {
      // Bola tidak terdeteksi di frame ini
      if (!raw) {
        if (this.x == null) return null;
        if (!this.lostSince) this.lostSince = now;
        const age = now - this.lostSince;
        // hold longer for smoother UI, but don't invent bounces
        // Lebih dari 260 ms hilang → anggap benar-benar hilang
        if (age > 260) {
          this.x = this.y = null;
          this.conf = 0;
          return null;
        }
        // Prediksi singkat: geser sedikit mengikuti kecepatan terakhir
        const t = age / 1000;
        return {
          x: Math.min(1, Math.max(0, this.x + this.vx * t * 0.15)),
          y: Math.min(1, Math.max(0, this.y + this.vy * t * 0.15)),
          radius: this.radius,
          conf: Math.max(0.1, this.conf * (1 - age / 260)),
          predicted: true,
        };
      }
      this.lostSince = 0;
      // Pertama kali ketemu bola → langsung ambil posisi itu
      if (this.x == null) {
        this.x = raw.x;
        this.y = raw.y;
        this.radius = raw.radius;
        this.conf = raw.conf;
        return { ...raw, predicted: false };
      }
      // stronger smoothing = less jitter
      // a = seberapa cepat ikut posisi baru (lebih besar jika conf tinggi)
      const a = raw.conf > 0.6 ? 0.28 : 0.18;
      const nx = this.x * (1 - a) + raw.x * a;
      const ny = this.y * (1 - a) + raw.y * a;
      // Perbarui kecepatan untuk prediksi saat hilang sebentar
      this.vx = this.vx * 0.6 + (nx - this.x) * 0.4;
      this.vy = this.vy * 0.6 + (ny - this.y) * 0.4;
      this.x = nx;
      this.y = ny;
      this.radius = this.radius * 0.82 + raw.radius * 0.18;
      this.conf = this.conf * 0.5 + raw.conf * 0.5;
      return {
        x: this.x,
        y: this.y,
        radius: this.radius,
        conf: this.conf,
        predicted: false,
      };
    }
  }

  /* ========== BAGIAN: Mesin Dribble ========== */
  /**
   * APA: Menghitung pantulan dribble (bola turun lalu naik lagi).
   * KAPAN: Tiap frame, setelah posisi bola sudah dihaluskan.
   *
   * ANALOGI pantulan:
   *   Bayangkan grafik tinggi bola seperti gelombang laut.
   *   - "Puncak" = bola di atas (peakY)
   *   - "Lembah" = bola di bawah dekat lantai (valleyY)
   *   Saat bola turun jelas lalu naik lagi dengan jarak cukup besar
   *   dan jeda waktu cukup → itu 1 dribble sah (bukan getaran kamera).
   */
  class DribbleEngine {
    constructor() {
      this.reset();
    }
    /** APA: Nolkan skor, combo, dan status gerak. */
    reset() {
      this.dribbles = 0;
      this.score = 0;
      this.combo = 0;
      this.bestCombo = 0;
      this.leftCount = 0;
      this.rightCount = 0;
      this.lastSide = "—";
      this.goingDown = false; // sedang fase turun?
      this.peakY = 1;         // Y kecil = atas layar; Y besar = bawah
      this.valleyY = 0;
      this.armed = false;     // siap mencatat pantulan?
      this.lastAt = 0;
      this.prevY = null;
      this.smoothY = null;
      this.downFrames = 0;
      this.upFrames = 0;
    }
    /**
     * APA: Proses satu posisi bola; return true jika baru terhitung 1 dribble.
     * PARAMETER: p = posisi filter; now = waktu ms.
     * RETURN: true jika ada dribble baru, false jika belum.
     */
    onBall(p, now = Date.now()) {
      // Ignore weak / predicted samples so noise doesn't create fake dribbles
      // Abaikan prediksi / conf rendah supaya tidak ada dribble palsu
      if (!p || p.predicted || p.conf < 0.22) {
        if (this.combo && now - this.lastAt > 1400) this.combo = 0;
        return false;
      }
      // Heavy smooth on Y for clean bounce edges
      // Haluskan lagi sumbu Y agar "tepi" pantulan lebih jelas
      this.smoothY =
        this.smoothY == null ? p.y : this.smoothY * 0.78 + p.y * 0.22;
      if (this.prevY == null) {
        this.prevY = this.smoothY;
        return false;
      }
      const dy = this.smoothY - this.prevY; // >0 = turun di layar, <0 = naik
      this.prevY = this.smoothY;

      // Clear downward / upward motion required
      // Butuh beberapa frame turun berturut-turut (bukan 1 lonjakan noise)
      if (dy > 0.0055) {
        this.downFrames++;
        this.upFrames = 0;
        if (this.downFrames >= 3) {
          if (!this.goingDown) {
            this.peakY = this.smoothY - dy * 3;
            this.goingDown = true;
            this.armed = true;
            this.valleyY = this.smoothY;
          }
          this.valleyY = Math.max(this.valleyY, this.smoothY);
        }
      } else if (dy < -0.0055) {
        this.upFrames++;
        this.downFrames = 0;
        if (this.upFrames >= 3 && this.goingDown) {
          const amp = this.valleyY - this.peakY; // amplitudo = tinggi pantulan
          // Real floor bounce needs meaningful amplitude + cooldown
          const minAmp = 0.055;
          const minGap = amp > 0.10 ? 220 : 280; // jeda minimal antar dribble
          if (this.armed && amp >= minAmp && now - this.lastAt >= minGap) {
            this.dribbles++;
            this.combo++;
            this.bestCombo = Math.max(this.bestCombo, this.combo);
            this.lastAt = now;
            // Sisi kiri / kanan lapangan (dari posisi X bola)
            this.lastSide =
              p.x < 0.4 ? "LEFT" : p.x > 0.6 ? "RIGHT" : "—";
            if (this.lastSide === "LEFT") this.leftCount++;
            if (this.lastSide === "RIGHT") this.rightCount++;
            // Poin bonus: pantulan tinggi + combo panjang
            let pts = 1;
            if (amp >= 0.10) pts++;
            if (this.combo >= 5) pts++;
            if (this.combo >= 10) pts++;
            this.score += pts;
            this.goingDown = false;
            this.armed = false;
            this.valleyY = 0;
            this.peakY = this.smoothY;
            this.upFrames = 0;
            this.downFrames = 0;
            return true;
          }
          // Weak bounce / noise — reset without scoring
          // Pantulan lemah / noise → reset tanpa skor
          this.goingDown = false;
          this.armed = false;
          this.valleyY = 0;
          this.peakY = this.smoothY;
          this.upFrames = 0;
        }
      } else {
        // Gerak sangat kecil: pelan-pelan kurangi hitungan frame
        this.downFrames = Math.max(0, this.downFrames - 1);
        this.upFrames = Math.max(0, this.upFrames - 1);
      }

      // Combo putus jika lama tidak ada dribble
      if (this.combo && now - this.lastAt > 1500) this.combo = 0;
      return false;
    }
  }

  /* ========== BAGIAN: Mesin Tembakan (MAKE / MISS) ========== */
  /**
   * APA: Menilai apakah bola masuk ring (MAKE) atau meleset (MISS).
   * KAPAN: Tiap frame saat mode shooting; butuh kotak ring (rimBox).
   *
   * ANALOGI seperti melewati pintu:
   *   1) Bola harus dulu naik DI ATAS ambang pintu (atas ring).
   *   2) Lalu turun dan LEWAT DI DALAM kotak pintu (area ring).
   *   3) Jika lewat tengah/bawah ring dengan rapi → MAKE.
   *   4) Jika sudah di atas lalu jatuh jauh di samping → MISS.
   *
   * Catatan koordinat: y=0 atas layar, y=1 bawah layar
   *   (bola "naik" = y mengecil; bola "turun" = y membesar).
   */
  /**
   * Shot scoring: MAKE when ball goes above rim then passes down through rim box.
   */
  class ShotEngine {
    constructor() {
      this.reset();
    }
    /** APA: Reset fase tembakan (belum ada "di atas ring"). */
    reset() {
      this.wasAbove = false;   // pernah di atas ring?
      this.enteredRim = false; // pernah masuk kotak ring?
      this.insideFrames = 0;
      this.peakY = 1;
      this.lastEvent = 0;      // waktu MAKE/MISS terakhir (anti double-count)
      this.prevY = null;
      this.trail = [];         // jejak posisi singkat (histori)
    }
    /**
     * APA: Proses posisi bola + kotak ring; kembalikan "MAKE", "MISS", atau null.
     * PARAMETER: p = posisi bola; rimBox = {left,right,top,bottom}; now = ms.
     * RETURN: "MAKE" | "MISS" | null (belum ada keputusan).
     */
    onBall(p, rimBox, now = Date.now()) {
      if (!rimBox) return null;
      if (!p || p.conf < 0.12) {
        // if we lost ball after it entered rim while descending → MAKE
        // Hilang di dalam ring setelah turun → sering berarti bola masuk net
        if (
          this.wasAbove &&
          this.enteredRim &&
          this.insideFrames >= 2 &&
          now - this.lastEvent > 700
        ) {
          this.lastEvent = now;
          this._clear();
          return "MAKE";
        }
        return null;
      }

      this.trail.push(p);
      if (this.trail.length > 24) this.trail.shift();

      // Sedikit "bantalan" horizontal agar deteksi tidak terlalu ketat
      const padX = (rimBox.right - rimBox.left) * 0.08;
      const left = rimBox.left - padX;
      const right = rimBox.right + padX;
      const top = rimBox.top;
      const bottom = rimBox.bottom;
      const midY = (top + bottom) / 2;
      const inX = p.x >= left && p.x <= right;
      const inRim =
        p.x >= rimBox.left &&
        p.x <= rimBox.right &&
        p.y >= top &&
        p.y <= bottom + 0.02;
      const descending =
        this.prevY != null ? p.y > this.prevY + 0.0015 : true;
      this.prevY = p.y;

      // Phase 1: ball rises above rim
      // Fase 1: bola naik di atas ring (dan masih sejajar X ring)
      if (p.y < top - 0.005 && inX) {
        this.wasAbove = true;
        this.peakY = Math.min(this.peakY, p.y);
      } else if (p.y < top && inX) {
        this.wasAbove = true;
        this.peakY = Math.min(this.peakY, p.y);
      }

      // Phase 2: enter / stay in rim while coming from above
      // Fase 2: dari atas, masuk & tinggal di kotak ring sambil turun
      if (this.wasAbove && inRim && descending) {
        this.enteredRim = true;
        this.insideFrames++;
      }

      // MAKE: after above, ball crosses mid/bottom of rim still roughly centered
      // MAKE: sudah di atas → masuk ring → melewati tengah/bawah sambil centered
      if (this.wasAbove && this.enteredRim && descending) {
        const centered =
          p.x >= rimBox.left - 0.02 && p.x <= rimBox.right + 0.02;
        const through =
          (p.y >= midY && this.insideFrames >= 2) ||
          (p.y > bottom && this.insideFrames >= 1) ||
          this.insideFrames >= 4;
        if (through && centered && now - this.lastEvent > 750) {
          this.lastEvent = now;
          this._clear();
          return "MAKE";
        }
      }

      // MISS: went above then clearly outside below
      // MISS jelas: sudah di atas, lalu jauh di bawah di luar samping ring
      if (
        this.wasAbove &&
        p.y > bottom + 0.12 &&
        (p.x < left - 0.06 || p.x > right + 0.06)
      ) {
        if (now - this.lastEvent > 750) {
          this.lastEvent = now;
          this._clear();
          return "MISS";
        }
      }

      // MISS timeout: above then gone sideways without entering
      // MISS lembut: pernah di atas tapi tidak masuk ring, lalu keluar samping
      if (
        this.wasAbove &&
        !this.enteredRim &&
        p.y > midY &&
        !inX &&
        now - this.lastEvent > 900
      ) {
        // soft miss only if clearly past rim height
        if (p.y > bottom) {
          this.lastEvent = now;
          this._clear();
          return "MISS";
        }
      }

      return null;
    }
    /** APA: Hapus status fase setelah MAKE/MISS tercatat. */
    _clear() {
      this.wasAbove = false;
      this.enteredRim = false;
      this.insideFrames = 0;
      this.peakY = 1;
      this.prevY = null;
      this.trail = [];
    }
  }

  // Ekspor API publik untuk dipakai file JS / halaman lain
  return { detect, TrackerFilter, DribbleEngine, ShotEngine };
})();
