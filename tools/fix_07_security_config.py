#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #7 — Xavfsizlik UI (IPv6 bloklash / DNS leak) config'ga        ║
║                 HAQIQATAN ta'sir qiladimi?                               ║
╠══════════════════════════════════════════════════════════════════════════╣
║  TEKSHIRUV NATIJASI:                                                     ║
║                                                                          ║
║  ✅ IPv6 BLOCKLASH — TO'G'RI ISHLAYDI:                                   ║
║     SettingsFragment ipv6Switch → IPv6Blocker.setBlocked()               ║
║     NurVpnService:1071 → IPv6Blocker.isBlocked(this)                     ║
║     → SingBoxConfig.buildFullConfig(..., ipv6Blocked, this)              ║
║     → assemble(): strategy = "ipv4_only", tunV6 = false, ipv6 maydonlari ║
║       chiqarilmaydi. ✓                                                   ║
║                                                                          ║
║  ❌ DNS LEAK HIMOYASI — CONFIG'GA UMUMAN BOG'LANMAGAN!                   ║
║     `dnsLeakEnabled` FAQAT bitta joyda ishlatiladi:                      ║
║         NurVpnService.kt:374  (VpnService Builder route'lari)            ║
║     `grep -rn "DNSLeakProtection" SingBoxConfig.kt` → HECH NARSA         ║
║                                                                          ║
║     Ya'ni: TUN route qo'shiladi (yaxshi), LEKIN sing-box DNS             ║
║     konfiguratsiyasida `dns-direct` serveri HAR DOIM mavjud va           ║
║     bootstrap DNS'ga to'g'ridan-to'g'ri so'rov yuboriladi →              ║
║     DNS LEAK imkoniyati qoladi.                                          ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   1. DNS leak YOQILGAN bo'lsa — `dns-direct` serverini `detour: proxy`   ║
║      bilan bog'lash (bootstrap ham VPN ichidan ketsin).                  ║
║   2. DNS leak O'CHIRILGAN bo'lsa — hozirgi xatti-harakat (direct).       ║
║   3. Yangi parametr `dnsLeakProtect` (standart: true — xavfsiz).         ║
║   4. Server domenini hal qilish uchun alohida "bootstrap" serveri        ║
║      qoldiriladi — aks holda "chicken-and-egg" (VPN'siz server           ║
║      domenini hal qilib bo'lmaydi).                                      ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CFG = ROOT / "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"
SVC = ROOT / "app/src/main/java/com/nurvpn/app/service/NurVpnService.kt"

# ── 1) assemble() ga dnsLeakProtect parametri ────────────────────────────
OLD_DNS = '''        val servers = JSONArray()
            .put(JSONObject()
                .put("type", if (noUdp) "tcp" else "udp").put("tag", "dns-remote")
                .put("server", remoteDns).put("detour", "proxy"))
            .put(JSONObject()
                .put("type", "udp").put("tag", "dns-direct")
                .put("server", BOOTSTRAP_DNS))'''

NEW_DNS = '''        // ★ TUZATISH #7: DNS LEAK HIMOYASI config darajasida.
        //   Yoqilgan bo'lsa — barcha DNS (bootstrap ham) VPN ichidan ketadi.
        //   O'chirilgan bo'lsa — bootstrap to'g'ridan-to'g'ri (tezroq, lekin
        //   provayder DNS so'rovlarini ko'ra oladi).
        val servers = JSONArray()
            .put(JSONObject()
                .put("type", if (noUdp) "tcp" else "udp").put("tag", "dns-remote")
                .put("server", remoteDns).put("detour", "proxy"))
            .put(JSONObject()
                .put("type", "udp").put("tag", "dns-direct")
                .put("server", BOOTSTRAP_DNS)
                // ★ Leak himoyasi ON bo'lsa bootstrap ham proxy orqali
                .apply { if (dnsLeakProtect) put("detour", "proxy") })'''

# ── 2) assemble() imzosiga parametr ──────────────────────────────────────
OLD_SIG = '''        ipv6Blocked: Boolean,
        tunV6: Boolean = !ipv6Blocked,'''
NEW_SIG = '''        ipv6Blocked: Boolean,
        // ★ TUZATISH #7: DNS leak himoyasi (standart YOQILGAN = xavfsiz)
        dnsLeakProtect: Boolean = true,
        tunV6: Boolean = !ipv6Blocked,'''

# ── 3) buildFullConfig imzosi ────────────────────────────────────────────
OLD_FULL = '''    fun buildFullConfig(
        link: String,
        dns: String,
        @Suppress("UNUSED_PARAMETER") cachePath: String = "",
        ipv6Blocked: Boolean = false,
        ctx: android.content.Context? = null
    ): JSONObject {'''
NEW_FULL = '''    fun buildFullConfig(
        link: String,
        dns: String,
        @Suppress("UNUSED_PARAMETER") cachePath: String = "",
        ipv6Blocked: Boolean = false,
        ctx: android.content.Context? = null,
        // ★ TUZATISH #7: DNS leak himoyasi (Sozlamalar → Xavfsizlik)
        dnsLeakProtect: Boolean = true
    ): JSONObject {'''

OLD_FULL_RET = '''        return assemble(proxy, false, dns, proxy.optString("server"), ipv6Blocked,'''
NEW_FULL_RET = '''        return assemble(proxy, false, dns, proxy.optString("server"), ipv6Blocked,
            dnsLeakProtect = dnsLeakProtect,'''


def patch_cfg() -> bool:
    src = CFG.read_text(encoding="utf-8")
    if "TUZATISH #7" in src:
        print("ℹ️  SingBoxConfig allaqachon tuzatilgan")
        return True

    safe_backup(CFG, "fix7")

    checks = [
        (OLD_DNS, NEW_DNS, "dns-direct detour"),
        (OLD_SIG, NEW_SIG, "assemble imzosi"),
        (OLD_FULL, NEW_FULL, "buildFullConfig imzosi"),
        (OLD_FULL_RET, NEW_FULL_RET, "buildFullConfig qaytarish"),
    ]
    for old, new, label in checks:
        if old not in src:
            print(f"❌ Topilmadi: {label}")
            return False
        src = src.replace(old, new, 1)
        print(f"   ✓ {label}")

    CFG.write_text(src, encoding="utf-8")
    print(f"✅ Yangilandi: {CFG.relative_to(ROOT)}")
    return True


def patch_service() -> bool:
    src = SVC.read_text(encoding="utf-8")
    if "TUZATISH #7" in src:
        print("ℹ️  NurVpnService allaqachon tuzatilgan")
        return True

    old = '''                SingBoxConfig.buildFullConfig(link!!, dns, "", ipv6Blocked, this)'''
    new = '''                // ★ TUZATISH #7: DNS leak sozlamasi config'ga ham uzatiladi
                //   (avval faqat VpnService route'larida ishlatilardi)
                SingBoxConfig.buildFullConfig(
                    link!!, dns, "", ipv6Blocked, this,
                    dnsLeakProtect = dnsLeakEnabled)'''

    if old not in src:
        print("❌ NurVpnService'da buildFullConfig chaqiruvi topilmadi")
        return False

    safe_backup(SVC, "fix7")
    src = src.replace(old, new, 1)
    SVC.write_text(src, encoding="utf-8")
    print(f"✅ Yangilandi: {SVC.relative_to(ROOT)}")
    return True


def main() -> int:
    a = patch_cfg()
    b = patch_service()
    return 0 if (a and b) else 1


if __name__ == "__main__":
    sys.exit(main())
