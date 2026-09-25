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
            when {
                // ═══ Xray JSON config ═══
                trimmed.startsWith("{") -> parseXrayJson(trimmed, subId)
                // ═══ URI links ═══
                trimmed.startsWith("vmess://") -> parseVmess(trimmed, subId)
                trimmed.startsWith("vless://") ||
                trimmed.startsWith("hysteria2://") ||
                trimmed.startsWith("hy2://") ||
                trimmed.startsWith("tuic://") ||
                trimmed.startsWith("trojan://") ||
                trimmed.startsWith("ss://", true) -> parseSs(trimmed, subId)
                else -> null
            }
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-PARSE", "parse fail: ${t.message}", t)
            null
        }
    }

    /** Xray JSON config → URI string → ServerItem. */
    private fun parseXrayJson(json: String, subId: String?): ServerItem? {
        return try {
            val root = org.json.JSONObject(json)
            val outbounds = root.optJSONArray("outbounds") ?: return null
            // Proxy outbound topamiz (direct/block/fragment emas)
            var proxy: org.json.JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.getJSONObject(i)
                val proto = ob.optString("protocol", "")
                if (proto in listOf("vless", "vmess", "trojan", "shadowsocks")) {
                    proxy = ob
                    break
                }
            }
            val ob = proxy ?: return null

            val protocol = ob.optString("protocol")
            val settings = ob.optJSONObject("settings") ?: return null
            val stream = ob.optJSONObject("streamSettings")

            // DEBUG: barcha JSON key larni logga chiqaramiz
            val dbgKeys = mutableListOf<String>()
            val kit = root.keys()
            while (kit.hasNext()) dbgKeys.add(kit.next())
            android.util.Log.i("NurVPN-PARSE", "JSON root keys: $dbgKeys")
            for (k in dbgKeys) {
                val v = root.opt(k)
                if (v is String) android.util.Log.i("NurVPN-PARSE", "  root.$k = $v")
            }
            val obKeys = mutableListOf<String>()
            val oit = ob.keys()
            while (oit.hasNext()) obKeys.add(oit.next())
            android.util.Log.i("NurVPN-PARSE", "outbound keys: $obKeys")
            for (k in obKeys) {
                val v = ob.opt(k)
                if (v is String) android.util.Log.i("NurVPN-PARSE", "  ob.$k = $v")
            }
            // Nomni turli maydonlardan izlaymiz
            val remark = listOf("remarks","remark","tag","name","title","label","ps","displayName","serverName","country")
                .firstNotNullOfOrNull { k ->
                    val v = root.optString(k, "")
                    if (v.isNotBlank() && !v.startsWith("http")) v else null
                } ?: listOf("remarks","remark","tag","name","title","label")
                .firstNotNullOfOrNull { k ->
                    val v = ob.optString(k, "")
                    if (v.isNotBlank() && !v.startsWith("http")) v else null
                } ?: ""
            android.util.Log.i("NurVPN-PARSE", "Extracted remark='$remark'")
            val link = when (protocol) {
                "vless" -> buildVlessUri(settings, stream, remark)
                else -> {
                    android.util.Log.w("NurVPN-PARSE", "JSON protocol qollab-quvvatlanmaydi: $protocol")
                    return null
                }
            } ?: return null

            android.util.Log.i("NurVPN-PARSE", "Xray JSON → URI: ${link.take(80)}...")
            parseStandard(link, subId)
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-PARSE", "Xray JSON parse xato", t)
            null
        }
    }

    /** Xray VLESS settings → vless:// URI. */
    private fun buildVlessUri(settings: org.json.JSONObject, stream: org.json.JSONObject?, remark: String? = null): String? {
        val vnext = settings.optJSONArray("vnext") ?: return null
        if (vnext.length() == 0) return null
        val node = vnext.getJSONObject(0)
        val addr = node.optString("address", "")
        val port = node.optInt("port", 443)
        val users = node.optJSONArray("users") ?: return null
        if (users.length() == 0) return null
        val user = users.getJSONObject(0)
        val uuid = user.optString("id", "")
        val flow = user.optString("flow", "")

        // ═══ Stream settings ═══
        val net = stream?.optString("network", "tcp") ?: "tcp"
        val security = stream?.optString("security", "none") ?: "none"

        val params = mutableListOf<String>()
        params.add("encryption=none")
        params.add("type=$net")

        if (flow.isNotEmpty()) params.add("flow=${java.net.URLEncoder.encode(flow, "UTF-8")}")

        when (net) {
            "xhttp" -> {
                val xh = stream?.optJSONObject("xhttpSettings")
                if (xh != null) {
                    val host = xh.optString("host", "")
                    val path = xh.optString("path", "/")
                    val mode = xh.optString("mode", "auto")
                    if (host.isNotEmpty()) params.add("host=${java.net.URLEncoder.encode(host, "UTF-8")}")
                    params.add("path=${java.net.URLEncoder.encode(path, "UTF-8")}")
                    params.add("mode=$mode")
                    params.add("xhttpMode=$mode")
                    params.add("xhttpPath=${java.net.URLEncoder.encode(path, "UTF-8")}")
                } else {
                    params.add("path=%2F")
                    params.add("mode=auto")
                    params.add("xhttpMode=auto")
                    params.add("xhttpPath=%2F")
                }
            }
            "ws" -> {
                val ws = stream?.optJSONObject("wsSettings")
                if (ws != null) {
                    val host = ws.optString("host", "")
                    val path = ws.optString("path", "")
                    if (host.isNotEmpty()) params.add("host=${java.net.URLEncoder.encode(host, "UTF-8")}")
                    if (path.isNotEmpty()) params.add("path=${java.net.URLEncoder.encode(path, "UTF-8")}")
                }
            }
            "grpc" -> {
                val grpc = stream?.optJSONObject("grpcSettings")
                if (grpc != null) {
                    val sn = grpc.optString("serviceName", "")
                    if (sn.isNotEmpty()) params.add("serviceName=${java.net.URLEncoder.encode(sn, "UTF-8")}")
                }
            }
            "tcp" -> {
                val tcp = stream?.optJSONObject("tcpSettings")
                if (tcp != null && tcp.optBoolean("headerType") == true) {
                    params.add("headerType=http")
                }
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
                    val fp = r.optString("fingerprint", "chrome")
                    if (sni.isNotEmpty()) params.add("sni=${java.net.URLEncoder.encode(sni, "UTF-8")}")
                    if (pbk.isNotEmpty()) params.add("pbk=$pbk")
                    if (sid.isNotEmpty()) params.add("sid=$sid")
                    if (fp.isNotEmpty()) params.add("fp=$fp")
                }
            }
            "tls" -> {
                val t = stream?.optJSONObject("tlsSettings")
                if (t != null) {
                    val sni = t.optString("serverName", "")
                        .removePrefix("https://").removePrefix("http://")
                        .removePrefix("://").trimEnd('/')
                    val fp = t.optString("fingerprint", "")
                    if (sni.isNotEmpty()) params.add("sni=${java.net.URLEncoder.encode(sni, "UTF-8")}")
                    if (fp.isNotEmpty()) params.add("fp=$fp")
                }
            }
        }

        val query = params.joinToString("&")
        val name = remark?.takeIf { it.isNotBlank() } ?: addr
        return "vless://$uuid@$addr:$port?$query#" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
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

        val colon = after.lastIndexOf(':')
        if (colon <= 0) return null
        val si = ServerItem(originalLink)
        si.subId = subId
        si.protocol = Protocol.VMESS
        si.host = after.substring(0, colon)
        si.port = after.substring(colon + 1).toIntOrNull() ?: 0
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
        var body = link
        val hash = body.indexOf('#')
        if (hash > 0) {
            si.remark = try { android.net.Uri.decode(body.substring(hash + 1)) }
                        catch (t: Throwable) { body.substring(hash + 1) }
            body = body.substring(0, hash)
        }
        val at = body.indexOf('@')
        if (at > 0) {
            var hp = body.substring(at + 1)
            val q = hp.indexOf('?'); if (q > 0) hp = hp.substring(0, q)
            val sl = hp.indexOf('/'); if (sl > 0) hp = hp.substring(0, sl)
            val col = hp.lastIndexOf(':')
            if (col > 0) {
                si.host = hp.substring(0, col)
                si.port = hp.substring(col + 1).toIntOrNull() ?: 0
            } else si.host = hp
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
        si.protocol = Protocol.SS_2022

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
                userInfo = try {
                    String(Base64.decode(userInfo, Base64.DEFAULT), Charsets.UTF_8)
                } catch (t: Throwable) { userInfo }
            }
        } else {
            val dec = try {
                String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
            } catch (t: Throwable) {
                android.util.Log.w("NurVPN-SS", "SS base64 xato: ${t.message}")
                return null
            }
            if (dec.contains('@')) {
                userInfo = dec.substringBefore('@')
                hostPort = dec.substringAfter('@')
            } else return null
        }

        val hp = hostPort.substringBefore('/').substringBefore('?')
        val colon = hp.lastIndexOf(':')
        if (colon > 0) {
            si.host = hp.substring(0, colon)
            si.port = hp.substring(colon + 1).toIntOrNull() ?: 8388
        } else {
            si.host = hp
            si.port = 8388
        }

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
}

// ═══════════ SUBSCRIPTION (obuna) ═══════════

