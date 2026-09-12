# Tutorial CourtAI — Basketball Training App
### Untuk siswa 

Halo! Tutorial ini menjelaskan cara menjalankan dan memahami aplikasi **CourtAI**.  
Bahasanya dibuat sederhana, dan ada contoh kode yang bisa kamu baca pelan-pelan.

---

## 1. Apa itu CourtAI?

CourtAI adalah aplikasi latihan basket yang memakai **kamera**.

| Mode | Fungsi |
|------|--------|
| **Pound The Rock** | Menghitung dribble bola |
| **Shot Tracker** | Menghitung bola yang masuk ring (MAKE) / meleset (MISS) |

Cara kerjanya mirip mata manusia:
1. Kamera mengambil gambar
2. Komputer mencari warna **oranye** (warna bola basket)
3. Program mengikuti gerakan bola
4. Skor bertambah kalau gerakan cocok (dribble / masuk ring)

---

## 2. Apa yang kamu butuhkan?

- Laptop/PC Windows
- Python (sudah terpasang di komputer proyek ini)
- Browser Chrome / Edge
- Bola basket berwarna oranye (untuk latihan)
- HP (opsional) untuk coba di browser HP

---

## 3. Menjalankan server (sangat penting)

Aplikasi web butuh **server** supaya bisa dibuka di browser.

### Cara cepat
1. Buka folder proyek: `Basket Ball APPS`
2. Masuk folder `server`
3. Double-klik salah satu file ini:

| File | Kegunaan |
|------|----------|
| `start.bat` | Server biasa (PC) → `http://127.0.0.1:8080` |
| `start-both.bat` | HTTP + HTTPS (untuk kamera di HP) |
| `stop.bat` | Mematikan server |

### Alamat yang dipakai

- Di laptop: **http://127.0.0.1:8080**
- Dashboard admin: **http://127.0.0.1:8080/login**
- Latihan web: **http://127.0.0.1:8080/app**
- Di HP (kamera): **https://IP-KOMPUTER:8443/app**  
  Contoh: `https://172.16.3.51:8443/app`

> Tip: IP komputer bisa berubah. Cek dengan ketik `ipconfig` di Command Prompt.

---

## 4. Login admin dashboard

1. Buka `http://127.0.0.1:8080/login`
2. Username: `admin`
3. Password: lihat file `server/data/ADMIN_LOGIN.txt`

Di dashboard kamu bisa:
- Melihat skor semua pemain
- Menghapus semua sesi (mulai dari 0)
- Download APK Android / source iOS

---

## 5. Cara main di Web App

1. Buka `http://127.0.0.1:8080/app`
2. Pilih:
   - **Pound The Rock**, atau
   - **Shot Tracker**
3. Izinkan kamera di browser
4. Tekan **Start**

### Pound The Rock
- Dribble bola di depan kamera
- Skor naik tiap bounce terbaca
- Tombol **Mirror** untuk membalik gambar
- Tombol **+ Dribble** kalau mau input manual

### Shot Tracker
- Geser dan **resize** kotak kuning (RING) supaya pas di ring basket
- Lempar bola ke ring
- **MAKE** = bola masuk (lewat kotak RING)
- **MISS** = bola meleset

---

## 6. Memahami kode (bagian paling seru)

Di bawah ini contoh kode **sederhana**, mirip ide di dalam CourtAI.  
Kamu tidak harus menghafal semua — cukup pahami alurnya.

### 6.1 HTML — kerangka halaman

HTML seperti kerangka rumah. Contoh tombol Start:

```html
<!DOCTYPE html>
<html>
<head>
  <title>Pound The Rock - Latihan</title>
</head>
<body>
  <h1>Pound The Rock</h1>
  <p id="skor">Dribble: 0</p>
  <button id="btnStart">Start</button>

  <!-- Video dari kamera -->
  <video id="video" autoplay playsinline muted></video>

  <script src="latihan.js"></script>
</body>
</html>
```

Penjelasan singkat:
- `<button>` = tombol
- `<video>` = tempat menampilkan kamera
- `<script>` = memanggil file JavaScript

### 6.2 JavaScript — menyalakan kamera

```javascript
// Minta izin kamera ke browser
async function nyalakanKamera() {
  const stream = await navigator.mediaDevices.getUserMedia({
    video: true,
    audio: false
  });

  // Tampilkan hasil kamera ke elemen <video>
  const video = document.getElementById("video");
  video.srcObject = stream;
}

nyalakanKamera();
```

Artinya:
1. Minta akses kamera
2. Kalau diizinkan, gambar kamera muncul di layar

### 6.3 Mencari bola berwarna oranye

Bola basket biasanya **oranye**. Program memeriksa warna tiap titik gambar.

```javascript
// Apakah pixel ini terlihat seperti bola oranye?
function isOrange(r, g, b) {
  // r = merah, g = hijau, b = biru (0 sampai 255)
  if (r < 90) return false;      // kurang terang
  if (r < g) return false;       // harus lebih merah daripada hijau
  if (b > r * 0.85) return false; // jangan terlalu biru
  return true;                   // kemungkinan besar oranye
}
```

