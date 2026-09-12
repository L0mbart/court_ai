/**
 * CourtAI ball sensor — orange blob detect + smooth tracking + dribble/shot engines.
 */
window.BallTracker = (() => {
  // --- color: basketball orange in RGB approx HSV ---
  function isOrange(r, g, b) {
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    if (max < 70) return false;
    const sat = max === 0 ? 0 : (max - min) / max;
    if (sat < 0.22) return false;
    let hue = 0;
    const d = max - min || 1;
    if (max === r) hue = ((g - b) / d) % 6;
    else if (max === g) hue = (b - r) / d + 2;
    else hue = (r - g) / d + 4;
    hue *= 60;
    if (hue < 0) hue += 360;
    // orange ~8..45 deg, allow reddish basketball
    if (hue < 5 || hue > 55) return false;
    if (r < 90) return false;
    if (r < g) return false;
    if (b > r * 0.85) return false;
    return true;
  }

  /**
   * Find densest orange blob via coarse grid, then refine.
   */
  function detect(ctx, w, h, step = 4) {
    const img = ctx.getImageData(0, 0, w, h);
    const d = img.data;
    const gw = Math.max(8, Math.floor(w / 24));
    const gh = Math.max(8, Math.floor(h / 24));
    const grid = new Float32Array(gw * gh);
    const gxSum = new Float32Array(gw * gh);
    const gySum = new Float32Array(gw * gh);

    for (let y = 0; y < h; y += step) {
      for (let x = 0; x < w; x += step) {
        const i = (y * w + x) * 4;
        if (!isOrange(d[i], d[i + 1], d[i + 2])) continue;
        const cx = Math.min(gw - 1, Math.floor((x / w) * gw));
        const cy = Math.min(gh - 1, Math.floor((y / h) * gh));
        const gi = cy * gw + cx;
        grid[gi] += 1;
        gxSum[gi] += x;
        gySum[gi] += y;
      }
    }

    let best = -1;
    let bestI = -1;
    for (let i = 0; i < grid.length; i++) {
      if (grid[i] > best) {
        best = grid[i];
        bestI = i;
      }
    }
    if (best < 3 || bestI < 0) return null;

    const cellX = bestI % gw;
    const cellY = Math.floor(bestI / gw);
    // expand neighborhood ±1 cell
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
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
      }
    }
    if (rCount < 5) return null;

    const cx = rSumX / rCount / w;
    const cy = rSumY / rCount / h;
    const bw = (maxX - minX) / w;
    const bh = (maxY - minY) / h;
    const radius = Math.max(bw, bh) / 2;
    const aspect = bh < 0.001 ? 99 : bw / bh;
    if (radius < 0.008 || radius > 0.38) return null;
    if (aspect < 0.35 || aspect > 2.8) return null;

    return {
      x: cx,
      y: cy,
      radius,
      conf: Math.min(1, rCount / 80),
    };
  }

  /** EMA smoother + brief hold when detection drops. */
  class TrackerFilter {
    constructor() {
      this.x = null;
      this.y = null;
      this.radius = 0.05;
      this.conf = 0;
      this.lostSince = 0;
      this.vx = 0;
      this.vy = 0;
    }
    reset() {
      this.x = this.y = null;
      this.conf = 0;
      this.lostSince = 0;
      this.vx = this.vy = 0;
    }
    update(raw, now = Date.now()) {
      if (!raw) {
        if (this.x == null) return null;
        if (!this.lostSince) this.lostSince = now;
        const age = now - this.lostSince;
        // hold longer for smoother UI, but don't invent bounces
        if (age > 260) {
          this.x = this.y = null;
          this.conf = 0;
          return null;
        }
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
      if (this.x == null) {
        this.x = raw.x;
        this.y = raw.y;
        this.radius = raw.radius;
        this.conf = raw.conf;
        return { ...raw, predicted: false };
      }
      // stronger smoothing = less jitter
      const a = raw.conf > 0.6 ? 0.28 : 0.18;
      const nx = this.x * (1 - a) + raw.x * a;
      const ny = this.y * (1 - a) + raw.y * a;
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

  class DribbleEngine {
    constructor() {
      this.reset();
    }
    reset() {
      this.dribbles = 0;
      this.score = 0;
      this.combo = 0;
      this.bestCombo = 0;
      this.leftCount = 0;
      this.rightCount = 0;
      this.lastSide = "—";
      this.goingDown = false;
      this.peakY = 1;
      this.valleyY = 0;
      this.armed = false;
      this.lastAt = 0;
      this.prevY = null;
      this.smoothY = null;
      this.downFrames = 0;
      this.upFrames = 0;
    }
    onBall(p, now = Date.now()) {
      // Ignore weak / predicted samples so noise doesn't create fake dribbles
      if (!p || p.predicted || p.conf < 0.22) {
        if (this.combo && now - this.lastAt > 1400) this.combo = 0;
        return false;
      }
      // Heavy smooth on Y for clean bounce edges
      this.smoothY =
        this.smoothY == null ? p.y : this.smoothY * 0.78 + p.y * 0.22;
      if (this.prevY == null) {
        this.prevY = this.smoothY;
        return false;
      }
      const dy = this.smoothY - this.prevY;
      this.prevY = this.smoothY;

      // Clear downward / upward motion required
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
          const amp = this.valleyY - this.peakY;
          // Real floor bounce needs meaningful amplitude + cooldown
          const minAmp = 0.055;
          const minGap = amp > 0.10 ? 220 : 280;
          if (this.armed && amp >= minAmp && now - this.lastAt >= minGap) {
            this.dribbles++;
            this.combo++;
            this.bestCombo = Math.max(this.bestCombo, this.combo);
            this.lastAt = now;
            this.lastSide =
              p.x < 0.4 ? "LEFT" : p.x > 0.6 ? "RIGHT" : "—";
            if (this.lastSide === "LEFT") this.leftCount++;
            if (this.lastSide === "RIGHT") this.rightCount++;
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
          this.goingDown = false;
          this.armed = false;
          this.valleyY = 0;
          this.peakY = this.smoothY;
          this.upFrames = 0;
        }
      } else {
        this.downFrames = Math.max(0, this.downFrames - 1);
        this.upFrames = Math.max(0, this.upFrames - 1);
      }

      if (this.combo && now - this.lastAt > 1500) this.combo = 0;
      return false;
    }
  }

  /**
   * Shot scoring: MAKE when ball goes above rim then passes down through rim box.
   */
  class ShotEngine {
    constructor() {
      this.reset();
    }
    reset() {
      this.wasAbove = false;
      this.enteredRim = false;
      this.insideFrames = 0;
      this.peakY = 1;
      this.lastEvent = 0;
      this.prevY = null;
      this.trail = [];
    }
    onBall(p, rimBox, now = Date.now()) {
      if (!rimBox) return null;
      if (!p || p.conf < 0.12) {
        // if we lost ball after it entered rim while descending → MAKE
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
      if (p.y < top - 0.005 && inX) {
        this.wasAbove = true;
        this.peakY = Math.min(this.peakY, p.y);
      } else if (p.y < top && inX) {
        this.wasAbove = true;
        this.peakY = Math.min(this.peakY, p.y);
      }

      // Phase 2: enter / stay in rim while coming from above
      if (this.wasAbove && inRim && descending) {
        this.enteredRim = true;
        this.insideFrames++;
      }

      // MAKE: after above, ball crosses mid/bottom of rim still roughly centered
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
    _clear() {
      this.wasAbove = false;
      this.enteredRim = false;
      this.insideFrames = 0;
      this.peakY = 1;
      this.prevY = null;
      this.trail = [];
    }
  }

  return { detect, TrackerFilter, DribbleEngine, ShotEngine };
})();
