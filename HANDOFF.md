# NurVPN — HANDOFF

## 🖥️ Muhit
- OS: Linux Mint
- Terminal: zsh
- Loyiha: ~/NurVPN
- GitHub: https://github.com/javoxir5543/NurVPN
- User: javoxir5543

## 🔧 Loyiha
Android VPN client:
- Kotlin, Material 3, XML + Fragment (Compose emas)
- sing-box 1.14.0 (libbox.aar)
- Gradle 8.2, JDK 21, Min SDK 24, Target SDK 34

## ✅ Refactor yakunlandi (2026-09-26)

Oldin -> Hozir:
- Fayllar soni: 7 -> 43
- Eng katta fayl: 4968 -> 2352 qator
- NurVPNCore.kt: ochirilgan
- NurVPNAllInOne.kt: ochirilgan

## 📦 Yangi struktura

com/nurvpn/app/
- core/       (9 fayl) — Protocol, ServerItem, AWGConfig, TunnelState
- util/       (8 fayl) — ThemeHelper, CountryLookup, PingTester, LeakTester
- parser/     (5 fayl) — ServerLinkParser, AWGParser, Base64Util
- storage/    (9 fayl) — ServerStore, SubscriptionStore, AWGStore
- ai/         (3 fayl) — SmartScoreEngine, AIInsights, AIServerSelector
- config/     (3 fayl) — SingBoxConfig, BuiltinAwgConfigs
- service/    (3 fayl) — NurVpnService, NurVpnTileService, BinaryRunner
- ui/         MainActivity.kt + 7 sub-paket

## ⚠️ Hali katta fayllar (ixtiyoriy keyingi ish)

| Fayl | Qator |
|------|-------|
| ui/home/HomeFragment.kt | 2352 |
| ui/servers/ServersFragment.kt | 1864 |
| service/NurVpnService.kt | 988 |
| config/SingBoxConfig.kt | 732 |

## 🛠️ Foydali buyruqlar

Build:
  cd ~/NurVPN
  ./gradlew --no-daemon clean assembleDebug
  adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

Xatolar:
  ./gradlew --no-daemon clean assembleDebug 2>&1 | grep "^e:" | head -30

Git:
  git status
  git log --oneline -5
  git add -A
  git commit -m "..."
  git push origin main

## ⚠️ Muhim eslatmalar

1. res/ papkasiga backup qoymang — Android resource merger buziladi
2. Backup: ~/NurVPN-backups/
3. libbox.aar = 52MB (strip qilingan, x86/x86_64 olib tashlangan)
4. APK hajmi: 84MB (arm64), 76MB (armeabi)
5. substringAfter(":", "") kerak — substringAfter(':', '') ishlamaydi
6. system_interface sing-box 1.14.0 endpoints da yoq
7. reserved WARP uchun majburiy
8. sed -i '/pattern/a\ntext' zsh da literal \n qoshadi — perl -i -pe ishlatish yaxshiroq

## 📝 Avvalgi sessiyalarda qilingan ishlar

1. JSON serverlar nomi (Xray-Config -> haqiqiy nom)
2. JSON subId tuzatildi
3. XHTTP transport
4. SNI tozalash
5. JSON fayl import
6. WireGuard WARP (reserved)
7. Select rejimi (Home + Servers)
8. AWG yulduzcha (Home + Servers)
9. Home long-press menyu
10. IPv6 leak test tuzatildi
11. Translation (uz/ru/en)
12. Deduplikatsiya
13. Shadowsocks + SS 2022
14. Bayroq remark dan
15. Sevimlilar/obuna ping
16. Release build
17. GitHub Release v1.1
18. SpeedWaveView v17 Cosmic Aurora
19. DNS picker tugmasi
20. VpnService.prepare faqat connect da
21. IPv6 bloklangan i18n
22. Yangi screenshots (7 ta)

## 🎯 Keyingi chatda nima qilish mumkin

A) HomeFragment ni bo'lish (2352 qator)
B) ServersFragment ni bo'lish (1864 qator)
C) NurVpnService ni bo'lish (988 qator)
D) SingBoxConfig ni parserlarga bo'lish (732 qator)
E) Yangi funksiyalar qoshish
F) Testlar yozish

## 🚀 Birinchi qadam (yangi chatda)

cd ~/NurVPN
git status
git log --oneline -5
./gradlew --no-daemon clean assembleDebug

Keyin qaysi variantni tanlashni aytaman.
