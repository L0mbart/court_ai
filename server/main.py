import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

BASE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BASE_DIR.parent
DATA_DIR = BASE_DIR / "data"
UPLOAD_DIR = DATA_DIR / "uploads"
VERSION_FILE = DATA_DIR / "version.json"
SESSIONS_FILE = DATA_DIR / "sessions.json"
IOS_ZIP_NAME = "CourtAI-iOS-source.zip"

UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
DATA_DIR.mkdir(parents=True, exist_ok=True)

from accounts_store import AccountStore
from admin_auth import AdminStore

accounts = AccountStore(DATA_DIR)
accounts.ensure_seed()
admin_store = AdminStore(DATA_DIR)

app = FastAPI(title="CourtAI Dashboard", version="1.2.0")
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))

ADMIN_COOKIE = "courtai_admin"
PUBLIC_PREFIXES = (
    "/static",
    "/login",
    "/logout",
    "/download",
    "/app",
    "/api/health",
    "/api/version",
    "/api/apk/",
    "/api/ios/",
    "/api/ipa/",
    "/api/auth/login",
    "/api/sessions/sync",
    "/api/sessions",  # POST sync from APK uses /api/sessions and /api/sessions/sync
    "/favicon.ico",
)


def is_public_path(path: str, method: str) -> bool:
    if path.startswith("/static"):
        return True
    if path in ("/login", "/logout", "/download", "/favicon.ico"):
        return True
    if path.startswith("/app"):
        return True
    if path.startswith("/api/health") or path.startswith("/api/version"):
        return True
    if path.startswith("/api/apk/") or path.startswith("/api/ios/") or path.startswith("/api/ipa/"):
        return True
    if path == "/api/auth/login":
        return True
    # Mobile / web training sync must stay open
    if path == "/api/sessions/sync" and method == "POST":
        return True
    if path == "/api/sessions" and method == "POST":
        return True
    return False


def admin_logged_in(request: Request) -> bool:
    token = request.cookies.get(ADMIN_COOKIE)
    return admin_store.session_valid(token)


@app.middleware("http")
async def admin_auth_middleware(request: Request, call_next):
    path = request.url.path
    method = request.method.upper()
    if is_public_path(path, method):
        return await call_next(request)
    if admin_logged_in(request):
        return await call_next(request)
    # API JSON -> 401; browser pages -> redirect login
    if path.startswith("/api/"):
        return HTMLResponse(
            '{"detail":"Admin login required"}',
            status_code=401,
            media_type="application/json",
        )
    next_url = path
    if request.url.query:
        next_url += "?" + request.url.query
    from urllib.parse import quote

    return RedirectResponse(f"/login?next={quote(next_url)}", status_code=303)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        path.write_text(json.dumps(default, indent=2), encoding="utf-8")
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def get_version() -> dict:
    return read_json(
        VERSION_FILE,
        {
            "versionCode": 1,
            "versionName": "1.0.0",
            "forceUpdate": False,
            "changelog": "",
            "apkFile": None,
            "iosFile": None,
            "ipaFile": None,
            "updatedAt": None,
        },
    )


def ensure_release_files() -> dict:
    """Pastikan file download Android/iOS tersedia di uploads."""
    import zipfile

    version = get_version()
    changed = False

    apk_name = version.get("apkFile")
    apk_path = UPLOAD_DIR / apk_name if apk_name else None
    if not apk_path or not apk_path.exists():
        candidates = [
            PROJECT_ROOT / "apk" / "CourtAI-debug.apk",
            PROJECT_ROOT / "apk" / "CourtAI-1.0.0.apk",
        ]
        src = next((p for p in candidates if p.exists()), None)
        if src:
            dest_name = "courtai-android-latest.apk"
            shutil.copy2(src, UPLOAD_DIR / dest_name)
            version["apkFile"] = dest_name
            version["versionName"] = version.get("versionName") or "1.1.3"
            version["versionCode"] = int(version.get("versionCode") or 5)
            version["updatedAt"] = _now()
            changed = True

    ios_zip = UPLOAD_DIR / IOS_ZIP_NAME
    ios_src = PROJECT_ROOT / "ios"
    if ios_src.exists() and (
        not ios_zip.exists() or ios_zip.stat().st_mtime < ios_src.stat().st_mtime
    ):
        if ios_zip.exists():
            ios_zip.unlink()
        with zipfile.ZipFile(ios_zip, "w", zipfile.ZIP_DEFLATED) as zf:
            for path in ios_src.rglob("*"):
                if path.is_file():
                    if any(
                        part in {".git", "DerivedData", "build", ".gradle"}
                        for part in path.parts
                    ):
                        continue
                    zf.write(path, arcname=str(path.relative_to(PROJECT_ROOT)))
        version["iosFile"] = IOS_ZIP_NAME
        version["iosUpdatedAt"] = _now()
        changed = True
    elif ios_zip.exists():
        version["iosFile"] = IOS_ZIP_NAME

    if version.get("ipaFile"):
        ipa_path = UPLOAD_DIR / version["ipaFile"]
        if not ipa_path.exists():
            version["ipaFile"] = None
            changed = True

    if changed:
        write_json(VERSION_FILE, version)
    return version


