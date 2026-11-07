#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #5 — Oddiy (shifrlanmagan) deep link'larni qabul qilish        ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ANIQLANGAN SABAB:                                                       ║
║                                                                          ║
║  `MainActivity.handleDeepLink()` da shafqatsiz filtr bor:                ║
║                                                                          ║
║      if (!Crypt1Decoder.isCryptLink(uri)) {                              ║
║          Log.w(..., "Not a crypt link, ignore")                          ║
║          return                       // ← TASHLAB YUBORILADI!           ║
║      }                                                                   ║
║                                                                          ║
║  Ya'ni:                                                                  ║
║    ✓ crypt1://   → ishlaydi  (isCryptLink = true)                        ║
║    ✓ incy://import/... → ishlaydi (alohida shox)                         ║
║    ✗ vless://...  → RAD ETILADI  (isCryptLink = false)                   ║
║    ✗ hysteria2://... → RAD ETILADI                                       ║
║    ✗ http(s)://...   → RAD ETILADI                                       ║
║                                                                          ║
║  QIZIQ: `HomeFragment.checkPendingDeepLink()` allaqachon http(s) ni      ║
║  qabul qilishga tayyor (2645-qator) — lekin link u yerga YETIB BORMAYDI, ║
║  chunki MainActivity uni saqlashdan oldin tashlab yuboradi.              ║
║                                                                          ║
║  YECHIM: proxy linklari (vless/vmess/trojan/ss/...) va http(s) ni ham    ║
║  qabul qilish. Crypt linklar avvalgidek ishlaydi.                        ║
║                                                                          ║
║  ⚠️ XAVFSIZLIK: faqat MA'LUM protokol prefikslari qabul qilinadi.       ║
║  Tasodifiy "file://" yoki "javascript://" kabi URI'lar rad etiladi.      ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"

OLD = '''        // Faqat crypt link'lar
        if (!com.nurvpn.app.parser.Crypt1Decoder.isCryptLink(uri)) {
            android.util.Log.w("NurVPN-DEEPLINK", "Not a crypt link, ignore")
            return
        }

        // Pending link sifatida saqlash
        prefs.edit().putString("pending_deeplink", uri).apply()
        android.util.Log.i("NurVPN-DEEPLINK", "Saved pending_deeplink")'''

NEW = '''        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #5: endi ODDIY linklar ham qabul qilinadi.
        //
        //  Avval faqat crypt linklar o'tardi:
        //      if (!isCryptLink(uri)) { return }   ← vless:// yo'qolardi
        //
        //  Endi: crypt1:// + incy:// + BARCHA proxy protokollari +
        //        http(s) obuna URL'lari qabul qilinadi.
        //  Boshqa hamma narsa (file://, javascript:// va h.k.) rad etiladi.
        // ═══════════════════════════════════════════════════════════════
        val isProxy = com.nurvpn.app.parser.SubscriptionLinkExtractor
            .extract(uri).isNotEmpty()
        val isHttp = uri.startsWith("http://", true) ||
                     uri.startsWith("https://", true)
        val isCrypt = com.nurvpn.app.parser.Crypt1Decoder.isCryptLink(uri)

        if (!isCrypt && !isProxy && !isHttp) {
            android.util.Log.w("NurVPN-DEEPLINK",
                "Qo'llab-quvvatlanmaydigan URI, rad etildi: ${uri.take(60)}")
            return
        }

        android.util.Log.i("NurVPN-DEEPLINK",
            "Qabul qilindi (crypt=$isCrypt proxy=$isProxy http=$isHttp)")

        // Pending link sifatida saqlash
        prefs.edit().putString("pending_deeplink", uri).apply()
        android.util.Log.i("NurVPN-DEEPLINK", "Saved pending_deeplink")'''

# Manifest'ga yangi scheme'lar
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
SCHEME_ANCHOR = '                <data android:scheme="nurvpn"/>\n'
SCHEMES = [
    "vless", "vmess", "trojan", "ss", "ssr", "hysteria2", "hy2",
    "tuic", "juicity", "anytls", "shadowtls", "socks5", "ssh",
]


def patch_main() -> bool:
    src = MAIN.read_text(encoding="utf-8")
    if "★ TUZATISH #5" in src:
        print("ℹ️  #5 allaqachon qo'llanilgan")
        return True
    if OLD not in src:
        print("❌ MainActivity'da eski blok topilmadi")
        return False

    safe_backup(MAIN, "fix5")

    src = src.replace(OLD, NEW, 1)
    MAIN.write_text(src, encoding="utf-8")
    print(f"✅ Yangilandi: {MAIN.relative_to(ROOT)}")
    return True


def patch_manifest() -> bool:
    src = MANIFEST.read_text(encoding="utf-8")
    if 'android:scheme="vless"' in src:
        print("ℹ️  Manifest'da scheme'lar allaqachon bor")
        return True
    if SCHEME_ANCHOR not in src:
        print("❌ Manifest anchor topilmadi")
        return False

    safe_backup(MANIFEST, "fix5")

    add = "".join(
        f'                <data android:scheme="{s}"/>\n' for s in SCHEMES
    )
    src = src.replace(SCHEME_ANCHOR, SCHEME_ANCHOR + add, 1)
    MANIFEST.write_text(src, encoding="utf-8")
    print(f"✅ Yangilandi: {MANIFEST.relative_to(ROOT)}")
    print(f"   + {len(SCHEMES)} ta scheme: {', '.join(SCHEMES)}")
    return True


def main() -> int:
    a = patch_main()
    b = patch_manifest()
    return 0 if (a and b) else 1


if __name__ == "__main__":
    sys.exit(main())
