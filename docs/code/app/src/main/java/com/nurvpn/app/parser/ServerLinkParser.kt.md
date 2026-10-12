# `app/src/main/java/com/nurvpn/app/parser/ServerLinkParser.kt`

```kotlin
package com.nurvpn.app.parser

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.util.CountryLookup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

object ServerLinkParser {

    /** vmess:// yoki boshqa linkdan ServerItem yasaydi. null — xato. */
    fun parse(link: String, subId: String? = null): ServerItem? {
        if (link.isEmpty()) return null
        return try {
            val trimmed = link.trim()
            val lower = trimmed.lowercase()
            val si = when {
                // Xray JSON config
                trimmed.startsWith("{") -> parseXrayJson(trimmed, subId)

                // VMess (maxsus parser — base64 yoki URI-style)
                lower.startsWith("vmess://") -> parseVmess(trimmed, subId)

                // SSR (maxsus parser — base64)
                lower.startsWith("ssr://") -> parseSsr(trimmed, subId)

                // SS (maxsus parser — base64 userInfo)
                lower.startsWith("ss://") -> parseSs(trimmed, subId)

                // Boshqa barcha protokollar — standart parser
                lower.startsWith("vless://") ||
                lower.startsWith("hysteria2://") ||
                lower.startsWith("hy2://") ||
                lower.startsWith("tuic://") ||
                lower.startsWith("juicity://") ||
                lower.startsWith("trojan://") ||
                lower.startsWith("socks://") ||
                lower.startsWith("socks5://") ||
                lower.startsWith("http-proxy://") ||
                lower.startsWith("https-proxy://") ||
                lower.startsWith("ssh://") ||
                lower.startsWith("shadowtls://") ||
                lower.startsWith("anytls://") -> parseStandard(trimmed, subId)

                else -> {
                    android.util.Log.w("NurVPN-PARSE",
                        "Noma'lum protokol: ${trimmed.take(40)}")
                    null
                }
            }
            si?.ensureTransport()
            si
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-PARSE", "parse fail: ${t.message}", t)
            null
        }
    }

    /** "443,20000-30000" kabi matndan boshidagi port raqami. */
    private fun leadingPort(s: String): Int = s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    /** IPv6-safe host:port ajratish. [::1]:443 -> ("::1", 443). */
    private fun splitHostPort(hp: String): Pair<String, Int> {
        val clean = hp.trim().removeSuffix("/")
        if (clean.startsWith("[")) {
            val end = clean.indexOf(']')
            if (end < 0) return clean to 0
            val host = clean.substring(1, end)
            val portStr = clean.substring(end + 1).removePrefix(":")
            return host to (leadingPort(portStr))
        }
        val c = clean.lastIndexOf(':')
        if (c <= 0) return clean to 0
        return clean.substring(0, c) to (leadingPort(clean.substring(c + 1)))
    }

    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun uriHost(addr: String): String =
        if (addr.contains(':') && !addr.startsWith("[")) "[$addr]" else addr

    /** Xray JSON config (vless / vmess / trojan / shadowsocks) → URI → ServerItem. */
    private fun parseXrayJson(json: String, subId: String?): ServerItem? {
        return try {
            val root = org.json.JSONObject(json)
            val outbounds = root.optJSONArray("outbounds") ?: return null
            var found: org.json.JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val o = outbounds.optJSONObject(i) ?: continue
                if (o.optString("protocol", "") in PROXY_PROTOCOLS) { found = o; break }
            }
            val proxy = found ?: return null
            val settings = proxy.optJSONObject("settings") ?: return null
            val stream = proxy.optJSONObject("streamSettings")

            val keys = listOf("remarks", "remark", "tag", "name", "title", "label",
                "ps", "displayName", "serverName", "country")
            fun find(o: org.json.JSONObject, ks: List<String>): String? =
                ks.firstNotNullOfOrNull { k ->
                    val v = o.optString(k, "")
                    if (v.isNotBlank() && !v.startsWith("http") && v.lowercase() != "proxy") v else null
                }
            val remark = find(root, keys) ?: find(proxy, keys.take(6)) ?: ""

            val link = when (proxy.optString("protocol")) {
                "vless" -> buildVlessUri(settings, stream, remark)
                "vmess" -> buildVmessUri(settings, stream, remark)
                "trojan" -> buildTrojanUri(settings, stream, remark)
                "shadowsocks" -> buildSsUri(settings, remark)
                else -> null
            } ?: return null
            parse(link, subId)
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-PARSE", "Xray JSON parse xato: ${t.message}")
            null
        }
    }

    /** Xray streamSettings → URI query parametrlari (hamma protokollar uchun umumiy). */
    private fun streamParams(stream: org.json.JSONObject?): MutableList<String> {
        val params = mutableListOf<String>()
        val net = (stream?.optString("network", "tcp") ?: "tcp").ifEmpty { "tcp" }
        val security = (stream?.optString("security", "none") ?: "none").ifEmpty { "none" }
        params.add("type=$net")

        when (net) {
            "xhttp", "splithttp" -> {
                val xh = stream?.optJSONObject("xhttpSettings")
                    ?: stream?.optJSONObject("splithttpSettings")
                val host = xh?.optString("host", "").orEmpty()
                val path = xh?.optString("path", "/").orEmpty().ifEmpty { "/" }
                val mode = xh?.optString("mode", "auto").orEmpty().ifEmpty { "auto" }
                if (host.isNotEmpty()) params.add("host=${enc(host)}")
                params.add("path=${enc(path)}")
                params.add("mode=$mode")
            }
            "ws" -> {
                val ws = stream?.optJSONObject("wsSettings")
                var host = ws?.optString("host", "").orEmpty()
                if (host.isEmpty()) {
                    host = ws?.optJSONObject("headers")?.optString("Host", "").orEmpty()
                }
                val path = ws?.optString("path", "").orEmpty()
                if (host.isNotEmpty()) params.add("host=${enc(host)}")
                if (path.isNotEmpty()) params.add("path=${enc(path)}")
            }
            "grpc" -> {
                val sn = stream?.optJSONObject("grpcSettings")
                    ?.optString("serviceName", "").orEmpty()
                if (sn.isNotEmpty()) params.add("serviceName=${enc(sn)}")
            }
            "httpupgrade" -> {
                val hu = stream?.optJSONObject("httpupgradeSettings")
                val host = hu?.optString("host", "").orEmpty()
                val path = hu?.optString("path", "").orEmpty()
                if (host.isNotEmpty()) params.add("host=${enc(host)}")
                if (path.isNotEmpty()) params.add("path=${enc(path)}")
            }
            "http", "h2" -> {
                val hs = stream?.optJSONObject("httpSettings")
                val arr = hs?.optJSONArray("host")
                val host = if (arr != null) {
                    (0 until arr.length()).map { arr.optString(it) }
                        .filter { it.isNotEmpty() }.joinToString(",")
                } else hs?.optString("host", "").orEmpty()
                val path = hs?.optString("path", "").orEmpty()
                if (host.isNotEmpty()) params.add("host=${enc(host)}")
                if (path.isNotEmpty()) params.add("path=${enc(path)}")
            }
        }

        params.add("security=$security")
        when (security) {
            "reality" -> {
                val r = stream?.optJSONObject("realitySettings")
                if (r != null) {
                    val sni = r.optString("serverName", "")
                    val pbk = r.optString("publicKey", "")
                    val sid = r.optString("shortId", "")
                    val fp = r.optString("fingerprint", "").ifEmpty { "chrome" }
                    if (sni.isNotEmpty()) params.add("sni=${enc(sni)}")
                    if (pbk.isNotEmpty()) params.add("pbk=${enc(pbk)}")
                    if (sid.isNotEmpty()) params.add("sid=${enc(sid)}")
                    params.add("fp=$fp")
                }
            }
            "tls" -> {
                val t = stream?.optJSONObject("tlsSettings")
                if (t != null) {
                    val sni = t.optString("serverName", "")
                        .removePrefix("https://").removePrefix("http://")
                        .removePrefix("://").trimEnd('/')
                    val fp = t.optString("fingerprint", "")
                    val alpn = t.optJSONArray("alpn")
                    if (sni.isNotEmpty()) params.add("sni=${enc(sni)}")
                    if (fp.isNotEmpty()) params.add("fp=$fp")
                    if (alpn != null && alpn.length() > 0) {
                        val joined = (0 until alpn.length()).map { alpn.optString(it) }
                            .filter { it.isNotEmpty() }.joinToString(",")
                        if (joined.isNotEmpty()) params.add("alpn=${enc(joined)}")
                    }
                    if (t.optBoolean("allowInsecure", false)) params.add("insecure=1")
                }
            }
        }
        return params
    }

    private fun nameOf(remark: String?, addr: String): String =
        enc(remark?.takeIf { it.isNotBlank() } ?: addr)

    /** Xray VLESS settings → vless:// URI. */
    private fun buildVlessUri(settings: org.json.JSONObject, stream: org.json.JSONObject?,
                              remark: String? = null): String? {
        val node = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
        val addr = node.optString("address", "")
        val port = node.optInt("port", 443)
        val user = node.optJSONArray("users")?.optJSONObject(0) ?: return null
        val uuid = user.optString("id", "")
        if (addr.isEmpty() || uuid.isEmpty()) return null
        val flow = user.optString("flow", "")

        val params = mutableListOf("encryption=none")
        if (flow.isNotEmpty()) params.add("flow=${enc(flow)}")
        params.addAll(streamParams(stream))
        return "vless://$uuid@${uriHost(addr)}:$port?${params.joinToString("&")}#${nameOf(remark, addr)}"
    }

    /** Xray VMess settings → vmess://uuid@host:port?... (URI uslubi). */
    private fun buildVmessUri(settings: org.json.JSONObject, stream: org.json.JSONObject?,
                              remark: String? = null): String? {
        val node = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
        val addr = node.optString("address", "")
        val port = node.optInt("port", 443)
        val user = node.optJSONArray("users")?.optJSONObject(0) ?: return null
        val id = user.optString("id", "")
        if (addr.isEmpty() || id.isEmpty()) return null
        val cipher = user.optString("security", "auto").ifEmpty { "auto" }

        val params = mutableListOf("encryption=${enc(cipher)}")
        params.addAll(streamParams(stream))
        return "vmess://$id@${uriHost(addr)}:$port?${params.joinToString("&")}#${nameOf(remark, addr)}"
    }

    /** Xray Trojan settings → trojan:// URI. */
    private fun buildTrojanUri(settings: org.json.JSONObject, stream: org.json.JSONObject?,
                               remark: String? = null): String? {
        val srv = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
        val addr = srv.optString("address", "")
        val pw = srv.optString("password", "")
        val port = srv.optInt("port", 443)
        if (addr.isEmpty() || pw.isEmpty()) return null
        val params = streamParams(stream)
        return "trojan://${enc(pw)}@${uriHost(addr)}:$port?${params.joinToString("&")}#${nameOf(remark, addr)}"
    }

    /** Xray Shadowsocks settings → ss://BASE64URL(method:password)@host:port. */
    private fun buildSsUri(settings: org.json.JSONObject, remark: String? = null): String? {
        val srv = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
        val addr = srv.optString("address", "")
        val method = srv.optString("method", "")
        val pw = srv.optString("password", "")
        val port = srv.optInt("port", 8388)
        if (addr.isEmpty() || method.isEmpty()) return null
        val ui = Base64.encodeToString("$method:$pw".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "ss://$ui@${uriHost(addr)}:$port#${nameOf(remark, addr)}"
    }

    private fun parseVmess(link: String, subId: String?): ServerItem? {
        // vmess://BASE64_JSON  yoki  vmess://BASE64_JSON#remark
        var payload = link.removePrefix("vmess://")
        var remark: String? = null
        val hash = payload.indexOf('#')
        if (hash > 0) {
            remark = try { java.net.URLDecoder.decode(
                payload.substring(hash + 1), "UTF-8") } catch (t: Throwable) {
                payload.substring(hash + 1) }
            payload = payload.substring(0, hash)
        }
        // Ba'zi subscriptionlar vmess://uuid@host:port?... ko'rinishida
        // keladi. Bu Base64 emas, shuning uchun avval oddiy formatni tekshiramiz.
        if (payload.contains("@")) {
            return parseVmessV1(payload, remark, subId, link)
        }

        // URL-safe base64 varianti ham bo'lishi mumkin
        val decoded = try {
            val cleaned = payload.replace("-", "+").replace("_", "/")
            val padded = when (cleaned.length % 4) {
                2 -> "$cleaned=="
                3 -> "$cleaned="
                else -> cleaned
            }
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT),
                Charsets.UTF_8)
        } catch (t: Throwable) {
            // Fallback: URL_SAFE flag bilan urinib ko'ramiz
            try {
                String(android.util.Base64.decode(payload,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),
                    Charsets.UTF_8)
            } catch (t2: Throwable) {
                android.util.Log.e("NurVPN-PARSE", "vmess base64 fail: ${t.message}")
                return null
            }
        }
        val json = try {
            org.json.JSONObject(decoded)
        } catch (t: Throwable) {
            // Ba'zi vmess'lar vmess://base64(field:value|...) shaklida
            return parseVmessV1(decoded, remark, subId, link)
        }

        val si = ServerItem(link)
        si.subId = subId
        si.protocol = Protocol.VMESS
        si.host = json.optString("add", "").ifEmpty {
            json.optString("address", "").ifEmpty { null }
        }
        val portStr = json.optString("port", "0").ifEmpty { "0" }
        si.port = portStr.toIntOrNull() ?: 0
        si.remark = remark ?: json.optString("ps", "").ifEmpty {
            json.optString("remarks", "").ifEmpty { si.host }
        }
        val cc = CountryLookup.lookup(si.host)
        si.countryCode = cc[0]
        si.country = cc[1]
        return si
    }

    private fun parseVmessV1(decoded: String, remark: String?,
                             subId: String?, originalLink: String): ServerItem? {
        // Format: "auto:uuid@host:port" yoki "method:uuid@host:port"
        val at = decoded.indexOf('@')
        if (at <= 0) return null
        var after = decoded.substring(at + 1)

        // Query va path port qiymatiga aralashmasin:
        // uuid@host:443?type=tcp yoki uuid@host:443/path
        val query = after.indexOf('?')
        if (query >= 0) after = after.substring(0, query)

        val slash = after.indexOf('/')
        if (slash > 0) after = after.substring(0, slash)

        val (h, p) = splitHostPort(after)
        if (h.isEmpty() || p <= 0) return null
        val si = ServerItem(originalLink)
        si.subId = subId
        si.protocol = Protocol.VMESS
        si.host = h
        si.port = p
        si.remark = remark ?: si.host
        val cc = CountryLookup.lookup(si.host)
        si.countryCode = cc[0]
        si.country = cc[1]
        return si
    }

    private fun parseStandard(link: String, subId: String?): ServerItem? {
        // Placeholder filtri: 0.0.0.0:1 yoki 00000000-uuid
        if (link.contains("00000000-0000-0000-0000-000000000000") ||
            link.contains("@0.0.0.0:1?") ||
            link.contains("@0.0.0.0:1/")) {
            android.util.Log.w("NurVPN-PARSE", "placeholder link rad etildi")
            return null
        }
        val si = ServerItem(link)
        si.subId = subId
        si.protocol = Protocol.fromUri(link)
        android.util.Log.i("NurVPN-PARSE",
            "detect: link=${link.take(40)} → ${si.protocol.name}")
        var body = link
        val hash = body.indexOf('#')
        if (hash > 0) {
            si.remark = try { android.net.Uri.decode(body.substring(hash + 1)) }
                        catch (t: Throwable) { body.substring(hash + 1) }
            body = body.substring(0, hash)
        }
        // Authority: [user@]host:port — user@ bo'lmasa ham (masalan socks5://host:1080)
        run {
            val auth = body.substringAfter("://").substringBefore('?').substringBefore('/')
            val hp = auth.substringAfterLast('@')
            if (hp.isNotEmpty()) {
                val (h, p) = splitHostPort(hp)
                si.host = h
                si.port = if (p > 0) p else 0
            }
        }
        if (si.remark.isNullOrEmpty()) si.remark = si.host
        // SNI tozalash: "://unsplash.com" → "unsplash.com"
        try {
            val qidx = body.indexOf('?')
            if (qidx > 0) {
                val qs = body.substring(qidx + 1).substringBefore('/')
                for (pair in qs.split("&")) {
                    if (pair.startsWith("sni=")) {
                        val raw = android.net.Uri.decode(pair.substring(4))
                        val clean = raw.removePrefix("https://").removePrefix("http://")
                            .removePrefix("://").trimEnd('/')
                        if (clean != raw) {
                            si.link = si.link.replace("sni=" + pair.substring(4),
                                "sni=" + java.net.URLEncoder.encode(clean, "UTF-8"))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-PARSE", "SNI tozalash xato: ${t.message}")
        }
        // Country code — avval host dan, keyin remark dan
        var cc = CountryLookup.lookup(si.host)
        if (cc[0].isEmpty()) {
            val fromRemark = CountryLookup.ccFromRemark(si.remark)
            if (fromRemark.isNotEmpty()) {
                val name = CountryLookup.MAP[fromRemark.lowercase()] ?: fromRemark
                cc = arrayOf(fromRemark, name)
            }
        }
        si.countryCode = cc[0]
        si.country = cc[1]
        return si
    }

    /** Shadowsocks (SS / SS 2022) link parser. */
    private fun parseSs(link: String, subId: String?): ServerItem? {
        val si = ServerItem(link)
        si.subId = subId
        si.protocol = Protocol.fromUri(link)   // XATO: hardcoded edi

        var body = link.removePrefix("ss://")
        val hash = body.indexOf('#')
        if (hash > 0) {
            si.remark = try { android.net.Uri.decode(body.substring(hash + 1)) }
                        catch (t: Throwable) { body.substring(hash + 1) }
            body = body.substring(0, hash)
        }
        val qIdx = body.indexOf('?')
        if (qIdx > 0) body = body.substring(0, qIdx)

        var userInfo: String
        var hostPort: String

        if (body.contains('@')) {
            userInfo = body.substringBefore('@')
            hostPort = body.substringAfter('@')
            if (!userInfo.contains(':')) {
                userInfo = decodeBase64Lenient(userInfo) ?: userInfo
            }
        } else {
            val dec = decodeBase64Lenient(body) ?: run {
                android.util.Log.w("NurVPN-SS", "SS base64 xato")
                return null
            }
            if (dec.contains('@')) {
                userInfo = dec.substringBefore('@')
                hostPort = dec.substringAfter('@')
            } else return null
        }

        val hp = hostPort.substringBefore('/').substringBefore('?')
        val (h, p) = splitHostPort(hp)
        si.host = h
        si.port = if (p > 0) p else 8388

        android.util.Log.i("NurVPN-SS",
            "parseSs: method=${userInfo.substringBefore(':')}, " +
            "host=${si.host}, port=${si.port}")

        if (si.remark.isNullOrEmpty()) si.remark = si.host
        // Country code — avval host dan, keyin remark dan
        var cc = CountryLookup.lookup(si.host)
        if (cc[0].isEmpty()) {
            val fromRemark = CountryLookup.ccFromRemark(si.remark)
            if (fromRemark.isNotEmpty()) {
                val name = CountryLookup.MAP[fromRemark.lowercase()] ?: fromRemark
                cc = arrayOf(fromRemark, name)
            }
        }
        si.countryCode = cc[0]
        si.country = cc[1]
        return si
    }

    /** SSR (ShadowsocksR) link parser. */
    private fun parseSsr(link: String, subId: String?): ServerItem? {
        val si = ServerItem(link)
        si.subId = subId
        si.protocol = Protocol.SSR

        val body = link.removePrefix("ssr://").substringBefore("#")
        val hash = link.indexOf('#')
        if (hash > 0) {
            si.remark = try {
                android.net.Uri.decode(link.substring(hash + 1))
            } catch (t: Throwable) { link.substring(hash + 1) }
        }

        val decoded = try {
            String(Base64.decode(body,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("NurVPN-PARSE", "SSR base64 xato: ${e.message}")
            return null
        }

        val mainPart = decoded.substringBefore("/?")
        val parts = mainPart.split(":")
        if (parts.size < 6) {
            android.util.Log.w("NurVPN-PARSE", "SSR format xato: $decoded")
            return null
        }

        si.host = parts[0]
        si.port = parts[1].toIntOrNull() ?: 8388
        if (si.remark.isNullOrEmpty()) si.remark = si.host

        val cc = CountryLookup.lookup(si.host)
        si.countryCode = cc[0]
        si.country = cc[1]
        si.transport = "tcp"
        return si
    }

}

// ═══════════ SUBSCRIPTION (obuna) ═══════════

```
