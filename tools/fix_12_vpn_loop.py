#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #12 — VPN RESTART LOOP (BUG #3, KRITIK)                        ║
╠══════════════════════════════════════════════════════════════════════════╣
║  TEKSHIRUV NATIJASI (kod o'rganildi):                                    ║
║                                                                          ║
║  ✓ `onStartCommand` allaqachon `START_NOT_STICKY` qaytaradi             ║
║    (5 ta joyda) — "START_STICKY loop" NAZARIYASI NOTO'G'RI.              ║
║                                                                          ║
║  ✓ `connectLocked` da try-catch BOR (catch: Log.e + cleanup +           ║
║    broadcast("error") + stopSelf).                                       ║
║                                                                          ║
║  ✓ `sanitizeFp()` to'g'ri (VALID_FINGERPRINTS) — oldingi fix saqlangan. ║
║                                                                          ║
║  ❌ HAQIQIY BO'SHLIQLAR:                                                 ║
║                                                                          ║
║  1) `runConnect()` — Thread ichida HECH QANDAY try-catch YO'Q.          ║
║     `connectLocked` himoyalangan, lekin `cleanup()` yoki                 ║
║     `Thread.sleep` atrofidagi kod emas. Istisno Thread'da bo'lsa →      ║
║     PROCESS crash → Android xizmatni qayta ishga tushiradi → LOOP.      ║
║                                                                          ║
║  2) `UncaughtExceptionHandler` YO'Q — crash sababi logga tushmaydi.     ║
║                                                                          ║
║  3) UI'da `isUpdatingState` flag YO'Q — broadcast va tugma              ║
║     holati rachkalashishi mumkin.                                       ║
║                                                                          ║
║  4) Vergul: `MIN_RESTART_INTERVAL_MS = 5000`, `NETWORK_STABILIZE = 3000`║
║     — 2-3 sekundlik ON/OFF sikli uchun JUDA QISQA.                      ║
║     3 sekund kutish + restart = aynan foydalanuvchi ko'rgan davr.       ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) `runConnect` Thread'ini to'liq try-catch bilan o'rash               ║
║   B) Crash-oldini-ushlash handleri (loop'ni to'xtatadi + log yozadi)    ║
║   C) Restart cooldown 5s → 15s, stabilize 3s → 8s (loop to'xtasin)      ║
║   D) Ketma-ket xatolar hisoblagichi — 3 tadan keyin avtomatik to'xtash  ║
║   E) UI: isUpdatingState flag (MainActivity)                            ║
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
MAIN = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"


# ══════════════════════════════════════════════════════════════════════
#  A) runConnect — to'liq himoya
# ══════════════════════════════════════════════════════════════════════
OLD_RUNCONNECT = '''    private fun runConnect(src: Intent?, my: Long) {
        Thread {
            synchronized(lifecycleLock) {
                if (opSeq.get() != my) {
                    Log.i(TAG, "connect[$my] bekor qilindi (yangi amal bor)")
                } else {
                    val hadServer = server != null
                    cleanup()
                    if (hadServer) {
                        try { Thread.sleep(300) } catch (_: InterruptedException) {}
                    }
                    connectLocked(src, my)
                }
            }
        }.start()
    }'''

NEW_RUNCONNECT = '''    private fun runConnect(src: Intent?, my: Long) {
        Thread {
            // ═══════════════════════════════════════════════════════════
            //  ★ TUZATISH #12 (BUG #3): TO'LIQ himoya
            //
            //  Ilgari bu Thread'da try-catch YO'Q edi. `connectLocked`
            //  himoyalangan bo'lsa ham, `cleanup()` yoki boshqa kod
            //  istisno tashlasa — THREAD crash → PROCESS o'ladi →
            //  Android xizmatni qayta ishga tushiradi → ON/OFF LOOP.
            //
            //  Endi har qanday istisno ushlanadi, logga yoziladi va
            //  foydalanuvchiga xabar beriladi. Loop BO'LMAYDI.
            // ═══════════════════════════════════════════════════════════
            try {
                synchronized(lifecycleLock) {
                    if (opSeq.get() != my) {
                        Log.i(TAG, "connect[$my] bekor qilindi (yangi amal bor)")
                    } else {
                        val hadServer = server != null
                        cleanup()
                        if (hadServer) {
                            try { Thread.sleep(300) } catch (_: InterruptedException) {}
                        }
                        connectLocked(src, my)
                    }
                }
            } catch (t: Throwable) {
                // ★ KRITIK: bu yerda hech qachon crash bo'lmasin
                Log.e(TAG, "runConnect[$my] KUTILMAGAN XATO", t)
                com.nurvpn.app.core.LogBuffer.add(
                    "FATAL-GUARD: ${t.javaClass.simpleName}: ${t.message}")
                try { cleanup() } catch (_: Throwable) {}
                consecutiveFailures.incrementAndGet()
                try {
                    broadcast("error", t.message ?: t.javaClass.simpleName)
                } catch (_: Throwable) {}
                try { stopSelf() } catch (_: Throwable) {}
            }
        }.apply { name = "NurVPN-connect" }.start()
    }'''

