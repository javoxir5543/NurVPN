package com.nurvpn.app.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.storage.MetricsStore
import com.nurvpn.app.util.PingTester

class AIServerSelector private constructor(private val ctx: Context) {

    interface Listener {
        fun onAnalysisStart()
        fun onServerScored(s: ServerItem, score: Double)
        fun onAnalysisComplete(ranked: List<AIInsights>)
        fun onBestSelected(best: ServerItem, insights: AIInsights)
    }

    companion object {
        @Volatile private var inst: AIServerSelector? = null
        @JvmStatic fun get(ctx: Context): AIServerSelector =
            inst ?: synchronized(this) {
                inst ?: AIServerSelector(ctx.applicationContext)
                    .also { inst = it }
            }
    }

    fun selectBest(servers: List<ServerItem>, listener: Listener) {
        listener.onAnalysisStart()
        val now = System.currentTimeMillis()
        // ═══ 20 thread pool — parallel ping (13 daq → ~30 sek) ═══
        Thread {
            val candidates = servers.filter { !it.host.isNullOrEmpty() && it.port > 0 }
            if (candidates.isEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    listener.onAnalysisComplete(emptyList())
                }
                return@Thread
            }
            val results = java.util.Collections.synchronizedList(
                ArrayList<Pair<ServerItem, Double>>(candidates.size))
            val pool = java.util.concurrent.Executors.newFixedThreadPool(20)
            val latch = java.util.concurrent.CountDownLatch(candidates.size)
            var completed = java.util.concurrent.atomic.AtomicInteger(0)
            val total = candidates.size

            for (si in candidates) {
                pool.execute {
                    try {
                        val h = si.host!!
                        val p = PingTester.pingAny(h, si.port, si.protocol)
                        val m = MetricsStore.get(ctx, si.link)
                        if (p > 0) m.recordPing(p, true, now)
                        else m.recordPing(0, false, now)
                        MetricsStore.save(ctx, m)
                        val score = SmartScoreEngine.computeScore(m, now)
                        si.ping = p
                        results.add(si to score)
                        val done = completed.incrementAndGet()
                        Handler(Looper.getMainLooper()).post {
                            listener.onServerScored(si, score)
                            if (done % 5 == 0 || done == total) {
                                Log.i("NurVPN-AI", "AI progress: $done/$total")
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w("NurVPN-AI", "AI ping xato: ${t.message}")
                    } finally {
                        latch.countDown()
                    }
                }
            }
            // Kutamiz (max 60s)
            try {
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {}
            pool.shutdown()
            try { pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: Throwable) {}

            val sorted = results.sortedByDescending { it.second }
            val ranked = sorted.map { pair ->
                val si = pair.first
                val score = pair.second
                val pingText = when {
                    si.ping > 0 -> "${si.ping} ms"
                    si.protocol.isUdp -> "N/A (UDP)"
                    else -> "—"
                }
                AIInsights(si.link, score, si.displayName(),
                    "$pingText • AI ${"%.0f".format(score)}")
            }
            Log.i("NurVPN-AI", "AI tugadi: ${sorted.size} ta, eng yaxshi: " +
                "${sorted.firstOrNull()?.first?.displayName()}")
            Handler(Looper.getMainLooper()).post {
                listener.onAnalysisComplete(ranked)
                if (sorted.isNotEmpty()) {
                    listener.onBestSelected(sorted[0].first, ranked[0])
                }
            }
        }.start()
    }

    fun onConnected(s: ServerItem) {
        val m = MetricsStore.get(ctx, s.link)
        m.recordPing(if (s.ping > 0) s.ping else 50, true,
            System.currentTimeMillis())
        MetricsStore.save(ctx, m)
    }

    fun onDisconnected(s: ServerItem, success: Boolean) {
        if (!success) {
            val m = MetricsStore.get(ctx, s.link)
            m.recordPing(0, false, System.currentTimeMillis())
            MetricsStore.save(ctx, m)
        }
    }
}

// ═══════════ AWG (AmneziaWG) ═══════════

