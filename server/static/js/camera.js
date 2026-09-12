/**
 * CourtAI web camera helper (desktop + mobile).
 * Mobile browsers require HTTPS (secure context) for getUserMedia on LAN.
 */
window.CourtCamera = (function () {
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

  const LS_KEY = "courtai_safe_cam_id";
  const LS_FACING = "courtai_facing";

  function isMobile() {
    return /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || "");
  }

  function isSecureOk() {
    if (window.isSecureContext) return true;
    const h = location.hostname;
    return h === "localhost" || h === "127.0.0.1" || h === "[::1]";
  }

  function httpsHintUrl() {
    const host = location.hostname || "127.0.0.1";
    const path = location.pathname + location.search + location.hash;
    return "https://" + host + ":8443" + path;
  }

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

  function isRiskyLabel(label) {
    return RISKY.some((re) => re.test(label || ""));
  }

  function isSafeLabel(label) {
    if (!label || isRiskyLabel(label)) return false;
    return true;
  }

  async function listVideoInputs() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return [];
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices.filter((d) => d.kind === "videoinput");
  }

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

  function trackLabel(stream) {
    const t = stream.getVideoTracks()[0];
    return (t && t.label) || "";
  }

  function trackDeviceId(stream) {
    try {
      const t = stream.getVideoTracks()[0];
      return (t && t.getSettings && t.getSettings().deviceId) || "";
    } catch (_) {
      return "";
    }
  }

  function getFacing() {
    return localStorage.getItem(LS_FACING) === "environment" ? "environment" : "user";
  }

  function setFacing(facing) {
    localStorage.setItem(LS_FACING, facing === "environment" ? "environment" : "user");
  }

  async function openSafe(preferredId, options) {
    options = options || {};
    assertSecureContext();

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error("Browser tidak mendukung kamera. Pakai Chrome/Safari terbaru.");
    }

    const facing = options.facingMode || getFacing();
    const mobile = isMobile();
    const attempts = [];

    // Mobile: facingMode first (deviceId often breaks on phones)
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
        if (isRiskyLabel(label)) {
          stream.getTracks().forEach((t) => t.stop());
          lastErr = new Error("Kamera virtual diblokir: " + label);
          continue;
        }
        const track = stream.getVideoTracks()[0];
        if (track && !mobile) {
          try {
            await track.applyConstraints({
              width: { ideal: 1280 },
              height: { ideal: 720 },
            });
          } catch (_) {}
        }
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

    const msg = (lastErr && lastErr.message) || "Tidak bisa membuka kamera";
    if (/NotAllowed|Permission|Denied/i.test(msg)) {
      throw new Error("Izin kamera ditolak. Aktifkan kamera untuk situs ini di pengaturan browser.");
    }
    if (/NotFound|DevicesNotFound/i.test(msg)) {
      throw new Error("Kamera tidak ditemukan di perangkat ini.");
    }
    throw lastErr || new Error(msg);
  }

  async function switchFacing(currentFacing) {
    const next = currentFacing === "environment" ? "user" : "environment";
    setFacing(next);
    return openSafe(null, { facingMode: next });
  }

  async function attach(videoEl, stream) {
    try { videoEl.pause(); } catch (_) {}
    videoEl.removeAttribute("src");
    videoEl.srcObject = null;

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

    await new Promise((resolve) => {
      const done = () => resolve();
      if (videoEl.readyState >= 1) return done();
      videoEl.onloadedmetadata = done;
      videoEl.onloadeddata = done;
      setTimeout(done, 2000);
    });

    try {
      await videoEl.play();
    } catch (_) {
      await new Promise((r) => setTimeout(r, 120));
      try { await videoEl.play(); } catch (_) {}
    }

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

  function startPreviewLoop(videoEl, canvasEl, getMirror) {
    let alive = true;
    const ctx = canvasEl.getContext("2d");

    function frame() {
      if (!alive) return;
      const stage = canvasEl.parentElement;
      const w = Math.max(2, stage ? stage.clientWidth : canvasEl.clientWidth);
      const h = Math.max(2, stage ? stage.clientHeight : canvasEl.clientHeight);
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
          ctx.translate(w, 0);
          ctx.scale(-1, 1);
        }
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
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
    return () => { alive = false; };
  }

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