def slugify_name(name: str) -> str:
    import re
    import unicodedata

    text = unicodedata.normalize("NFD", (name or "").strip().lower())
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    text = re.sub(r"[^a-z0-9]+", "-", text).strip("-")
    return (text or "player")[:40]


def normalize_session(raw: dict) -> dict:
    makes = int(raw.get("makes") or 0)
    misses = int(raw.get("misses") or 0)
    attempts = int(raw.get("attempts") or (makes + misses))
    drill_id = raw.get("drillId") or "freestyle"
    activity_type = raw.get("activityType")
    if not activity_type:
        activity_type = "dribble" if drill_id == "pound_the_rock" else "shoot"
    fg = raw.get("fgPercent")
    if fg is None and attempts > 0 and activity_type != "dribble":
        fg = round(makes * 100.0 / attempts, 1)
    title = raw.get("title") or "Session"
    user_name = (raw.get("userName") or "").strip() or raw.get("deviceId") or "Unknown"
    user_id = (raw.get("userId") or "").strip()
    if not user_id or user_id in ("unknown", "belum-set-nama"):
        user_id = slugify_name(str(user_name))
    return {
        **raw,
        "userId": user_id,
        "userName": user_name,
        "title": title,
        "drillId": drill_id,
        "activityType": activity_type,
        "activityLabel": raw.get("activityLabel")
        or ("Dribble - Pound The Rock" if activity_type == "dribble" else f"Shoot - {title}"),
        "makes": makes,
        "misses": misses,
        "attempts": attempts,
        "fgPercent": fg,
        "score": raw.get("score"),
        "durationMs": int(raw.get("durationMs") or 0),
        "source": raw.get("source") or "android",
        "syncedAt": raw.get("syncedAt") or _now(),
        "createdAt": raw.get("createdAt"),
        "clientId": raw.get("clientId"),
        "deviceId": raw.get("deviceId") or "-",
    }


def build_users(sessions: list[dict]) -> list[dict]:
    buckets: dict[str, dict] = {}
    for s in sessions:
        key = s["userId"]
        if key not in buckets:
            buckets[key] = {
                "userId": key,
                "userName": s["userName"],
                "sessions": 0,
                "shoots": 0,
                "dribbles_sessions": 0,
                "makes": 0,
                "misses": 0,
                "dribbles": 0,
                "score": 0,
                "lastActivity": None,
                "lastLabel": "—",
                "devices": set(),
            }
        u = buckets[key]
        u["userName"] = s["userName"] or u["userName"]
        u["sessions"] += 1
        u["devices"].add(s.get("deviceId") or "—")
        if s["activityType"] == "dribble":
            u["dribbles_sessions"] += 1
            u["dribbles"] += s["makes"]
            if s.get("score") is not None:
                u["score"] += int(s["score"])
            else:
                u["score"] += s["makes"]
        else:
            u["shoots"] += 1
            u["makes"] += s["makes"]
            u["misses"] += s["misses"]
        synced = s.get("syncedAt") or ""
        if not u["lastActivity"] or synced > u["lastActivity"]:
            u["lastActivity"] = synced
            u["lastLabel"] = s.get("activityLabel") or s.get("title") or "—"

    users = []
    for u in buckets.values():
        attempts = u["makes"] + u["misses"]
        users.append(
            {
                **u,
                "devices": sorted(u["devices"]),
                "fgPercent": round(u["makes"] * 100.0 / attempts, 1) if attempts else None,
                "attempts": attempts,
            }
        )
    users.sort(key=lambda x: x["lastActivity"] or "", reverse=True)
    return users


def dashboard_stats(sessions: list[dict]) -> dict:
    users = build_users(sessions)
    shoot = [s for s in sessions if s["activityType"] != "dribble"]
    dribble = [s for s in sessions if s["activityType"] == "dribble"]
    makes = sum(s["makes"] for s in shoot)
    misses = sum(s["misses"] for s in shoot)
    attempts = makes + misses
    return {
        "user_count": len(users),
        "session_count": len(sessions),
        "shoot_sessions": len(shoot),
        "dribble_sessions": len(dribble),
        "total_makes": makes,
        "total_misses": misses,
        "total_attempts": attempts,
        "total_dribbles": sum(s["makes"] for s in dribble),
        "fg": round(makes * 100.0 / attempts, 1) if attempts else None,
        "users": users,
        "feed": sessions[:50],
    }


