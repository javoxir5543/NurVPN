package com.nurvpn.app.config

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nurvpn.app.parser.decodeBase64Lenient
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfig {

    private const val TAG = "NurVPN-CFG"

    /** Server domenini ulanishdan OLDIN hal qilish uchun (VPN'siz, to'g'ridan-to'g'ri). */
    private const val BOOTSTRAP_DNS = "8.8.8.8"

    class ParseException(msg: String) : Exception(msg)

    // ═══════════════════════════════════════════════════════════
    // UMUMIY YORDAMCHILAR
    // ═══════════════════════════════════════════════════════════

    /** Host string IP manzilmi? (IPv4 yoki IPv6) */
    fun isIpAddress(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            return h.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
        }
        if (h.contains(":")) return h.matches(Regex("^[0-9a-fA-F:.]+$"))
        return false
    }

    /** CIDR suffiks: IPv4 → /32, IPv6 → /128 */
    fun cidrSuffix(ip: String): String = if (ip.contains(":")) "/128" else "/32"

    fun isHysteria2Link(link: String): Boolean {
        val l = link.lowercase()
        return l.startsWith("hy2://") || l.startsWith("hysteria2://")
    }

    /**
     * Qo'lda yozilgan yengil URL parser.
     * java.net.URI ISHLATILMAYDI: u '#' dagi bo'sh joy / emoji / maxsus belgilarda
     * URISyntaxException tashlaydi (aksar obuna linklari shunday), IPv6 host'ni
     * esa [qavs] bilan qaytaradi.
     */
    private class Lk(
        val userInfo: String?, val host: String?, val port: Int,
        val query: String?, val rawPort: String = ""
    )

    private fun leadingInt(s: String): Int =
        Regex("^\\d+").find(s)?.value?.toIntOrNull() ?: -1

    private fun splitHostPort(hp: String): Pair<String, Int> {
        val s = hp.trim()
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return s.removePrefix("[") to -1
            return s.substring(1, end) to leadingInt(s.substring(end + 1).removePrefix(":"))
        }
        val c = s.lastIndexOf(':')
        // Bir nechta ':' bo'lsa — bu qavssiz IPv6 (port yo'q)
        if (c < 0 || s.indexOf(':') != c) return s to -1
        return s.substring(0, c) to leadingInt(s.substring(c + 1))
    }

    private fun parseLink(link: String): Lk {
        val noFrag = link.trim().substringBefore('#')
        val sep = noFrag.indexOf("://")
        if (sep < 0) throw ParseException("Link formati xato")
        var rest = noFrag.substring(sep + 3)
        var query: String? = null
        val qi = rest.indexOf('?')
        if (qi >= 0) {
            query = rest.substring(qi + 1)
            rest = rest.substring(0, qi)
        }
        val si = rest.indexOf('/')
        if (si >= 0) rest = rest.substring(0, si)
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) Uri.decode(rest.substring(0, at)) else null
        val hp = if (at >= 0) rest.substring(at + 1) else rest
        val (h, p) = splitHostPort(hp)
        // Port matni (hysteria2: host:443,20000-30000 kabi diapazonlar uchun)
        val rawPort = when {
            hp.startsWith("[") -> hp.substringAfter("]:", "")
            hp.count { it == ':' } == 1 -> hp.substringAfter(':')
            else -> ""
        }
        return Lk(userInfo, h.ifEmpty { null }, p, query, rawPort)
    }

    private fun parseQuery(query: String?): Map<String, String> {
        val out = HashMap<String, String>()
        if (query.isNullOrEmpty()) return out
        for (pair in query.replace("&amp;", "&").split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotEmpty()) out[kv[0]] = Uri.decode(kv[1])
        }
        return out
    }

    /** user:pass (oddiy yoki base64) → (user, pass). */
    private fun userPass(ui: String?): Pair<String, String>? {
        if (ui.isNullOrEmpty()) return null
        var s: String = ui
        if (!s.contains(':')) s = decodeBase64Lenient(s)?.takeIf { it.contains(':') } ?: s
        val i = s.indexOf(':')
        return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1)
    }

    private fun isTrue(v: String?) = v == "1" || v.equals("true", ignoreCase = true)

    private fun insecureOf(q: Map<String, String>) =
        isTrue(q["insecure"]) || isTrue(q["allowInsecure"]) ||
            isTrue(q["allow_insecure"]) || isTrue(q["skip-cert-verify"])

    private fun cleanSni(v: String?): String? =
        v?.trim()?.removePrefix("https://")?.removePrefix("http://")
            ?.removePrefix("://")?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private fun alpnArray(v: String): JSONArray {
        val a = JSONArray()
        for (x in v.split(",")) {
            val t = x.trim()
            if (t.isNotEmpty()) a.put(t)
        }
        return a
    }

    private fun baseTls(q: Map<String, String>, host: String, defaultAlpn: String? = null): JSONObject {
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", cleanSni(q["sni"]) ?: cleanSni(q["peer"]) ?: host)
        if (insecureOf(q)) tls.put("insecure", true)
        val alpn = q["alpn"]?.takeIf { it.isNotBlank() } ?: defaultAlpn
        if (alpn != null) tls.put("alpn", alpnArray(alpn))
        return tls
    }

    private fun withUtls(tls: JSONObject, fp: String?) {
        if (!fp.isNullOrEmpty()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // OUTBOUND DISPATCH
    // ═══════════════════════════════════════════════════════════

    @Throws(ParseException::class)
    fun outboundFor(link: String, ctx: android.content.Context): JSONObject {
        val l = link.trim()
        val lower = l.lowercase()
        return try {
            when {
                lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> hysteria2(l)
                lower.startsWith("tuic://") -> tuic(l)
                lower.startsWith("juicity://") -> juicity(l)
                lower.startsWith("vless://") -> vless(l, ctx)
                lower.startsWith("vmess://") -> vmess(l)
                lower.startsWith("trojan://") -> trojan(l)
                lower.startsWith("ssr://") -> shadowsocksr(l)
                lower.startsWith("ss://") -> shadowsocks(l)
                lower.startsWith("socks://") || lower.startsWith("socks5://") -> socks(l)
                lower.startsWith("http-proxy://") || lower.startsWith("https-proxy://") -> httpProxy(l)
                lower.startsWith("ssh://") -> ssh(l)
                lower.startsWith("shadowtls://") -> shadowtls(l)
                lower.startsWith("anytls://") -> anytls(l)
                else -> throw ParseException("Noma'lum protokol: ${l.take(30)}")
            }
        } catch (e: ParseException) {
            throw e
        } catch (e: Throwable) {
            throw ParseException("Link xato: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PROTOKOLLAR
    // ═══════════════════════════════════════════════════════════

    private fun hysteria2(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("Hysteria2: host yo'q")
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.query)

        val o = JSONObject()
        o.put("type", "hysteria2")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("password", u.userInfo ?: "")

        // Port hopping: mport=20000-30000 yoki 443,20000-30000
        val mport = q["mport"] ?: u.rawPort.takeIf { it.contains(",") || it.contains("-") }
        if (!mport.isNullOrBlank()) {
            val ranges = JSONArray()
            for (part in mport.split(",")) {
                val p = part.trim().replace("-", ":")
                if (p.isEmpty()) continue
                ranges.put(if (p.contains(":")) p else "$p:$p")
            }
            if (ranges.length() > 0) {
                o.remove("server_port")
                o.put("server_ports", ranges)
            }
        }

        // ★ XAVFSIZLIK: sertifikat tekshiruvi sukut bo'yicha YOQILGAN.
        //   Faqat link o'zida insecure=1 bo'lsagina o'chiriladi.
        o.put("tls", baseTls(q, host, "h3"))

        val obfs = q["obfs"]
        if (!obfs.isNullOrEmpty() && obfs != "none") {
            o.put("obfs", JSONObject()
                .put("type", obfs)
                .put("password", q["obfs-password"] ?: ""))
        }
        q["upmbps"]?.toIntOrNull()?.let { o.put("up_mbps", it) }
        q["downmbps"]?.toIntOrNull()?.let { o.put("down_mbps", it) }
        Log.i(TAG, "Hysteria2: $host:$port insecure=${insecureOf(q)}")
        return o
    }

    private fun tuic(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("TUIC: host yo'q")
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.query)
        val ui = u.userInfo?.takeIf { it.isNotEmpty() } ?: throw ParseException("TUIC: uuid yo'q")
        val o = JSONObject()
        o.put("type", "tuic")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("uuid", ui.substringBefore(':'))
        o.put("password", ui.substringAfter(':', ""))
        o.put("congestion_control", q["congestion_control"]?.takeIf { it.isNotEmpty() } ?: "bbr")
        val mode = q["udp_relay_mode"]
        if (mode == "native" || mode == "quic") o.put("udp_relay_mode", mode)
        o.put("tls", baseTls(q, host, "h3"))
        Log.i(TAG, "TUIC: $host:$port")
        return o
    }

    private fun vless(link: String, ctx: android.content.Context): JSONObject {
        val u = parseLink(link)
        val id = u.userInfo?.takeIf { it.isNotEmpty() } ?: throw ParseException("VLESS: UUID yo'q")
        val host = u.host ?: throw ParseException("VLESS: host yo'q")
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.query)

        val o = JSONObject()
        o.put("type", "vless")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("uuid", id)
        val security = q["security"] ?: "none"

        // Yangi VLESS "encryption" (mlkem...) qo'llab-quvvatlanmaydi — jimgina yiqilmasin
        val encr = q["encryption"]
        if (!encr.isNullOrEmpty() && encr != "none") {
            throw ParseException("VLESS encryption='${encr.take(12)}...' qo'llab-quvvatlanmaydi")
        }
        // TCP HTTP-obfuscation (headerType=http) sing-box'da yo'q
        val netType = (q["type"] ?: "tcp").lowercase()
        if ((netType == "tcp" || netType == "raw") && q["headerType"] == "http") {
            throw ParseException("TCP HTTP obfuscation (headerType=http) qo'llab-quvvatlanmaydi")
        }
        // flow: faqat "xtls-rprx-vision" (masalan "xtls-rprx-vision-udp443" ni sing-box rad etadi);
        // Vision TLS/Reality talab qiladi — TLS'siz flow yuborilmaydi
        val rawFlow = q["flow"]?.takeIf { it.isNotEmpty() }
        val flow: String? = when {
            rawFlow == null -> null
            security != "tls" && security != "reality" -> null
            rawFlow.startsWith("xtls-rprx-vision") -> "xtls-rprx-vision"
            else -> throw ParseException("VLESS flow '$rawFlow' qo'llab-quvvatlanmaydi")
        }
        if (flow != null) o.put("flow", flow)
        o.put("packet_encoding", "xudp")

        if (security == "reality" || security == "tls") {
            val tls = baseTls(q, host)
            val fp = q["fp"]?.takeIf { it.isNotEmpty() }
            if (security == "reality") {
                val pbk = q["pbk"]?.takeIf { it.isNotEmpty() }
                    ?: throw ParseException("VLESS Reality: pbk yo'q")
                val r = JSONObject().put("enabled", true).put("public_key", pbk)
                q["sid"]?.let { r.put("short_id", it) }
                tls.put("reality", r)
                withUtls(tls, fp ?: "chrome")
            } else {
                // fp ko'rsatilmasa ham chrome (Go TLS barmoq izi bloklanadi, Vision'ga ham mos)
                withUtls(tls, fp ?: "chrome")
            }
            // ═══ DPI BYPASS: TLS Fragment ═══
            if (com.nurvpn.app.storage.AdvSettings.isFragmentEnabled(ctx)) {
                tls.put("fragment", true)
                tls.put("fragment_fallback_delay", "500ms")
                tls.put("record_fragment", true)
            }
            o.put("tls", tls)
        }
        // (tcp_fast_open majburiy yoqilmaydi: ko'p tarmoqlarda SYN+data tushib, ulanish osilib qoladi)
        // ═══ MUX ═══ — xtls-rprx-vision (flow) bilan birga ishlamaydi
        if (com.nurvpn.app.storage.AdvSettings.isMuxEnabled(ctx) && flow == null) {
            o.put("multiplex", JSONObject()
                .put("enabled", true)
                .put("protocol", "smux")
                .put("max_streams", 8)
                .put("padding", true))
        }
        addTransport(o, q)
        return o
    }

    private val VMESS_CIPHERS = setOf(
        "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305"
    )

    private fun vmess(link: String): JSONObject {
        val body = link.substringAfter("://").substringBefore("#")
        // vmess://UUID@host:port?query  (yangi format)
        if (body.contains("@")) return vmessUriStyle(link)

        // vmess://BASE64_JSON  (eski format, URL-safe ham bo'lishi mumkin)
        val json = decodeBase64Lenient(body) ?: throw ParseException("VMess: base64 xato")
        val v = JSONObject(json)
        val host = v.optString("add").ifEmpty { v.optString("address") }
        if (host.isEmpty()) throw ParseException("VMess: add (host) yo'q")

        val o = JSONObject()
        o.put("type", "vmess")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", v.optString("port").toIntOrNull() ?: 443)
        o.put("uuid", v.optString("id"))
        val scy = v.optString("scy").ifEmpty { "auto" }
        o.put("security", if (scy in VMESS_CIPHERS) scy else "auto")
        o.put("alter_id", v.optString("aid").toIntOrNull() ?: 0)

        val tlsFlag = v.optString("tls").lowercase()
        if (tlsFlag == "tls" || tlsFlag == "1" || tlsFlag == "true") {
            val tls = JSONObject()
            tls.put("enabled", true)
            val sni = cleanSni(v.optString("sni")) ?: cleanSni(v.optString("host")) ?: host
            tls.put("server_name", sni)
            val alpn = v.optString("alpn")
            if (alpn.isNotEmpty()) tls.put("alpn", alpnArray(alpn))
            withUtls(tls, v.optString("fp").ifEmpty { null })
            if (isTrue(v.optString("allowInsecure")) || isTrue(v.optString("insecure"))) {
                tls.put("insecure", true)
            }
            o.put("tls", tls)
        }

        val tq = HashMap<String, String>()
        val hostHdr = v.optString("host")
        val path = v.optString("path")
        when (val net = v.optString("net", "tcp").lowercase()) {
            "ws" -> {
                tq["type"] = "ws"; tq["path"] = path.ifEmpty { "/" }
                if (hostHdr.isNotEmpty()) tq["host"] = hostHdr
            }
            "grpc" -> {
                tq["type"] = "grpc"
                tq["serviceName"] = path.ifEmpty { v.optString("serviceName") }
            }
            "xhttp", "splithttp" -> {
                tq["type"] = "xhttp"; tq["path"] = path.ifEmpty { "/" }
                if (hostHdr.isNotEmpty()) tq["host"] = hostHdr
                tq["mode"] = v.optString("mode", "auto")
            }
            "httpupgrade" -> {
                tq["type"] = "httpupgrade"; tq["path"] = path.ifEmpty { "/" }
                if (hostHdr.isNotEmpty()) tq["host"] = hostHdr
            }
            "h2", "http" -> {
                tq["type"] = "http"; tq["path"] = path.ifEmpty { "/" }
                if (hostHdr.isNotEmpty()) tq["host"] = hostHdr
            }
            "tcp", "" -> { /* transport yo'q */ }
            else -> throw ParseException("VMess transport '$net' qo'llab-quvvatlanmaydi")
        }
        if (tq.isNotEmpty()) addTransport(o, tq)
        return o
    }

    private fun vmessUriStyle(link: String): JSONObject {
        val u = parseLink(link)
        val uuid = u.userInfo?.takeIf { it.isNotEmpty() } ?: throw ParseException("VMess: UUID yo'q")
        val host = u.host ?: throw ParseException("VMess: host yo'q")
        val port = if (u.port > 0) u.port else 443
        val q = parseQuery(u.query)

        val o = JSONObject()
        o.put("type", "vmess")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("uuid", uuid)
        val enc = q["encryption"]?.lowercase()
        o.put("security", if (enc != null && enc in VMESS_CIPHERS) enc else "auto")
        o.put("alter_id", 0)

        if (q["security"] == "tls" || q["tls"] == "tls") {
            val tls = baseTls(q, host)
            if (!tls.has("server_name") || q["sni"] == null) {
                tls.put("server_name", cleanSni(q["sni"]) ?: cleanSni(q["host"]) ?: host)
            }
            withUtls(tls, q["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome")
            o.put("tls", tls)
        }
        addTransport(o, q)
        return o
    }

    private fun trojan(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("Trojan: host yo'q")
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "trojan")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 443)
        o.put("password", u.userInfo ?: "")
        if (q["security"] != "none") {
            val tls = baseTls(q, host)
            withUtls(tls, q["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome")
            // TLS fragmentatsiya faqat link so'ragandagina (sukut bo'yicha tezlikni tushiradi)
            if (isTrue(q["fragment"])) {
                tls.put("fragment", true)
                tls.put("fragment_fallback_delay", "500ms")
            }
            o.put("tls", tls)
        }
        addTransport(o, q)
        return o
    }

    private fun shadowsocks(link: String): JSONObject {
        var body = link.substring(link.indexOf("://") + 3)
        val hash = body.indexOf('#')
        if (hash >= 0) body = body.substring(0, hash)
        var query = ""
        val qi = body.indexOf('?')
        if (qi >= 0) {
            query = body.substring(qi + 1)
            body = body.substring(0, qi)
        }
        body = body.trimEnd('/')

        val userInfo: String
        val hostPort: String
        if (body.contains('@')) {
            val rawUi = body.substringBeforeLast('@')
            hostPort = body.substringAfterLast('@')
            var ui = Uri.decode(rawUi)
            // method:password oddiy ko'rinishda bo'lmasa — URL-safe/oddiy base64
            if (!ui.contains(':')) ui = decodeBase64Lenient(ui) ?: ui
            userInfo = ui
        } else {
            val dec = decodeBase64Lenient(body) ?: throw ParseException("SS: base64 xato")
            if (!dec.contains('@')) throw ParseException("SS: format xato")
            userInfo = dec.substringBeforeLast('@')
            hostPort = dec.substringAfterLast('@')
        }

        val (host, port0) = splitHostPort(hostPort.substringBefore('/'))
        val port = if (port0 > 0) port0 else 8388
        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':', "")
        if (host.isEmpty()) throw ParseException("SS: host yo'q")
        if (method.isEmpty()) throw ParseException("SS: method yo'q")

        val o = JSONObject()
        o.put("type", "shadowsocks")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", port)
        o.put("method", method)
        o.put("password", password)

        if (query.isNotEmpty()) {
            val params = parseQuery(query)
            // plugin=v2ray-plugin;mode=websocket;host=x  →  plugin + plugin_opts
            params["plugin"]?.takeIf { it.isNotEmpty() }?.let { pl ->
                var name = pl.substringBefore(';')
                if (name == "simple-obfs") name = "obfs-local"
                if (name != "obfs-local" && name != "v2ray-plugin") {
                    throw ParseException("SS plugin '$name' qo'llab-quvvatlanmaydi")
                }
                val opts = pl.substringAfter(';', "")
                o.put("plugin", name)
                if (opts.isNotEmpty()) o.put("plugin_opts", opts)
            }
            if (isTrue(params["uot"])) {
                o.put("udp_over_tcp", JSONObject().put("enabled", true).put("version", 2))
            }
        }
        Log.i(TAG, "Shadowsocks: method=$method $host:$port")
        return o
    }

    /** ALPN ko'rsatilmagan bo'lsa transportga mosini majburlaydi (Xray ham shunday qiladi). */
    private fun forceAlpn(o: JSONObject, proto: String) {
        val tls = o.optJSONObject("tls") ?: return
        if (!tls.has("alpn")) tls.put("alpn", JSONArray().put(proto))
    }

    private fun addTransport(o: JSONObject, q: Map<String, String>) {
        val type = (q["type"] ?: q["net"] ?: return).lowercase()
        val t = JSONObject()
        when (type) {
            "", "tcp", "raw", "none" -> return
            "ws", "websocket" -> {
                t.put("type", "ws")
                forceAlpn(o, "http/1.1")
                val h = q["host"]
                if (!h.isNullOrEmpty()) {
                    t.put("headers", JSONObject().put("Host", JSONArray().put(h)))
                }
                t.put("path", q["path"]?.takeIf { it.isNotEmpty() } ?: "/")
            }
            "grpc" -> {
                t.put("type", "grpc")
                t.put("service_name", q["serviceName"] ?: q["service_name"] ?: "")
                // gRPC faqat HTTP/2 da ishlaydi: ALPN ko'rsatilmagan bo'lsa h2 majburlanadi
                // (Xray ham shunday qiladi). Reality + gRPC uchun ayniqsa muhim.
                forceAlpn(o, "h2")
            }
            "http", "h2" -> {
                t.put("type", "http")
                forceAlpn(o, "h2")
                val h = q["host"]
                if (!h.isNullOrEmpty()) {
                    val arr = JSONArray()
                    for (x in h.split(",")) if (x.trim().isNotEmpty()) arr.put(x.trim())
                    t.put("host", arr)
                }
                q["path"]?.takeIf { it.isNotEmpty() }?.let { t.put("path", it) }
            }
            "httpupgrade" -> {
                t.put("type", "httpupgrade")
                forceAlpn(o, "http/1.1")
                q["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("host", it) }
                q["path"]?.takeIf { it.isNotEmpty() }?.let { t.put("path", it) }
            }
            "xhttp", "splithttp" -> {
                t.put("type", "xhttp")
                q["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("host", it) }
                t.put("path", q["path"] ?: q["xhttpPath"] ?: "/")
                t.put("mode", q["mode"] ?: q["xhttpMode"] ?: "auto")
            }
            else -> throw ParseException("Transport '$type' qo'llab-quvvatlanmaydi")
        }
        o.put("transport", t)
    }

    // ═══════════════════════════════════════════════════════════
    // QOLGAN PROTOKOLLAR
    // ═══════════════════════════════════════════════════════════

    private fun socks(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("SOCKS: host yo'q")
        val o = JSONObject()
        o.put("type", "socks")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 1080)
        o.put("version", "5")
        userPass(u.userInfo)?.let {
            o.put("username", it.first)
            o.put("password", it.second)
        }
        return o
    }

    private fun httpProxy(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("HTTP: host yo'q")
        val o = JSONObject()
        o.put("type", "http")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 8080)
        userPass(u.userInfo)?.let {
            o.put("username", it.first)
            o.put("password", it.second)
        }
        if (link.lowercase().startsWith("https-proxy://")) {
            o.put("tls", JSONObject().put("enabled", true).put("server_name", host))
        }
        return o
    }

    private fun ssh(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("SSH: host yo'q")
        val o = JSONObject()
        o.put("type", "ssh")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 22)
        if (!u.userInfo.isNullOrEmpty()) {
            val p = u.userInfo.split(":", limit = 2)
            o.put("user", p.getOrElse(0) { "root" })
            o.put("password", p.getOrElse(1) { "" })
        } else {
            o.put("user", "root")
        }
        return o
    }

    private fun shadowsocksr(link: String): JSONObject {
        val body = link.substringAfter("://").substringBefore("#")
        val decoded = decodeBase64Lenient(body) ?: throw ParseException("SSR: base64 xato")
        val mainPart = decoded.substringBefore("/?")
        val parts = mainPart.split(":")
        if (parts.size < 6) throw ParseException("SSR: format xato")
        val password = decodeBase64Lenient(parts[5]) ?: parts[5]

        var protocolParam = ""
        var obfsParam = ""
        val paramStr = decoded.substringAfter("/?", "")
        if (paramStr.isNotEmpty()) {
            for (kv in paramStr.split("&")) {
                val p = kv.split("=", limit = 2)
                if (p.size != 2) continue
                val v = decodeBase64Lenient(p[1]) ?: ""
                when (p[0]) {
                    "protoparam" -> protocolParam = v
                    "obfsparam" -> obfsParam = v
                }
            }
        }
        val o = JSONObject()
        o.put("type", "shadowsocksr")
        o.put("tag", "proxy")
        o.put("server", parts[0])
        o.put("server_port", parts[1].toIntOrNull() ?: 8388)
        o.put("method", parts[3])
        o.put("password", password)
        o.put("protocol", parts[2])
        o.put("obfs", parts[4])
        if (protocolParam.isNotEmpty()) o.put("protocol_param", protocolParam)
        if (obfsParam.isNotEmpty()) o.put("obfs_param", obfsParam)
        return o
    }

    private fun shadowtls(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("ShadowTLS: host yo'q")
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "shadowtls")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 443)
        o.put("version", q["version"]?.toIntOrNull() ?: 3)
        o.put("password", u.userInfo ?: "")
        o.put("tls", baseTls(q, host))
        return o
    }

    private fun anytls(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("AnyTLS: host yo'q")
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "anytls")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 443)
        o.put("password", u.userInfo ?: "")
        o.put("tls", baseTls(q, host))
        return o
    }

    private fun juicity(link: String): JSONObject {
        val u = parseLink(link)
        val host = u.host ?: throw ParseException("Juicity: host yo'q")
        val q = parseQuery(u.query)
        val o = JSONObject()
        o.put("type", "juicity")
        o.put("tag", "proxy")
        o.put("server", host)
        o.put("server_port", if (u.port > 0) u.port else 443)
        val ui = u.userInfo ?: ""
        o.put("uuid", ui.substringBefore(':'))
        if (ui.contains(':')) o.put("password", ui.substringAfter(':'))
        o.put("tls", baseTls(q, host, "h3"))
        q["congestion_control"]?.takeIf { it.isNotEmpty() }
            ?.let { o.put("congestion_control", it) }
        return o
    }

    // ═══════════════════════════════════════════════════════════
    // AmneziaWG
    // ═══════════════════════════════════════════════════════════

    /** AWG qiymati raqam bo'lsa Long (uint32 ham sig'adi), aks holda (masalan "100-200" diapazon) matn. */
    private fun numOrStr(v: String): Any = v.toLongOrNull() ?: v

    private class AwgParsed(
        val endpoint: JSONObject, val dns: List<String>, val host: String,
        val mtu: Int, val hasV6: Boolean
    )

    @Throws(ParseException::class)
    private fun parseAwg(rawConf: String, ipv6Blocked: Boolean): AwgParsed {
        val o = JSONObject()
        o.put("type", "wireguard")
        o.put("tag", "proxy")

        var inIface = false
        var inPeer = false
        val localAddrs = ArrayList<String>()
        val allowedIps = JSONArray()
        val dnsList = ArrayList<String>()
        var host = ""
        var port = 51820
        var mtu = 1280
        var keepalive = 0
        var reserved: List<Int> = emptyList()
        var pub: String? = null
        var psk: String? = null

        for (raw in rawConf.lines()) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue
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
                    for (a in v.split(",")) if (a.trim().isNotEmpty()) localAddrs.add(a.trim())
                k.equals("DNS", true) && inIface ->
                    for (d in v.split(",")) if (d.trim().isNotEmpty()) dnsList.add(d.trim())
                k.equals("MTU", true) && inIface -> mtu = (v.toIntOrNull() ?: 1280).coerceIn(576, 1500)

                // ★ AWG 1.x / 2.0 parametrlari
                k.equals("Jc", true) -> o.put("jc", v.toIntOrNull() ?: 0)
                k.equals("Jmin", true) -> o.put("jmin", v.toIntOrNull() ?: 0)
                k.equals("Jmax", true) -> o.put("jmax", v.toIntOrNull() ?: 0)
                k.equals("S1", true) -> o.put("s1", v.toIntOrNull() ?: 0)
                k.equals("S2", true) -> o.put("s2", v.toIntOrNull() ?: 0)
                k.equals("S3", true) -> o.put("s3", v.toIntOrNull() ?: 0)
                k.equals("S4", true) -> o.put("s4", v.toIntOrNull() ?: 0)
                // ★ H1..H4 — uint32 (Int'dan katta bo'lishi mumkin!) yoki diapazon
                k.equals("H1", true) -> o.put("h1", numOrStr(v))
                k.equals("H2", true) -> o.put("h2", numOrStr(v))
                k.equals("H3", true) -> o.put("h3", numOrStr(v))
                k.equals("H4", true) -> o.put("h4", numOrStr(v))
                k.equals("I1", true) -> o.put("i1", v)
                k.equals("I2", true) -> o.put("i2", v)
                k.equals("I3", true) -> o.put("i3", v)
                k.equals("I4", true) -> o.put("i4", v)
                k.equals("I5", true) -> o.put("i5", v)
                // AWG 2.0 masquerade sugar (WireSock-style)
                k.equals("Id", true) -> o.put("id", v)
                k.equals("Ip", true) -> o.put("ip", v)
                k.equals("Ib", true) -> o.put("ib", v)

                k.equals("PublicKey", true) && inPeer -> pub = v
                k.equals("PresharedKey", true) && inPeer -> psk = v
                k.equals("Endpoint", true) && inPeer -> {
                    val (h, p) = splitHostPort(v)
                    host = h
                    port = if (p > 0) p else 51820
                }
                k.equals("AllowedIPs", true) && inPeer ->
                    for (ip in v.split(",")) if (ip.trim().isNotEmpty()) allowedIps.put(ip.trim())
                k.equals("PersistentKeepalive", true) && inPeer -> keepalive = v.toIntOrNull() ?: 0
                k.equals("Reserved", true) && inPeer -> {
                    val clean = v.removePrefix("[").removeSuffix("]")
                    val parts = clean.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    reserved = if (parts.isNotEmpty() && parts.all { it.toIntOrNull() != null }) {
                        parts.mapNotNull { it.toIntOrNull() }
                    } else {
                        try {
                            Base64.decode(v, Base64.DEFAULT).map { it.toInt() and 0xFF }
                        } catch (_: Throwable) { emptyList() }
                    }
                }
            }
        }

        if (host.isEmpty()) throw ParseException("AWG: Endpoint yo'q")
        if (!o.has("private_key")) throw ParseException("AWG: PrivateKey yo'q")
        val pubKey = pub ?: throw ParseException("AWG: Peer PublicKey yo'q")

        val addrList = JSONArray()
        for (a in localAddrs) {
            val isV6 = a.contains(":")
            if (isV6 && ipv6Blocked) continue
            when {
                a.contains("/") -> addrList.put(a)
                isV6 -> addrList.put("$a/128")
                a.matches(Regex("^[0-9.]+$")) -> addrList.put("$a/32")
            }
        }
        if (addrList.length() == 0) addrList.put("10.0.0.2/32")
        o.put("address", addrList)
        o.put("mtu", mtu)
        // ★ "dns" endpoint maydoni EMAS (sing-box noma'lum maydonni rad etadi) —
        //   DNS buildAwgConfig'da dns.servers ga o'tkaziladi.

        val peer = JSONObject()
        peer.put("address", host)
        peer.put("port", port)
        peer.put("public_key", pubKey)
        psk?.let { peer.put("pre_shared_key", it) }
        if (allowedIps.length() > 0) peer.put("allowed_ips", allowedIps)
        else peer.put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        if (keepalive > 0) peer.put("persistent_keepalive_interval", keepalive)
        if (reserved.isNotEmpty()) {
            val rArr = JSONArray()
            for (n in reserved) rArr.put(n)
            peer.put("reserved", rArr)
        }
        o.put("peers", JSONArray().put(peer))
        Log.i(TAG, "AWG: $host:$port mtu=$mtu addrs=${addrList.length()}")
        Log.i(TAG, "AWG JSON: " + o.toString())
        var v6 = false
        for (i in 0 until addrList.length()) if (addrList.getString(i).contains(":")) v6 = true
        return AwgParsed(o, dnsList, host, mtu, v6)
    }

    @Throws(ParseException::class)
    private fun isMasqueConf(rawConf: String): Boolean {
        // MASQUE config: [Interface]/[Peer] yo'q, lekin PublicKey + PrivateKey + Server/Endpoint bor
        if (rawConf.contains("[Interface]") || rawConf.contains("[Peer]")) return false
        if (!rawConf.contains("PrivateKey")) return false
        if (!rawConf.contains("PublicKey")) return false
        if (!rawConf.contains("Server") && !rawConf.contains("Endpoint")) return false
        // AWG'ga xos maydonlar bo'lmasin
        if (rawConf.contains("Jc =") || rawConf.contains("Jmin =")) return false
        return true
    }

    fun awgOutbound(rawConf: String): JSONObject {
        if (isMasqueConf(rawConf)) {
            Log.i(TAG, "MASQUE detected in awgOutbound")
            return parseMasque(rawConf, false)
        }
        return parseAwg(rawConf, false).endpoint
    }

    // ═══════════════════════════════════════════════════════════
    // MASQUE (Cloudflare WARP CONNECT-IP, RFC 9484)
    // ═══════════════════════════════════════════════════════════

    private fun parseMasque(rawConf: String, ipv6Blocked: Boolean): JSONObject {
        val o = JSONObject()
        o.put("type", "masque")
        o.put("tag", "proxy")
        o.put("profile", "cloudflare")
        o.put("network", "h2")

        var server = ""
        var port = 443
        var sni = ""
        var privateKey = ""
        var publicKey = ""
        var ip = ""
        var ipv6 = ""
        var mtu = 1280

        for (raw in rawConf.lines()) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
            val eq = t.indexOf('=')
            if (eq < 0) continue
            val k = t.substring(0, eq).trim()
            val v = t.substring(eq + 1).trim()
            if (v.isEmpty()) continue
            when {
                k.equals("Server", true) || k.equals("Endpoint", true) -> {
                    val (h, p) = splitHostPort(v)
                    server = h
                    if (p > 0) port = p
                }
                k.equals("Port", true) -> port = v.toIntOrNull() ?: 443
                k.equals("SNI", true) -> sni = v
                k.equals("PrivateKey", true) -> privateKey = v
                k.equals("PublicKey", true) -> publicKey = v
                k.equals("Address", true) -> {
                    for (a in v.split(",")) {
                        val clean = a.trim().substringBefore("/")
                        if (clean.isEmpty()) continue
                        if (clean.contains(":")) {
                            if (!ipv6Blocked) ipv6 = clean
                        } else {
                            ip = clean
                        }
                    }
                }
                k.equals("MTU", true) -> mtu = (v.toIntOrNull() ?: 1280).coerceIn(576, 1500)
                k.equals("Profile", true) -> o.put("profile", v.lowercase())
                k.equals("Network", true) -> o.put("network", v.lowercase())
            }
        }

        if (server.isEmpty()) throw ParseException("MASQUE: Server yo'q")
        if (privateKey.isEmpty()) throw ParseException("MASQUE: PrivateKey yo'q")
        if (publicKey.isEmpty()) throw ParseException("MASQUE: PublicKey yo'q")
        if (ip.isEmpty() && ipv6.isEmpty()) throw ParseException("MASQUE: Address yo'q")

        o.put("server", server)
        o.put("server_port", port)
        o.put("private_key", privateKey)
        o.put("public_key", publicKey)
        if (ip.isNotEmpty()) o.put("ip", ip)
        if (ipv6.isNotEmpty() && !ipv6Blocked) o.put("ipv6", ipv6)
        o.put("mtu", mtu)
        if (sni.isNotEmpty()) o.put("sni", sni)

        Log.i(TAG, "MASQUE: $server:$port sni=$sni mtu=$mtu profile=${o.optString("profile")} network=${o.optString("network")}")
        Log.i(TAG, "MASQUE JSON: " + o.toString())
        return o
    }

    @Throws(ParseException::class)
    fun masqueOutbound(rawConf: String): JSONObject = parseMasque(rawConf, false)

    @Throws(ParseException::class)
    fun buildMasqueConfig(
        rawConf: String,
        dns: String = "1.1.1.1",
        ipv6Blocked: Boolean = false
    ): JSONObject {
        // ★ MASQUE IPv6 ni QO'LLAB-QUVVATLAYDI (Cloudflare WARP).
        //   Config'da IPv6 manzil bo'lsa — MASQUE JSON'ga qo'shamiz,
        //   TUN'ga IPv6 beramiz. Telegram IPv6 DC orqali ishlaydi.
        val proxy = parseMasque(rawConf, false)
        val hasV6 = proxy.has("ipv6") && proxy.optString("ipv6").isNotEmpty()
        android.util.Log.i("NurVPN-CFG", "MASQUE hasV6=$hasV6 ipv6=${proxy.optString("ipv6")}")
        return assemble(proxy, false, dns, null, !hasV6, 1400, hasV6, "gvisor")
    }

    // ═══════════════════════════════════════════════════════════
    // TO'LIQ KONFIGURATSIYA
    // ═══════════════════════════════════════════════════════════

    private fun assemble(
        proxy: JSONObject,
        asEndpoint: Boolean,
        remoteDns: String,
        serverHost: String?,
        ipv6Blocked: Boolean,
        tunMtu: Int = 1400,
        tunV6: Boolean = !ipv6Blocked,
        stack: String = "gvisor",
        fakeDns: Boolean = false
    ): JSONObject {
        val root = JSONObject()

        // ═══ DNS ═══
        // dns-remote : VPN ichidan (proxy orqali)
        // dns-direct : server domenini ulanishdan OLDIN hal qilish uchun.
        //   ★ "local" tur ishlatilmaydi: PlatformInterface.localDNSTransport() = null
        //     bo'lganda Android'da /etc/resolv.conf yo'q va resolve ishlamaydi.
        // TCP asosidagi proxy'lar (VLESS/VMess/Trojan/SS/HTTP/SSH/SOCKS...) — DNS ni TCP orqali yuboramiz:
        // serverda UDP (xudp) yopiq bo'lsa DNS UDP'da yo'qolib, "VPN yoqilgan, internet yo'q" bo'lardi.
        // UDP-native (Hysteria2, TUIC, WireGuard) — UDP DNS qoladi.
        val noUdp = !asEndpoint && proxy.optString("type") in
            setOf("http", "ssh", "socks", "vless", "vmess", "trojan", "shadowsocks",
                "shadowsocksr", "anytls", "shadowtls")
        val servers = JSONArray()
            .put(JSONObject()
                .put("type", if (noUdp) "tcp" else "udp").put("tag", "dns-remote")
                .put("server", remoteDns).put("detour", "proxy"))
            .put(JSONObject()
                .put("type", "udp").put("tag", "dns-direct")
                .put("server", BOOTSTRAP_DNS))
        val dnsRules = JSONArray()
        // Server domeni HAR DOIM avval (VPN'siz) hal qilinadi
        if (!serverHost.isNullOrEmpty() && !isIpAddress(serverHost)) {
            dnsRules.put(JSONObject()
                .put("domain", JSONArray().put(serverHost))
                .put("action", "route")
                .put("server", "dns-direct"))
        }
        // ═══ FAKEDNS (faqat Sozlamalarda yoqilganda) ═══
        //  AAAA (soxta IPv6) faqat tunnelda IPv6 bo'lsagina — aks holda ilovalar
        //  mavjud bo'lmagan IPv6 manzilga ulanib, osilib qoladi.
        if (fakeDns) {
            val fakeIpServer = JSONObject()
                .put("type", "fakeip")
                .put("tag", "dns-fakeip")
                .put("inet4_range", "198.18.0.0/15")
            val qTypes = JSONArray().put("A")
            if (tunV6) {
                fakeIpServer.put("inet6_range", "fc00::/18")
                qTypes.put("AAAA")
            }
            servers.put(fakeIpServer)
            dnsRules.put(JSONObject()
                .put("query_type", qTypes)
                .put("action", "route")
                .put("server", "dns-fakeip"))
        }
        val dns = JSONObject()
            .put("servers", servers)
            .put("final", "dns-remote")
            .put("strategy", if (ipv6Blocked || !tunV6) "ipv4_only" else "prefer_ipv4")
        if (dnsRules.length() > 0) dns.put("rules", dnsRules)
        root.put("dns", dns)

        // ═══ LOG ═══ (fayl yo'q — cheksiz o'sib ketmasin)
        root.put("log", JSONObject().put("level", "debug").put("timestamp", true))

        // ═══ INBOUND: TUN ═══
        val addrs = JSONArray().put("172.19.0.1/30")
        if (tunV6) addrs.put("fdfe:dcba:9876::1/126")
        root.put("inbounds", JSONArray().put(JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", addrs)
            .put("mtu", tunMtu)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", stack)))

        // ═══ OUTBOUND / ENDPOINT ═══
        val direct = JSONObject().put("type", "direct").put("tag", "direct")
        if (asEndpoint) {
            root.put("endpoints", JSONArray().put(proxy))
            root.put("outbounds", JSONArray().put(direct))
        } else {
            root.put("outbounds", JSONArray().put(proxy).put(direct))
        }

        // ═══ ROUTE ═══
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
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
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        // ★ IPv6 reject endi kerak emas — MASQUE IPv6 ni o'tkazadi.
        // ═══ FAQAT YouTube uchun QUIC bloklash ═══
        rules.put(JSONObject()
            .put("domain_suffix", JSONArray()
                .put(".youtube.com")
                .put(".googlevideo.com")
                .put(".ytimg.com")
                .put(".youtu.be")
                .put(".ggpht.com"))
            .put("port", 443)
            .put("network", "udp")
            .put("action", "reject"))

        root.put("route", JSONObject()
            .put("rules", rules)
            .put("final", "proxy")
            .put("auto_detect_interface", true)
            // ★ Outbound server domenini to'g'ridan-to'g'ri (VPN'siz) hal qilamiz.
            //   Avval "dns-remote" edi → proxy server manzilini proxy orqali
            //   izlardi (aylanma bog'liqlik) va domenli serverlar ulanmasdi.
            .put("default_domain_resolver", JSONObject().put("server", "dns-direct")))

        return root
    }

    @Throws(ParseException::class)
    fun buildAwgConfig(
        rawConf: String,
        dns: String = "1.1.1.1",
        ipv6Blocked: Boolean = false
    ): JSONObject {
        // MASQUE auto-detection
        if (isMasqueConf(rawConf)) {
            Log.i(TAG, "MASQUE config detected, routing to buildMasqueConfig")
            return buildMasqueConfig(rawConf, dns, ipv6Blocked)
        }
        val a = parseAwg(rawConf, ipv6Blocked)
        val remoteDns = a.dns.firstOrNull { isIpAddress(it) && !it.contains(":") } ?: dns
        // ★ TUN MTU <= WireGuard MTU, aks holda katta TCP paketlar (brauzer TLS,
        //   Telegram) tunnelga sig'maydi va tushib ketadi (QUIC kichik — ishlaydi).
        //   AWG sarlavha/junk qo'shadi, shuning uchun yuqori chegara 1280.
        val tunMtu = minOf(a.mtu, 1200)
        // Config'da IPv6 manzil bo'lmasa, tunnelda IPv6 yo'q: AAAA javoblar va IPv6 ulanishlar osilib qolmasin
        return assemble(a.endpoint, true, remoteDns, a.host, ipv6Blocked, tunMtu, a.hasV6 && !ipv6Blocked,
            // ★ gvisor: TCP ham, UDP ham userspace'da. "gvisor" TCP'ni yadro (system) stack'iga
            //   beradi — u Android'da (o'z paketimiz VPN'dan chiqarilgani uchun) javob paketlari
            //   yo'qolib, TCP umuman ishlamasligi, UDP/QUIC esa ishlashi mumkin.
            "gvisor")
    }

    @Throws(ParseException::class)
    fun buildFullConfig(
        link: String,
        dns: String,
        @Suppress("UNUSED_PARAMETER") cachePath: String = "",
        ipv6Blocked: Boolean = false,
        ctx: android.content.Context? = null
    ): JSONObject {
        val c = ctx ?: throw ParseException("ctx yo'q")
        val proxy = outboundFor(link, c)
        val fake = com.nurvpn.app.storage.AdvSettings.isFakeDnsEnabled(c)
        // ★ Protokolga qarab TUN MTU:
        //   QUIC (Hysteria2/TUIC/Juicity) → 1280 (katta overhead)
        //   TCP (VLESS/VMess/Trojan) → 1400
        val protoType = proxy.optString("type")
        val mtu = when (protoType) {
            "hysteria2", "tuic", "juicity" -> 1280
            else -> 1400
        }
        Log.i(TAG, "TUN MTU: $mtu ($protoType)")
        return assemble(proxy, false, dns, proxy.optString("server"), ipv6Blocked,
            tunMtu = mtu,
            fakeDns = fake)
    }
}
