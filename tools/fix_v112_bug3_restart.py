#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  FIX v1.12.0 — BUG #3: VPN restart loop (cooldown yo'q)                   ║
╠══════════════════════════════════════════════════════════════════════════╣
║  TASDIQLANGAN FAKTLAR:                                                   ║
║                                                                          ║
║   ✓ Logcat: `sing-box started (0.38s)`, `(0.33s)`, `(0.39s)`            ║
║     → 2-3 sekundlik sikl                                                 ║
║                                                                          ║
║   ✓ MainActivity.kt:391 `restartVpn(reason)`:                            ║
║        Handler(mainLooper).postDelayed({ ... }, 2500)                    ║
║     → AYNAN 2.5 sekund. Logcat bilan MOS.                                ║
║                                                                          ║
║   ✓ `restartGeneration` (65-qator) BOR, lekin COOLDOWN YO'Q.             ║
║     Har chaqiruvda `++restartGeneration` → yangi generation boshlanadi.  ║
║     Eski generation bekor bo'ladi, LEKIN yangisi DARHOL boshlanadi.      ║
║                                                                          ║
║   ✓ `restartVpn()` faqat 2 joydan chaqiriladi:                           ║
║        :363 selectServer()  (isRunning && changed)                       ║
║        :379 selectAWG()     (isRunning && changed)                       ║
║     Ikkalasi ham "VPN yoniq + server o'zgardi".                          ║
║                                                                          ║
║   ✓ `onStartCommand` — 5 joyda `START_NOT_STICKY` (BU TO'G'RI,           ║
║     o'zgartirish kerak emas)                                             ║
║                                                                          ║
║  ⚠️ QO'SHIMCHA TOPILGAN XATO:                                            ║
║     `si.putExtra("generation", myGen)` yuboriladi, LEKIN xizmat          ║
║     tomonida "generation" extra O'QILMAYDI (grep tasdiqladi).            ║
║     Ya'ni generation faqat UI tomonida ishlaydi — xizmat eski           ║
║     STOP buyrug'ini ham bajarib, yangi START bilan to'qnashishi mumkin. ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   1. `RESTART_COOLDOWN_MS = 5000` + `lastRestartAt` — takroriy           ║
║      chaqiruvlarni bloklaydi                                             ║
║   2. Cooldown oynasida kelgan so'rovni "pending" sifatida eslab,         ║
║      cooldown tugagach BIR MARTA bajaradi (so'rov yo'qolmasin)          ║
║   3. Cooldown logga yoziladi (diagnostika uchun)                         ║
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

MARK = "FIX112-BUG3"

# ── Eski restartVpn (to'liq) ─────────────────────────────────────────
OLD = '''    /** VPN ishlab turganda server o'zgarsa — qayta ulanish. */
    fun restartVpn(reason: String) {
        // ═══ GENERATION: faqat eng oxirgi restart ishlaydi ═══
        val myGen = ++restartGeneration
        android.util.Log.i("NurVPN-DBG", "restartVpn[$myGen]: $reason")
        Toast.makeText(this, getString(R.string.toast_reconnecting, reason), Toast.LENGTH_SHORT).show()

        val si = Intent(this, NurVpnService::class.java)
        si.putExtra(EXTRA_STOP, true)
        si.putExtra(EXTRA_FORCE, true)
        si.putExtra("generation", myGen)
        startService(si)
        connectStart = 0
        isRunning = false

        // 2.5 sekund — cache file lock bo'shash uchun
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // ═══ Faqat oxirgi restart davom etadi ═══
            if (myGen != restartGeneration) {
                android.util.Log.i("NurVPN-DBG",
                    "restartVpn[$myGen]: bekor (yangi gen $restartGeneration)")
                return@postDelayed
            }
            connectStart = System.currentTimeMillis()
            val si2 = Intent(this, NurVpnService::class.java)
            si2.putExtra(EXTRA_FORCE, true)
            si2.putExtra("generation", myGen)
            if (PROTO_AWG == protocol) {
                currentAWG?.let {
                    si2.putExtra(EXTRA_AWG, it.rawConf)
                    si2.putExtra(EXTRA_NAME, it.name ?: "AWG")
                }
            } else {
                currentServer?.let {
                    si2.putExtra(EXTRA_LINK, it.link)
                    si2.putExtra(EXTRA_NAME, it.displayName())
                }
            }
            si2.putExtra("protocol", protocol)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si2)
            else startService(si2)
        }, 2500)
    }'''

NEW = '''    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX112-BUG3: RESTART COOLDOWN
    //
    //  Muammo (logcat bilan tasdiqlangan):
    //     sing-box started (0.38s), (0.33s), (0.39s)  → 2-3s sikl
    //
    //  Sabab: `restartVpn()` da `generation` bor edi, lekin COOLDOWN
    //  YO'Q. Har chaqiruvda yangi generation boshlanib, 2500ms dan
    //  keyin yangi ulanish ishga tushardi. Bir necha so'rov ketma-ket
    //  kelsa — ON/OFF/ON sikl hosil bo'lardi.
    //
    //  Yechim: 5 sekundlik cooldown. Cooldown ichida kelgan so'rov
    //  YO'QOLMAYDI — "pending" sifatida eslab qolinadi va cooldown
    //  tugagach BIR MARTA bajariladi.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var lastRestartAt = 0L
    private val RESTART_COOLDOWN_MS = 5000L

    /** Cooldown ichida kelgan so'rov (yo'qolmasin). */
    @Volatile private var pendingRestartReason: String? = null
    private val restartHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingRestartRunnable = Runnable {
        val r = pendingRestartReason
        pendingRestartReason = null
        if (r != null) {
            android.util.Log.i("NurVPN-DBG",
                "restartVpn: cooldown tugadi — kechiktirilgan so'rov bajariladi ($r)")
            restartVpn(r)
        }
    }

    /** VPN ishlab turganda server o'zgarsa — qayta ulanish. */
    fun restartVpn(reason: String) {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastRestartAt

        // ═══ COOLDOWN TEKSHIRUVI ═══
        if (lastRestartAt != 0L && sinceLast < RESTART_COOLDOWN_MS) {
            android.util.Log.w("NurVPN-DBG",
                "restartVpn: COOLDOWN (${sinceLast}ms < ${RESTART_COOLDOWN_MS}ms) " +
                "— bekor qilindi. Sabab: $reason")
            // So'rovni eslab qolamiz — cooldown tugagach bajariladi
            pendingRestartReason = reason
            restartHandler.removeCallbacks(pendingRestartRunnable)
            restartHandler.postDelayed(
                pendingRestartRunnable, RESTART_COOLDOWN_MS - sinceLast)
            return
        }
        lastRestartAt = now

        // ═══ GENERATION: faqat eng oxirgi restart ishlaydi ═══
        val myGen = ++restartGeneration
        android.util.Log.i("NurVPN-DBG", "restartVpn[$myGen]: $reason")
        Toast.makeText(this, getString(R.string.toast_reconnecting, reason), Toast.LENGTH_SHORT).show()

        val si = Intent(this, NurVpnService::class.java)
        si.putExtra(EXTRA_STOP, true)
        si.putExtra(EXTRA_FORCE, true)
        si.putExtra("generation", myGen)
        startService(si)
        connectStart = 0
        isRunning = false

        // 2.5 sekund — cache file lock bo'shash uchun
        restartHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // ═══ Faqat oxirgi restart davom etadi ═══
            if (myGen != restartGeneration) {
                android.util.Log.i("NurVPN-DBG",
                    "restartVpn[$myGen]: bekor (yangi gen $restartGeneration)")
                return@postDelayed
            }
            connectStart = System.currentTimeMillis()
            val si2 = Intent(this, NurVpnService::class.java)
            si2.putExtra(EXTRA_FORCE, true)
            si2.putExtra("generation", myGen)
            if (PROTO_AWG == protocol) {
                currentAWG?.let {
                    si2.putExtra(EXTRA_AWG, it.rawConf)
                    si2.putExtra(EXTRA_NAME, it.name ?: "AWG")
                }
            } else {
                currentServer?.let {
                    si2.putExtra(EXTRA_LINK, it.link)
                    si2.putExtra(EXTRA_NAME, it.displayName())
                }
            }
            si2.putExtra("protocol", protocol)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si2)
            else startService(si2)
        }, 2500)
    }'''


def patch() -> bool:
    src = MAIN.read_text(encoding="utf-8")

    if MARK in src:
        print("ℹ️  MainActivity allaqachon tuzatilgan")
        return True

    if OLD not in src:
        print("❌ restartVpn: kutilgan shakl topilmadi")
        # Diagnostika
        m = re.search(r'fun restartVpn\(reason: String\) \{', src)
        if m:
            print(f"   restartVpn {src[:m.start()].count(chr(10)) + 1}-qatorda "
                  f"topildi, lekin tanasi boshqacha")
        return False

    safe_backup(MAIN, "fix_v112_bug3")
    src = src.replace(OLD, NEW, 1)
    MAIN.write_text(src, encoding="utf-8")
    print("✅ MainActivity.restartVpn: cooldown qo'shildi (5s)")
    return True


def verify() -> bool:
    src = MAIN.read_text(encoding="utf-8")
    ok = True
    print()
    print("── Tekshiruv ──")

    checks = [
        ("RESTART_COOLDOWN_MS = 5000L", "cooldown konstantasi"),
        ("lastRestartAt", "oxirgi restart vaqti"),
        ("COOLDOWN (${sinceLast}ms", "cooldown logi"),
        ("pendingRestartReason", "kechiktirilgan so'rov"),
        ("restartGeneration", "generation (saqlangan)"),
    ]
    for needle, label in checks:
        if needle in src:
            print(f"  ✓ {label}")
        else:
            print(f"  ✗ {label} YO'Q")
            ok = False

    # Cooldown 2500ms dan katta bo'lishi SHART
    m = re.search(r'RESTART_COOLDOWN_MS\s*=\s*([\d_]+)L', src)
    if m:
        val = int(m.group(1).replace("_", ""))
        if val > 2500:
            print(f"  ✓ cooldown ({val}ms) > 2500ms restart kechikishi")
        else:
            print(f"  ✗ cooldown ({val}ms) 2500ms dan KICHIK — loop davom etadi!")
            ok = False

    # START_NOT_STICKY tegilmaganini tasdiqlaymiz
    svc = (ROOT / "app/src/main/java/com/nurvpn/app/service/NurVpnService.kt") \
        .read_text(encoding="utf-8")
    n = svc.count("START_NOT_STICKY")
    if n >= 5 and "START_STICKY" not in svc.replace("START_NOT_STICKY", ""):
        print(f"  ✓ NurVpnService: {n} ta START_NOT_STICKY (o'zgarmagan)")
    else:
        print(f"  ⚠️  NurVpnService: START_NOT_STICKY soni {n}")

    return ok


def main() -> int:
    a = patch()
    v = verify()
    return 0 if (a and v) else 1


if __name__ == "__main__":
    sys.exit(main())
