package com.nurvpn.app.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nurvpn.app.core.PingStrategy
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.TunnelState
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

object PingTester {
    interface Listener {
        fun onPingUpdate(item: ServerItem, ping: Int)
        fun onAllDone()
    }

    private val pool = Executors.newFixedThreadPool(8)

    // BATCH: har bir ping natijasi to'planadi, 200ms da bir marta UI'ga post
    private const val BATCH_INTERVAL_MS = 200L

    fun tcpPing(host: String, port: Int, timeoutMs: Int = 4000): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.currentTimeMillis() - start).toInt()
            }
        } catch (e: Exception) { -1 }
    }

    fun pingAny(host: String, port: Int, proto: Protocol, timeoutMs: Int = 4000): Int {
        val r = when (proto.pingStrategy) {
            PingStrategy.TCP_CONNECT -> tcpPing(host, port, timeoutMs)
            PingStrategy.HOST_RTT -> icmpPing(host, timeoutMs)
        }
        Log.d("NurVPN-PING", "pingAny $host:$port proto=$proto strategy=${proto.pingStrategy} -> $r ms")
        return r
    }

    fun icmpPing(host: String, timeoutMs: Int = 4000): Int {
        return try {
            val start = System.currentTimeMillis()
            val addr = java.net.InetAddress.getByName(host)
            if (addr.isReachable(timeoutMs)) {
                (System.currentTimeMillis() - start).toInt()
            } else -1
        } catch (e: Exception) {
            Log.d("NurVPN-PING", "icmpPing fail $host: ${e.message}")
            -1
        }
    }

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
            Regex("""\"delay\"\s*:\s*(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * BATCH MODE: har ping natijasi to'planadi, 200ms da bir marta UI'ga post.
     * 100+ server uchun UI freeze oldini oladi (100x kam UI yangilanish).
     */
    fun testAll(servers: List<ServerItem>, listener: Listener) {
        val total = servers.size
        if (total == 0) {
            listener.onAllDone()
            return
        }

        // Batch queue — thread-safe
        val pending = ConcurrentLinkedQueue<Pair<ServerItem, Int>>()
        val handler = Handler(Looper.getMainLooper())
        val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)

        val flushRunnable = Runnable {
            scheduled.set(false)
            // Batch'dagi hamma natijalarni bir marta yetkazish
            val batch = ArrayList<Pair<ServerItem, Int>>(pending.size)
            while (true) {
                val item = pending.poll() ?: break
                batch.add(item)
            }
            if (batch.isEmpty()) return@Runnable
            for ((si, p) in batch) {
                listener.onPingUpdate(si, p)
            }
        }

        val remaining = java.util.concurrent.atomic.AtomicInteger(total)

        for (si in servers) {
            pool.submit {
                val h = si.host
                val p = if (h.isNullOrEmpty() || si.port <= 0) -1
                        else pingAny(h, si.port, si.protocol)
                si.ping = p

                // Batch'ga qo'shamiz
                pending.add(si to p)

                // 200ms da bir marta flush
                if (scheduled.compareAndSet(false, true)) {
                    handler.postDelayed(flushRunnable, BATCH_INTERVAL_MS)
                }

                // Hamma tugadi
                if (remaining.decrementAndGet() == 0) {
                    // Yakuniy flush (kutmasdan)
                    handler.removeCallbacks(flushRunnable)
                    handler.post(flushRunnable)
                    handler.post { listener.onAllDone() }
                }
            }
        }
    }
}
