# CourtAI

Android basketball trainer + web dashboard (HomeCourt-style).

## Dua versi

| Platform | Fitur |
|----------|--------|
| **APK Android** | Shot tracker, Pound The Rock, drills, update dari server |
| **Web** | Dashboard admin, web training (`/app`), sync sessions |

## Jalankan web dashboard

```bat
cd server
start.bat
```

Buka:
- Dashboard: http://localhost:8080/
- Web app: http://localhost:8080/app
- API version: http://localhost:8080/api/version

### Publish update APK
1. Build APK baru (naikkan `versionCode` di `app/build.gradle.kts`)
2. Di dashboard → upload APK + isi version code/name
3. Di HP: set **Server** ke `http://IP-PC-ANDA:8080` (satu WiFi)
4. Tap **Update** — app unduh & install APK dari server

Emulator Android pakai default: `http://10.0.2.2:8080`

## Build APK

```bat
gradlew.bat assembleDebug
```

Output: `apk/CourtAI-debug.apk` (atau `app/build/outputs/apk/debug/`)

## iOS
Lihat folder `ios/` — SwiftUI app (login, biometrik, camera training) yang sync ke dashboard yang sama.
Build butuh Mac + Xcode. Baca `ios/README.md`.

## Login APK
1. Admin buat user di http://localhost:8080/accounts
2. Di APK: set Server URL, login email/telepon + password
3. Opsional: aktifkan biometrik setelah login sukses

Demo akun:
- `demo@courtai.app` / `court123`
- `usera@courtai.app` / `court123`
- telepon juga bisa: `08333333333` / `court123`
