package com.nurvpn.app.config

import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

object SingBoxConfig {

    /** Host string IP manzilmi? (IPv4 yoki IPv6) */
    fun isIpAddress(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.removePrefix("[").removeSuffix("]")
        // IPv4: 1.2.3.4
        if (h.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            val parts = h.split(".")
            return parts.all { it.toIntOrNull() in 0..255 }
        }
        // IPv6: contains ":"
        if (h.contains(":")) {
            return h.matches(Regex("^[0-9a-fA-F:]+$"))
        }
        return false
    }

    /** CIDR suffiks: IPv4 → /32, IPv6 → /128 */
    fun cidrSuffix(ip: String): String =
        if (ip.contains(":")) "/128" else "/32"


    class ParseException(msg: String) : Exception(msg)

    fun isHysteria2Link(link: String): Boolean =
        link.startsWith("hy2://") || link.startsWith("hysteria2://")

    @Throws(ParseException::class)
    fun outboundFor(link: String): JSONObject {
        return when {
            isHysteria2Link(link) -> hysteria2(link)
            link.startsWith("vless://") -> vless(link)
            link.startsWith("vmess://") -> vmess(link)
            link.startsWith("trojan://") -> trojan(link)
            link.startsWith("ss://") -> shadowsocks(link)
            else -> throw ParseException("Noma'lum protokol")
        }
    }

