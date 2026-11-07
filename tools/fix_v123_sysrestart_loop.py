#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH v1.12.3 — VPN ON/OFF LOOP (2-bosqich, oxirgi ildiz sabab)      ║
╠══════════════════════════════════════════════════════════════════════════╣
║  QURILMADA LOG BILAN TASDIQLANDI (Armor 8, Android 10, 90 sekund):       ║
║                                                                          ║
║    sing-box started      : 16 marta   ← HAR 5 SEKUNDDA QAYTA!            ║
║    onStartCommand        : 23 marta   ← INTENT = NULL                    ║
║    restart rejalashtirildi: 0         ← network restart EMAS             ║
║    onCreate (Crash guard) : 1         ← xizmat O'LMAGAN                  ║
║                                                                          ║
║  ═══ KETMA-KETLIK (00:40:03) ═══                                        ║
║    00:40:03.334  ═══ onStartCommand: null     ← INTENT NULL!             ║
║    00:40:03.344  startForeground OK                                     ║
║    00:40:03.345  🔰 Tarmoq baseline tozalandi ← cleanup ishlaydi         ║
║    00:40:03.393  work tozalandi: 32 KB                                  ║
║    00:40:03.695  AWG rejimi: 468 belgi config                           ║
║    00:40:03.705  Libbox.setup OK              ← QAYTA QURILMOQDA         ║
║    00:40:04.432  ═══ openTun CHAQIRILDI        ← QAYTA TUN!              ║
║                                                                          ║
║  ═══ ILDIZ SABAB ═══                                                    ║
║                                                                          ║
║  `onStartCommand(intent = null)` — Android xizmatni qayta ishga          ║
║  tushirmoqda. Kodda `if (intent == null) { stopSelf(startId); return }`  ║
║  bor, LEKIN bu YETARLI EMAS:                                             ║
║                                                                          ║
║  1) `stopSelf(startId)` FAQAT o'sha startId ga tegishli bo'lgan barcha   ║
║     so'rovlar bajarilganda xizmatni to'xtatadi. Agar `startId` dan       ║
║     KEYINROQ kelgan boshqa START bo'lsa — XIZMAT TO'XTAMAYDI.            ║
║                                                                          ║
║  2) `restartVpn()` 2.5 sekundlik `postDelayed` bilan QAYTA               ║
║     `startForegroundService()` chaqiradi — bu AYNAN `intent == null`     ║
║     oynasiga to'g'ri keladi.                                             ║
║                                                                          ║
║  3) `onStartCommand` da `stopSelf()` chaqirilgach `return` qilinadi,     ║
║     lekin ALLAQACHON ishga tushgan `runConnect()` thread davom etadi     ║
║     va `Libbox.setup` + `openTun` ni QAYTA bajaradi.                    ║
║                                                                          ║
║  ═══ YECHIM ═══                                                          ║
║                                                                          ║
║  A) `intent == null` da TO'LIQ LOKAL tozalash — hatto xizmat qayta      ║
║     ishga tushsa ham hech narsa ochilmasin (`intent == null` = "tizim    ║
║     tiklash", ilova VPN xohlamaydi).                                     ║
║                                                                          ║
║  B) `lastConnectIntent` NI TOZALASH — `PAUSE` oqimi `lastConnectIntent` ║
║     bo'lsa qayta ulanadi. Agar u saqlanib qolsa, intent=null dan keyin   ║
║     PAUSE/RESUME oqimi ESKI intent bilan qayta ulanadi.                  ║
║                                                                          ║
║  C) `isSystemRestart` bayrog'i — `onStartCommand(null)` paytida          ║
║     o'rnatiladi, `runConnect`/`connectLocked` uni ko'rib QAYTARMAYDI.    ║
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

MARK = "FIX123-SYSRESTART"

# ══════════════════════════════════════════════════════════════════════
#  1) Yangi bayroq maydoni
# ══════════════════════════════════════════════════════════════════════
OLD_FLAG_ANCHOR = '''    @Volatile private var networkBaselineReady = false'''

