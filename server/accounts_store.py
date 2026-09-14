"""
CourtAI — penyimpanan akun pemain (bukan admin).

Peran file ini:
  Mengurus daftar user yang login di aplikasi CourtAI (Android / iOS / web).
  Data disimpan di file users.json (folder data/).

Alur singkat:
  1. Server membuat AccountStore(data_dir).
  2. Jika users.json kosong → ensure_seed() membuat 3 akun contoh.
  3. Admin bisa create / update / delete lewat halaman /accounts (main.py).
  4. App pemain login lewat POST /api/auth/login → fungsi login() di sini.
  5. Password selalu di-hash + salt; tidak pernah disimpan teks biasa.

Bedanya dengan admin_auth.py:
  - admin_auth.py  → 1 akun admin dashboard
  - accounts_store.py → banyak akun pemain latihan
"""

import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

USERS_FILE_NAME = "users.json"  # nama file daftar pemain di folder data/


def _now() -> str:
    """Output: waktu sekarang (UTC) format ISO teks."""
    return datetime.now(timezone.utc).isoformat()


# ========== BAGIAN: PASSWORD (HASH + SALT) ==========
# Ide sederhana: jangan simpan "rahasia123" apa adanya.
# Salt = garam acak; hash = hasil acak-tetap dari password+salt.
# Orang yang mencuri file users.json tetap sulit menebak password aslinya.

def hash_password(password: str, salt: Optional[str] = None) -> tuple[str, str]:
    """
    Ubah password jadi bentuk aman untuk disimpan.

    Input:
      password — teks biasa dari user
      salt     — opsional; kalau None, dibuatkan acak baru
    Output:
      (digest, salt) — digest = hash hex; salt = teks acak
    """
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), 120_000
    ).hex()
    return digest, salt


def verify_password(password: str, password_hash: str, salt: str) -> bool:
    """
    Cek apakah password yang diketik cocok dengan yang tersimpan.

    Input:  password (dari form), password_hash + salt (dari database/file)
    Output: True jika cocok, False jika tidak
    Catatan: hmac.compare_digest dipakai agar lebih aman dari timing attack.
    """
    digest, _ = hash_password(password, salt)
    return hmac.compare_digest(digest, password_hash)


# ========== BAGIAN: NORMALISASI & DATA PUBLIK ==========

def normalize_phone(phone: str) -> str:
    """
    Rapikan nomor telepon: hanya digit dan tanda +.
    Input: teks bebas. Output: string bersih (contoh "08123456789").
    """
    digits = "".join(c for c in (phone or "") if c.isdigit() or c == "+")
    return digits


def normalize_email(email: str) -> str:
    """
    Rapikan email: buang spasi + huruf kecil semua.
    Input: "Budi@Mail.COM ". Output: "budi@mail.com"
    """
    return (email or "").strip().lower()


def public_user(user: dict) -> dict:
    """
    Ambil data user yang AMAN dikirim ke luar (tanpa passwordHash/salt).

    Input:  dict user lengkap dari file
    Output: dict ringkas untuk API / halaman HTML
    """
    return {
        "id": user.get("id"),
        "name": user.get("name"),
        "email": user.get("email") or "",
        "phone": user.get("phone") or "",
        "active": bool(user.get("active", True)),
        "createdAt": user.get("createdAt"),
        "updatedAt": user.get("updatedAt"),
    }


# ========== BAGIAN: KELAS AccountStore ==========

class AccountStore:
    """
    Gudang akun pemain: create, baca, update, hapus, dan login.

    Semua perubahan ditulis ke users.json di disk.
    """

    def __init__(self, data_dir: Path):
        """
        Input: data_dir — folder data server.
        Jika users.json belum ada → buat file kosong lalu seed contoh.
        """
        self.path = data_dir / USERS_FILE_NAME
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self._write([])
            self.ensure_seed()

    def _read(self) -> list[dict]:
        """Output: list semua user dari users.json (atau [] jika kosong)."""
        import json

        if not self.path.exists():
            return []
        return json.loads(self.path.read_text(encoding="utf-8") or "[]")

    def _write(self, users: list[dict]) -> None:
        """Input: list user. Menyimpan seluruh daftar ke users.json."""
        import json

        self.path.write_text(json.dumps(users, indent=2), encoding="utf-8")

    def ensure_seed(self) -> None:
        """
        Jika belum ada user sama sekali, buat 3 akun demo.
        Password contoh: court123 (hanya untuk development / latihan).
        Input/Output: tidak ada; efeknya menulis ke file bila perlu.
        """
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
        """
        Output: list user versi publik (aman ditampilkan).
        Dipakai halaman /accounts dan GET /api/accounts.
        """
        self.ensure_seed()
        return [public_user(u) for u in self._read()]

    def get(self, user_id: str) -> Optional[dict]:
        """
        Cari 1 user berdasarkan id.

        Input:  user_id (string pendek, contoh "a1b2c3d4")
        Output: dict user lengkap, atau None jika tidak ketemu
        """
        for u in self._read():
            if u.get("id") == user_id:
                return u
        return None

    def find_by_login(self, identifier: str) -> Optional[dict]:
        """
        Cari user lewat email ATAU nomor telepon.

        Input:  identifier — bisa email atau nomor HP
        Output: dict user lengkap, atau None
        """
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
        """
        Buat akun baru.

        Input: name (wajib), email dan/atau phone, password (min 4 karakter), active
        Output: dict publik user baru
        Error (ValueError): nama kosong, email+phone kosong, password pendek,
                            atau email/telepon sudah dipakai orang lain
        """
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
        """
        Ubah data akun yang sudah ada.

        Input: user_id + field yang ingin diubah (None = jangan diubah).
               password kosong/None = password lama tetap.
        Output: dict publik user setelah diupdate
        """
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

        # Cegah email/telepon bentrok dengan user lain
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
        """
        Hapus akun.

        Input:  user_id
        Output: tidak ada (None). Error jika id tidak ditemukan.
        """
        users = self._read()
        new_users = [u for u in users if u.get("id") != user_id]
        if len(new_users) == len(users):
            raise ValueError("User tidak ditemukan")
        self._write(new_users)

    def login(self, identifier: str, password: str) -> dict:
        """
        Login pemain untuk aplikasi.

        Input:  identifier (email atau telepon), password
        Output: { "token": "...", "user": {...publik...} }
        Error:  identitas/password salah, atau akun nonaktif

        Catatan: token di sini dibuat sederhana (belum disimpan server-side
        seperti sesi admin). Cukup untuk alur demo / client-side.
        """
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
