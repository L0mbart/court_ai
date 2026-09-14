"""
CourtAI — autentikasi admin dashboard.

Peran file ini:
  Mengurus "pintu masuk" khusus admin (bukan pemain biasa).
  Menyimpan username/password admin (sudah di-hash), membuat tiket sesi
  (token), dan mengecek apakah cookie browser masih valid.

Alur singkat:
  1. Saat server pertama kali jalan, jika belum ada admin → buat username
     "admin" + password acak, lalu tulis petunjuk ke ADMIN_LOGIN.txt.
  2. Admin login di /login → main.py memanggil verify() lalu create_session().
  3. Token disimpan di cookie browser + di admin_sessions.json.
  4. Setiap request terlindungi: main.py memanggil session_valid(token).
  5. Logout → destroy_session(token).

Catatan keamanan untuk pemula:
  Password TIDAK disimpan apa adanya. Yang disimpan = hash + salt
  (lihat accounts_store.hash_password / verify_password).
"""

import json
import secrets
import string
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from accounts_store import hash_password, verify_password

# ========== BAGIAN: NAMA FILE DATA ==========
ADMIN_FILE = "admin.json"              # username + hash password admin
CREDS_FILE = "ADMIN_LOGIN.txt"         # catatan password awal (jangan dibagikan!)
SESSIONS_FILE = "admin_sessions.json"  # daftar token login yang masih aktif


def _now() -> str:
    """Output: waktu sekarang (UTC) format ISO teks."""
    return datetime.now(timezone.utc).isoformat()


def generate_password(length: int = 16) -> str:
    """
    Buat password acak yang kuat untuk admin pertama kali.

    Input:  length — panjang password (default 16)
    Output: string acak (huruf + angka, tanpa karakter yang mudah tertukar
            seperti O/0 atau l/I/1)
    """
    alphabet = string.ascii_letters + string.digits
    # avoid ambiguous chars
    alphabet = alphabet.replace("O", "").replace("0", "").replace("l", "").replace("I", "").replace("1", "")
    return "".join(secrets.choice(alphabet) for _ in range(length))


# ========== BAGIAN: KELAS AdminStore ==========

class AdminStore:
    """
    "Gudang" data admin: baca/tulis file JSON di folder data/.

    Dipakai oleh main.py untuk login, cek cookie, dan logout.
    """

    def __init__(self, data_dir: Path):
        """
        Input: data_dir — folder tempat menyimpan admin.json & sesi.
        Saat objek dibuat, otomatis pastikan admin sudah ada (ensure_admin).
        """
        self.data_dir = data_dir
        self.path = data_dir / ADMIN_FILE
        self.creds_path = data_dir / CREDS_FILE
        self.sessions_path = data_dir / SESSIONS_FILE
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.ensure_admin()

    # ---------- baca / tulis file (helper internal) ----------

    def _read_admin(self) -> dict:
        """Output: isi admin.json sebagai dict (atau {} jika belum ada)."""
        if not self.path.exists():
            return {}
        return json.loads(self.path.read_text(encoding="utf-8") or "{}")

    def _write_admin(self, data: dict) -> None:
        """Input: data admin. Menyimpan ke admin.json."""
        self.path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    def _read_sessions(self) -> dict:
        """
        Output: dict token → info sesi.
        Contoh bentuk: { "abcToken...": {"createdAt": "...", "username": "admin"} }
        """
        if not self.sessions_path.exists():
            return {}
        return json.loads(self.sessions_path.read_text(encoding="utf-8") or "{}")

    def _write_sessions(self, data: dict) -> None:
        """Input: dict sesi. Menyimpan ke admin_sessions.json."""
        self.sessions_path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    # ---------- setup admin pertama kali ----------

    def ensure_admin(self) -> dict:
        """
        Pastikan akun admin sudah ada.

        Jika admin.json sudah lengkap → kembalikan datanya.
        Jika belum → buat password acak, hash, simpan, dan tulis ADMIN_LOGIN.txt.

        Output: dict admin (username, passwordHash, salt, createdAt, ...)
        """
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
        # File teks ini hanya untuk kamu (pengembang). Jangan commit / share publik.
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

    # ---------- login & sesi ----------

    def verify(self, username: str, password: str) -> bool:
        """
        Cek apakah username + password cocok dengan admin.

        Input:  username, password (teks biasa dari form login)
        Output: True jika benar, False jika salah
        """
        admin = self.ensure_admin()
        if (username or "").strip().lower() != (admin.get("username") or "").lower():
            return False
        return verify_password(password, admin.get("passwordHash", ""), admin.get("salt", ""))

    def create_session(self) -> str:
        """
        Buat tiket login baru setelah password benar.

        Output: token acak (string). Token ini nanti disimpan di cookie browser.
        Catatan: jika sesi terlalu banyak, sisakan ~40 yang paling baru.
        """
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
        """
        Cek apakah token masih terdaftar (admin masih "masuk").

        Input:  token dari cookie (bisa None)
        Output: True jika token ada di admin_sessions.json
        """
        if not token:
            return False
        sessions = self._read_sessions()
        return token in sessions

    def destroy_session(self, token: Optional[str]) -> None:
        """
        Hapus tiket (logout).

        Input: token dari cookie. Jika kosong / tidak ada → tidak melakukan apa-apa.
        """
        if not token:
            return
        sessions = self._read_sessions()
        if token in sessions:
            del sessions[token]
            self._write_sessions(sessions)

    def username(self) -> str:
        """Output: nama username admin (biasanya "admin")."""
        return self.ensure_admin().get("username") or "admin"