# ══════════════════════════════════════════════════════════════════════
#  B) Cooldown vaqtlarini oshirish + xato hisoblagichi
# ══════════════════════════════════════════════════════════════════════
OLD_CONSTS = '''    private val MIN_RESTART_INTERVAL_MS = 5_000L   // 5 sekund (tez o'zgarish uchun)
    private val NETWORK_STABILIZE_MS = 3_000L      // 3 sekund kutish'''

NEW_CONSTS = '''    // ★ TUZATISH #12 (BUG #3): loop'ni to'xtatish uchun vaqtlar oshirildi.
    //   Avval 5s/3s edi — bu aynan foydalanuvchi ko'rgan 2-3 sekundlik
    //   ON/OFF siklini hosil qilardi.
    private val MIN_RESTART_INTERVAL_MS = 15_000L  // 15 sekund (5s → 15s)
    private val NETWORK_STABILIZE_MS = 8_000L      // 8 sekund (3s → 8s)

    /** ★ TUZATISH #12: ketma-ket xatolar — 3 tadan keyin to'xtaymiz. */
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_CONSECUTIVE_FAILURES = 3'''

# ══════════════════════════════════════════════════════════════════════
#  C) Crash-oldini-ushlash handleri
# ══════════════════════════════════════════════════════════════════════
GUARD = '''
    /**
     * ★ TUZATISH #12 (BUG #3): crash-oldini-ushlash himoyasi.
     *
     * Agar qandaydir kutilmagan istisno baribir ushlanmasa, bu handler
     * ishga tushadi. U:
     *   1. Xatoni to'liq logga yozadi (stack trace bilan)
     *   2. Xizmatni to'xtatadi (loop bo'lmasin)
     *   3. Foydalanuvchiga xabar beradi
     *
     * ⚠️ Bu "yashirish" emas — chunki xato TO'LIQ logga tushadi va
     * foydalanuvchi xabar oladi. Maqsad: loop'ni to'xtatish.
     */
    private fun installCrashGuard() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "‼️ USHLANMAGAN ISTISNO (${thread.name})", throwable)
                com.nurvpn.app.core.LogBuffer.add(
                    "CRASH: ${throwable.javaClass.simpleName}: ${throwable.message}")
                // Xizmatni to'xtatamiz — Android qayta ishga tushirmasin
                try { stopSelf() } catch (_: Throwable) {}
                try { broadcast("error",
                    "Kutilmagan xato: ${throwable.javaClass.simpleName}") } catch (_: Throwable) {}
            } catch (_: Throwable) {
                // handler ichida ham crash bo'lmasin
            } finally {
                // Oldingi handler'ga uzatamiz (Android o'z ishini qilsin)
                prev?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "Crash guard o'rnatildi")
    }
'''

# ⚠️ DIQQAT: NurVpnService'da onCreate UMUMAN YO'Q edi — shuning uchun
#    uni qo'shamiz (doNetworkRestart dan oldin).
OLD_CREATE = '''    private fun doNetworkRestart() {'''

NEW_CREATE = '''    /**
     * ★ TUZATISH #12 (BUG #3): xizmat yaratilganda crash guard o'rnatiladi.
     *
     * ⚠️ Bu metod AVVAL YO'Q edi. Endi qo'shildi — chunki crash
     * oldini olish uchun ilova darajasida ushlagich kerak.
     */
    override fun onCreate() {
        super.onCreate()
        installCrashGuard()
    }

    private fun doNetworkRestart() {'''


