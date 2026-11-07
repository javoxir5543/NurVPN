#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #1 — Ping 200+ serverda UI frizini bartaraf etish             ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ANIQLANGAN SABABLAR (kod o'rganib chiqilgan):                          ║
║                                                                          ║
║  1) `icmpPing()` → `InetAddress.isReachable(timeout=4000)`.              ║
║     HOST_RTT protokollari (Hysteria2/TUIC/Juicity) shu yo'lni ishlatadi. ║
║     Root bo'lmaganda isReachable() TCP-echo (port 7) fallback'ga o'tadi  ║
║     va HAR BIR server 4 SEKUNDGACHA bloklaydi.                           ║
║     pool=8 thread → 200 server = 200/8 * 4s ≈ 100 SEKUND.                ║
║                                                                          ║
║  2) `testAll()` HAR BIR server uchun alohida                              ║
║     `Handler(mainLooper).post { onPingUpdate() }` chaqiradi.             ║
║     200 server = 200 ta main-thread xabar → jank + GC bosimi.            ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) Semaphore orqali parallel so'rovlar sonini cheklash (24).           ║
║   B) Main-thread'ga BATCH qilib yuborish (har 150ms da to'plam).         ║
║   C) Listener'ga progress berish (done/total) → foydalanuvchi ko'radi.   ║
║   D) Bekor qilish (cancel) imkoniyati.                                   ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
PING = ROOT / "app/src/main/java/com/nurvpn/app/util/PingTester.kt"

