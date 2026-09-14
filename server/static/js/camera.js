/**
 * ============================================================
 * FILE: camera.js — pembantu membuka & menampilkan kamera
 * ============================================================
 * Apa file ini?
 *   Mengatur akses kamera browser (HP & komputer), menghindari
 *   kamera virtual, menempelkan video ke elemen <video>, dan
 *   menggambar preview ke canvas.
 *
 * Alur kerja singkat:
 *   1. Cek keamanan (HTTPS / localhost) — HP butuh koneksi aman.
 *   2. openSafe() → minta izin kamera, coba beberapa cara sampai berhasil.
 *   3. attach() + startPreviewLoop() → tampilkan live video di layar.
 *
 * Dipakai lewat: window.CourtCamera
 * Catatan: di HP, getUserMedia biasanya hanya jalan di HTTPS.
 * ============================================================
 */
/**
 * CourtAI web camera helper (desktop + mobile).
 * Mobile browsers require HTTPS (secure context) for getUserMedia on LAN.
 */
window.CourtCamera = (function () {
  /* ========== BAGIAN: Daftar Kamera "Berbahaya" (Virtual) ========== */
  /**
   * Nama-nama kamera virtual / mirror yang sering dipakai cheat atau
   * bukan kamera fisik. Kita blokir agar deteksi bola tetap adil.
   * ANALOGI: seperti daftar "baju palsu" — jika labelnya cocok, ditolak.
   */
  const RISKY = [
    /transcreen/i,
    /idea\s*camera/i,
    /wonder\s*camera/i,
    /virtual/i,
    /obs\s*virtual/i,
    /manycam/i,
    /snap\s*camera/i,
    /ndi/i,
    /iriun/i,
    /epoccam/i,
    /droidcam/i,
  ];

  // Kunci penyimpanan di browser (localStorage)
  const LS_KEY = "courtai_safe_cam_id";   // ID kamera terakhir yang aman
  const LS_FACING = "courtai_facing";     // "user" (depan) / "environment" (belakang)

  /* ========== BAGIAN: Cek Perangkat & Keamanan ========== */

  /**
   * APA: Menebak apakah user sedang di HP / tablet.
   * KAPAN: Saat memilih strategi buka kamera (HP vs PC beda cara).
   * RETURN: true jika user-agent terlihat mobile.
   */
  function isMobile() {
    return /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || "");
  }

  /**
   * APA: Cek apakah halaman cukup "aman" untuk akses kamera.
   * KAPAN: Sebelum getUserMedia.
   * RETURN: true jika secure context / localhost.
   *
   * ANALOGI: Kamera seperti kunci rumah — browser hanya memberi kunci
   *   jika kamu datang lewat pintu resmi (HTTPS), bukan gang gelap (HTTP).
   */
  function isSecureOk() {
    if (window.isSecureContext) return true;
    const h = location.hostname;
    return h === "localhost" || h === "127.0.0.1" || h === "[::1]";
  }

  /**
   * APA: Buat saran URL HTTPS (port 8443) jika user masih di HTTP.
   * RETURN: string URL lengkap yang bisa dibuka di HP.
   */
  function httpsHintUrl() {
    const host = location.hostname || "127.0.0.1";
    const path = location.pathname + location.search + location.hash;
    return "https://" + host + ":8443" + path;
  }

  /**
   * APA: Lempar error ramah jika konteks tidak aman (terutama di HP).
   * KAPAN: Awal openSafe().
   */
  function assertSecureContext() {
    if (isSecureOk()) return;
    const url = httpsHintUrl();
    const err = new Error(
      "Kamera HP butuh HTTPS. Buka: " + url +
      " (terima peringatan sertifikat dulu). Jangan pakai http:// di mobile."
    );
    err.code = "INSECURE_CONTEXT";
    err.httpsUrl = url;
    throw err;
  }

  /**
   * APA: Apakah label kamera termasuk daftar virtual / risky?
   * PARAMETER: label = nama perangkat dari browser.
   * RETURN: true jika harus diblokir.
   */
  function isRiskyLabel(label) {
    return RISKY.some((re) => re.test(label || ""));
  }

  /**
   * APA: Label dianggap aman jika ada dan tidak risky.
   */
  function isSafeLabel(label) {
    if (!label || isRiskyLabel(label)) return false;
    return true;
  }

  /* ========== BAGIAN: Pilih Perangkat Kamera ========== */

  /**
   * APA: Ambil daftar semua perangkat video (kamera) di komputer/HP.
   * KAPAN: Saat desktop memilih kamera fisik yang aman.
   * RETURN: array device berjenis videoinput (bisa kosong).
   */
  async function listVideoInputs() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return [];
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices.filter((d) => d.kind === "videoinput");
  }

  /**
   * APA: Pilih deviceId kamera yang aman (bukan virtual).
   * Urutan prioritas: preferredId → yang diingat di localStorage → kamera berlabel aman.
   * PARAMETER: devices = daftar kamera; preferredId = ID pilihan user (opsional).
   * RETURN: deviceId string atau undefined.
   */
  function pickSafeDeviceId(devices, preferredId) {
    if (preferredId) {
      const pref = devices.find((d) => d.deviceId === preferredId);
      if (pref && !isRiskyLabel(pref.label)) return pref.deviceId;
    }
    const remembered = localStorage.getItem(LS_KEY);
    if (remembered) {
      const rem = devices.find((d) => d.deviceId === remembered);
      if (rem && !isRiskyLabel(rem.label)) return remembered;
    }
    const labeled = devices.find((d) => d.label && !isRiskyLabel(d.label));
    return labeled && labeled.deviceId;
  }

  /**
   * APA: Ambil nama (label) track video dari stream yang sedang hidup.
   */
  function trackLabel(stream) {
    const t = stream.getVideoTracks()[0];
    return (t && t.label) || "";
  }

  /**
   * APA: Ambil deviceId dari settings track video (jika browser mengizinkan).
   */
  function trackDeviceId(stream) {
    try {
      const t = stream.getVideoTracks()[0];
      return (t && t.getSettings && t.getSettings().deviceId) || "";
    } catch (_) {
      return "";
    }
  }

  /**
   * APA: Baca preferensi kamera depan/belakang dari localStorage.
   * RETURN: "environment" (belakang) atau "user" (depan / selfie).
   */
  function getFacing() {
    return localStorage.getItem(LS_FACING) === "environment" ? "environment" : "user";
  }

  /**
   * APA: Simpan preferensi facing ke localStorage.
   */
  function setFacing(facing) {
    localStorage.setItem(LS_FACING, facing === "environment" ? "environment" : "user");
  }

  /* ========== BAGIAN: Buka Kamera dengan Aman ========== */

  /**
   * APA: Membuka kamera dengan banyak percobaan (fallback) sampai berhasil.
   * KAPAN: User tekan mulai latihan / buka preview.
   * PARAMETER:
   *   preferredId = ID kamera yang diinginkan (lebih relevan di PC),
   *   options.facingMode = "user" | "environment" (lebih relevan di HP).
   * RETURN: MediaStream hidup (siap dipasang ke <video>).
   *
   * ANALOGI: Mengetuk beberapa pintu sampai ada yang dibuka —
   *   HP biasanya pakai facingMode dulu; PC lebih sering pakai deviceId.
   *   Kalau yang terbuka ternyata kamera virtual → ditutup lagi, lanjut coba.
   */
  async function openSafe(preferredId, options) {
    options = options || {};
    assertSecureContext();

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error("Browser tidak mendukung kamera. Pakai Chrome/Safari terbaru.");
    }

    const facing = options.facingMode || getFacing();
    const mobile = isMobile();
    const attempts = []; // daftar "cara minta kamera" yang akan dicoba berurutan

    // Mobile: facingMode first (deviceId often breaks on phones)
    // Di HP: minta lewat facingMode dulu (deviceId sering bermasalah)
    if (mobile || options.facingMode) {
      attempts.push({
        facingMode: { ideal: facing },
        width: { ideal: 1280 },
        height: { ideal: 720 },
      });
      attempts.push({ facingMode: facing });
      attempts.push({ facingMode: { exact: facing } });
      // fallback other camera
      const other = facing === "user" ? "environment" : "user";
      attempts.push({ facingMode: other });
    }

    // Di desktop: coba deviceId kamera fisik yang aman
    if (!mobile) {
      const devices = await listVideoInputs();
      const first = pickSafeDeviceId(devices, preferredId);
      if (first) attempts.push({ deviceId: { exact: first } });
      for (const d of devices) {
        if (isRiskyLabel(d.label)) continue;
        if (first && d.deviceId === first) continue;
        attempts.push({ deviceId: { ideal: d.deviceId } });
      }
    }

    // Cadangan terakhir: biarkan browser pilih apa saja
    attempts.push(true);
    attempts.push({ facingMode: "user" });
    attempts.push({ facingMode: "environment" });

    let lastErr = null;
    for (const video of attempts) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: false,
          video: video,
        });
        const label = trackLabel(stream);
        // Tolak kamera virtual meski browser berhasil membukanya
        if (isRiskyLabel(label)) {
          stream.getTracks().forEach((t) => t.stop());
          lastErr = new Error("Kamera virtual diblokir: " + label);
          continue;
        }
        const track = stream.getVideoTracks()[0];
        // Di PC, coba naikkan resolusi agar deteksi bola lebih jelas
        if (track && !mobile) {
          try {
            await track.applyConstraints({
              width: { ideal: 1280 },
              height: { ideal: 720 },
            });
          } catch (_) {}
        }
        // Ingat kamera yang berhasil supaya next time lebih cepat
        const did = trackDeviceId(stream);
        if (did) localStorage.setItem(LS_KEY, did);
        try {
          const fm = track && track.getSettings && track.getSettings().facingMode;
          if (fm) setFacing(fm);
        } catch (_) {}
        return stream;
      } catch (e) {
        lastErr = e;
      }
    }

    // Ubah error teknis browser jadi pesan yang mudah dipahami
    const msg = (lastErr && lastErr.message) || "Tidak bisa membuka kamera";
    if (/NotAllowed|Permission|Denied/i.test(msg)) {
      throw new Error("Izin kamera ditolak. Aktifkan kamera untuk situs ini di pengaturan browser.");
    }
    if (/NotFound|DevicesNotFound/i.test(msg)) {
      throw new Error("Kamera tidak ditemukan di perangkat ini.");
    }
    throw lastErr || new Error(msg);
  }

  /**
   * APA: Tukar kamera depan ↔ belakang, lalu buka ulang.
   * KAPAN: Tombol flip kamera di HP.
   * PARAMETER: currentFacing = facing yang sedang dipakai.
   * RETURN: MediaStream kamera baru.
   */
  async function switchFacing(currentFacing) {
    const next = currentFacing === "environment" ? "user" : "environment";
    setFacing(next);
    return openSafe(null, { facingMode: next });
  }

  /* ========== BAGIAN: Pasang Video & Preview ========== */

  /**
   * APA: Menempelkan MediaStream ke elemen <video> dan menunggu siap diputar.
   * KAPAN: Setelah openSafe() berhasil.
   * PARAMETER: videoEl = elemen HTMLVideoElement; stream = hasil getUserMedia.
   * RETURN: { width, height, label } ukuran aktual + nama kamera.
   *
   * Catatan: muted + playsinline penting di HP agar autoplay tidak diblokir.
   */
  async function attach(videoEl, stream) {
    // Bersihkan sumber lama dulu
    try { videoEl.pause(); } catch (_) {}
    videoEl.removeAttribute("src");
    videoEl.srcObject = null;

    // Atribut wajib agar video jalan di iOS/Android tanpa gestur ekstra
    videoEl.setAttribute("playsinline", "true");
    videoEl.setAttribute("webkit-playsinline", "true");
    videoEl.setAttribute("autoplay", "true");
    videoEl.setAttribute("muted", "true");
    videoEl.muted = true;
    videoEl.defaultMuted = true;
    videoEl.autoplay = true;
    videoEl.playsInline = true;
    videoEl.controls = false;
    videoEl.style.display = "block";
    videoEl.style.opacity = "1";
    videoEl.style.visibility = "visible";

    videoEl.srcObject = stream;

    // Tunggu metadata / data, atau timeout 2 detik supaya tidak menggantung
    await new Promise((resolve) => {
      const done = () => resolve();
      if (videoEl.readyState >= 1) return done();
      videoEl.onloadedmetadata = done;
      videoEl.onloadeddata = done;
      setTimeout(done, 2000);
    });

    // Coba play; jika gagal, tunggu sebentar lalu coba lagi
    try {
      await videoEl.play();
    } catch (_) {
      await new Promise((r) => setTimeout(r, 120));
      try { await videoEl.play(); } catch (_) {}
    }

    // Tunggu sampai ukuran video terbaca (maks ~3 detik)
    const start = Date.now();
    while (Date.now() - start < 3000) {
      if (videoEl.videoWidth > 0 && videoEl.videoHeight > 0) break;
      await new Promise((r) => setTimeout(r, 100));
    }
    return {
      width: videoEl.videoWidth,
      height: videoEl.videoHeight,
      label: trackLabel(stream),
    };
  }

  /**
   * APA: Loop animasi yang menggambar frame video ke canvas (preview penuh).
   * KAPAN: Saat UI menampilkan live camera di canvas overlay.
   * PARAMETER:
   *   videoEl  = sumber video,
   *   canvasEl = tempat menggambar,
   *   getMirror = boolean atau fungsi → true jika gambar perlu dibalik (mirror).
   * RETURN: fungsi stop() untuk menghentikan loop (set alive=false).
   *
   * ANALOGI: Seperti proyektor yang terus memotret layar HP lalu
   *   menempelkannya penuh ke "papan" canvas, bisa dibalik kiri-kanan
   *   supaya gerak tangan terasa seperti cermin.
   */
  function startPreviewLoop(videoEl, canvasEl, getMirror) {
    let alive = true;
    const ctx = canvasEl.getContext("2d");

    function frame() {
      if (!alive) return;
      const stage = canvasEl.parentElement;
      const w = Math.max(2, stage ? stage.clientWidth : canvasEl.clientWidth);
      const h = Math.max(2, stage ? stage.clientHeight : canvasEl.clientHeight);
      // Sesuaikan resolusi canvas dengan ukuran tampilan
      if (canvasEl.width !== w || canvasEl.height !== h) {
        canvasEl.width = w;
        canvasEl.height = h;
      }
      ctx.save();
      ctx.fillStyle = "#000";
      ctx.fillRect(0, 0, w, h);
      if (videoEl.videoWidth > 0) {
        const mirror = typeof getMirror === "function" ? getMirror() : !!getMirror;
        if (mirror) {
          // Mirror: balik sumbu X (seperti selfie)
          ctx.translate(w, 0);
          ctx.scale(-1, 1);
        }
        // Scale "cover": isi penuh tanpa distorsi, potong sisi jika perlu
        const vw = videoEl.videoWidth;
        const vh = videoEl.videoHeight;
        const scale = Math.max(w / vw, h / vh);
        const dw = vw * scale;
        const dh = vh * scale;
        const dx = (w - dw) / 2;
        const dy = (h - dh) / 2;
        ctx.drawImage(videoEl, dx, dy, dw, dh);
      }
      ctx.restore();
      requestAnimationFrame(frame); // jadwalkan frame berikutnya (~60 fps)
    }
    requestAnimationFrame(frame);
    return () => { alive = false; };
  }

  // API publik yang bisa dipanggil dari halaman / modul lain
  return {
    listVideoInputs,
    pickSafeDeviceId,
    isRiskyLabel,
    isSafeLabel,
    isMobile,
    isSecureOk,
    httpsHintUrl,
    getFacing,
    setFacing,
    openSafe,
    switchFacing,
    attach,
    startPreviewLoop,
  };
})();