NEW_FLAG = '''    @Volatile private var networkBaselineReady = false

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX123-SYSRESTART: TIZIM TIKLASH BAYROG'I
    //
    //  QURILMADA TASDIQLANDI (00:40:03, 90 sekundlik test):
    //    sing-box started : 16 marta  ← har 5 sekundda qayta!
    //    onStartCommand   : 23 marta, HAMMASI intent = null
    //
    //  `onStartCommand(null)` = Android xizmatni QAYTA TIKLAMOQDA.
    //  Bu holatda ilova VPN ni XOHLAMAYDI — hech narsa ochilmasligi
    //  kerak. Lekin `stopSelf(startId)` YETARLI EMAS: agar shu payt
    //  boshqa (kattaroq) startId bilan START kelgan bo'lsa, xizmat
    //  TO'XTAMAYDI va allaqachon ishga tushgan `runConnect()` thread
    //  `Libbox.setup` + `openTun` ni QAYTA bajaradi → LOOP.
    //
    //  Bu bayroq `runConnect`/`connectLocked` ga "to'xta" deb aytadi.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var isSystemRestart = false'''

# ══════════════════════════════════════════════════════════════════════
#  2) intent == null bloki — to'liq lokal tozalash
# ══════════════════════════════════════════════════════════════════════
OLD_NULL = '''        // Tizim jarayonni o'ldirib qayta tiklagan (intent = null) — o'zi ulanmaydi
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }'''

NEW_NULL = '''        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX123-SYSRESTART: TIZIM TIKLASH — HECH NARSA QILMAYMIZ
        //
        //  QURILMADA TASDIQLANDI: bu blok 23 marta chaqirilgan va
        //  HAR SAFAR `Libbox.setup` + `openTun` qayta bajarilib,
        //  VPN har ~5 sekundda ON/OFF bo'lgan.
        //
        //  `stopSelf(startId)` YETARLI EMAS edi, chunki:
        //    • undan keyin kelgan boshqa START xizmatni tirik saqlaydi
        //    • allaqachon ishga tushgan runConnect() thread davom etadi
        //
        //  Endi: bayroq o'rnatamiz, eski intent'ni unutamiz va
        //  barcha ochiq resurslarni yopamiz. Qayta ishga tushsa ham
        //  hech narsa ochilmaydi.
        // ═══════════════════════════════════════════════════════════════
        if (intent == null) {
            Log.w(TAG, "⚠️  TIZIM TIKLASH (intent=null) — VPN ochilmaydi")
            isSystemRestart = true
            lastConnectIntent = null          // eski intent'ni unutamiz
            opSeq.incrementAndGet()           // barcha eski amallarni bekor qilamiz
            hideForeground()
            Thread {
                synchronized(lifecycleLock) {
                    try { cleanup() } catch (t: Throwable) {
                        Log.e(TAG, "sysRestart cleanup xatosi", t)
                    }
                }
                stopSelf(startId)
            }.apply { name = "NurVPN-sysrestart" }.start()
            return START_NOT_STICKY
        }'''

# ══════════════════════════════════════════════════════════════════════
#  3) runConnect — tizim tiklashda to'xtash
# ══════════════════════════════════════════════════════════════════════
OLD_RUNCONNECT_GUARD = '''            try {
                synchronized(lifecycleLock) {
                    if (opSeq.get() != my) {
                        Log.i(TAG, "connect[$my] bekor qilindi (yangi amal bor)")'''

NEW_RUNCONNECT_GUARD = '''            try {
                // ★ FIX123-SYSRESTART: tizim tiklash — umuman ulanmaymiz
                if (isSystemRestart) {
                    Log.w(TAG, "connect[$my] BEKOR — tizim tiklash rejimi")
                    return@Thread
                }
                synchronized(lifecycleLock) {
                    if (opSeq.get() != my) {
                        Log.i(TAG, "connect[$my] bekor qilindi (yangi amal bor)")'''

# ══════════════════════════════════════════════════════════════════════
#  4) Haqiqiy START — bayroqni tozalash
# ══════════════════════════════════════════════════════════════════════
OLD_START = '''        // ═══ START (ilovadan, tile'dan yoki Always-on VPN'dan) ═══
        isPaused = false
        lastConnectIntent = intent
        startForegroundWithNotif()
        runConnect(intent, opSeq.incrementAndGet())
        return START_NOT_STICKY'''