### 6.4 Menghitung dribble (naik-turun)

Dribble = bola turun ke lantai, lalu naik lagi.

```javascript
let dribbles = 0;
let prevY = null;
let sedangTurun = false;

function cekDribble(ySekarang) {
  // y membesar = bola terlihat lebih ke bawah di layar
  if (prevY === null) {
    prevY = ySekarang;
    return;
  }

  const dy = ySekarang - prevY; // selisih posisi
  prevY = ySekarang;

  if (dy > 0.01) {
    // bola sedang turun
    sedangTurun = true;
  } else if (dy < -0.01 && sedangTurun) {
    // bola naik lagi setelah turun = bounce!
    dribbles = dribbles + 1;
    sedangTurun = false;
    document.getElementById("skor").textContent = "Dribble: " + dribbles;
  }
}
```

### 6.5 Menghitung bola masuk ring (MAKE)

Ide sederhana:

```javascript
let sudahDiAtas = false;
let skorMake = 0;

function cekMasukRing(bola, ring) {
  // 1) Bola pernah di atas ring?
  if (bola.y < ring.atas) {
    sudahDiAtas = true;
  }

  // 2) Setelah dari atas, bola masuk kotak ring sambil turun?
  const diDalamX = bola.x >= ring.kiri && bola.x <= ring.kanan;
  const diDalamY = bola.y >= ring.atas && bola.y <= ring.bawah;

  if (sudahDiAtas && diDalamX && diDalamY) {
    skorMake = skorMake + 1;
    sudahDiAtas = false;
    console.log("MAKE! Skor:", skorMake);
  }
}
```

---

## 7. Struktur folder proyek (supaya tidak bingung)

```
Basket Ball APPS/
├── app/                 → Aplikasi Android (Kotlin)
├── ios/                 → Aplikasi iPhone (Swift)
├── server/              → Dashboard + Web App
│   ├── templates/       → Halaman HTML
│   ├── static/js/       → Kode JavaScript (kamera & deteksi bola)
│   ├── start.bat        → Menjalankan server
│   └── start-both.bat   → HTTP + HTTPS (untuk HP)
└── apk/                 → File APK siap install
```

File penting untuk deteksi bola web:
- `server/static/js/tracker.js` → sensor bola
- `server/static/js/camera.js` → kamera
- `server/templates/dribble.html` → Pound The Rock
- `server/templates/shoot.html` → Shot Tracker

---

## 8. Latihan untuk siswa (kerjakan di buku / laptop)

### Latihan A — Observasi
1. Jalankan server
2. Buka Pound The Rock
3. Dribble lambat 10 kali
4. Catat: berapa yang terbaca aplikasi?

### Latihan B — Percobaan warna
1. Coba bola oranye terang
2. Coba bola gelap / kotor
3. Mana yang lebih mudah terbaca? Mengapa?

### Latihan C — Shot Tracker
1. Atur kotak RING pas di ring
2. Lempar 10 bola
3. Hitung MAKE dan MISS
4. Hitung FG% = MAKE / (MAKE+MISS) × 100

### Latihan D — Koding mini
Salin kode `isOrange` dan ubah angka `r < 90` menjadi `r < 120`.  
Apa yang terjadi pada deteksi? (lebih ketat / lebih longgar?)

---

## 9. Troubleshooting (kalau error)

| Masalah | Solusi |
|---------|--------|
| Dashboard tidak buka | Jalankan `start.bat` / `start-both.bat` |
| Kamera laptop hitam | Klik **Nyalakan Kamera**, izinkan di browser |
| Kamera HP tidak jalan | Pakai **HTTPS** (`https://IP:8443/app`), bukan `http` |
| Bluescreen saat kamera | Matikan **TranScreen Camera** di Device Manager |
| Dribble terlalu banyak/sedikit | Atur jarak kamera & pencahayaan ruangan |
| Hapus semua skor | Dashboard → **Hapus semua sesi** |

---

## 10. Kesimpulan

CourtAI mengajarkan 3 ide penting:

1. **Input** — kamera mengambil gambar  
2. **Proses** — komputer mencari bola oranye & gerakan  
3. **Output** — skor dribble / MAKE / MISS muncul di layar  

Kalau kamu paham 3 langkah itu, kamu sudah memahami dasar banyak aplikasi AI sederhana!

---

## 11. Tugas proyek kelas (opsional)

Buat laporan singkat (1–2 halaman):

1. Judul: *Latihan Basket dengan CourtAI*
2. Tujuan praktikum
3. Alat dan bahan
4. Langkah percobaan
5. Hasil (tabel skor)
6. Kendala + cara mengatasinya
7. Kesimpulan

---

**Selamat belajar dan selamat berlatih!**  
Kalau ada bagian kode yang belum jelas, tanya guru atau diskusikan dengan teman sekelompok.
