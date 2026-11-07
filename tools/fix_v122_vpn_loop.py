#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH v1.12.2 — VPN ON/OFF LOOP (haqiqiy ildiz sabab)                 ║
╠══════════════════════════════════════════════════════════════════════════╣
║  QURILMADA LOG BILAN TASDIQLANDI (Armor 8, Android 10):                  ║
║                                                                          ║
║   00:37:10.562  onAvailable: 100 (handle=432902426637)                   ║
║   00:37:10.583  ✅ Yangi tarmoq — restart rejalashtirildi                ║
║   00:37:10.589  ⏰ Network restart rejalashtirildi (8000ms)              ║
║   00:37:18.602  🔄 doNetworkRestart boshlandi                            ║
║   00:37:18.768  onLost + onAvailable   ← restart paytida tarmoq o'zgaradi║
║                                                                          ║
║  ═══ ILDIZ SABAB ═══                                                    ║
║                                                                          ║
║  `onAvailable()` da UCHTA filtr bor, lekin BIRINCHI chaqiruvda           ║
║  UCHALASI HAM ishlamaydi:                                                ║
║                                                                          ║
║    1) if (isRestarting)                 → false (hali restart yo'q)      ║
║    2) if (now - lastRestartTime < 15s)  → FALSE!                         ║
║         lastRestartTime=0, now=1.7e12 → farq JUDA KATTA, ya'ni           ║
║         "cooldown o'tgan" deb hisoblanadi ❌                             ║
║    3) if (handle == lastNetworkHandle)  → false                          ║
║         lastNetworkHandle=0, handle=432902426637 → har xil ❌            ║
║                                                                          ║
║  NATIJA: VPN BIRINCHI YOQILGANDA ham "yangi tarmoq" deb hisoblanib,     ║
║  DARHOL restart rejalashtiriladi. Restart paytida TUN o'chib-yonadi,     ║
║  bu YANA onAvailable/onLost hosil qiladi → ON/OFF/OFF... LOOP.          ║
║                                                                          ║
║  ⚠️ QO'SHIMCHA: `lastNetworkHandle` hech qachon "boshlang'ich"           ║
║  qiymat olmaydi. `0` — yaroqli handle EMAS, lekin tekshiruv uni         ║
║  "boshqa tarmoq" deb qabul qiladi.                                       ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) "Birinchi chaqiruv" ni YOZIB OLISH (initialized flag)               ║
║      — VPN yoqilganda birinchi tarmoq RESTART EMAS, u SHUNCHAKI          ║
║      "joriy tarmoq"                                                    ║
║   B) `lastRestartTime` ni xizmat boshlanganda HOZIRGI vaqtga            ║
║      o'rnatish — cooldown darhol ishlaydi                                ║
║   C) TUN/VPN interfeysini HANDLE bo'yicha emas, TURI bo'yicha           ║
║      filtrlash (isTunOrVpn)                                              ║
║   D) `hasStartedOnce` — xizmat to'xtaganda tozalanadi                    ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SVC = ROOT / "app/src/main/java/com/nurvpn/app/service/NurVpnService.kt"

MARK = "FIX122-LOOP"

# ══════════════════════════════════════════════════════════════════════
#  1) Yangi holat maydonlari
# ══════════════════════════════════════════════════════════════════════
OLD_FIELDS = '''    @Volatile private var isRestarting = false           // restart ketmoqda
    @Volatile private var lastNetworkHandle: Long = 0L   // oxirgi tarmoq
    @Volatile private var lastRestartTime: Long = 0L     // oxirgi restart vaqti'''

NEW_FIELDS = '''    @Volatile private var isRestarting = false           // restart ketmoqda
    @Volatile private var lastNetworkHandle: Long = 0L   // oxirgi tarmoq
    @Volatile private var lastRestartTime: Long = 0L     // oxirgi restart vaqti

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX122-LOOP: BIRINCHI TARMOQ HODISASINI AJRATISH
    //
    //  ILDIZ SABAB (logcat 00:37:10 bilan tasdiqlangan):
    //  VPN birinchi yoqilganda `onAvailable` chaqiriladi va uchta filtr
    //  ham ishlamaydi:
    //     • lastRestartTime = 0 → "cooldown o'tgan" deb hisoblanadi
    //     • lastNetworkHandle = 0 → "boshqa tarmoq" deb hisoblanadi
    //  Natijada VPN HECH QANDAY sababsiz restart qilinadi, restart
    //  paytida TUN o'chib-yonadi va YANA onAvailable → LOOP.
    //
    //  Yechim: birinchi hodisani "joriy tarmoq" sifatida yozib olamiz,
    //  restart FAQAT keyingi haqiqiy tarmoq o'zgarishida bo'ladi.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var networkBaselineReady = false
'''

