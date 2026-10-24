# NurVPN

**Modern VPN client for Android** — Kotlin, Material 3, INCY style.

[English](README.md) | [O'zbekcha](README.uz.md) | [Русский](README.ru.md)

![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet?logo=kotlin)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?logo=android)
![License](https://img.shields.io/badge/License-GPLv3-blue)
![Version](https://img.shields.io/badge/version-1.8-blue)

---

> **Note:** I am **not a developer at all** — just someone **interested in this field**. This project was created with the help of **DeepSeek AI** (and other AI assistants) for learning and personal use. Code may contain errors — improvements and pull requests are welcome!

---

## Features

- **18 protocols**: VLESS Reality, VLESS, VMess, Trojan, Shadowsocks, Hysteria, Hysteria2, TUIC, WireGuard, AmneziaWG 1.x/2.x/3.x, MASQUE, AnyTLS, ShadowTLS, SSH, SOCKS, HTTP/HTTPS, NaiveProxy
- **Modern UI** — Material 3, dark-green + lime
- **Real-time traffic** — up/down B/s
- **3 languages**: Uzbek, Russian, English
- **Light/Dark mode**
- **Favorites** — quick connect
- **Ping test** — TCP + ICMP
- **Split Tunneling** — Whitelist/Blacklist
- **DNS leak + IPv6 protection**
- **QR scanner** — links and AWG configs
- **Open sources** — 10+ free subscriptions (can be fully disabled/removed)
- **AI Selector** — auto-select best server
- **Pause/Resume** — notification panel
- **Quick Settings Tile** — control center
- **Crypt1 Decoder** — decrypts encrypted `incy://crypt1/...` subscriptions
- **Deep Links** — `incy://`, `crypt1://`, `nurvpn://` schemes
- **Biometric Lock** — fingerprint/face protection for encrypted subscriptions
- **HWID** — incy-style device identification
- **Provider name** — auto-detected from profile-title header

---

## 📸 Screenshots

<p align="center">
  <img src="docs/screenshots/01_home_off.jpg" width="220">
  <img src="docs/screenshots/02_home_on.jpg" width="220">
  <img src="docs/screenshots/03_servers.jpg" width="220">
</p>

<p align="center">
  <img src="docs/screenshots/04_settings.jpg" width="220">
  <img src="docs/screenshots/05_security.jpg" width="220">
  <img src="docs/screenshots/06_sources.jpg" width="220">
</p>

<p align="center">
  <img src="docs/screenshots/07_sources2.jpg" width="220">
</p>

---

## Installation

### Download APK

1. Go to **Releases** section
2. Download **nurvpn-v1.9-arm64-v8a.apk** (modern phones, ~64 MB)
3. Install it

### Build from source

    git clone https://github.com/javoxir5543/NurVPN.git
    cd NurVPN
    ./gradlew assembleRelease

**Note:** `libbox.aar` is already included in `app/libs/`. Create `keystore.properties` with your signing config before release build.

---

## Usage

1. Open the app
2. **Settings → Open sources** → enable a free subscription
3. Or **+ Add** → enter your VLESS/VMess/AWG link
4. **Home tab** → select server
5. **Play** button → VPN connects

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Material 3, View Binding
- **Core**: sing-box v1.14.3-lx.18 (libbox.aar)
- **QR**: ZXing
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: arm64-v8a only

---

## 🔐 Signature

- **SHA-256**: `5C:07:01:E8:92:65:C8:55:66:60:0E:E1:1C:88:49:60:33:9F:3B:17:25:C3:52:29:0C:46:A6:4F:99:2D:47:B6`
- **Signer**: `CN=NurVPN Developer, OU=Development, O=NurVPN, L=Andijan, ST=Andijan, C=UZ`

---

## 📝 Changelog

### v1.8 (2026-10-15)

- **HWID system** (incy-style) — salt + Build.* → SHA-256 → 30 chars
- **VLESS REALITY**: `key_share: hybrid`, `fingerprint: chrome` enforced
- **libbox.aar rebuilt** — sing-box v1.14.3-lx.18 (naive outbound removed, X25519MLKEM768 filter)
- **MASQUE fixes** — MTU 1360 (#1, #2) and 1420 (#3)
- **Split Tunneling** — selected apps sorted to top
- **YAML parser** — flat proxies support
- **Provider name** — read from `profile-title` header
- **AWG Config** — checkbox toggle fixed
- **Translations** — UZ and RU complete (370 strings each)
- **New keystore** (security update — previous one was exposed)

### v1.8

- Initial public release

---

## Contributing

Pull requests and issues are welcome!

1. Fork it
2. Create a branch
3. Commit changes
4. Push
5. Open a Pull Request

---

## License

**GNU General Public License v3.0** — see [LICENSE](LICENSE) for details.

---

## Author

**Javohir** — [@javoxir5543](https://github.com/javoxir5543)

> I am **not a developer** — just **interested in this field**. Built with help from **DeepSeek AI**.

---

## Credits

- sing-box — VPN core
- ZXing — QR scanner
- **DeepSeek AI** — main development assistant
- All open source subscription owners

If useful — give a star!

---

## 🚊 Code Documentation

All source files are documented in MD format:

**📂 [docs/code/](docs/code)** — 111 files

Contents:
- `app/src/main/java/` — Kotlin code (backend, parsers, UI, services)
- `app/src/main/res/` — XML resources (layouts, strings, drawables)
- `app/build.gradle` + `proguard-rules.pro`
- Root Gradle files (build, settings, properties)

Each file — with a title and a code block. Can be read directly on GitHub.