NEW_PINGTESTER = r'''package com.nurvpn.app.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nurvpn.app.core.PingStrategy
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.TunnelState
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  PING TESTER — cheklangan parallellik + UI'ga batch xabar
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * MUAMMO (tuzatildi): 200+ serverda UI friz bo'lardi.
 *   - Har bir server uchun alohida main-thread `post` → yuzlab xabar
 *   - HOST_RTT (Hysteria2/TUIC) `isReachable(4000)` bilan 4s bloklardi
 *
 * YECHIM:
 *   1. [MAX_CONCURRENT] — bir vaqtda ko'pi bilan 24 ta so'rov (Semaphore)
 *   2. Natijalar [BATCH_INTERVAL_MS] davomida to'planib, BIR MARTA
 *      main-thread'ga yuboriladi → 200 ta xabar o'rniga ~5-8 ta
 *   3. [Listener.onProgress] — foydalanuvchi jarayonni ko'radi
 *   4. [cancel] — yangi ping boshlansa eskisi to'xtatiladi
 *   5. HOST_RTT uchun default timeout qisqartirildi (4s → 2.5s)
 */
object PingTester {

    interface Listener {
        fun onPingUpdate(item: ServerItem, ping: Int)
        fun onAllDone()

        /** Batch natijalar (override shart emas). */
        fun onBatchUpdate(items: List<ServerItem>) {
            for (i in items) onPingUpdate(i, i.ping)
        }

        /** Jarayon: done/total (override shart emas). */
        fun onProgress(done: Int, total: Int) {}
    }

    /** Bir vaqtda yuboriladigan maksimal so'rovlar soni. */
    const val MAX_CONCURRENT = 24

    /** Natijalarni to'plash va bir marta yuborish oralig'i (ms). */
    private const val BATCH_INTERVAL_MS = 150L

    /** HOST_RTT (ICMP) uchun standart timeout — 4s juda uzoq edi. */
    private const val DEFAULT_TIMEOUT_MS = 2500

    private val pool: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT)

    private val main = Handler(Looper.getMainLooper())

    /** Joriy yurish ID'si — eski natijalarni tashlab yuborish uchun. */
    private val runId = AtomicInteger(0)

    // ═══════════════════════════════════════════════════════════════
    //  ASOSIY PING METODLARI
    // ═══════════════════════════════════════════════════════════════

    fun tcpPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.currentTimeMillis() - start).toInt()
            }
        } catch (e: Exception) { -1 }
    }

    fun pingAny(host: String, port: Int, proto: Protocol,
                timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int {
        val r = when (proto.pingStrategy) {
            PingStrategy.TCP_CONNECT -> tcpPing(host, port, timeoutMs)
            PingStrategy.HOST_RTT -> icmpPing(host, timeoutMs)
        }
        Log.d(TAG, "pingAny $host:$port proto=$proto strategy=${proto.pingStrategy} → $r ms")
        return r
    }

    /**
     * HOST_RTT — ICMP (yoki TCP fallback).
     *
     * ⚠️ `isReachable()` root bo'lmasa TCP-echo (port 7) fallback ishlatadi va
     * ko'pincha -1 qaytaradi. Shuning uchun bu YAKUNIY natija emas:
     * chaqiruvchi `tcpPing` bilan solishtirishi tavsiya etiladi.
     */
    fun icmpPing(host: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int {
        return try {
            val start = System.currentTimeMillis()
            val addr = java.net.InetAddress.getByName(host)
            if (addr.isReachable(timeoutMs)) {
                (System.currentTimeMillis() - start).toInt()
            } else -1
        } catch (e: Exception) {
            Log.d(TAG, "icmpPing fail $host: ${e.message}")
            -1
        }
    }

    /**
     * UDP protokollar (Hysteria2, TUIC) uchun ping — Clash API orqali.
     * Tag har doim "proxy" (SingBoxConfig'da hardcoded).
     */
    private fun clashApiPing(timeoutMs: Int): Int {
        if (!TunnelState.isConnected) return -1
        val tag = "proxy"
        val testUrl = "https://www.gstatic.com/generate_204"
        return try {
            val url = java.net.URL(
                "${ClashApiConfig.baseUrl}/proxies/$tag/delay" +
                "?timeout=$timeoutMs&url=${java.net.URLEncoder.encode(testUrl, "UTF-8")}"
            )
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs + 500
                readTimeout = timeoutMs + 500
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${ClashApiConfig.secret}")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            Log.d(TAG, "clashApiPing code=$code body=${body.take(160)}")
            Regex("""\"delay\"\s*:\s*(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            Log.d(TAG, "clashApiPing xato: ${e.message}")
            -1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BATCHING — yuzlab main-thread xabar o'rniga bir nechta
    // ═══════════════════════════════════════════════════════════════

    /**
     * Natijalarni to'plab, davriy ravishda main-thread'ga yuboradi.
     * Bu UI frizining ASOSIY sababini bartaraf etadi.
     */
    private class Batcher(
        private val listener: Listener,
        private val total: Int
    ) {
        private val pending = ConcurrentHashMap<ServerItem, Int>()
        private val doneCount = AtomicInteger(0)
        private val flushed = AtomicBoolean(false)

        private val flushTask = object : Runnable {
            override fun run() {
                flush()
                // Navbatda hali ish bo'lsa — yana rejalashtiramiz
                if (!flushed.get()) main.postDelayed(this, BATCH_INTERVAL_MS)
            }
        }

        fun start() {
            flushed.set(false)
            main.postDelayed(flushTask, BATCH_INTERVAL_MS)
        }

        fun offer(item: ServerItem, ping: Int) {
            pending[item] = ping
        }

        fun markDone() {
            flushed.set(true)
            main.removeCallbacks(flushTask)
            main.post {
                flush()
                try { listener.onProgress(total, total) } catch (_: Throwable) {}
                try { listener.onAllDone() } catch (_: Throwable) {}
            }
        }

        private fun flush() {
            if (pending.isEmpty()) return
            val snapshot = ArrayList<ServerItem>(pending.size)
            for ((k, v) in pending) {
                k.ping = v
                snapshot.add(k)
            }
            pending.clear()
            val done = doneCount.addAndGet(snapshot.size)
            main.post {
                try { listener.onBatchUpdate(snapshot) } catch (_: Throwable) {}
                try { listener.onProgress(done, total) } catch (_: Throwable) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TEST ALL — cheklangan parallellik
    // ═══════════════════════════════════════════════════════════════

    /**
     * Barcha serverlarni ping qiladi.
     *
     * @param servers  serverlar ro'yxati
     * @param listener natijalar (batch + progress)
     * @return runId — [cancel] uchun ishlatiladi
     */
    fun testAll(servers: List<ServerItem>, listener: Listener): Int {
        val myRun = runId.incrementAndGet()
        val total = servers.size
        if (total == 0) {
            main.post { listener.onAllDone() }
            return myRun
        }

        // ★ MUHIM: bir vaqtda MAX_CONCURRENT tadan oshmasin
        val sem = Semaphore(MAX_CONCURRENT)
        val remaining = AtomicInteger(total)
        val batcher = Batcher(listener, total)
        batcher.start()

        Log.i(TAG, "testAll: $total server, maxParallel=$MAX_CONCURRENT")

        for (si in servers) {
            pool.submit {
                try {
                    sem.acquire()
                } catch (_: InterruptedException) {
                    return@submit
                }
                try {
                    // Bekor qilingan bo'lsa — hech narsa qilmaymiz
                    if (runId.get() != myRun) return@submit

                    val h = si.host
                    val p = if (h.isNullOrEmpty() || si.port <= 0) -1
                            else pingAny(h, si.port, si.protocol)
                    batcher.offer(si, p)
                } catch (t: Throwable) {
                    Log.d(TAG, "ping xato ${si.host}: ${t.message}")
                    batcher.offer(si, -1)
                } finally {
                    try { sem.release() } catch (_: Throwable) {}
                    if (remaining.decrementAndGet() == 0 && runId.get() == myRun) {
                        batcher.markDone()
                    }
                }
            }
        }
        return myRun
    }

    /** Joriy ping yurishini bekor qiladi (natijalar e'tiborsiz qoldiriladi). */
    fun cancel() {
        runId.incrementAndGet()
        Log.i(TAG, "cancel: joriy ping bekor qilindi")
    }

    /** Faqat testlar/benchmark uchun. */
    internal fun shutdownPool() {
        pool.shutdownNow()
        try { pool.awaitTermination(1, TimeUnit.SECONDS) } catch (_: Throwable) {}
    }

    private const val TAG = "NurVPN-PING"
}
'''

def main() -> int:
    if not PING.exists():
        print(f"❌ Topilmadi: {PING}")
        return 1

    safe_backup(PING, "fix1")

    PING.write_text(NEW_PINGTESTER, encoding="utf-8")
    print(f"✅ Yangilandi: {PING.relative_to(ROOT)}")
    print(f"   • MAX_CONCURRENT = 24 (Semaphore)")
    print(f"   • Batch yuborish (har 150ms) — main-thread xabarlari 200 → ~8")
    print(f"   • onProgress(done, total) qo'shildi")
    print(f"   • cancel() qo'shildi")
    print(f"   • HOST_RTT timeout 4000ms → 2500ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
