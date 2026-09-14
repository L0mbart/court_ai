# CourtAI iOS

Native SwiftUI app (login, biometrik, shot tracker, Pound The Rock) yang memakai **server dashboard yang sama** dengan Android.

**Versi saat ini:** `1.1.4` (build `6`)

## Syarat
- Mac dengan **Xcode 15+**
- iPhone iOS 17+ (kamera + opsional Face ID/Touch ID)
- Dashboard server berjalan (`server/start.bat` atau uvicorn di Mac)

> Build IPA **tidak bisa** dari Windows. Source iOS sudah disiapkan; compile di Mac. Download source: `/api/ios/download`.

## Auto update
App mengecek `/api/version` saat Home dibuka. Jika `versionCode` server lebih tinggi, muncul alert + local notification, lalu tombol Update membuka download IPA/source.

## Cara buka di Xcode

### Opsi A — XcodeGen (disarankan)
```bash
brew install xcodegen
cd ios/CourtAI
xcodegen generate
open CourtAI.xcodeproj
```

### Opsi B — Manual
1. Xcode → File → New → Project → App (SwiftUI, Swift)
2. Product Name: `CourtAI`, Bundle ID: `com.courtai.basketball`
3. Hapus file default, lalu drag folder `CourtAI/` (source) ke project
4. Set `Info.plist` ke `CourtAI/Info.plist`
5. Pastikan target iOS 17+

## Run di device
1. Hubungkan iPhone, pilih team signing di Signing & Capabilities
2. Jalankan server dashboard (PC/Mac di WiFi yang sama)
3. Di app: **Atur Server URL** → `http://IP-SERVER:8080`
4. Login dengan akun dari web `/accounts`  
   Demo: `demo@courtai.app` / `court123`

## Fitur
- Login email atau nomor telepon
- Face ID / Touch ID setelah login sekali
- Home athletic (profile + training floor)
- Shot Tracker (kalibrasi rim + auto make/miss)
- Pound The Rock (kamera depan default)
- Sync otomatis ke dashboard (`source: ios`)

## Catatan ATS
`Info.plist` mengizinkan HTTP lokal agar bisa pakai dashboard LAN. Untuk production, gunakan HTTPS.