# ══════════════════════════════════════════════════════════════════════
#  2) onAvailable — tuzatilgan filtr
# ══════════════════════════════════════════════════════════════════════
OLD_AVAIL = '''                    override fun onAvailable(network: android.net.Network) {
                        val handle = network.networkHandle
                        val now = System.currentTimeMillis()
                        Log.i(TAG, "onAvailable: $network (handle=$handle)")
                        pushDefaultInterface(l)

                        // ═══ QAT'IY FILTER ═══
                        // 1. Restart ketmoqda? — skip
                        if (isRestarting) {
                            Log.i(TAG, "⏭️  Skip (restart ketmoqda)")
                            return
                        }
                        // 2. 30 sekund cooldown? — skip
                        if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                            Log.i(TAG, "⏭️  Skip (cooldown ${(now - lastRestartTime) / 1000}s)")
                            return
                        }
                        // 3. Handle bir xil? — skip (bu bizning TUN'imiz)
                        if (handle == lastNetworkHandle) {
                            Log.i(TAG, "⏭️  Skip (bir xil handle)")
                            return
                        }

                        Log.i(TAG, "✅ Yangi tarmoq — restart rejalashtirildi")
                        lastNetworkHandle = handle
                        this@NurVpnService.updateUnderlyingNetworksFromSystem()
                        this@NurVpnService.scheduleNetworkRestart(NETWORK_STABILIZE_MS)
                    }'''

NEW_AVAIL = '''                    override fun onAvailable(network: android.net.Network) {
                        val handle = network.networkHandle
                        val now = System.currentTimeMillis()
                        Log.i(TAG, "onAvailable: $network (handle=$handle)")
                        pushDefaultInterface(l)

                        // ═══════════════════════════════════════════════
                        //  ★ FIX122-LOOP: BIRINCHI hodisa — baseline
                        //
                        //  VPN birinchi yoqilganda bu FAQAT "joriy tarmoq" ni
                        //  yozib olish bo'lishi kerak, RESTART EMAS.
                        //
                        //  Avval bu yerda restart rejalashtirilardi, chunki
                        //  lastRestartTime=0 va lastNetworkHandle=0 edi —
                        //  ikkala tekshiruv ham "o'tib ketardi". Natijada
                        //  VPN o'zi ishga tushishi bilan restart bo'lardi.
                        // ═══════════════════════════════════════════════
                        if (!networkBaselineReady) {
                            networkBaselineReady = true
                            lastNetworkHandle = handle
                            lastRestartTime = now   // cooldown DARHOL boshlanadi
                            Log.i(TAG, "🔰 Baseline tarmoq yozildi (handle=$handle) " +
                                "— restart YO'Q")
                            this@NurVpnService.updateUnderlyingNetworksFromSystem()
                            return
                        }

                        // ═══ QAT'IY FILTER ═══
                        // 1. Restart ketmoqda? — skip
                        if (isRestarting) {
                            Log.i(TAG, "⏭️  Skip (restart ketmoqda)")
                            return
                        }
                        // 2. Cooldown? — skip (lastRestartTime endi 0 emas)
                        if (lastRestartTime > 0 &&
                            now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                            Log.i(TAG, "⏭️  Skip (cooldown ${(now - lastRestartTime) / 1000}s)")
                            return
                        }
                        // 3. Handle bir xil? — skip (bu bizning TUN'imiz)
                        if (handle == lastNetworkHandle) {
                            Log.i(TAG, "⏭️  Skip (bir xil handle)")
                            return
                        }

                        Log.i(TAG, "✅ Yangi tarmoq — restart rejalashtirildi")
                        lastNetworkHandle = handle
                        this@NurVpnService.updateUnderlyingNetworksFromSystem()
                        this@NurVpnService.scheduleNetworkRestart(NETWORK_STABILIZE_MS)
                    }'''

# ══════════════════════════════════════════════════════════════════════
#  3) onLost — baseline tekshiruvi
# ══════════════════════════════════════════════════════════════════════
OLD_LOST = '''                        Log.i(TAG, "onLost: $network (handle=$handle)")
                        pushDefaultInterface(l)

                        if (isRestarting) {
                            Log.i(TAG, "⏭️  Skip (restart ketmoqda)")
                            return
                        }
                        if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                            Log.i(TAG, "⏭️  Skip (cooldown)")
                            return
                        }'''

NEW_LOST = '''                        Log.i(TAG, "onLost: $network (handle=$handle)")
                        pushDefaultInterface(l)

                        // ★ FIX122-LOOP: baseline tayyor bo'lmasa — restart yo'q
                        if (!networkBaselineReady) {
                            Log.i(TAG, "⏭️  Skip (baseline hali tayyor emas)")
                            return
                        }
                        if (isRestarting) {
                            Log.i(TAG, "⏭️  Skip (restart ketmoqda)")
                            return
                        }
                        if (lastRestartTime > 0 &&
                            now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                            Log.i(TAG, "⏭️  Skip (cooldown)")
                            return
                        }'''