NEW_START = '''        // ═══ START (ilovadan, tile'dan yoki Always-on VPN'dan) ═══
        // ★ FIX123-SYSRESTART: haqiqiy START keldi — tizim tiklash rejimidan chiqamiz
        if (isSystemRestart) {
            Log.i(TAG, "✅ Haqiqiy START — tizim tiklash rejimi bekor qilindi")
            isSystemRestart = false
        }
        isPaused = false
        lastConnectIntent = intent
        startForegroundWithNotif()
        runConnect(intent, opSeq.incrementAndGet())
        return START_NOT_STICKY'''

# ══════════════════════════════════════════════════════════════════════
#  5) PAUSE / RESUME — eski intent bilan qayta ulanmasin
# ══════════════════════════════════════════════════════════════════════
OLD_RESUME = '''            val saved = lastConnectIntent
            if (saved != null) runConnect(saved, opSeq.incrementAndGet())'''

NEW_RESUME = '''            // ★ FIX123-SYSRESTART: tizim tiklashdan keyin eski intent bilan
            // qayta ulanmaymiz — `lastConnectIntent` null qilingan
            val saved = lastConnectIntent
            if (saved != null && !isSystemRestart) {
                runConnect(saved, opSeq.incrementAndGet())
            }'''


def patch() -> bool:
    src = SVC.read_text(encoding="utf-8")
    if MARK in src:
        print("ℹ️  NurVpnService allaqachon tuzatilgan (FIX123)")
        return True

    safe_backup(SVC, "fix123")
    ok = True

    if OLD_FLAG_ANCHOR in src:
        src = src.replace(OLD_FLAG_ANCHOR, NEW_FLAG, 1)
        print("   ✓ isSystemRestart bayrog'i qo'shildi")
    else:
        print("   ❌ anchor topilmadi")
        ok = False

    if OLD_NULL in src:
        src = src.replace(OLD_NULL, NEW_NULL, 1)
        print("   ✓ intent==null — to'liq lokal tozalash")
    else:
        print("   ❌ intent==null bloki topilmadi")
        ok = False

    if OLD_RUNCONNECT_GUARD in src:
        src = src.replace(OLD_RUNCONNECT_GUARD, NEW_RUNCONNECT_GUARD, 1)
        print("   ✓ runConnect — tizim tiklash himoyasi")
    else:
        print("   ❌ runConnect topilmadi")
        ok = False

    if OLD_START in src:
        src = src.replace(OLD_START, NEW_START, 1)
        print("   ✓ haqiqiy START — bayroq tozalanadi")
    else:
        print("   ❌ START bloki topilmadi")
        ok = False

    if OLD_RESUME in src:
        src = src.replace(OLD_RESUME, NEW_RESUME, 1)
        print("   ✓ RESUME — eski intent bilan ulanmaydi")
    else:
        print("   ⚠️  RESUME bloki topilmadi")

    if ok:
        SVC.write_text(src, encoding="utf-8")
        print("✅ NurVpnService: TIZIM TIKLASH loop'i tuzatildi")
    return ok


def verify() -> bool:
    print()
    print("── Tekshiruv ──")
    src = SVC.read_text(encoding="utf-8")
    code = "\n".join(
        l for l in src.splitlines()
        if not l.strip().startswith("//") and not l.strip().startswith("*")
    )
    ok = True
    for needle, label in [
        ("isSystemRestart = true", "tizim tiklashda bayroq o'rnatiladi"),
        ("lastConnectIntent = null          // eski intent", "eski intent tozalanadi"),
        ("BEKOR — tizim tiklash rejimi", "runConnect to'xtaydi"),
        ("tizim tiklash rejimi bekor qilindi", "haqiqiy START tiklaydi"),
        ("saved != null && !isSystemRestart", "RESUME himoyalangan"),
        ("NurVPN-sysrestart", "alohida thread nomi"),
    ]:
        if needle in code:
            print(f"  ✓ {label}")
        else:
            print(f"  ✗ {label} YO'Q")
            ok = False
    return ok


def main() -> int:
    a = patch()
    v = verify()
    return 0 if (a and v) else 1


if __name__ == "__main__":
    sys.exit(main())
