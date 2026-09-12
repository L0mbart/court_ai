import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

USERS_FILE_NAME = "users.json"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def hash_password(password: str, salt: Optional[str] = None) -> tuple[str, str]:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), 120_000
    ).hex()
    return digest, salt


def verify_password(password: str, password_hash: str, salt: str) -> bool:
    digest, _ = hash_password(password, salt)
    return hmac.compare_digest(digest, password_hash)


def normalize_phone(phone: str) -> str:
    digits = "".join(c for c in (phone or "") if c.isdigit() or c == "+")
    return digits


def normalize_email(email: str) -> str:
    return (email or "").strip().lower()


def public_user(user: dict) -> dict:
    return {
        "id": user.get("id"),
        "name": user.get("name"),
        "email": user.get("email") or "",
        "phone": user.get("phone") or "",
        "active": bool(user.get("active", True)),
        "createdAt": user.get("createdAt"),
        "updatedAt": user.get("updatedAt"),
    }


class AccountStore:
    def __init__(self, data_dir: Path):
        self.path = data_dir / USERS_FILE_NAME
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self._write([])
            self.ensure_seed()

    def _read(self) -> list[dict]:
        import json

        if not self.path.exists():
            return []
        return json.loads(self.path.read_text(encoding="utf-8") or "[]")

    def _write(self, users: list[dict]) -> None:
        import json

        self.path.write_text(json.dumps(users, indent=2), encoding="utf-8")

    def ensure_seed(self) -> None:
        users = self._read()
        if users:
            return
        samples = [
            ("User A", "usera@courtai.app", "08111111111", "court123"),
            ("User B", "userb@courtai.app", "08222222222", "court123"),
            ("Demo Player", "demo@courtai.app", "08333333333", "court123"),
        ]
        for name, email, phone, password in samples:
            self.create(name=name, email=email, phone=phone, password=password, active=True)

    def list_users(self) -> list[dict]:
        self.ensure_seed()
        return [public_user(u) for u in self._read()]

    def get(self, user_id: str) -> Optional[dict]:
        for u in self._read():
            if u.get("id") == user_id:
                return u
        return None

    def find_by_login(self, identifier: str) -> Optional[dict]:
        ident = (identifier or "").strip()
        email = normalize_email(ident)
        phone = normalize_phone(ident)
        for u in self._read():
            if email and normalize_email(u.get("email") or "") == email:
                return u
            if phone and normalize_phone(u.get("phone") or "") == phone:
                return u
        return None

    def create(
        self,
        name: str,
        email: str = "",
        phone: str = "",
        password: str = "",
        active: bool = True,
    ) -> dict:
        name = (name or "").strip()
        email = normalize_email(email)
        phone = normalize_phone(phone)
        if not name:
            raise ValueError("Nama wajib diisi")
        if not email and not phone:
            raise ValueError("Isi email atau nomor telepon")
        if len(password or "") < 4:
            raise ValueError("Password minimal 4 karakter")

        users = self._read()
        for u in users:
            if email and normalize_email(u.get("email") or "") == email:
                raise ValueError("Email sudah dipakai")
            if phone and normalize_phone(u.get("phone") or "") == phone:
                raise ValueError("Nomor telepon sudah dipakai")

        pw_hash, salt = hash_password(password)
        user = {
            "id": str(uuid.uuid4())[:8],
            "name": name,
            "email": email,
            "phone": phone,
            "passwordHash": pw_hash,
            "salt": salt,
            "active": active,
            "createdAt": _now(),
            "updatedAt": _now(),
        }
        users.append(user)
        self._write(users)
        return public_user(user)

    def update(
        self,
        user_id: str,
        name: Optional[str] = None,
        email: Optional[str] = None,
        phone: Optional[str] = None,
        password: Optional[str] = None,
        active: Optional[bool] = None,
    ) -> dict:
        users = self._read()
        idx = next((i for i, u in enumerate(users) if u.get("id") == user_id), None)
        if idx is None:
            raise ValueError("User tidak ditemukan")

        user = dict(users[idx])
        if name is not None:
            name = name.strip()
            if not name:
                raise ValueError("Nama wajib diisi")
            user["name"] = name
        if email is not None:
            email = normalize_email(email)
            user["email"] = email
        if phone is not None:
            phone = normalize_phone(phone)
            user["phone"] = phone
        if active is not None:
            user["active"] = active
        if password:
            if len(password) < 4:
                raise ValueError("Password minimal 4 karakter")
            pw_hash, salt = hash_password(password)
            user["passwordHash"] = pw_hash
            user["salt"] = salt

        if not user.get("email") and not user.get("phone"):
            raise ValueError("Isi email atau nomor telepon")

        for i, other in enumerate(users):
            if i == idx:
                continue
            if user.get("email") and normalize_email(other.get("email") or "") == user["email"]:
                raise ValueError("Email sudah dipakai")
            if user.get("phone") and normalize_phone(other.get("phone") or "") == user["phone"]:
                raise ValueError("Nomor telepon sudah dipakai")

        user["updatedAt"] = _now()
        users[idx] = user
        self._write(users)
        return public_user(user)

    def delete(self, user_id: str) -> None:
        users = self._read()
        new_users = [u for u in users if u.get("id") != user_id]
        if len(new_users) == len(users):
            raise ValueError("User tidak ditemukan")
        self._write(new_users)

    def login(self, identifier: str, password: str) -> dict:
        user = self.find_by_login(identifier)
        if not user:
            raise ValueError("Email/telepon atau password salah")
        if not user.get("active", True):
            raise ValueError("Akun nonaktif. Hubungi admin.")
        if not verify_password(password, user.get("passwordHash", ""), user.get("salt", "")):
            raise ValueError("Email/telepon atau password salah")
        token = secrets.token_hex(24)
        return {
            "token": token,
            "user": public_user(user),
        }