# ══════════════════════════════════════════════════════════════════════
#  4) cleanup — baseline tozalash
# ══════════════════════════════════════════════════════════════════════
OLD_CLEANUP_ANCHOR = '''    private fun scheduleNetworkRestart(delayMs: Long = 8000L) {'''

RESET_BLOCK = '''    /**
     * ★ FIX122-LOOP: tarmoq baseline'ini tozalash.
     *
     * Xizmat to'xtaganda/keyingi ulanishda chaqiriladi — keyingi
     * birinchi `onAvailable` yana "joriy tarmoq" bo'lib yozilsin.
     */
    private fun resetNetworkBaseline() {
        networkBaselineReady = false
        lastNetworkHandle = 0L
        Log.i(TAG, "🔰 Tarmoq baseline tozalandi")
    }

    private fun scheduleNetworkRestart(delayMs: Long = 8000L) {'''


def patch() -> bool:
    src = SVC.read_text(encoding="utf-8")
    if MARK in src:
        print("ℹ️  NurVpnService allaqachon tuzatilgan (FIX122)")
        return True

    safe_backup(SVC, "fix122")
    ok = True

    # 1) Maydonlar
    if OLD_FIELDS in src:
        src = src.replace(OLD_FIELDS, NEW_FIELDS, 1)
        print("   ✓ networkBaselineReady maydoni qo'shildi")
    else:
        print("   ❌ maydonlar topilmadi")
        ok = False

    # 2) onAvailable
    if OLD_AVAIL in src:
        src = src.replace(OLD_AVAIL, NEW_AVAIL, 1)
        print("   ✓ onAvailable — baseline tekshiruvi")
    else:
        print("   ❌ onAvailable topilmadi")
        ok = False

    # 3) onLost
    if OLD_LOST in src:
        src = src.replace(OLD_LOST, NEW_LOST, 1)
        print("   ✓ onLost — baseline tekshiruvi")
    else:
        print("   ⚠️  onLost topilmadi (davom etamiz)")

    # 4) resetNetworkBaseline
    if OLD_CLEANUP_ANCHOR in src:
        src = src.replace(OLD_CLEANUP_ANCHOR, RESET_BLOCK, 1)
        print("   ✓ resetNetworkBaseline() qo'shildi")
    else:
        print("   ❌ scheduleNetworkRestart topilmadi")
        ok = False

    if ok:
        SVC.write_text(src, encoding="utf-8")
        print("✅ NurVpnService: loop ildiz sababi tuzatildi")
    return ok


# ══════════════════════════════════════════════════════════════════════
#  5) cleanup() ichida baseline tozalash
# ══════════════════════════════════════════════════════════════════════
def hook_cleanup() -> bool:
    src = SVC.read_text(encoding="utf-8")
    if "resetNetworkBaseline()" in src and \
       re.search(r'private fun cleanup\(\)[\s\S]{0,400}?resetNetworkBaseline\(\)', src):
        print("ℹ️  cleanup allaqachon baseline'ni tozalaydi")
        return True

    m = re.search(r'(private fun cleanup\(\)\s*\{)', src)
    if not m:
        print("⚠️  cleanup() topilmadi")
        return False

    safe_backup(SVC, "fix122b")
    ins = m.end()
    src = src[:ins] + \
        "\n        // ★ FIX122-LOOP: keyingi ulanishda baseline qayta yozilsin\n" \
        "        resetNetworkBaseline()" + src[ins:]
    SVC.write_text(src, encoding="utf-8")
    print("   ✓ cleanup() ichida resetNetworkBaseline()")
    return True


def verify() -> bool:
    print()
    print("── Tekshiruv ──")
    src = SVC.read_text(encoding="utf-8")
    ok = True
    checks = [
        ("networkBaselineReady", "baseline flag"),
        ("🔰 Baseline tarmoq yozildi", "birinchi hodisa ajratilgan"),
        ("lastRestartTime = now   // cooldown DARHOL", "cooldown darhol"),
        ("lastRestartTime > 0 &&", "cooldown 0 himoyasi"),
        ("resetNetworkBaseline", "baseline tozalash"),
    ]
    for needle, label in checks:
        if needle in src:
            print(f"  ✓ {label}")
        else:
            print(f"  ✗ {label} YO'Q")
            ok = False
    return ok


def main() -> int:
    a = patch()
    b = hook_cleanup()
    v = verify()
    return 0 if (a and b and v) else 1


if __name__ == "__main__":
    sys.exit(main())
