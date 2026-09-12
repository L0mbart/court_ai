window.CourtStorage = (() => {
  const KEY = "courtai_sessions_v1";

  function all() {
    try {
      return JSON.parse(localStorage.getItem(KEY) || "[]");
    } catch {
      return [];
    }
  }

  function save(session) {
    const list = all();
    list.unshift({ ...session, createdAt: Date.now(), source: "web" });
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, 200)));
  }

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

  function clear() {
    localStorage.removeItem(KEY);
  }

  async function syncToServer() {
    const name = localStorage.getItem("courtai_web_name") || prompt("Nama pemain untuk dashboard?") || "Web Player";
    localStorage.setItem("courtai_web_name", name);
    const userId = localStorage.getItem("courtai_web_id") || ("web_" + Math.random().toString(36).slice(2, 8));
    localStorage.setItem("courtai_web_id", userId);
    const sessions = all().map((s) => {
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
        score: type === "dribble" ? s.makes : null,
        createdAt: s.createdAt || Date.now(),
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

  return { all, save, summary, clear, syncToServer };
})();

