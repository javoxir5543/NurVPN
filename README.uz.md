# NurVPN

**Android uchun zamonaviy VPN client** — Kotlin, Material 3, INCY uslubi.

[English](README.md) | [O'zbekcha](README.uz.md) | [Русский](README.ru.md)

![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet?logo=kotlin)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?logo=android)
![License](https://img.shields.io/badge/License-MIT-yellow)
![Version](https://img.shields.io/badge/version-1.8-blue)

---

> **Eslatma:** Men **umuman dasturchi emasman** — bu sohaga **qiziqishim bor**. Bu loyiha **DeepSeek AI** (va boshqa AI yordamchilar) yordamida **o'rganish va shaxsiy foydalanish** uchun yaratilgan. Kodda xatolar yoki optimal bo'lmagan yechimlar bo'lishi mumkin. Pull request va yaxshilanishlar xush kelibsiz!

---

## Xususiyatlar

- **18 ta protokol**: VLESS Reality, VLESS, VMess, Trojan, Shadowsocks, Hysteria, Hysteria2, TUIC, WireGuard, AmneziaWG 1.x/2.x/3.x, MASQUE, AnyTLS, ShadowTLS, SSH, SOCKS, HTTP/HTTPS, NaiveProxy
- **Zamonaviy UI** — Material 3, qora-yashil + lime
- **Real-time trafik** — yuqoriga/pastga B/s
- **3 til**: O'zbek, Rus, Ingliz
- **Kun/tun rejimi**
- **Sevimlilar** — tez ulanish uchun
- **Ping test** — TCP + ICMP
- **Split Tunneling** — Whitelist/Blacklist
- **DNS leak + IPv6 himoya**
- **QR skaner** — link va AWG config
- **Ochiq manbalar** — 10+ bepul obuna (butunlay o'chirish mumkin)
- **AI Selector** — eng yaxshi server avtomatik tanlash
- **Pauza/Davom etish** — notification panel
- **Quick Settings Tile** — control center
- **HWID** — incy-style qurilma identifikatsiyasi
- **Provayder nomi** — `profile-title` header'dan avtomatik o'qiladi

---

## 📸 Skrinshotlar

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

## O'rnatish

### APK yuklab olish

1. **Releases** bo'limiga o'ting
2. **nurvpn-v1.8-arm64-v8a.apk** ni yuklab oling (zamonaviy telefonlar, ~64 MB)
3. O'rnating

### Manbadan qurish

    git clone https://github.com/javoxir5543/NurVPN.git
    cd NurVPN
    ./gradlew assembleRelease

**Eslatma:** `libbox.aar` allaqachon `app/libs/`da mavjud. Release build uchun `keystore.properties` fayl yaratishingiz kerak.

---

## Foydalanish

1. Ilovani oching
2. **Sozlamalar → Ochiq manbalar** → bepul obunani yoqing
3. Yoki **+ Qo'shish** → o'z VLESS/VMess/AWG linkingizni kiriting
4. **Asosiy tab** → server tanlang
5. **Play** tugmasini bosing → VPN ulanadi

---

## Texnologiya

- **Til**: Kotlin
- **UI**: Material 3, View Binding
- **Core**: sing-box v1.14.3-lx.18 (libbox.aar)
- **QR**: ZXing
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Arxitektura**: faqat arm64-v8a

---

## 🔐 Imzo

- **SHA-256**: `5C:07:01:E8:92:65:C8:55:66:60:0E:E1:1C:88:49:60:33:9F:3B:17:25:C3:52:29:0C:46:A6:4F:99:2D:47:B6`
- **Signer**: `CN=NurVPN Developer, OU=Development, O=NurVPN, L=Andijan, ST=Andijan, C=UZ`

---

## 📝 O'zgarishlar tarixi

### v1.8 (2026-10-15)

- **HWID tizimi** (incy-style) — salt + Build.* → SHA-256 → 30 belgi
- **VLESS REALITY**: `key_share: hybrid`, `fingerprint: chrome` majburiy
- **libbox.aar qayta yig'ildi** — sing-box v1.14.3-lx.18
- **MASQUE tuzatishlari** — MTU 1360 (#1, #2), 1420 (#3)
- **Split Tunneling** — tanlanganlar tepada ko'rinadi
- **YAML parser** — flat proxies qo'llab-quvvatlash
- **Provayder nomi** — `profile-title` header'dan o'qiladi
- **AWG Config** — checkbox muammosi tuzatildi
- **Tarjimalar** — UZ va RU to'liq (370 string)
- **Yangi keystore** (xavfsizlik yangilanishi — eski oshkor bo'lgan)

### v1.8

- Birinchi ommaviy versiya

---

## Hissa qo'shish

Pull request va issue'lar xush kelibsiz!

1. Fork qiling
2. Branch yarating
3. Commit qiling
4. Push qiling
5. Pull Request oching

---

## Litsenziya

**MIT License** — batafsil: [LICENSE](LICENSE)

---

## Muallif

**Javohir** — [@javoxir5543](https://github.com/javoxir5543)

> **Dasturchi emasman** — bu sohaga **qiziqishim bor**. **DeepSeek AI** yordamida yaratilgan.

---

## Rahmat

- sing-box — VPN core
- ZXing — QR skaner
- **DeepSeek AI** — asosiy yordamchi
- Barcha ochiq manba obuna egalariga

Agar foydali bo'lsa — star bosing!

---

## 📦 F-Droid Repozitoriysi

Avtomatik yangilanishlar uchun rasmiy repozitoriyni qo'shing:

**Repo URL:** `https://javoxir5543.github.io/nurvpn-repo/repo`

### Qanday qo'shish:

1. **F-Droid** yoki **Neo Store** ilovasini oching
2. **Sozlamalar** → **Repozitoriylar** → **Repozitoriy qo'shish**
3. Yuqoridagi URL'ni kiriting
4. **Qo'shish** tugmasini bosing
5. **Yangilanishlar** → **NurVPN** ko'rinadi

Yoki to'g'ridan-to'g'ri havola: [F-Droid repozitoriysi](https://javoxir5543.github.io/nurvpn-repo/repo)

---

## 🚊 Kod Hujjatlari

Loyihaning barcha kod fayllari MD formatida hujjatlashtirilgan:

**📂 [docs/code/](docs/code)** — 111 ta fayl

Tarkibi:
- `app/src/main/java/` — Kotlin kod (backend, parser, UI, service)
- `app/src/main/res/` — XML resurslar (layout, strings, drawable)
- `app/build.gradle` + `proguard-rules.pro`
- Root Gradle fayllar (build, settings, properties)

Har bir fayl — sarlavha va kod bloki bilan. GitHub'da to'g'ridan-to'g'ri o'qish mumkin.