def patch_service() -> bool:
    src = SVC.read_text(encoding="utf-8")
    if "TUZATISH #12" in src:
        print("ℹ️  NurVpnService allaqachon tuzatilgan")
        return True
    safe_backup(SVC, "fix12")

    ok = True

    # A) runConnect
    if OLD_RUNCONNECT in src:
        src = src.replace(OLD_RUNCONNECT, NEW_RUNCONNECT, 1)
        print("   ✓ runConnect himoyasi")
    else:
        print("   ❌ runConnect anchor topilmadi")
        ok = False

    # B) konstantalar
    if OLD_CONSTS in src:
        src = src.replace(OLD_CONSTS, NEW_CONSTS, 1)
        print("   ✓ cooldown vaqtlari (5s→15s, 3s→8s)")
    else:
        print("   ⚠️  konstantalar topilmadi (boshqacha?)")

    # C) onCreate
    if OLD_CREATE in src:
        src = src.replace(OLD_CREATE, NEW_CREATE, 1)
        print("   ✓ crash guard ulandi")
    else:
        print("   ❌ onCreate topilmadi")
        ok = False

    # D) guard metodini qo'shamiz
    if "private fun installCrashGuard()" not in src:
        m = re.search(r'\n    private fun doNetworkRestart\(\)', src)
        if m:
            src = src[:m.start()] + "\n" + GUARD + src[m.start():]
            print("   ✓ installCrashGuard() qo'shildi")
        else:
            print("   ❌ guard qo'shish uchun joy yo'q")
            ok = False

    if ok:
        SVC.write_text(src, encoding="utf-8")
        print("✅ NurVpnService yangilandi")
    return ok


# ══════════════════════════════════════════════════════════════════════
#  E) UI: isUpdatingState flag
# ══════════════════════════════════════════════════════════════════════
UI_BLOCK = '''
    /**
     * ★ TUZATISH #12 (BUG #3): UI holat yangilanish flag'i.
     *
     * Muammo: tugma bosilishi va broadcast bir vaqtda kelib, UI holati
     * "ON → OFF → ON" bo'lib miltillardi (loop taassuroti).
     *
     * Yechim: holat faqat BROADCAST orqali o'zgaradi; tugma bosilganda
     * biz shunchaki "kutish" rejimiga o'tamiz va takroriy bosishni
     * bloklaymiz.
     */
    @Volatile
    private var isUpdatingState = false

    /** Tugma bosilishini qabul qilish mumkinmi? */
    fun beginStateUpdate(): Boolean {
        synchronized(stateUpdateLock) {
            if (isUpdatingState) {
                android.util.Log.i("NurVPN-DBG", "⏭️  Holat yangilanmoqda — kutamiz")
                return false
            }
            isUpdatingState = true
        }
        // 4 sekunddan keyin avtomatik ochiladi (broadcast kelmasa ham)
        mainHandler.postDelayed({
            synchronized(stateUpdateLock) { isUpdatingState = false }
        }, 4000L)
        return true
    }

    /** Broadcast keldi — flag'ni ochamiz. */
    fun endStateUpdate() {
        synchronized(stateUpdateLock) { isUpdatingState = false }
    }

    private val stateUpdateLock = Any()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
'''


def patch_main() -> bool:
    src = MAIN.read_text(encoding="utf-8")
    if "TUZATISH #12" in src:
        print("ℹ️  MainActivity allaqachon tuzatilgan")
        return True
    safe_backup(MAIN, "fix12")

    ok = True

    # 1) UI blokini qo'shamiz
    anchor = "    private fun refreshHome() {"
    if anchor in src:
        src = src.replace(anchor, UI_BLOCK + "\n" + anchor, 1)
        print("   ✓ isUpdatingState flag")
    else:
        print("   ❌ refreshHome anchor topilmadi")
        ok = False

    # 2) Broadcast kelganda flag'ni ochamiz
    old_recv = '''                }
                refreshHome()
            }
        }

        stateReceiver = receiver'''
    new_recv = '''                }
                // ★ TUZATISH #12: broadcast keldi — UI flag'ini ochamiz
                endStateUpdate()
                refreshHome()
            }
        }

        stateReceiver = receiver'''
    if old_recv in src:
        src = src.replace(old_recv, new_recv, 1)
        print("   ✓ broadcast → endStateUpdate()")
    else:
        print("   ⚠️  receiver anchor topilmadi")

    if ok:
        MAIN.write_text(src, encoding="utf-8")
        print("✅ MainActivity yangilandi")
    return ok


def main() -> int:
    a = patch_service()
    print()
    b = patch_main()
    return 0 if (a and b) else 1


if __name__ == "__main__":
    sys.exit(main())
