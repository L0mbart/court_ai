import json
import secrets
import string
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from accounts_store import hash_password, verify_password

ADMIN_FILE = "admin.json"
CREDS_FILE = "ADMIN_LOGIN.txt"
SESSIONS_FILE = "admin_sessions.json"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def generate_password(length: int = 16) -> str:
    alphabet = string.ascii_letters + string.digits
    # avoid ambiguous chars
    alphabet = alphabet.replace("O", "").replace("0", "").replace("l", "").replace("I", "").replace("1", "")
    return "".join(secrets.choice(alphabet) for _ in range(length))


class AdminStore:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.path = data_dir / ADMIN_FILE
        self.creds_path = data_dir / CREDS_FILE
        self.sessions_path = data_dir / SESSIONS_FILE
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.ensure_admin()

    def _read_admin(self) -> dict:
        if not self.path.exists():
            return {}
        return json.loads(self.path.read_text(encoding="utf-8") or "{}")

    def _write_admin(self, data: dict) -> None:
        self.path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    def _read_sessions(self) -> dict:
        if not self.sessions_path.exists():
            return {}
        return json.loads(self.sessions_path.read_text(encoding="utf-8") or "{}")

    def _write_sessions(self, data: dict) -> None:
        self.sessions_path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    def ensure_admin(self) -> dict:
        admin = self._read_admin()
        if admin.get("username") and admin.get("passwordHash") and admin.get("salt"):
            return admin

        password = generate_password(16)
        pw_hash, salt = hash_password(password)
        admin = {
            "username": "admin",
            "passwordHash": pw_hash,
            "salt": salt,
            "createdAt": _now(),
            "updatedAt": _now(),
        }
        self._write_admin(admin)
        self.creds_path.write_text(
            "CourtAI Admin Dashboard Login\n"
            "================================\n"
            f"URL      : http://127.0.0.1:8080/login\n"
            f"Username : admin\n"
            f"Password : {password}\n"
            "================================\n"
            "Simpan file ini. Password tidak ditampilkan ulang di UI.\n"
            "Untuk reset: hapus data/admin.json lalu restart server.\n",
            encoding="utf-8",
        )
        print("\n[CourtAI] Default admin dibuat.")
        print(f"[CourtAI] Username : admin")
        print(f"[CourtAI] Password : {password}")
        print(f"[CourtAI] Disimpan di: {self.creds_path}\n")
        return admin

    def verify(self, username: str, password: str) -> bool:
        admin = self.ensure_admin()
        if (username or "").strip().lower() != (admin.get("username") or "").lower():
            return False
        return verify_password(password, admin.get("passwordHash", ""), admin.get("salt", ""))

    def create_session(self) -> str:
        token = secrets.token_urlsafe(32)
        sessions = self._read_sessions()
        # prune old (>30 days) casually by keeping last 50
        if len(sessions) > 50:
            items = sorted(sessions.items(), key=lambda kv: kv[1].get("createdAt", ""), reverse=True)[:40]
            sessions = dict(items)
        sessions[token] = {"createdAt": _now(), "username": "admin"}
        self._write_sessions(sessions)
        return token

    def session_valid(self, token: Optional[str]) -> bool:
        if not token:
            return False
        sessions = self._read_sessions()
        return token in sessions

    def destroy_session(self, token: Optional[str]) -> None:
        if not token:
            return
        sessions = self._read_sessions()
        if token in sessions:
            del sessions[token]
            self._write_sessions(sessions)

    def username(self) -> str:
        return self.ensure_admin().get("username") or "admin"
