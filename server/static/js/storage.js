/**
 * ============================================================
 * FILE: storage.js — "buku catatan" sesi latihan di browser
 * ============================================================
 * Apa file ini?
 *   Menyimpan hasil latihan (MAKE/MISS, dribble, dll.) di
 *   localStorage browser, membuat ringkasan, dan mengirim
 *   data ke server agar muncul di dashboard.
 *
 * Alur kerja singkat:
 *   1. save()     → tambah 1 sesi baru ke daftar lokal.
 *   2. all() / summary() → baca daftar / hitung statistik.
 *   3. syncToServer() → kirim semua sesi ke API backend.
 *
 * Dipakai lewat: window.CourtStorage
 * Catatan: data lokal tinggal di perangkat user (bukan otomatis cloud).
 * ============================================================
 */
window.CourtStorage = (() => {
  /* ========== BAGIAN: Kunci Penyimpanan ========== */
  // Nama "laci" di localStorage — ganti versi jika format data berubah
  const KEY = "courtai_sessions_v1";

  /* ========== BAGIAN: Baca & Tulis Sesi Lokal ========== */

  /**
   * APA: Ambil semua sesi latihan yang tersimpan di browser.
   * KAPAN: Saat buka riwayat, hitung summary, atau sync ke server.
   * RETURN: array objek sesi; [] jika kosong / data rusak.
   *
   * ANALOGI: Membuka buku catatan dan membaca semua halaman.
   *   Kalau catatannya rusak (JSON error), kita anggap kosong saja.
   */
  function all() {
    try {
      return JSON.parse(localStorage.getItem(KEY) || "[]");
    } catch {
      return [];
    }
  }

  /**
   * APA: Simpan satu sesi latihan baru di depan daftar.
   * KAPAN: Setelah user selesai 1 ronde drill.
   * PARAMETER: session = objek berisi title, makes, misses, drillId, dll.
   * RETURN: tidak ada (void) — efeknya tertulis di localStorage.
   *
   * Catatan: maksimal 200 sesi terakhir (yang lama dibuang).
   */
  function save(session) {
    const list = all();
    // unshift = sisip di depan (sesi terbaru paling atas)
    list.unshift({ ...session, createdAt: Date.now(), source: "web" });
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, 200)));
  }

  /**
   * APA: Hitung ringkasan statistik dari semua sesi lokal.
   * KAPAN: Menampilkan angka total di UI (sessions, FG%, dll.).
   * RETURN: { sessions, makes, misses, attempts, fg }
   *   fg = field goal % (persentase masuk), atau null jika belum ada tembakan.
   *
   * ANALOGI: Seperti rapor — jumlahkan semua nilai lalu hitung rata-rata.
   */
  function summary() {
    const list = all();
    const makes = list.reduce((a, s) => a + (s.makes || 0), 0);
    const misses = list.reduce((a, s) => a + (s.misses || 0), 0);
    const attempts = makes + misses;
    return {
      sessions: list.length,
      makes,
      misses,
      attempts,
      fg: attempts ? (makes * 100) / attempts : null,
    };
  }

  /**
   * APA: Hapus seluruh riwayat sesi lokal.
   * KAPAN: User minta reset data di perangkat ini.
   */
  function clear() {
    localStorage.removeItem(KEY);
  }

  /* ========== BAGIAN: Sinkron ke Server ========== */

  /**
   * APA: Kirim semua sesi lokal ke server (/api/sessions/sync).
   * KAPAN: User tekan sync / upload ke dashboard.
   * RETURN: JSON respons dari server (Promise).
   *
   * Langkah di dalam:
   *   1) Pastikan ada nama pemain & userId (buat jika belum ada).
   *   2) Ubah format sesi lokal → format yang diharapkan API.
   *   3) POST ke server; jika gagal, lempar Error.
   *
   * ANALOGI: Menyalin catatan dari buku pribadi ke papan skor sekolah
   *   supaya pelatih / dashboard bisa melihatnya.
   */
  async function syncToServer() {
    // Nama tampilan di dashboard (minta sekali lewat prompt jika belum ada)
    const name = localStorage.getItem("courtai_web_name") || prompt("Nama pemain untuk dashboard?") || "Web Player";
    localStorage.setItem("courtai_web_name", name);
    // ID unik sederhana untuk pemain web ini
    const userId = localStorage.getItem("courtai_web_id") || ("web_" + Math.random().toString(36).slice(2, 8));
    localStorage.setItem("courtai_web_id", userId);
    // Petakan tiap sesi ke bentuk data server
    const sessions = all().map((s) => {
      // drill dribble khusus vs latihan shoot biasa
      const type = s.drillId === "pound_the_rock" ? "dribble" : "shoot";
      const attempts = (s.makes || 0) + (s.misses || 0);
      return {
        title: s.title,
        drillId: s.drillId || "web",
        makes: s.makes || 0,
        misses: s.misses || 0,
        durationMs: s.durationMs || 0,
        deviceId: "browser",
        source: "web",
        userId,
        userName: name,
        activityType: type,
        activityLabel: type === "dribble" ? "Dribble - Pound The Rock" : "Shoot - " + (s.title || "Web"),
        attempts,
        fgPercent: type === "shoot" && attempts ? (s.makes * 100) / attempts : null,
        // Untuk dribble, "makes" dipakai sebagai skor dribble
        score: type === "dribble" ? s.makes : null,
        createdAt: s.createdAt || Date.now(),
        // clientId membantu server menghindari duplikat
        clientId: userId + "_" + (s.createdAt || Date.now()) + "_" + (s.title || ""),
      };
    });
    const res = await fetch("/api/sessions/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessions }),
    });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  // API publik
  return { all, save, summary, clear, syncToServer };
})();
