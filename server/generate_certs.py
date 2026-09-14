"""
CourtAI — generator sertifikat HTTPS lokal (self-signed).

Peran file ini:
  Membuat pasangan file cert.pem + key.pem di folder server/certs/.
  Sertifikat ini dipakai agar server bisa jalan pakai HTTPS di laptop/HP
  di jaringan lokal (bukan sertifikat resmi dari internet).

Kenapa perlu HTTPS?
  Browser modern sering menolak akses kamera (getUserMedia) di halaman HTTP
  biasa. Latihan shoot di /app/shoot butuh kamera → butuh HTTPS.

Alur singkat:
  1. Jalankan: python generate_certs.py
  2. Script cari alamat IP lokal (127.0.0.1, IP Wi-Fi, dll.)
  3. Buat kunci RSA + sertifikat yang mencakup localhost & IP tersebut
  4. Tulis cert.pem dan key.pem — siap dipakai server HTTPS

Catatan: ini "self-signed" = dibuat sendiri, jadi browser akan bilang
"tidak terpercaya". Untuk development lokal itu normal; klik lanjut/lanjutkan.
"""
from __future__ import annotations

import ipaddress
import socket
from datetime import datetime, timedelta, timezone
from pathlib import Path

# ========== BAGIAN: LOKASI FILE SERTIFIKAT ==========
CERT_DIR = Path(__file__).resolve().parent / "certs"
CERT_FILE = CERT_DIR / "cert.pem"  # sertifikat publik
KEY_FILE = CERT_DIR / "key.pem"    # kunci privat (jangan dibagikan!)


def local_ips() -> list[str]:
    """
    Cari alamat IP mesin ini agar HP di Wi-Fi yang sama bisa buka server.

    Input:  tidak ada
    Output: list string IP, selalu termasuk "127.0.0.1", diurutkan
    Cara kerja kasar:
      - Tanya hostname ke sistem operasi
      - Coba "keluar" ke 8.8.8.8 hanya untuk mengetahui IP lokal kita
    """
    ips = {"127.0.0.1"}
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    return sorted(ips)


def generate() -> tuple[Path, Path]:
    """
    Buat sertifikat + kunci privat baru.

    Input:  tidak ada (membaca IP lokal sendiri)
    Output: (path cert.pem, path key.pem)
    Efek samping: menulis file di folder certs/; jika library cryptography
                  belum terpasang, mencoba install lewat pip dulu.
    """
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
    except ImportError:
        # Belum ada library → install otomatis lalu import lagi
        import subprocess
        import sys

        subprocess.check_call([sys.executable, "-m", "pip", "install", "cryptography", "-q"])
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID

    CERT_DIR.mkdir(parents=True, exist_ok=True)

    # Kunci privat RSA 2048-bit (standar aman untuk development)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    ips = local_ips()
    # Nama alternatif (SAN): hostname & IP yang boleh memakai sertifikat ini
    hostnames = ["localhost", "courtai.local", *ips]

    alt_names = []
    for h in hostnames:
        try:
            alt_names.append(x509.IPAddress(ipaddress.ip_address(h)))
        except ValueError:
            # Bukan IP → anggap nama DNS (localhost, courtai.local, ...)
            alt_names.append(x509.DNSName(h))

    # Subject = identitas di dalam sertifikat; self-signed → subject = issuer
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "CourtAI Local"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "CourtAI"),
    ])
    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=1))
        .not_valid_after(now + timedelta(days=825))  # berlaku ~2+ tahun
        .add_extension(x509.SubjectAlternativeName(alt_names), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    # Tulis kunci privat (format PEM klasik OpenSSL)
    KEY_FILE.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    # Tulis sertifikat publik
    CERT_FILE.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(f"Wrote {CERT_FILE}")
    print(f"Wrote {KEY_FILE}")
    print("SANs:", ", ".join(hostnames))
    return CERT_FILE, KEY_FILE


# ========== BAGIAN: JALANKAN LANGSUNG DARI TERMINAL ==========
# if __name__ == "__main__" artinya: hanya jalan saat file ini dijalankan
# langsung (python generate_certs.py), bukan saat di-import modul lain.
if __name__ == "__main__":
    generate()