class SessionIn(BaseModel):
    title: str
    drillId: str = "freestyle"
    makes: int = 0
    misses: int = 0
    durationMs: int = 0
    deviceId: str = "unknown"
    source: str = "android"
    userId: str = "unknown"
    userName: str = "Player"
    activityType: Optional[str] = None
    activityLabel: Optional[str] = None
    attempts: Optional[int] = None
    fgPercent: Optional[float] = None
    score: Optional[int] = None
    createdAt: Optional[int] = None
    clientId: Optional[str] = None


class SyncPayload(BaseModel):
    sessions: list[SessionIn] = Field(default_factory=list)


class LoginIn(BaseModel):
    identifier: str
    password: str


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    if admin_logged_in(request):
        return RedirectResponse("/", status_code=303)
    err = request.query_params.get("err")
    next_url = request.query_params.get("next") or "/"
    return templates.TemplateResponse(
        request,
        "login.html",
        {"err": err, "next_url": next_url},
    )


@app.post("/login")
async def login_submit(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    next_url: str = Form("/", alias="next"),
):
    if not admin_store.verify(username, password):
        from urllib.parse import quote

        return RedirectResponse(
            f"/login?err=Username+atau+password+salah&next={quote(next_url or '/')}",
            status_code=303,
        )
    token = admin_store.create_session()
    dest = next_url or "/"
    if not dest.startswith("/"):
        dest = "/"
    resp = RedirectResponse(dest, status_code=303)
    resp.set_cookie(
        key=ADMIN_COOKIE,
        value=token,
        httponly=True,
        samesite="lax",
        max_age=60 * 60 * 24 * 14,
        path="/",
    )
    return resp


@app.get("/logout")
@app.post("/logout")
async def logout(request: Request):
    token = request.cookies.get(ADMIN_COOKIE)
    admin_store.destroy_session(token)
    resp = RedirectResponse("/login", status_code=303)
    resp.delete_cookie(ADMIN_COOKIE, path="/")
    return resp


@app.get("/", response_class=HTMLResponse)
async def home(request: Request):
    version = ensure_release_files()
    sessions = [normalize_session(s) for s in read_json(SESSIONS_FILE, [])]
    stats = dashboard_stats(sessions)
    has_apk = bool(version.get("apkFile") and (UPLOAD_DIR / version["apkFile"]).exists())
    has_ios = bool(version.get("iosFile") and (UPLOAD_DIR / version["iosFile"]).exists())
    has_ipa = bool(version.get("ipaFile") and (UPLOAD_DIR / version["ipaFile"]).exists())
    return templates.TemplateResponse(
        request,
        "dashboard.html",
        {
            "version": version,
            "has_apk": has_apk,
            "has_ios": has_ios,
            "has_ipa": has_ipa,
            "account_count": len(accounts.list_users()),
            **stats,
        },
    )


@app.get("/accounts", response_class=HTMLResponse)
async def accounts_page(request: Request):
    msg = request.query_params.get("msg")
    err = request.query_params.get("err")
    return templates.TemplateResponse(
        request,
        "accounts.html",
        {
            "accounts": accounts.list_users(),
            "msg": msg,
            "err": err,
            "edit": None,
        },
    )


@app.get("/accounts/{user_id}/edit", response_class=HTMLResponse)
async def accounts_edit_page(request: Request, user_id: str):
    user = accounts.get(user_id)
    if not user:
        return RedirectResponse("/accounts?err=User+tidak+ditemukan", status_code=303)
    from accounts_store import public_user

    return templates.TemplateResponse(
        request,
        "accounts.html",
        {
            "accounts": accounts.list_users(),
            "msg": None,
            "err": None,
            "edit": public_user(user),
        },
    )


@app.post("/accounts/create")
async def accounts_create(
    name: str = Form(...),
    email: str = Form(""),
    phone: str = Form(""),
    password: str = Form(...),
    active: Optional[str] = Form(None),
):
    try:
        accounts.create(
            name=name,
            email=email,
            phone=phone,
            password=password,
            active=True if active is None else active in ("on", "true", "1"),
        )
        return RedirectResponse("/accounts?msg=User+berhasil+dibuat", status_code=303)
    except Exception as e:
        from urllib.parse import quote

        return RedirectResponse(f"/accounts?err={quote(str(e))}", status_code=303)


