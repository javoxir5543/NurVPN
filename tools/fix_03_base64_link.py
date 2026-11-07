#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #3 — Base64 obuna va to'liq protokol ro'yxatini qabul qilish   ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ANIQLANGAN SABAB (aniq reproduksiya qilindi):                          ║
║                                                                          ║
║  `ServersFragment.addLink()` (1154-qator) o'zining SHAXSIY regex'i bor:  ║
║                                                                          ║
║    Regex("(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://.*?...")        ║
║                                                                          ║
║  Ikki xato:                                                              ║
║   1) BASE64 DECODE YO'Q. Foydalanuvchi base64 obuna joylashtirsa        ║
║      (`dmxlc3M6Ly8...`) regex HECH NARSA topmaydi → "noto'g'ri link".    ║
║      Tekshirildi: addLink(base64) → 0 ta link.                           ║
║   2) Protokol ro'yxati TO'LIQ EMAS — loyihada qo'llab-quvvatlanadigan    ║
║      14 protokoldan faqat 7 tasi bor. Yo'q: anytls, juicity, socks5,     ║
║      http-proxy, ssh, shadowtls, ssr.                                    ║
║      Tekshirildi: addLink("anytls://...") → 0 ta link.                   ║
║                                                                          ║
║  YECHIM: mavjud va sinovdan o'tgan `SubscriptionLinkExtractor.extract()` ║
║  (14 protokol) + `decodeBase64Safely()` ni ishlatish. Kod TAKRORLANMASIN.║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SERVERS = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"

OLD_BLOCK = '''        val a = activity as? MainActivity ?: return
        val regex = Regex("(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://.*?(?=(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://|\\\\s|$)")
        val matches = regex.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) {
            Toast.makeText(context, R.string.toast_invalid_link,
                Toast.LENGTH_SHORT).show()
            return
        }'''

NEW_BLOCK = '''        val a = activity as? MainActivity ?: return

        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #3: yagona, TO'LIQ parser ishlatiladi (14 protokol)
        //    + base64 obuna avtomatik dekodlanadi.
        //
        //  Avval bu yerda 7 protolik shaxsiy regex bor edi va base64
        //  umuman qo'llab-quvvatlanmasdi → foydalanuvchi base64 obuna
        //  joylashtirsa "noto'g'ri link" xatosi chiqardi.
        // ═══════════════════════════════════════════════════════════════
        var matches = SubscriptionLinkExtractor.extract(text)

        // Base64 obuna bo'lishi mumkin (masalan `dmxlc3M6Ly8...`)
        if (matches.isEmpty()) {
            val decoded = com.nurvpn.app.parser.decodeBase64Safely(text)
            if (decoded.isNotEmpty()) {
                matches = SubscriptionLinkExtractor.extract(decoded)
                android.util.Log.i("NurVPN-DBG",
                    "addLink: base64 dekodlandi → ${matches.size} link")
            }
        }

        // Bitta link bo'lib, u URL (http/https) bo'lsa — obuna sifatida qo'shamiz
        if (matches.isEmpty()) {
            val t = text.trim()
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
                Toast.makeText(context, R.string.toast_sub_url_hint,
                    Toast.LENGTH_LONG).show()
                return
            }
        }

        if (matches.isEmpty()) {
            Toast.makeText(context, R.string.toast_invalid_link,
                Toast.LENGTH_SHORT).show()
            return
        }'''

# Anchors used for import injection
IMPORT_ANCHOR = "import com.nurvpn.app.parser.SubscriptionLinkExtractor\n"


def main() -> int:
    if not SERVERS.exists():
        print(f"❌ Topilmadi: {SERVERS}")
        return 1

    src = SERVERS.read_text(encoding="utf-8")

    if "★ TUZATISH #3" in src:
        print("ℹ️  #3 allaqachon qo'llanilgan — o'tkazib yuborildi")
        return 0

    if OLD_BLOCK not in src:
        print("❌ Eski blok topilmadi. Fayl o'zgargan bo'lishi mumkin.")
        print("   Qidirilgan matn:")
        print("   " + OLD_BLOCK.splitlines()[1].strip()[:90])
        return 1

    safe_backup(SERVERS, "fix3")

    src = src.replace(OLD_BLOCK, NEW_BLOCK, 1)

    # Kerakli importlar
    if IMPORT_ANCHOR not in src:
        # SubscriptionLinkExtractor allaqachon import qilingan bo'lishi mumkin
        if "import com.nurvpn.app.parser.SubscriptionLinkExtractor" not in src:
            src = src.replace(
                "import com.nurvpn.app.parser.ServerLinkParser\n",
                "import com.nurvpn.app.parser.ServerLinkParser\n"
                "import com.nurvpn.app.parser.SubscriptionLinkExtractor\n",
                1,
            )
            print("   + import SubscriptionLinkExtractor")

    SERVERS.write_text(src, encoding="utf-8")
    print(f"✅ Yangilandi: {SERVERS.relative_to(ROOT)}")
    print("   • 7 protolik shaxsiy regex → SubscriptionLinkExtractor (14 protokol)")
    print("   • base64 obuna avtomatik dekodlanadi")
    print("   • http(s) URL yopishtirilsa — tushunarli xabar")
    return 0


if __name__ == "__main__":
    sys.exit(main())
