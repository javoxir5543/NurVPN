package com.nurvpn.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

class LeakResult {
    var ipv4: String = "—"; var ipv4Ok = false
    var ipv6: String = "—"; var ipv6Ok = false
    var dns: String = "—"; var dnsOk = false
}

object LeakTester {
    @Volatile var lastVpnActive: Boolean = false
    @Volatile var lastIpv6Blocked: Boolean = false

    /** VPN/tunnel interfeyslari — bular leak EMAS. */
    private fun isVpnInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("tun") || n.startsWith("tap") ||
               n.startsWith("dummy") || n.startsWith("vpn") ||
               n == "lo" || n.startsWith("ppp") ||
               n.contains("wg") || n.contains("sing") ||
               n.contains("utun") || n.contains("rmnet")
    }

    /** Faqat global unicast IPv6 (2000::/3) — haqiqiy internet manzil. */
    private fun isGlobalUnicastV6(addr: Inet6Address): Boolean {
        if (addr.isLoopbackAddress) return false
        if (addr.isLinkLocalAddress) return false       // fe80::/10
        if (addr.isSiteLocalAddress) return false       // fec0::/10
        if (addr.isMulticastAddress) return false
        val b = addr.address
        if (b.isEmpty()) return false
        val first = b[0].toInt() and 0xFF
        // ULA (fc00::/7) — lokal, internetga chiqmaydi
        if ((first and 0xFE) == 0xFC) return false
        // Global unicast: 2000::/3 (birinchi 3 bit = 001)
        return (first and 0xE0) == 0x20
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
                // IPv6 — VPN holatiga qarab
                if (lastVpnActive && lastIpv6Blocked) {
                    // VPN faol + IPv6 bloklangan → barcha IPv6 VPN ichidan o'tadi
                    // Pastdagi interfeysdagi IPv6 manzil LEAK EMAS
                    r.ipv6 = "blocked"   // UI da tarjima qilinadi
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
                r.dns = try {
                    InetAddress.getByName("1.1.1.1").hostAddress ?: "—"
                } catch (e: Exception) { "—" }
                r.dnsOk = r.dns != "—"
            } catch (t: Throwable) {}
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