@app.post("/accounts/{user_id}/update")
async def accounts_update(
    user_id: str,
    name: str = Form(...),
    email: str = Form(""),
    phone: str = Form(""),
    password: str = Form(""),
    active: Optional[str] = Form(None),
):
    try:
        accounts.update(
            user_id=user_id,
            name=name,
            email=email,
            phone=phone,
            password=password or None,
            active=active in ("on", "true", "1"),
        )
        return RedirectResponse("/accounts?msg=User+berhasil+diupdate", status_code=303)
    except Exception as e:
        from urllib.parse import quote

        return RedirectResponse(f"/accounts?err={quote(str(e))}", status_code=303)


@app.post("/accounts/{user_id}/delete")
async def accounts_delete(user_id: str):
    try:
        accounts.delete(user_id)
        return RedirectResponse("/accounts?msg=User+dihapus", status_code=303)
    except Exception as e:
        from urllib.parse import quote

        return RedirectResponse(f"/accounts?err={quote(str(e))}", status_code=303)


@app.post("/api/auth/login")
async def api_login(payload: LoginIn):
    try:
        result = accounts.login(payload.identifier, payload.password)
        return {"ok": True, **result}
    except ValueError as e:
        raise HTTPException(status_code=401, detail=str(e))


@app.get("/api/accounts")
async def api_accounts():
    return {"users": accounts.list_users()}


@app.get("/users/{user_id}", response_class=HTMLResponse)
async def user_detail(request: Request, user_id: str):
    sessions = [
        normalize_session(s)
        for s in read_json(SESSIONS_FILE, [])
        if (s.get("userId") or s.get("deviceId")) == user_id
    ]
    users = build_users(sessions)
    user = users[0] if users else {
        "userId": user_id,
        "userName": "Unknown",
        "sessions": 0,
        "fgPercent": None,
        "makes": 0,
        "misses": 0,
        "dribbles": 0,
        "score": 0,
        "attempts": 0,
        "shoots": 0,
        "dribbles_sessions": 0,
        "lastLabel": "—",
        "devices": [],
    }
    return templates.TemplateResponse(
        request,
        "user_detail.html",
        {"user": user, "sessions": sessions},
    )


@app.get("/app", response_class=HTMLResponse)
async def web_app(request: Request):
    return templates.TemplateResponse(request, "webapp.html")


@app.get("/app/dribble", response_class=HTMLResponse)
async def web_dribble(request: Request):
    return templates.TemplateResponse(request, "dribble.html")


@app.get("/app/shoot", response_class=HTMLResponse)
async def web_shoot(request: Request):
    return templates.TemplateResponse(request, "shoot.html")


@app.get("/api/health")
async def health():
    return {"ok": True, "service": "CourtAI", "time": _now()}


@app.get("/api/activity")
async def api_activity():
    sessions = [normalize_session(s) for s in read_json(SESSIONS_FILE, [])]
    return dashboard_stats(sessions)


@app.get("/api/version")
async def api_version(request: Request):
    version = ensure_release_files()
    base = str(request.base_url).rstrip("/")
    apk_url = f"{base}/api/apk/download" if version.get("apkFile") else None
    ios_url = f"{base}/api/ios/download" if version.get("iosFile") else None
    ipa_url = f"{base}/api/ipa/download" if version.get("ipaFile") else None
    return {
        **version,
        "apkUrl": apk_url,
        "iosUrl": ios_url,
        "ipaUrl": ipa_url,
        "webAppUrl": f"{base}/app",
        "dashboardUrl": f"{base}/",
        "downloadPageUrl": f"{base}/download",
    }


@app.get("/download", response_class=HTMLResponse)
async def download_page(request: Request):
    version = ensure_release_files()
    has_apk = bool(version.get("apkFile") and (UPLOAD_DIR / version["apkFile"]).exists())
    has_ios = bool(version.get("iosFile") and (UPLOAD_DIR / version["iosFile"]).exists())
    has_ipa = bool(version.get("ipaFile") and (UPLOAD_DIR / version["ipaFile"]).exists())
    return templates.TemplateResponse(
        request,
        "download.html",
        {
            "version": version,
            "has_apk": has_apk,
            "has_ios": has_ios,
            "has_ipa": has_ipa,
        },
    )


@app.get("/api/apk/download")
async def download_apk():
    version = ensure_release_files()
    name = version.get("apkFile")
    if not name:
        raise HTTPException(status_code=404, detail="No APK available yet")
    path = UPLOAD_DIR / name
    if not path.exists():
        raise HTTPException(status_code=404, detail="APK file missing on server")
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename=f"CourtAI-{version.get('versionName', 'update')}.apk",
    )


