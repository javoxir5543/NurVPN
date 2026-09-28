package com.nurvpn.app.core

import com.nurvpn.app.util.CountryLookup
import java.util.Locale

class ServerItem(@JvmField var link: String) {
    var host: String? = null
    var port: Int = 0
    var remark: String? = null
    var countryCode: String = ""
    var country: String = ""
    var ping: Int = -1
    var subId: String? = null
    var favorite: Boolean = false
    /** Transport turi: tcp, ws, grpc, xhttp, quic, httpupgrade, split, ... */
    var transport: String = ""

    /**
     * Transport bo'sh bo'lsa — link dan avtomatik aniqlaydi.
     * Eski serverlar uchun (transport maydonisiz saqlangan).
     */
    fun ensureTransport() {
        if (transport.isNotEmpty()) return
        val l = link.lowercase()
        transport = when {
            l.startsWith("hysteria2://") || l.startsWith("hy2://") -> "quic"
            l.startsWith("tuic://") -> "quic"
            l.contains("type=ws") || l.contains("type=websocket") -> "ws"
            l.contains("type=grpc") -> "grpc"
            l.contains("type=xhttp") -> "xhttp"
            l.contains("type=httpupgrade") -> "httpupgrade"
            l.contains("type=splithttp") -> "split"
            l.contains("type=kcp") -> "kcp"
            l.contains("type=quic") -> "quic"
            l.contains("security=reality") -> "reality"
            l.contains("security=tls") -> "tls"
            else -> "tcp"
        }
    }

    var protocol: Protocol = Protocol.VLESS_REALITY

    fun flag(): String {
        val cc = countryCode.uppercase(Locale.US)
        if (cc.length == 2 && cc.all { it in 'A'..'Z' }) {
            val sb = StringBuilder()
            for (c in cc) {
                sb.appendCodePoint(0x1F1E6 + (c - 'A'))
            }
            return sb.toString()
        }
        // Remark dan emoji bayroqni olishga urinamiz
        val fromRemark = CountryLookup.flagFromRemark(remark)
        if (fromRemark.isNotEmpty()) return fromRemark
        return "🌍"
    }

    fun displayName(): String {
        if (!remark.isNullOrEmpty()) {
            val r = remark!!.replace("+", " ").replace("%20", " ").trim()
            // Agar remark juda uzun bo'lsa (butun link bo'lsa) — qisqartiramiz
            if (r.length > 60 || r.startsWith("vless://") ||
                r.startsWith("vmess://") || r.startsWith("hysteria2://") ||
                r.startsWith("hy2://") || r.startsWith("tuic://") ||
                r.startsWith("ss://") || r.startsWith("trojan://")) {
                return if (!host.isNullOrEmpty()) host!! else "server"
            }
            return r
        }
        if (!host.isNullOrEmpty()) return host!!
        return "server"
    }
}