    private fun hysteria2(link: String): JSONObject {
        val u = URI(link.replace("hysteria2://", "hy2://"))
        val pass = u.userInfo ?: ""
        val host = u.host ?: throw ParseException("host yo'q")
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "hysteria2")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("password", pass)
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", q["sni"] ?: host)
        if (q["insecure"] == "1") tls.put("insecure", true)
        o.put("tls", tls)
        return o
    }

    private fun vless(link: String): JSONObject {
        val u = URI(link)
        val id = u.userInfo ?: throw ParseException("UUID yo'q")
        val host = u.host ?: throw ParseException("host yo'q")
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "vless")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("uuid", id)
        if (!q["flow"].isNullOrEmpty()) o.put("flow", q["flow"])
        val security = q["security"] ?: "none"
        if (security == "reality" || security == "tls") {
            val tls = JSONObject()
            tls.put("enabled", true)
            tls.put("server_name", q["sni"] ?: host)
            // ★ TLS fragmentatsiya (Rossiya DPI bypass)
            tls.put("fragment", true)
            tls.put("fragment_fallback_delay", "500ms")
            if (q["fp"] != null) {
                tls.put("utls", JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", q["fp"]))
            }
            if (security == "reality") {
                val r = JSONObject()
                r.put("enabled", true)
                r.put("public_key", q["pbk"]
                    ?: throw ParseException("reality pbk yo'q"))
                if (q["sid"] != null) r.put("short_id", q["sid"])
                tls.put("reality", r)
                // FIX: tls.utls FAQAT bir marta o'rnatiladi
                if (!tls.has("utls")) {
                    tls.put("utls", JSONObject()
                        .put("enabled", true)
                        .put("fingerprint", q["fp"] ?: "chrome"))
                }
            }
            if (q["insecure"] == "1") tls.put("insecure", true)
            o.put("tls", tls)
        }
        addTransport(o, q)
        return o
    }

    private fun vmess(link: String): JSONObject {
        val body = link.removePrefix("vmess://").substringBefore("#")

        // ═══ YANGI FORMAT: vmess://UUID@host:port?query ═══
        if (body.contains("@")) {
            return vmessUriStyle(body)
        }

        // ═══ ESKI FORMAT: vmess://BASE64_JSON ═══
        val b64 = body
        val json = try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ParseException("vmess base64 xato")
        }
        val v = JSONObject(json)
        val host = v.optString("add")
        if (host.isEmpty()) throw ParseException("vmess add yo'q")
        val o = JSONObject()
        o.put("type", "vmess")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", v.optInt("port", 443))
        o.put("uuid", v.optString("id"))
        o.put("security", v.optString("scy", "auto"))
        if (v.optString("tls").isNotEmpty()) {
            val tls = JSONObject()
            tls.put("enabled", true)
            tls.put("server_name", v.optString("host", host))
            o.put("tls", tls)
        }
        val q = mutableMapOf<String, String>()
        if (v.optString("net") == "ws") {
            q["type"] = "ws"
            q["path"] = v.optString("path")
            q["host"] = v.optString("host")
        }
        addTransport(o, q)
        return o
    }

    /** vmess://UUID@host:port?query — URI-style format. */
    private fun vmessUriStyle(body: String): JSONObject {
        val at = body.indexOf('@')
        if (at <= 0) throw ParseException("vmess URI xato")

        val uuid = body.substring(0, at)
        var after = body.substring(at + 1)

        var query = ""
        val qIdx = after.indexOf('?')
        if (qIdx >= 0) {
            query = after.substring(qIdx + 1)
            after = after.substring(0, qIdx)
        }

        val slash = after.indexOf('/')
        if (slash > 0) after = after.substring(0, slash)

        val colon = after.lastIndexOf(':')
        if (colon <= 0) throw ParseException("vmess URI port yo'q")

        val host = after.substring(0, colon)
        val port = after.substring(colon + 1).toIntOrNull() ?: 443

        if (uuid.isEmpty()) throw ParseException("vmess UUID yo'q")
        if (host.isEmpty()) throw ParseException("vmess host yo'q")

        val q = parseQuery(query)

        val o = JSONObject()
        o.put("type", "vmess")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("uuid", uuid)
        val sec = q["encryption"]?.takeIf { it.isNotEmpty() && it != "auto" }
            ?: "aes-128-gcm"
        o.put("security", sec)
        o.put("alter_id", 0)

        // TLS
        if (q["security"] == "tls" || q["tls"] == "tls") {
            val tls = JSONObject()
            tls.put("enabled", true)
            tls.put("server_name", q["sni"] ?: q["host"] ?: host)
            if (q["alpn"] != null) {
                val alpn = JSONArray()
                for (a in q["alpn"]!!.split(",")) alpn.put(a.trim())
                tls.put("alpn", alpn)
            }
            if (q["allowInsecure"] == "1" || q["insecure"] == "1") {
                tls.put("insecure", true)
            }
            if (!tls.has("alpn")) {
                tls.put("alpn", JSONArray().put("http/1.1"))
            }
            val utls = JSONObject()
            utls.put("enabled", true)
            utls.put("fingerprint", q["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome")
            tls.put("utls", utls)
            o.put("tls", tls)
        }

        // Transport
        val tq = mutableMapOf<String, String>()
        q["type"]?.let { tq["type"] = it }
        q["path"]?.let { tq["path"] = it }
        q["host"]?.let { tq["host"] = it }
        q["serviceName"]?.let { tq["serviceName"] = it }
        addTransport(o, tq)

        android.util.Log.i("NurVPN-PARSE",
            "vmessUriStyle: uuid=$uuid, host=$host:$port, " +
            "type=${q["type"]}, security=${q["security"]}, sni=${q["sni"]}")

        return o
    }

    private fun trojan(link: String): JSONObject {
        val u = URI(link)
        val host = u.host ?: throw ParseException("host yo'q")
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "trojan")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 443)
        o.put("password", u.userInfo ?: "")
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", q["sni"] ?: host)
        if (q["insecure"] == "1") tls.put("insecure", true)
        o.put("tls", tls)
        addTransport(o, q)
        return o
    }

    private fun shadowsocks(link: String): JSONObject {
        val o = JSONObject()
        o.put("type", "shadowsocks")
        o.put("tag", "proxy")

        var body = link.removePrefix("ss://")
        val hash = body.indexOf('#')
        if (hash > 0) body = body.substring(0, hash)

        var query = ""
        val qIdx = body.indexOf('?')
        if (qIdx > 0) {
            query = body.substring(qIdx + 1)
            body = body.substring(0, qIdx)
        }

        var userInfo: String
        var hostPort: String

        if (body.contains('@')) {
            userInfo = body.substringBefore('@')
            hostPort = body.substringAfter('@')
            if (!userInfo.contains(':')) {
                userInfo = try {
                    String(Base64.decode(userInfo, Base64.DEFAULT), Charsets.UTF_8)
                } catch (t: Throwable) {
                    android.util.Log.w("NurVPN-SS",
                        "base64 userinfo xato: ${t.message}")
                    userInfo
                }
            }
        } else {
            val dec = try {
                String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                throw ParseException("ss base64 xato")
            }
            if (dec.contains('@')) {
                userInfo = dec.substringBefore('@')
                hostPort = dec.substringAfter('@')
            } else {
                throw ParseException("ss format xato")
            }
        }

        val hp = hostPort.substringBefore('/').substringBefore('?')
        val colon = hp.lastIndexOf(':')
        val host = if (colon > 0) hp.substring(0, colon) else hp
        val port = if (colon > 0) hp.substring(colon + 1).toIntOrNull() ?: 8388 else 8388

        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(":", "")

        if (host.isEmpty()) throw ParseException("ss host yo'q")
        if (method.isEmpty()) throw ParseException("ss method yo'q")

        o.put("server", host)
        o.put("server_port", port)
        o.put("method", method)
        o.put("password", password)

        // Plugin (v2ray-plugin, obfs-local) va UoT
        if (query.isNotEmpty()) {
            val params = parseQuery(query)
            val plugin = params["plugin"]
            if (!plugin.isNullOrEmpty()) {
                o.put("plugin", plugin)
                val opts = params["plugin_opts"]
                if (!opts.isNullOrEmpty()) o.put("plugin_opts", opts)
            }
            val uot = params["uot"]
            if (uot == "1" || uot == "true") {
                o.put("udp_over_tcp", JSONObject()
                    .put("enabled", true)
                    .put("version", 2))
            }
        }

        android.util.Log.i("NurVPN-SS",
            "shadowsocks: method=$method, host=$host:$port")
        return o
    }

    private fun addTransport(o: JSONObject, q: Map<String, String>) {
        val type = q["type"] ?: return
        val t = JSONObject()
        when (type) {
            "ws" -> {
                t.put("type", "ws")
                if (q["host"] != null && q["host"]!!.isNotEmpty()) {
                    val headers = JSONObject()
                    headers.put("Host", JSONArray().put(q["host"]))
                    t.put("headers", headers)
                }
                t.put("path", if (q["path"]?.isNotEmpty() == true) q["path"] else "/")
            }
            "grpc" -> {
                t.put("type", "grpc")
                t.put("service_name", q["serviceName"] ?: "")
            }
            "http", "h2" -> {
                t.put("type", "http")
                if (q["host"] != null)
                    t.put("host", JSONArray().put(q["host"]))
                if (q["path"] != null) t.put("path", q["path"])
            }
            "httpupgrade" -> {
                t.put("type", "httpupgrade")
                if (q["host"] != null) t.put("host", q["host"])
                if (q["path"] != null) t.put("path", q["path"])
            }
            "xhttp" -> {
                // ═══ XHTTP transport (sing-box 1.10+) ═══
                t.put("type", "xhttp")
                if (q["host"] != null && q["host"]!!.isNotEmpty())
                    t.put("host", q["host"])
                // path: ham "path", ham "xhttpPath" ni qabul qilamiz
                val xpath = q["path"] ?: q["xhttpPath"] ?: "/"
                val xmode = q["mode"] ?: q["xhttpMode"] ?: "auto"
                t.put("path", xpath)
                t.put("mode", xmode)
                android.util.Log.i("NurVPN-PARSE",
                    "XHTTP config: host=${q["host"]}, path=$xpath, mode=$xmode")
            }
            else -> {
                android.util.Log.w("NurVPN-PARSE", "Noma'lum transport: $type")
                return
            }
        }
        o.put("transport", t)
    }

    private fun parseQuery(query: String?): Map<String, String> {
        val out = HashMap<String, String>()
        if (query.isNullOrEmpty()) return out
        for (pair in query.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2)
                out[kv[0]] = android.net.Uri.decode(kv[1])
        }
        return out
    }

    /**
     * To'liq sing-box konfiguratsiya.
     * TUN fd ni sing-box ga env orqali beramiz (SING_BOX_TUN_FD).
     * sing-box tun inbound platform fd ni ishlatadi.
     */
    /** Link'dan server host'ini ajratib olish (route uchun) */
    private fun extractServerHost(link: String): String? {
        return try {
            val u = java.net.URI(link)
            val host = u.host
            if (!host.isNullOrEmpty()) return host
            // vmess:// uchun base64 decode
            if (link.startsWith("vmess://")) {
                val b64 = link.removePrefix("vmess://").substringBefore("#")
                val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                org.json.JSONObject(json).optString("add").takeIf { it.isNotEmpty() }
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * AmneziaWG outbound generatori.
     */
    @Throws(ParseException::class)
    fun awgOutbound(rawConf: String): JSONObject {
        val o = JSONObject()
        o.put("type", "wireguard")
        o.put("tag", "proxy")

        var inIface = false
        var inPeer = false
        val localAddrs = JSONArray()
        val allowedIps = JSONArray()
        val dnsList = JSONArray()
        var host = ""
        var port = 51820
        var mtu = 1280
        var keepalive = 0
        var reserved: List<Int> = emptyList()

        for (raw in rawConf.lines()) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t.equals("[Interface]", true)) { inIface = true; inPeer = false; continue }
            if (t.equals("[Peer]", true)) { inIface = false; inPeer = true; continue }
            val eq = t.indexOf('=')
            if (eq < 0) continue
            val k = t.substring(0, eq).trim()
            val v = t.substring(eq + 1).trim()
            if (v.isEmpty()) continue
            when {
                k.equals("PrivateKey", true) && inIface -> o.put("private_key", v)
                k.equals("Address", true) && inIface ->
                    for (a in v.split(",")) if (a.trim().isNotEmpty()) localAddrs.put(a.trim())
                k.equals("DNS", true) && inIface ->
                    for (d in v.split(",")) if (d.trim().isNotEmpty()) dnsList.put(d.trim())
                k.equals("MTU", true) && inIface -> mtu = v.toIntOrNull() ?: 1280
                // ★ AWG parametrlari — barchasi
                k.equals("Jc", true) -> o.put("jc", v.toIntOrNull() ?: 0)
                k.equals("Jmin", true) -> o.put("jmin", v.toIntOrNull() ?: 0)
                k.equals("Jmax", true) -> o.put("jmax", v.toIntOrNull() ?: 0)
                k.equals("S1", true) -> o.put("s1", v.toIntOrNull() ?: 0)
                k.equals("S2", true) -> o.put("s2", v.toIntOrNull() ?: 0)
                k.equals("S3", true) -> o.put("s3", v.toIntOrNull() ?: 0)
                k.equals("S4", true) -> o.put("s4", v.toIntOrNull() ?: 0)
                k.equals("H1", true) -> o.put("h1", v.toIntOrNull() ?: 0)
                k.equals("H2", true) -> o.put("h2", v.toIntOrNull() ?: 0)
                k.equals("H3", true) -> o.put("h3", v.toIntOrNull() ?: 0)
                k.equals("H4", true) -> o.put("h4", v.toIntOrNull() ?: 0)
                k.equals("I1", true) -> o.put("i1", v)
                k.equals("I2", true) -> o.put("i2", v)
                k.equals("I3", true) -> o.put("i3", v)
                k.equals("I4", true) -> o.put("i4", v)
                k.equals("I5", true) -> o.put("i5", v)
                k.equals("PublicKey", true) && inPeer -> o.put("_pub", v)
                k.equals("PresharedKey", true) && inPeer -> o.put("_psk", v)
                k.equals("Endpoint", true) && inPeer -> {
                    val i = v.lastIndexOf(':')
                    if (i > 0) {
                        host = v.substring(0, i).trim()
                        port = v.substring(i + 1).trim().toIntOrNull() ?: 51820
                    } else host = v
                }
                k.equals("AllowedIPs", true) && inPeer ->
                    for (ip in v.split(",")) if (ip.trim().isNotEmpty()) allowedIps.put(ip.trim())
                k.equals("PersistentKeepalive", true) && inPeer ->
                    keepalive = v.toIntOrNull() ?: 0
                k.equals("Reserved", true) && inPeer -> {
                    // Format: "[113, 208, 80]" yoki "113,208,80" yoki "cdBQ" (base64)
                    val clean = v.trim().removePrefix("[").removeSuffix("]")
                    val parts = clean.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    reserved = if (parts.all { it.toIntOrNull() != null }) {
                        parts.mapNotNull { it.toIntOrNull() }
                    } else {
                        // base64 ni dekod qilishga urinamiz
                        try {
                            val bytes = android.util.Base64.decode(v, android.util.Base64.DEFAULT)
                            bytes.map { it.toInt() and 0xFF }
                        } catch (_: Throwable) { emptyList() }
                    }
                    android.util.Log.i("NurVPN-AWG", "Reserved parsed: $reserved")
                }
            }
        }

        if (host.isEmpty()) throw ParseException("AWG: Endpoint yo'q")
        if (!o.has("private_key")) throw ParseException("AWG: PrivateKey yo'q")
        if (!o.has("_pub")) throw ParseException("AWG: Peer PublicKey yo'q")
        android.util.Log.i("NurVPN-AWG",
            "awgOutbound: host=$host, port=$port, mtu=$mtu, " +
            "localAddrs=${localAddrs.length()}, allowedIps=${allowedIps.length()}")

        // ★ address — ARRAY (IPv4 va IPv6 ikkalasi)
        val addrList = JSONArray()
        for (i in 0 until localAddrs.length()) {
            val a = localAddrs.getString(i).trim()
            if (a.isEmpty()) continue
            when {
                a.contains("/") -> addrList.put(a)                    // allaqachon CIDR
                a.contains(":") -> addrList.put("$a/128")             // IPv6
                a.matches(Regex("^[0-9.]+$")) -> addrList.put("$a/32") // IPv4
            }
        }
        if (addrList.length() == 0) {
            addrList.put("10.0.0.2/32")
        }
        o.put("address", addrList)
        o.put("mtu", mtu)
        if (dnsList.length() > 0) o.put("dns", dnsList)// Peers
        val peer = JSONObject()
        peer.put("address", host)
        peer.put("port", port)
        peer.put("public_key", o.remove("_pub"))
        if (o.has("_psk")) peer.put("pre_shared_key", o.remove("_psk"))
        if (allowedIps.length() > 0) peer.put("allowed_ips", allowedIps)
        else peer.put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        if (keepalive > 0) peer.put("persistent_keepalive_interval", keepalive)
        // ★ Reserved — WARP uchun majburiy
        if (reserved.isNotEmpty()) {
            val rArr = JSONArray()
            for (n in reserved) rArr.put(n)
            peer.put("reserved", rArr)
            android.util.Log.i("NurVPN-AWG", "peer.reserved = $reserved")
        }
        o.put("peers", JSONArray().put(peer))

        return o
    }

    @Throws(ParseException::class)
    fun buildAwgConfig(rawConf: String): JSONObject {
        val root = JSONObject()
        val proxy = awgOutbound(rawConf)

        root.put("dns", JSONObject()
            .put("servers", JSONArray()
                .put(JSONObject()
                    .put("type", "udp")
                    .put("tag", "dns-remote")
                    .put("server", "1.1.1.1")
                    .put("detour", "proxy")))
            .put("final", "dns-remote")
            .put("strategy", "prefer_ipv4"))

        root.put("log", JSONObject()
            .put("level", "info")
            .put("timestamp", true))

        root.put("inbounds", JSONArray().put(JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", JSONArray()
                .put("172.19.0.1/30")
                .put("fdfe:dcba:9876::1/126"))
            .put("mtu", 1500)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "gvisor")))

        // ★ ENDPOINT format (sing-box 1.11+)
        root.put("endpoints", JSONArray().put(proxy))
        root.put("outbounds", JSONArray()
            .put(JSONObject().put("type", "direct").put("tag", "direct")))

        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        // AWG server IP → direct
        try {
            val peers = proxy.optJSONArray("peers")
            if (peers != null && peers.length() > 0) {
                val h = peers.getJSONObject(0).optString("address")
                if (h.isNotEmpty() && h.matches(Regex("^[0-9.]+$"))) {
                    rules.put(JSONObject()
                        .put("ip_cidr", JSONArray().put(h + "/32"))
                        .put("outbound", "direct"))
                }
            }
        } catch (ignored: Throwable) {}
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))

        root.put("route", JSONObject()
            .put("rules", rules)
            .put("final", "proxy")
            .put("auto_detect_interface", true))

        return root
    }

    @Throws(ParseException::class)
    fun buildFullConfig(link: String, dns: String): JSONObject {
        val root = JSONObject()
        val serverHost = extractServerHost(link)

        // ═══ CACHE — o'chirilgan (Android storage muammosi) ═══
        // ═══ DNS — MAJBURIY (server host qoidasi bilan) ═══
        val dnsRules = JSONArray()
        if (!serverHost.isNullOrEmpty()) {
            // IP bo'lsa — ip_cidr, hostname bo'lsa — domain
            if (isIpAddress(serverHost)) {
                dnsRules.put(JSONObject()
                    .put("ip_cidr", JSONArray().put(serverHost + cidrSuffix(serverHost)))
                    .put("action", "route")
                    .put("server", "dns-direct"))
            } else {
                dnsRules.put(JSONObject()
                    .put("domain", JSONArray().put(serverHost))
                    .put("action", "route")
                    .put("server", "dns-direct"))
            }
        }
        root.put("dns", JSONObject()
            .put("servers", JSONArray()
                .put(JSONObject()
                    .put("type", "udp")
                    .put("tag", "dns-remote")
                    .put("server", dns)
                    .put("detour", "proxy"))
                .put(JSONObject()
                    .put("type", "local")
                    .put("tag", "dns-direct")))
            .put("rules", dnsRules)
            .put("final", "dns-remote")
            .put("strategy", "prefer_ipv4"))

        // ═══ CACHE — o'chirildi (Android'da flock timeout beradi) ═══
        // experimental/cache_file UMUMAN YO'Q.
        // MIUI'da flock timeout beradi. Sing-box cache'siz ishlaydi.

        // ═══ LOG ═══
        root.put("log", JSONObject()
            .put("level", "info")
            .put("timestamp", true))

        // DNS bo'limi olib tashlandi (sing-box o'zi hal qiladi)

        // ═══ INBOUNDS — TUN ═══
        val inbounds = JSONArray()
        inbounds.put(JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("auto_route", true)
            .put("mtu", 1500)
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", "gvisor"))
        root.put("inbounds", inbounds)

        // ═══ OUTBOUNDS ═══
        val outbounds = JSONArray()
        outbounds.put(outboundFor(link))
        outbounds.put(JSONObject()
            .put("type", "direct")
            .put("tag", "direct"))
        root.put("outbounds", outbounds)

        // ═══ ROUTE ═══
        val rules = JSONArray()
        // Sniff action (1.11+)
        rules.put(JSONObject()
            .put("action", "sniff"))


        // QUIC reject — YouTube TCP'ga tushadi
        rules.put(JSONObject()
            .put("protocol", "quic")
            .put("action", "reject"))
        // 1. DNS hijack
        rules.put(JSONObject()
            .put("protocol", "dns")
            .put("action", "hijack-dns"))
        // QUIC endi proxy orqali o'tadi (YouTube ishlashi uchun)

        // 2. Server host → DIRECT (loop oldini olish)
        if (!serverHost.isNullOrEmpty()) {
            if (isIpAddress(serverHost)) {
                rules.put(JSONObject()
                    .put("ip_cidr", JSONArray().put(serverHost + cidrSuffix(serverHost)))
                    .put("outbound", "direct"))
            } else {
                rules.put(JSONObject()
                    .put("domain", JSONArray().put(serverHost))
                    .put("outbound", "direct"))
            }
        }

        // 3. Private IP → DIRECT
        rules.put(JSONObject()
            .put("ip_is_private", true)
            .put("outbound", "direct"))

        root.put("route", JSONObject()
            .put("rules", rules)
            .put("final", "proxy")
            .put("auto_detect_interface", true))

        // ═══ CLASH API — O'CHIRILGAN ═══
        // libbox.aar "with_clash_api" tegisiz qurilgan.
        // experimental.clash_api qo'shilsa, VPN umuman ulanmaydi.

        return root
    }
}


// ═══════════ MAIN ACTIVITY ═══════════