@app.get("/api/ios/download")
async def download_ios():
    version = ensure_release_files()
    name = version.get("iosFile")
    if not name:
        raise HTTPException(status_code=404, detail="No iOS package available yet")
    path = UPLOAD_DIR / name
    if not path.exists():
        raise HTTPException(status_code=404, detail="iOS file missing on server")
    return FileResponse(
        path,
        media_type="application/zip",
        filename="CourtAI-iOS-source.zip",
    )


@app.get("/api/ipa/download")
async def download_ipa():
    version = ensure_release_files()
    name = version.get("ipaFile")
    if not name:
        raise HTTPException(status_code=404, detail="No IPA uploaded yet")
    path = UPLOAD_DIR / name
    if not path.exists():
        raise HTTPException(status_code=404, detail="IPA file missing on server")
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename=f"CourtAI-{version.get('versionName', 'update')}.ipa",
    )


@app.post("/dashboard/upload")
async def upload_apk(
    versionCode: int = Form(...),
    versionName: str = Form(...),
    changelog: str = Form(""),
    forceUpdate: Optional[str] = Form(None),
    apk: UploadFile = File(...),
):
    if not apk.filename or not apk.filename.lower().endswith(".apk"):
        raise HTTPException(status_code=400, detail="File must be an .apk")

    safe_name = f"courtai-v{versionCode}.apk"
    dest = UPLOAD_DIR / safe_name
    with dest.open("wb") as out:
        shutil.copyfileobj(apk.file, out)

    for old in UPLOAD_DIR.glob("courtai-v*.apk"):
        if old.name != safe_name:
            old.unlink(missing_ok=True)
    legacy = UPLOAD_DIR / "courtai-android-latest.apk"
    if legacy.exists() and legacy.name != safe_name:
        legacy.unlink(missing_ok=True)

    version = get_version()
    version.update(
        {
            "versionCode": versionCode,
            "versionName": versionName,
            "changelog": changelog,
            "forceUpdate": forceUpdate in ("on", "true", "1"),
            "apkFile": safe_name,
            "updatedAt": _now(),
        }
    )
    write_json(VERSION_FILE, version)
    return RedirectResponse(url="/?uploaded=1", status_code=303)


@app.post("/dashboard/upload-ipa")
async def upload_ipa(
    versionName: str = Form("1.0.0"),
    ipa: UploadFile = File(...),
):
    if not ipa.filename or not ipa.filename.lower().endswith(".ipa"):
        raise HTTPException(status_code=400, detail="File must be an .ipa")

    safe_name = "courtai-ios-latest.ipa"
    dest = UPLOAD_DIR / safe_name
    with dest.open("wb") as out:
        shutil.copyfileobj(ipa.file, out)

    version = get_version()
    version.update(
        {
            "ipaFile": safe_name,
            "versionName": versionName or version.get("versionName") or "1.0.0",
            "iosUpdatedAt": _now(),
        }
    )
    write_json(VERSION_FILE, version)
    return RedirectResponse(url="/?uploaded_ipa=1", status_code=303)


@app.post("/dashboard/clear-sessions")
async def clear_sessions():
    write_json(SESSIONS_FILE, [])
    return RedirectResponse(url="/?cleared=1", status_code=303)


@app.get("/api/sessions")
async def list_sessions():
    sessions = [normalize_session(s) for s in read_json(SESSIONS_FILE, [])]
    return {"sessions": sessions, **dashboard_stats(sessions)}


@app.post("/api/sessions/sync")
async def sync_sessions(payload: SyncPayload):
    sessions = read_json(SESSIONS_FILE, [])
    existing = {s.get("clientId") for s in sessions if s.get("clientId")}
    added = 0
    for item in payload.sessions:
        row = normalize_session({**item.model_dump(), "syncedAt": _now()})
        cid = row.get("clientId")
        if cid and cid in existing:
            # refresh identity fields for same session
            for i, old in enumerate(sessions):
                if old.get("clientId") == cid:
                    sessions[i] = {**old, **row}
                    break
            continue
        sessions.insert(0, row)
        if cid:
            existing.add(cid)
        added += 1
    sessions = sessions[:1000]
    write_json(SESSIONS_FILE, sessions)
    return {"ok": True, "added": added, "total": len(sessions)}


@app.post("/api/sessions")
async def create_session(session: SessionIn):
    sessions = read_json(SESSIONS_FILE, [])
    row = normalize_session({**session.model_dump(), "syncedAt": _now()})
    sessions.insert(0, row)
    write_json(SESSIONS_FILE, sessions[:1000])
    return {"ok": True, "session": row}

