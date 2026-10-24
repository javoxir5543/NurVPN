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
import java.util.concurrent.Executors

object PingTester {
    interface Listener {
        fun onPingUpdate(item: ServerItem, ping: Int)
        fun onAllDone()
    }

    private val pool = Executors.newFixedThreadPool(8)

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
        Log.d("NurVPN-PING", "pingAny $host:$port proto=$proto strategy=${proto.pingStrategy} → $r ms")
        return r
    }

    /** Hysteria2/TUIC/AWG uchun host RTT (ICMP yoki TCP fallback). */
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

    /**
     * UDP protokollar (Hysteria2, TUIC) uchun ping — Clash API orqali.
     * Tag har doim "proxy" (SingBoxConfig'da hardcoded).
     * API 127.0.0.1 da, addDisallowedApplication bypass ta'sir qilmaydi.
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
            Log.d("NurVPN-PING", "clashApiPing code=$code body=${body.take(160)}")
            Regex("""\"delay\"\s*:\s*(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            Log.d("NurVPN-PING", "clashApiPing xato: ${e.message}")
            -1
        }
    }

    fun testAll(servers: List<ServerItem>, listener: Listener) {
        var remaining = servers.size
        if (remaining == 0) { listener.onAllDone(); return }
        for (si in servers) {
            pool.submit {
                val h = si.host
                val p = if (h.isNullOrEmpty() || si.port <= 0) -1
                        else pingAny(h, si.port, si.protocol)
                si.ping = p
                Handler(Looper.getMainLooper()).post {
                    listener.onPingUpdate(si, p)
                }
                synchronized(listener) {
                    remaining--
                    if (remaining == 0) {
                        Handler(Looper.getMainLooper()).post {
                            listener.onAllDone()
                        }
                    }
                }
            }
        }
    }
}
