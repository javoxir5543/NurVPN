package com.nurvpn.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import org.json.JSONArray
import java.util.Collections


class LeakResult {
    var ipv4: String = "—"; var ipv4Ok = false
    var ipv6: String = "—"; var ipv6Ok = false
    var dns: String = "—"; var dnsOk = false
}


object LeakTester {
    private const val TAG = "NurVPN-LEAK"

    @Volatile var lastVpnActive: Boolean = false
    @Volatile var lastIpv6Blocked: Boolean = false

    /** VPN/tunnel interfeyslari — bular leak EMAS. */
    private fun isVpnInterface(name: String): Boolean {
        val n = name.lowercase()
        // FIX: rmnet/ccmni OLIB TASHLANDI — bular mobil data interfeyslari
        return n.startsWith("tun") || n.startsWith("tap") ||
               n.startsWith("dummy") || n.startsWith("vpn") ||
               n == "lo" || n.startsWith("ppp") ||
               n.contains("wg") || n.contains("sing") ||
               n.contains("utun")
    }

    /** Faqat global unicast IPv6 (2000::/3) — haqiqiy internet manzil. */
    private fun isGlobalUnicastV6(addr: Inet6Address): Boolean {
        if (addr.isLoopbackAddress) return false
        if (addr.isLinkLocalAddress) return false
        if (addr.isSiteLocalAddress) return false
        if (addr.isMulticastAddress) return false
        val b = addr.address
        if (b.isEmpty()) return false
        val first = b[0].toInt() and 0xFF
        if ((first and 0xFE) == 0xFC) return false
        return (first and 0xE0) == 0x20
    }

    /**
     * HAQIQIY DNS LEAK TESTI (bash.ws orqali).
     * Token olib, 5 ta maxsus subdomain'ga DNS so'rov yuboradi.
     * bash.ws resolver'larni qayd qiladi va JSON'da qaytaradi.
     */
    private fun realDnsLeakTest(): Pair<String, Boolean> {
        return try {
            val token = URL("https://bash.ws/id").readText().trim()
            if (token.isEmpty()) {
                Log.w(TAG, "bash.ws: token bo'sh")
                return "—" to false
            }
            Log.i(TAG, "bash.ws token: $token")

            for (i in 1..5) {
                try {
                    InetAddress.getByName("$i.$token.bash.ws")
                } catch (t: Throwable) {
                    // Ba'zi resolver'lar NXDOMAIN qaytaradi — normal
                }
            }

            Thread.sleep(2000)

            val jsonText = URL("https://bash.ws/dnsleak/test/$token?json").readText()
            val arr = JSONArray(jsonText)

            val resolvers = mutableListOf<String>()
            var conclusion = ""
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("type", "")
                if (type == "dns") {
                    val ip = o.optString("ip", "")
                    val country = o.optString("country_name", "")
                    if (ip.isNotEmpty()) {
                        resolvers.add(if (country.isNotEmpty()) "$ip ($country)" else ip)
                    }
                } else {
                    val c = o.optString("conclusion", "")
                    if (c.isNotEmpty()) conclusion = c
                }
            }

            Log.i(TAG, "DNS test: ${resolvers.size} resolver, conclusion='$conclusion'")

            if (resolvers.isEmpty()) return "—" to false

            val ok = when {
                conclusion.contains("not leaked", ignoreCase = true) -> true
                conclusion.contains("leaked", ignoreCase = true) -> false
                else -> resolvers.size <= 1
            }

            val summary = if (resolvers.size <= 2) {
                resolvers.joinToString(", ")
            } else {
                "${resolvers.take(2).joinToString(", ")} +${resolvers.size - 2}"
            }
            summary to ok
        } catch (t: Throwable) {
            Log.e(TAG, "realDnsLeakTest xato: ${t.message}", t)
            "—" to false
        }
    }

    fun test(cb: (LeakResult) -> Unit) {
        Thread {
            val r = LeakResult()
            try {
                // IPv4 — faqat tashqi interfeyslar
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback) continue
                    if (isVpnInterface(ni.name)) continue
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            r.ipv4 = addr.hostAddress ?: "—"
                            r.ipv4Ok = true
                            break
                        }
                    }
                    if (r.ipv4Ok) break
                }

                // IPv6
                if (lastVpnActive && lastIpv6Blocked) {
                    r.ipv6 = "blocked"
                    r.ipv6Ok = true
                } else {
                    var v6: String? = null
                    for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                        if (!ni.isUp || ni.isLoopback) continue
                        if (isVpnInterface(ni.name)) continue
                        for (addr in ni.inetAddresses) {
                            if (addr is Inet6Address && isGlobalUnicastV6(addr)) {
                                v6 = addr.hostAddress
                                break
                            }
                        }
                        if (v6 != null) break
                    }
                    r.ipv6 = v6 ?: "not_found"
                    r.ipv6Ok = v6 == null
                }

                // DNS — haqiqiy test (bash.ws)
                val (dnsSummary, dnsOk) = realDnsLeakTest()
                r.dns = dnsSummary
                r.dnsOk = dnsOk
            } catch (t: Throwable) {
                Log.e(TAG, "test xato: ${t.message}", t)
            }
            Handler(Looper.getMainLooper()).post { cb(r) }
        }.start()
    }
}


object IPv6Blocker {
    private const val KEY = "block_ipv6"
    fun isBlocked(ctx: Context): Boolean =
        ctx.getSharedPreferences("sec", Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    fun setBlocked(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("sec", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, v).apply()
}


object DNSLeakProtection {
    private const val KEY = "dns_leak"
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("sec", Context.MODE_PRIVATE)
            .getBoolean(KEY, true)
    fun setEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("sec", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, v).apply()
}
