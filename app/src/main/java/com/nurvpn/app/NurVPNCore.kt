// ═══════════════════════════════════════════════════════════════
// NurVPN CORE — MainActivity, AI, AWG, sing-box, VpnService, util
// Tuzatilgan versiya (Kimi AI xatolari tuzatildi)
// ═══════════════════════════════════════════════════════════════
package com.nurvpn.app
import com.nurvpn.app.core.PingStrategy
import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.service.NurVpnTileService
import com.nurvpn.app.config.BuiltinAwgConfigs
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceSubscription
import com.nurvpn.app.storage.OpenSourceStore
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState





import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

// ═══════════ UTIL ═══════════

object ThemeHelper {
    private const val PREF = "settings"

    fun getThemeMode(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("theme", "dark") ?: "dark"

    fun setThemeMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("theme", mode).apply()
        applyTheme(ctx)
    }

    fun applyTheme(ctx: Context) {
        val mode = getThemeMode(ctx)
        val nightMode = when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else    -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun getLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("lang", "") ?: ""

    fun setLanguage(ctx: Context, tag: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("lang", tag).apply()
}

object CountryLookup {
    val MAP = mapOf(
        "us" to "🇺🇸 AQSH", "de" to "🇩🇪 Germaniya", "nl" to "🇳🇱 Niderlandiya",
        "fr" to "🇫🇷 Fransiya", "gb" to "🇬🇧 Buyuk Britaniya", "uk" to "🇬🇧 Buyuk Britaniya",
        "ru" to "🇷🇺 Rossiya", "kz" to "🇰🇿 Qozog'iston", "tr" to "🇹🇷 Turkiya",
        "ae" to "🇦🇪 BAA", "jp" to "🇯🇵 Yaponiya", "kr" to "🇰🇷 Koreya",
        "sg" to "🇸🇬 Singapur", "in" to "🇮🇳 Hindiston", "fi" to "🇫🇮 Finlyandiya",
        "se" to "🇸🇪 Shvetsiya", "no" to "🇳🇴 Norvegiya", "ch" to "🇨🇭 Shveytsariya",
        "pl" to "🇵🇱 Polsha", "ua" to "🇺🇦 Ukraina", "ca" to "🇨🇦 Kanada",
        "au" to "🇦🇺 Avstraliya", "br" to "🇧🇷 Braziliya", "hk" to "🇭🇰 Gonkong"
    )

    fun lookup(host: String?): Array<String> {
        if (host.isNullOrEmpty()) return arrayOf("", "🌍 Noma'lum")
        val h = host.lowercase()
        val tld = h.substringAfterLast('.', "")
        if (tld.length == 2 && tld.all { it.isLetter() }) {
            val name = MAP[tld] ?: "🌍 " + tld.uppercase()
            return arrayOf(tld.uppercase(), name)
        }
        if (h.contains("germany")) return arrayOf("DE", "🇩🇪 Germaniya")
        if (h.contains("turk")) return arrayOf("TR", "🇹🇷 Turkiya")
        if (h.contains("america")) return arrayOf("US", "🇺🇸 AQSH")
        if (h.contains("london")) return arrayOf("GB", "🇬🇧 Buyuk Britaniya")
        return arrayOf("", "🌍 " + h)
    }

    /** Remark dan emoji bayroqni ajratib olish (🇨🇦, 🇩🇪 va h.k.). */
    fun flagFromRemark(remark: String?): String {
        if (remark.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < remark.length) {
            val cp = remark.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            } else if (sb.isNotEmpty()) break
            else i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Remark dan davlat kodini olish (🇨🇦 → CA). */
    fun ccFromRemark(remark: String?): String {
        if (remark.isNullOrEmpty()) return ""
        var i = 0
        val codes = mutableListOf<Int>()
        while (i < remark.length && codes.size < 2) {
            val cp = remark.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                codes.add(cp - 0x1F1E6)
                i += Character.charCount(cp)
            } else if (codes.isEmpty()) {
                i += Character.charCount(cp)
            } else break
        }
        if (codes.size != 2) return ""
        return "${('A' + codes[0])}${('A' + codes[1])}"
    }
}

object ClashApiConfig {
    @JvmField @Volatile var baseUrl: String = "http://127.0.0.1:9090"
    @JvmField @Volatile var secret: String = ""
    @JvmField @Volatile var ready: Boolean = false

    /** Har safar random secret — config bilan bir xil bo'lishi uchun bir marta. */
    fun ensureSecret(): String {
        if (secret.isEmpty()) {
            secret = "nurvpn-" + (100000..999999).random()
        }
        return secret
    }
}


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

// ═══════════ MODEL ═══════════

class ServerItem(@JvmField var link: String) {
    var host: String? = null
    var port: Int = 0
    var remark: String? = null
    var countryCode: String = ""
    var country: String = ""
    var ping: Int = -1
    var subId: String? = null
    var favorite: Boolean = false

    
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

object ServerStore {
    private const val PREF = "servers"
    private const val KEY = "list"

    fun save(ctx: Context, servers: List<ServerItem>) {
        val arr = JSONArray()
        for (si in servers) {
            val o = JSONObject()
            o.put("link", si.link)
            o.put("host", si.host)
            o.put("port", si.port)
            o.put("remark", si.remark)
            o.put("cc", si.countryCode)
            o.put("country", si.country)
            o.put("ping", si.ping)
            o.put("subId", si.subId ?: JSONObject.NULL)
            o.put("favorite", si.favorite)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): MutableList<ServerItem> {
        val out = ArrayList<ServerItem>()
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val si = ServerItem(o.getString("link"))
                si.host = o.optString("host", null)
                si.port = o.optInt("port", 0)
                si.remark = o.optString("remark", null)
                si.countryCode = o.optString("cc", "")
                si.country = o.optString("country", "")
                si.ping = o.optInt("ping", -1)
                si.favorite = o.optBoolean("favorite", false)
                si.subId = if (o.has("subId") && !o.isNull("subId"))
                    o.getString("subId") else null
                si.protocol = Protocol.fromUri(o.getString("link"))
                Log.d("NurVPN-PING", "load: ${si.host}:${si.port} proto=${si.protocol} link=${o.getString("link").take(20)}…")
                out.add(si)
            }
        } catch (e: Throwable) { Log.e("NurVPN", "load servers", e) }
        return out
    }
}

// ═══════════ UNIVERSAL PARSER ═══════════

/** Base64 matnni xavfsiz decode qiladi. Plain matn uchun "" qaytaradi. */
fun decodeBase64Safely(value: String): String {
    val compact = value.filterNot { it.isWhitespace() }
    val looksLikeBase64 = compact.length >= 16 &&
        compact.length % 4 != 1 &&
        Regex("^[A-Za-z0-9+/=_-]+$").matches(compact)
    if (!looksLikeBase64) return ""
    return try {
        String(
            android.util.Base64.decode(compact, android.util.Base64.DEFAULT),
            Charsets.UTF_8
        )
    } catch (t: Throwable) { "" }
}

object SubscriptionLinkExtractor {

    private val protocolStart = Regex(
        "(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic)://"
    )

    // Precompile — har safar yangi Regex yaratmaslik uchun
    private val whitespace = Regex("\\s+")

    fun extract(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val matches = protocolStart.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList()

        // TAXMINIY hajm — xotirani oldindan ajratish
        val result = ArrayList<String>(matches.size.coerceAtMost(600))

        for (i in matches.indices) {
            // Limit: 500 link yetarli
            if (result.size >= 500) break

            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) {
                matches[i + 1].range.first
            } else {
                raw.length
            }

            // Oddiy substring — trim + whitespace
            val rawLink = raw.substring(start, end)
            if (rawLink.length <= 20) continue

            // Faqat link ichida whitespace bo'lsa tozalaymiz
            val link = if (rawLink.indexOf(' ') >= 0 ||
                           rawLink.indexOf('\n') >= 0 ||
                           rawLink.indexOf('\r') >= 0 ||
                           rawLink.indexOf('\t') >= 0) {
                whitespace.replace(rawLink, "")
                    .trim()
                    .trimEnd(',', ';', '"')
            } else {
                rawLink.trimEnd(',', ';', '"', '\n', '\r')
            }

            if (link.length > matches[i].value.length) {
                result.add(link)
            }
        }
        return result
    }
}

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

class Subscription(
    @JvmField var id: String,
    @JvmField var url: String,
    @JvmField var name: String
) {
    @JvmField var lastUpdated: Long = 0
    /** Tartib — pastdan yuqoriga (0 = eng yuqori). */
    @JvmField var order: Int = 0
    /** Saralash rejimi: default, ping_asc, ping_desc, name_asc, name_desc */
    @JvmField var sortMode: String = "default"
    @JvmField var updateIntervalHours: Int = 24
    @JvmField var expireAt: Long = 0
    @JvmField var trafficUsed: Long = 0
    @JvmField var trafficTotal: Long = 0
    @JvmField var serverCount: Int = 0

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expireAt > 0 && now > expireAt

    fun daysLeft(now: Long = System.currentTimeMillis()): Int {
        if (expireAt <= 0) return -1
        return ((expireAt - now) / 86400_000L).toInt()
    }

    fun trafficPercent(): Int {
        if (trafficTotal <= 0) return -1
        return ((trafficUsed * 100) / trafficTotal).toInt()
    }
}

object SubscriptionStore {
    private const val PREF = "subscriptions"
    private const val KEY = "list"

    fun save(ctx: Context, subs: List<Subscription>) {
        val arr = JSONArray()
        for (sub in subs) {
            val o = JSONObject()
            o.put("id", sub.id)
            o.put("url", sub.url)
            o.put("name", sub.name)
            o.put("lastUpdated", sub.lastUpdated)
            o.put("updateIntervalHours", sub.updateIntervalHours)
            o.put("expireAt", sub.expireAt)
            o.put("trafficUsed", sub.trafficUsed)
            o.put("trafficTotal", sub.trafficTotal)
            o.put("serverCount", sub.serverCount)
            o.put("order", sub.order)
            o.put("sortMode", sub.sortMode)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): MutableList<Subscription> {
        val out = ArrayList<Subscription>()
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sub = Subscription(
                    o.getString("id"),
                    o.getString("url"),
                    o.optString("name", "Subscription")
                )
                sub.lastUpdated = o.optLong("lastUpdated", 0)
                sub.updateIntervalHours = o.optInt("updateIntervalHours", 24)
                sub.expireAt = o.optLong("expireAt", 0)
                sub.trafficUsed = o.optLong("trafficUsed", 0)
                sub.trafficTotal = o.optLong("trafficTotal", 0)
                sub.serverCount = o.optInt("serverCount", 0)
                sub.order = o.optInt("order", 0)
                sub.sortMode = o.optString("sortMode", "default")
                out.add(sub)
            }
        } catch (e: Throwable) { Log.e("NurVPN", "load subscriptions", e) }
        return out
    }

    /** Obunani yuqoriga (dir=-1) yoki pastga (dir=+1) ko'chirish. */
    fun moveSubscription(ctx: Context, subId: String, dir: Int): Boolean {
        val list = load(ctx)
        if (list.isEmpty()) return false
        // Tartib bo'yicha saralash
        list.sortBy { it.order }
        val idx = list.indexOfFirst { it.id == subId }
        if (idx < 0) return false
        val newIdx = (idx + dir).coerceIn(0, list.size - 1)
        if (idx == newIdx) return false
        // Swap
        val tmp = list[idx]
        list[idx] = list[newIdx]
        list[newIdx] = tmp
        // Order'larni qayta yozamiz
        list.forEachIndexed { i, sub -> sub.order = i }
        save(ctx, list)
        return true
    }

    /** Obuna sortMode ni o'zgartirish. */
    fun setSortMode(ctx: Context, subId: String, mode: String) {
        val list = load(ctx)
        list.find { it.id == subId }?.sortMode = mode
        save(ctx, list)
    }

    fun delete(ctx: Context, sub: Subscription) {
        val list = load(ctx)
        list.removeAll { it.id == sub.id }
        save(ctx, list)
    }
}

// ═══════════ AI ENGINE ═══════════

class ServerMetrics(@JvmField val link: String) {
    var avgLatency: Double = 0.0
    var successRate: Double = 0.5
    var samples: Int = 0
    var fails: Int = 0
    var totalBytesUp: Long = 0
    var totalBytesDown: Long = 0
    var lastGoodTime: Long = 0

    fun recordPing(latencyMs: Int, ok: Boolean, now: Long) {
        samples++
        if (ok) {
            avgLatency = if (samples == 1) latencyMs.toDouble()
                         else avgLatency * 0.8 + latencyMs * 0.2
            lastGoodTime = now
            val succ = 1.0 - fails.toDouble() / samples
            successRate = successRate * 0.7 + succ * 0.3
        } else {
            fails++
            successRate = successRate * 0.7 +
                (1.0 - fails.toDouble() / samples) * 0.3
        }
    }

    fun addTraffic(up: Long, down: Long) {
        totalBytesUp += up
        totalBytesDown += down
    }

    fun confidence(): Double = Math.min(1.0, samples / 20.0)

    companion object {
        @JvmStatic fun latencyScore(lat: Int): Double = when {
            lat < 0 -> 0.0
            lat >= 500 -> 0.0
            else -> 100.0 * (1.0 - lat / 500.0)
        }
    }
}

object SmartScoreEngine {
    @JvmStatic
    fun computeScore(m: ServerMetrics, now: Long): Double {
        val base = ServerMetrics.latencyScore(m.avgLatency.toInt())
        val conf = m.confidence()
        var score = 50.0 * (1 - conf) + base * conf
        score *= (0.4 + 0.6 * m.successRate)
        if (m.lastGoodTime > 0 && now - m.lastGoodTime < 3600_000L) score += 5
        if (m.totalBytesDown > 1_000_000) score += 3
        return Math.max(0.0, Math.min(100.0, score))
    }
}

object MetricsStore {
    private const val PREF = "metrics"

    fun get(ctx: Context, link: String): ServerMetrics {
        val m = ServerMetrics(link)
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(link, null) ?: return m
            val o = JSONObject(s)
            m.avgLatency = o.optDouble("lat", 0.0)
            m.successRate = o.optDouble("succ", 0.5)
            m.samples = o.optInt("n", 0)
            m.fails = o.optInt("f", 0)
            m.totalBytesUp = o.optLong("up", 0)
            m.totalBytesDown = o.optLong("down", 0)
            m.lastGoodTime = o.optLong("t", 0)
        } catch (ignored: Throwable) {}
        return m
    }

    fun save(ctx: Context, m: ServerMetrics) {
        val o = JSONObject()
        o.put("lat", m.avgLatency)
        o.put("succ", m.successRate)
        o.put("n", m.samples)
        o.put("f", m.fails)
        o.put("up", m.totalBytesUp)
        o.put("down", m.totalBytesDown)
        o.put("t", m.lastGoodTime)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(m.link, o.toString()).apply()
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().clear().apply()

    fun exportJson(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in prefs.all) out.put(k, v)
        return out.toString(2)
    }
}

class AIInsights(
    @JvmField val link: String,
    @JvmField val score: Double,
    @JvmField val name: String,
    @JvmField val summary: String
)

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

class AWGConfig(@JvmField var rawConf: String?) {
    var name: String? = null
    var endpoint: String? = null
    var address: String? = null
    var ping: Int = -1
    var favorite: Boolean = false
}

object AWGStore {
    private const val PREF = "awg"
    private const val KEY = "configs"

    fun save(ctx: Context, configs: List<AWGConfig>) {
        val arr = JSONArray()
        for (c in configs) {
            val o = JSONObject()
            o.put("raw", c.rawConf)
            o.put("name", c.name)
            o.put("endpoint", c.endpoint)
            o.put("address", c.address ?: "")
            o.put("ping", c.ping)
            o.put("favorite", c.favorite)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): MutableList<AWGConfig> {
        val out = ArrayList<AWGConfig>()
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = AWGConfig(o.optString("raw"))
                c.name = o.optString("name", null)
                c.endpoint = o.optString("endpoint", null)
                c.address = o.optString("address", null)
                c.favorite = o.optBoolean("favorite", false)
                c.ping = o.optInt("ping", -1)
                out.add(c)
            }
        } catch (ignored: Throwable) {}
        return out
    }
}

object AWGEditor {

    class Data {
        var host: String = ""
        var port: Int = 0
        var jc: Int = 4
        var jmin: Int = 40
        var jmax: Int = 70
        var mtu: Int = 1280
        var keepalive: Int = 25
        var dns: String = ""
    }

    fun parse(raw: String): Data {
        val d = Data()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("[")) continue
            val parts = t.split("=", limit = 2)
            if (parts.size < 2) continue
            val k = parts[0].trim()
            val v = parts[1].trim()
            when (k.lowercase()) {
                "endpoint" -> {
                    d.host = v.substringBeforeLast(":")
                    d.port = v.substringAfterLast(":").toIntOrNull() ?: 0
                }
                "jc" -> d.jc = v.toIntOrNull() ?: 4
                "jmin" -> d.jmin = v.toIntOrNull() ?: 40
                "jmax" -> d.jmax = v.toIntOrNull() ?: 70
                "mtu" -> d.mtu = v.toIntOrNull() ?: 1280
                "persistentkeepalive" ->
                    d.keepalive = v.toIntOrNull() ?: 25
                "dns" -> d.dns = v
            }
        }
        return d
    }

    fun setValue(raw: String, key: String, value: String): String {
        val sb = StringBuilder()
        var replaced = false
        for (line in raw.lines()) {
            val t = line.trim()
            // ═══ Case-insensitive + aniq kalit mos kelishi ═══
            val eqIdx = t.indexOf('=')
            if (eqIdx > 0) {
                val lineKey = t.substring(0, eqIdx).trim()
                if (lineKey.equals(key, ignoreCase = true)) {
                    sb.append("$key = $value\n")
                    replaced = true
                    continue
                }
            }
            sb.append(line).append("\n")
        }
        if (!replaced) sb.append("$key = $value\n")
        return sb.toString().trimEnd()
    }

    fun setEndpoint(raw: String, host: String, port: Int): String =
        setValue(raw, "Endpoint", "$host:$port")

    fun applyAll(raw: String, d: Data): String {
        var out = raw
        out = setEndpoint(out, d.host, d.port)
        out = setValue(out, "Jc", d.jc.toString())
        out = setValue(out, "Jmin", d.jmin.toString())
        out = setValue(out, "Jmax", d.jmax.toString())
        out = setValue(out, "MTU", d.mtu.toString())
        out = setValue(out, "PersistentKeepalive", d.keepalive.toString())
        if (d.dns.isNotEmpty()) out = setValue(out, "DNS", d.dns)
        return out
    }

    fun applyPort443(raw: String): String {
        val d = parse(raw)
        return setEndpoint(raw, d.host, 443)
    }

    fun applyBeelinePreset(raw: String): String {
        var out = setValue(raw, "Jc", "120")
        out = setValue(out, "Jmin", "50")
        out = setValue(out, "Jmax", "100")
        out = setValue(out, "MTU", "1180")
        return applyPort443(out)
    }

    fun applyMtsPreset(raw: String): String {
        var out = setValue(raw, "Jc", "8")
        out = setValue(out, "Jmin", "20")
        out = setValue(out, "Jmax", "50")
        out = setValue(out, "MTU", "1280")
        return applyPort443(out)
    }
}

object AWGParser {
    class Result {
        var ok: Boolean = false
        var error: String = ""
        var endpoint: String = ""
        var address: String = ""
        var version: String = "2.0"
    }

    fun parse(conf: String): Result {
        val r = Result()
        if (conf.isBlank()) { r.error = "Bo'sh config"; return r }
        var hasInterface = false
        var hasPeer = false
        for (line in conf.lines()) {
            val t = line.trim()
            when {
                t.equals("[Interface]", true) -> hasInterface = true
                t.equals("[Peer]", true) -> hasPeer = true
                t.startsWith("Endpoint", true) ->
                    r.endpoint = t.substringAfter("=").trim()
                t.startsWith("Address", true) ->
                    r.address = t.substringAfter("=").trim()
                t.startsWith("Jc", true) || t.startsWith("Jmin", true) ||
                t.startsWith("Jmax", true) || t.startsWith("S1", true) ||
                t.startsWith("I1", true) || t.startsWith("I2", true) ->
                    r.version = "3.1"
            }
        }
        if (!hasInterface || !hasPeer) {
            r.error = "[Interface] va [Peer] kerak"
            return r
        }
        if (r.endpoint.isEmpty()) {
            r.error = "Endpoint topilmadi"
            return r
        }
        r.ok = true
        return r
    }
}

// ═══════════ NET / SECURITY ═══════════

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

object AwgSortStore {
    private const val PREF = "awg_sort"
    private const val KEY = "mode"

    fun getMode(ctx: android.content.Context): String =
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .getString(KEY, "default") ?: "default"

    fun setMode(ctx: android.content.Context, mode: String) =
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()

    /** AWG listini sortMode bo'yicha saralash. */
    fun sort(list: List<AWGConfig>, mode: String): List<AWGConfig> =
        when (mode) {
            "name_asc" -> list.sortedBy { (it.name ?: "AWG").lowercase() }
            "name_desc" -> list.sortedByDescending { (it.name ?: "AWG").lowercase() }
            "ping_asc" -> list.sortedBy { if (it.ping < 0) Int.MAX_VALUE else it.ping }
            "ping_desc" -> list.sortedByDescending { it.ping }
            else -> list
        }
}

object SplitTunnelStore {
    const val MODE_ALL = 0
    const val MODE_WHITELIST = 1
    const val MODE_BLACKLIST = 2
    private const val PREF = "split"

    fun getMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt("mode", MODE_ALL)
    fun setMode(ctx: Context, mode: Int) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt("mode", mode).apply()

    fun getApps(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()
    fun setApps(ctx: Context, apps: Set<String>) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet("apps", HashSet(apps)).apply()
}

class AppInfo {
    var label: String = ""
    var packageName: String = ""
    var icon: Drawable? = null
    var selected: Boolean = false
}

object AppListLoader {
    fun loadAsync(ctx: Context, cb: (List<AppInfo>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val out = ArrayList<AppInfo>()
            try {
                val pm = ctx.packageManager
                val intents = pm.getInstalledApplications(
                    PackageManager.GET_META_DATA)
                for (app in intents) {
                    if (pm.getLaunchIntentForPackage(app.packageName) == null)
                        continue
                    val ai = AppInfo()
                    ai.packageName = app.packageName
                    ai.label = app.loadLabel(pm).toString()
                    ai.icon = app.loadIcon(pm)
                    out.add(ai)
                }
                out.sortBy { it.label.lowercase() }
            } catch (ignored: Throwable) {}
            Handler(Looper.getMainLooper()).post { cb(out) }
        }
    }
}

// ═══════════ AI CARD VIEW ═══════════

class AICardView @JvmOverloads constructor(
    ctx: Context, attrs: android.util.AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    interface OnAutoConnect {
        fun onAutoConnect(best: AIInsights?)
        fun onRefreshRequested()
    }

    private var listener: OnAutoConnect? = null
    private var best: AIInsights? = null
    private var dot: TextView
    private var topName: TextView
    private var topMeta: TextView
    private var topSummary: TextView
    private var rankingBox: LinearLayout
    private var btnLabel: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(ctx).inflate(R.layout.view_ai_card, this, true)
        dot = findViewById(R.id.ai_status_dot)
        topName = findViewById(R.id.ai_top_name)
        topMeta = findViewById(R.id.ai_top_meta)
        topSummary = findViewById(R.id.ai_top_summary)
        rankingBox = findViewById(R.id.ai_ranking_box)
        btnLabel = findViewById(R.id.ai_btn_label)
        findViewById<ImageButton>(R.id.ai_refresh)
            .setOnClickListener { listener?.onRefreshRequested() }
        findViewById<View>(R.id.ai_btn).setOnClickListener {
            listener?.onAutoConnect(best)
        }
    }

    fun setListener(l: OnAutoConnect) { listener = l }

    fun showAnalyzing() {
        dot.text = "●"
        dot.setTextColor(androidx.core.content.ContextCompat
            .getColor(context, R.color.warning))
        topName.text = context.getString(R.string.ai_selector)
        topMeta.text = context.getString(R.string.ai_analyzing)
        topSummary.text = ""
        rankingBox.removeAllViews()
        btnLabel.text = context.getString(R.string.ai_analyzing)
    }

    fun showRanked(ranked: List<AIInsights>) {
        rankingBox.removeAllViews()
        if (ranked.isEmpty()) {
            dot.text = "●"
            dot.setTextColor(androidx.core.content.ContextCompat
            .getColor(context, R.color.text_tertiary))
            topMeta.text = context.getString(R.string.ai_add_hint)
            btnLabel.text = context.getString(R.string.ai_auto_connect)
            return
        }
        best = ranked[0]
        dot.text = "●"
        dot.setTextColor(androidx.core.content.ContextCompat
            .getColor(context, R.color.success))
        val b = ranked[0]
        topName.text = "🤖 " + b.name
        topMeta.text = b.summary
        topSummary.text = "#1 • " + b.score.toInt() + "/100"
        val max = Math.min(3, ranked.size)
        for (i in 0 until max) {
            val r = ranked[i]
            val tv = TextView(context)
            tv.textSize = 12f
            tv.setPadding(0, 6, 0, 0)
            val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; else -> "🥉" }
            tv.text = "$medal ${r.name}  —  ${"%.0f".format(r.score)}"
            tv.setTextColor(androidx.core.content.ContextCompat
                .getColor(context, R.color.text_secondary))
            rankingBox.addView(tv)
        }
        btnLabel.text = context.getString(R.string.ai_auto_connect)
    }
}

// ═══════════ SING-BOX CONFIG ═══════════

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
                if (!tls.has("utls")) {
                    tls.put("utls", JSONObject()
                        .put("enabled", true)
                        .put("fingerprint", q["fp"] ?: "chrome"))
                }
                // ★ X25519MLKEM768 ni o'chirish (eski serverlar uchun)
                tls.put("utls", JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", q["fp"] ?: "chrome"))
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
    fun buildFullConfig(link: String, dns: String, cachePath: String): JSONObject {
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

class MainActivity : AppCompatActivity() {

    companion object {
        const val PROTO_XRAY = "xray"
        const val PROTO_AWG = "awg"
        const val EXTRA_LINK = "link"
        const val EXTRA_NAME = "name"
        const val EXTRA_AWG = "awg_conf"
        const val EXTRA_STOP = "stop"
        const val EXTRA_PAUSE = "pause"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_FORCE = "force"
        const val ACTION_BROADCAST = "com.nurvpn.app.VPN_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERR = "error"
    }

    @JvmField var servers: MutableList<ServerItem> = ArrayList()
    @JvmField var awgConfigs: MutableList<AWGConfig> = ArrayList()
    @JvmField var subscriptions: MutableList<Subscription> = ArrayList()
    @JvmField var currentServer: ServerItem? = null
    @JvmField var currentAWG: AWGConfig? = null
    @JvmField var protocol: String = PROTO_XRAY
    @JvmField var isRunning = false
    /** Har restart'da oshadi. Faqat eng oxirgi restart ishlaydi. */
    @Volatile private var restartGeneration = 0L
    @JvmField var connectStart: Long = 0
    lateinit var prefs: SharedPreferences

    private val notificationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(this,
                    R.string.toast_notification_denied,
                    Toast.LENGTH_LONG).show()
            }
            doStartVpn()
        }

    private var stateReceiver: BroadcastReceiver? = null
    private var dbgReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyTheme(this)
        prefs = getSharedPreferences("main", Context.MODE_PRIVATE)

        // ═══ STALE SUBSCRIPTIONS PURGE ═══
        try {
            OpenSourceStore.cleanupOrphans(this)
            val subs = SubscriptionStore.load(this)
            val allServers = ServerStore.load(this)
            val cleaned = subs.filter { sub ->
                when {
                    // 1) open:xxx — katalogda bor + yoqilgan + AWG emas
                    sub.id.startsWith("open:") -> {
                        val openId = sub.id.removePrefix("open:")
                        val open = OpenSourceCatalog.byId(openId)
                        open != null &&
                            !open.isAwg &&
                            OpenSourceStore.isEnabled(this, openId)
                    }
                    // 2) sub_xxx — legacy race artifact (server yo'q + yuklanmagan)
                    sub.id.startsWith("sub_") -> {
                        allServers.any { it.subId == sub.id } || sub.lastUpdated > 0
                    }
                    // 3) Qo'lda — qoladi
                    else -> true
                }
            }
            if (cleaned.size != subs.size) {
                android.util.Log.i("NurVPN-DBG",
                    "PURGE: ${subs.size - cleaned.size} ta o'chirildi, ${cleaned.size} ta qoldi")
                SubscriptionStore.save(this, cleaned)
            }
            subscriptions = cleaned.toMutableList()
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "PURGE xato", t)
        }

        servers = ServerStore.load(this)
        awgConfigs = AWGStore.load(this)

        // ═══ WARP AWG configlarni avtomatik tiklash ═══
        try {
            var awgRestored = 0
            for (open in OpenSourceCatalog.ALL) {
                if (!open.isAwg) continue
                if (!OpenSourceStore.isEnabled(this, open.id)) continue
                val raw = BuiltinAwgConfigs.byId(open.awgId ?: continue) ?: continue
                if (awgConfigs.none { it.rawConf == raw }) {
                    val cfg = AWGConfig(raw)
                    cfg.name = open.name(this)
                    val parsed = AWGParser.parse(raw)
                    if (parsed.ok) {
                        cfg.endpoint = parsed.endpoint
                        cfg.address = parsed.address
                    }
                    awgConfigs.add(cfg)
                    awgRestored++
                }
            }
            if (awgRestored > 0) {
                AWGStore.save(this, awgConfigs)
                android.util.Log.i("NurVPN-DBG", "WARP AWG configlar tiklandi: $awgRestored ta")
            }
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "WARP restore xato", t)
        }
        subscriptions = SubscriptionStore.load(this)
        // ═══ MIGRATSIYA: eski serverlarda protocol noto'g'ri bo'lsa, qayta aniqlash ═══
        var protoFixed = 0
        for (si in servers) {
            val correct = Protocol.fromUri(si.link)
            if (si.protocol != correct) {
                si.protocol = correct
                protoFixed++
            }
        }
        if (protoFixed > 0) {
            ServerStore.save(this, servers)
            android.util.Log.i("NurVPN-DBG", "Migratsiya: $protoFixed ta server protokoli tuzatildi")
        }
        for (si in servers) {
            val cc = CountryLookup.lookup(si.host)
            si.countryCode = cc[0]
            si.country = cc[1]
        }
        protocol = prefs.getString("protocol", PROTO_XRAY) ?: PROTO_XRAY
        val savedLink = prefs.getString("current_link", null)
        if (savedLink != null)
            currentServer = servers.find { it.link == savedLink }
        val savedAwg = prefs.getString("current_awg", null)
        if (savedAwg != null)
            currentAWG = awgConfigs.find { it.rawConf == savedAwg }
        if (currentServer == null && servers.isNotEmpty())
            currentServer = servers[0]
        if (currentAWG == null && awgConfigs.isNotEmpty())
            currentAWG = awgConfigs[0]

        AWGEditorBus.init(awgConfigs, currentAWG, protocol)

        setContentView(R.layout.activity_main)

        // ⚠️ MUHIM: VpnService.prepare() bu yerda CHAQIRILMAYDI!
        // Sabab: ilova ochilishi bilan Android tizim NurVPN ni "faol VPN"
        // deb belgilaydi va boshqa VPN larni o'chiradi.
        // prepare() faqat foydalanuvchi "Ulanish" tugmasini bosganda
        // startVpn() ichida chaqiriladi.

        val bottom = findViewById<com.google.android.material.bottomnavigation
            .BottomNavigationView>(R.id.bottom_nav)
        bottom?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFrag(HomeFragment())
                R.id.nav_servers -> showFrag(ServersFragment())
                R.id.nav_settings -> showFrag(SettingsFragment())
            }
            true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra(EXTRA_STATE)) {
                    "connected" -> {
                        isRunning = true; TunnelState.isConnected = true
                        connectStart = System.currentTimeMillis()
                    }
                    "disconnected" -> {
                        isRunning = false; connectStart = 0; TunnelState.isConnected = false
                    }
                    "error" -> {
                        isRunning = false; connectStart = 0; TunnelState.isConnected = false
                        Toast.makeText(this@MainActivity,
                            getString(R.string.toast_error_prefix, intent.getStringExtra(EXTRA_ERR)),
                            Toast.LENGTH_LONG).show()
                    }
                }
                refreshHome()
            }
        }

        stateReceiver = receiver
        this.dbgReceiver = dbgReceiver

        val filter = IntentFilter(ACTION_BROADCAST)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        // ═══ DEBUG receiver — adb orqali VPN boshlash uchun ═══
        val dbgReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                val b64 = i?.getStringExtra("link64")
                val link = if (b64 != null) {
                    try {
                        String(android.util.Base64.decode(b64,
                            android.util.Base64.DEFAULT), Charsets.UTF_8)
                    } catch (t: Throwable) {
                        android.util.Log.e("NurVPN-DBG", "base64 decode fail", t)
                        return
                    }
                } else {
                    i?.getStringExtra("link")
                } ?: return

                android.util.Log.i("NurVPN-DBG", "Debug start: ${link.take(80)}")
                val si = ServerItem(link)
                si.host = "debug"
                servers.add(si)
                currentServer = si
                protocol = PROTO_XRAY
                startVpn()
            }
        }
        val dbgFilter = IntentFilter("com.nurvpn.app.DEBUG_START")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dbgReceiver, dbgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dbgReceiver, dbgFilter)
        }
        // TEST KOD OLIB TASHLANDI

        // ★ Avto-start O'CHIRILDI — foydalanuvchi qo'lda bosadi
        android.util.Log.i("NurVPN-DBG", "Avto-start o'chirilgan, qo'lda bosishni kuting")

        if (savedInstanceState == null) showFrag(HomeFragment())

        // Ochiq manbalar o'zgarganda barcha tablarni yangilash
        // (settings'dan chaqiriladi)
    }

    /** Barcha fragmentlarni yangilash. */
    fun refreshAllTabs() {
        subscriptions = SubscriptionStore.load(this)
        for (f in supportFragmentManager.fragments) {
            when (f) {
                is HomeFragment -> try { f.refreshSubscriptions() } catch (_: Throwable) {}
                is ServersFragment -> try { f.refreshServers() } catch (_: Throwable) {}
            }
        }
    }

    /** Tab almashtirish: 0=home, 1=servers, 2=settings. */
    fun switchToTab(index: Int) {
        val bottom = findViewById<com.google.android.material.bottomnavigation
            .BottomNavigationView>(R.id.bottom_nav) ?: return
        val id = when (index) {
            0 -> R.id.nav_home
            1 -> R.id.nav_servers
            2 -> R.id.nav_settings
            else -> return
        }
        bottom.selectedItemId = id
    }



    private fun refreshHome() {
        val f = supportFragmentManager.findFragmentById(R.id.container)
        if (f is HomeFragment) f.refresh()
    }

    private fun showFrag(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f).commitAllowingStateLoss()
    }

    fun selectServer(s: ServerItem) {
        val changed = currentServer?.link != s.link
        currentServer = s
        protocol = PROTO_XRAY
        prefs.edit()
            .putString("current_link", s.link)
            .putString("protocol", PROTO_XRAY)
            .apply()
        // VPN ishlab turgan bo'lsa va server o'zgargan bo'lsa — qayta ulanamiz
        if (isRunning && changed) {
            restartVpn(getString(R.string.reason_new_server, s.displayName()))
        }
    }

    fun selectAWG(c: AWGConfig) {
        val changed = currentAWG?.rawConf != c.rawConf
        currentAWG = c
        protocol = PROTO_AWG
        prefs.edit()
            .putString("current_awg", c.rawConf)
            .putString("protocol", PROTO_AWG)
            .apply()
        AWGEditorBus.init(awgConfigs, c, protocol)
        // VPN ishlab turgan bo'lsa va AWG o'zgargan bo'lsa — qayta ulanamiz
        if (isRunning && changed) {
            restartVpn(getString(R.string.reason_awg, c.name ?: "Config"))
        }
    }

    /** VPN ishlab turganda server o'zgarsa — qayta ulanish. */
    fun restartVpn(reason: String) {
        // ═══ GENERATION: faqat eng oxirgi restart ishlaydi ═══
        val myGen = ++restartGeneration
        android.util.Log.i("NurVPN-DBG", "restartVpn[$myGen]: $reason")
        Toast.makeText(this, getString(R.string.toast_reconnecting, reason), Toast.LENGTH_SHORT).show()

        val si = Intent(this, NurVpnService::class.java)
        si.putExtra(EXTRA_STOP, true)
        si.putExtra(EXTRA_FORCE, true)
        si.putExtra("generation", myGen)
        startService(si)
        connectStart = 0
        isRunning = false

        // 2.5 sekund — cache file lock bo'shash uchun
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // ═══ Faqat oxirgi restart davom etadi ═══
            if (myGen != restartGeneration) {
                android.util.Log.i("NurVPN-DBG",
                    "restartVpn[$myGen]: bekor (yangi gen $restartGeneration)")
                return@postDelayed
            }
            connectStart = System.currentTimeMillis()
            val si2 = Intent(this, NurVpnService::class.java)
            si2.putExtra(EXTRA_FORCE, true)
            si2.putExtra("generation", myGen)
            val cur = currentServer
            if (cur != null) {
                si2.putExtra(EXTRA_LINK, cur.link)
                si2.putExtra(EXTRA_NAME, cur.displayName())
            } else if (currentAWG != null) {
                si2.putExtra(EXTRA_AWG, currentAWG!!.rawConf)
            }
            si2.putExtra("protocol", protocol)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si2)
            else startService(si2)
        }, 2500)
    }

    fun startVpn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // ═══ VPN ruxsatini faqat shu yerda so'raymiz ═══
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // Ruxsat berilmagan — dialog ochamiz
            startActivityForResult(vpnIntent, 1001)
            return
        }
        doStartVpn()
    }

    private fun doStartVpn() {
        val i = Intent(this, NurVpnService::class.java)
        if (PROTO_AWG == protocol) {
            val awg = currentAWG ?: return
            i.putExtra(EXTRA_AWG, awg.rawConf)
            i.putExtra(EXTRA_NAME, awg.name ?: "AWG")
        } else {
            val s = currentServer ?: return
            i.putExtra(EXTRA_LINK, s.link)
            i.putExtra(EXTRA_NAME, s.displayName())
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i)
        else startService(i)
    }

    fun stopVpn() {
        val i = Intent(this, NurVpnService::class.java)
        i.putExtra(EXTRA_STOP, true)
        startService(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stateReceiver?.let { unregisterReceiver(it) }
        } catch (ignored: Throwable) {}
        try {
            dbgReceiver?.let { unregisterReceiver(it) }
            dbgReceiver = null
        } catch (ignored: Throwable) {}
        stateReceiver = null
    }
}

// ═══════════ VPN SERVICE ═══════════

// ═══════════ VPN SERVICE (libbox API) ═══════════

class NurVpnService : VpnService() {

    companion object {
        const val CH_ID = "vpn"
        const val NOTIF_ID = 1
        const val TAG = "NurVPN-Svc"
    }

    /** Start/Stop bir vaqtda chaqirilishini oldini oladi. */
    @Volatile private var isTransitioning = false

    /** Oxirgi start/stop vaqti (debounce uchun). */
    @Volatile private var lastToggleTime = 0L

    /** Service generation — eski START'ni o'tkazib yuborish uchun. */
    @Volatile private var serviceGeneration = 0L

    /** Pause holati (notification'dan boshqariladi). */
    @Volatile private var isPaused = false

    /** Oxirgi ishlatilgan libbox JSON config (resume uchun). */
    @Volatile private var lastConfigJson: String? = null

    /** Oxirgi connect intent (resume uchun). */
    @Volatile private var lastConnectIntent: Intent? = null

    private var tun: ParcelFileDescriptor? = null
    private var server: CommandServer? = null
    private var underlyingNetwork: android.net.Network? = null

    // ═══════ PLATFORM INTERFACE ═══════
    private val platform = object : PlatformInterface {

        override fun openTun(options: TunOptions): Int {
            Log.i(TAG, "═══ openTun CHAQIRILDI ═══")
            val builder = Builder()
            builder.setSession("NurVPN")

            // ★ Manzil faqat libbox config'dan keladi — qo'lda qo'shmaymiz
            val it4 = options.inet4Address
            var addrCount = 0
            while (it4.hasNext()) {
                val p = it4.next()
                Log.i(TAG, "libbox inet4: ${p.address()}/${p.prefix()}")
                builder.addAddress(p.address(), p.prefix())
                addrCount++
            }
            Log.i(TAG, "openTun: $addrCount libbox address")
            val it6 = options.inet6Address
            while (it6.hasNext()) {
                val p = it6.next()
                runCatching { builder.addAddress(p.address(), p.prefix()) }
            }
            val rt4 = options.inet4RouteAddress
            var rtCount = 0
            while (rt4.hasNext()) {
                val p = rt4.next()
                Log.i(TAG, "addRoute v4: ${p.address()}/${p.prefix()}")
                builder.addRoute(p.address(), p.prefix())
                rtCount++
            }
            Log.i(TAG, "Jami v4 route: $rtCount")
            if (rtCount == 0) {
                Log.w(TAG, "BO'SH route -> 0.0.0.0/0 qo'shamiz")
                builder.addRoute("0.0.0.0", 0)
            }
            val rt6 = options.inet6RouteAddress
            while (rt6.hasNext()) {
                val p = rt6.next()
                runCatching { builder.addRoute(p.address(), p.prefix()) }
            }
            // ═══ DNS LEAK PROTECTION ═══
            // 1. DNS prefs'dan
            val dnsPref = getSharedPreferences("main", Context.MODE_PRIVATE)
                .getString("dns", "1.1.1.1") ?: "1.1.1.1"
            val appCtx = this@NurVpnService
            val dnsLeakEnabled = DNSLeakProtection.isEnabled(appCtx)
            val ipv6Blocked = IPv6Blocker.isBlocked(appCtx)
            Log.i(TAG, "DNS: pref=$dnsPref, leakProtect=$dnsLeakEnabled, ipv6Block=$ipv6Blocked")

            // 2. IPv4 DNS — har doim (VPN orqali)
            if (dnsPref.isNotEmpty()) {
                runCatching { builder.addDnsServer(dnsPref) }
                    .onFailure { Log.w(TAG, "addDnsServer v4 xato: ${it.message}") }
            }

            // 3. IPv6 siyosati:
            //    A) IPv6 bloklangan → DNS va route qo'shilmaydi (IPv6 o'chiriladi)
            //    B) IPv6 bloklanmagan + DNS leak ON → IPv6 DNS + route (VPN orqali)
            //    C) Ikkalasi OFF → IPv6 ochiq (leak xavfi)
            when {
                ipv6Blocked -> {
                    Log.i(TAG, "IPv6 bloklangan — ::/0 route qo'shamiz (leak oldini olish)")
                    // MUHIM: IPv6 route qo'shmasak, tizim pastdagi interfeysdan (ccmni1)
                    // foydalanadi va IPv6 LEAK bo'ladi.
                    // ::/0 route qo'shsak, IPv6 trafik VPN ichidan o'tadi.
                    runCatching { builder.addRoute("::", 0) }
                        .onFailure { Log.w(TAG, "addRoute v6 xato: ${it.message}") }
                }
                dnsLeakEnabled -> {
                    // IPv6 DNS — VPN orqali
                    runCatching { builder.addDnsServer("2606:4700:4700::1111") }
                        .onFailure { Log.w(TAG, "addDnsServer v6 xato: ${it.message}") }
                    // IPv6 route — VPN orqali
                    runCatching { builder.addRoute("::", 0) }
                        .onFailure { Log.w(TAG, "addRoute v6 xato: ${it.message}") }
                    Log.i(TAG, "IPv6 VPN orqali (leak himoya ON)")
                }
                else -> {
                    // Ikkalasi ham OFF — IPv6 route qo'shamiz (leak himoyasiz)
                    runCatching { builder.addRoute("::", 0) }
                    Log.w(TAG, "IPv6 ochiq — DNS leak xavfi bor!")
                }
            }
            builder.setMtu(options.mtu)
            if (options.strictRoute) {
                runCatching { builder.setBlocking(true) }
            }
            // ═══════ SPLIT TUNNELING — EXCLUSIVE MODE ═══════
            // Replit AI: addAllowedApplication va addDisallowedApplication
            // birga chaqirilmaydi (mutually exclusive)

            val includePackages = linkedSetOf<String>()
            val excludePackages = linkedSetOf<String>()

            val inc = options.includePackage
            while (inc.hasNext()) includePackages.add(inc.next())

            val exc = options.excludePackage
            while (exc.hasNext()) excludePackages.add(exc.next())

            val splitMode = SplitTunnelStore.getMode(this@NurVpnService)
            val selectedApps = SplitTunnelStore.getApps(this@NurVpnService)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            Log.e(TAG, "★★★★★ SPLIT: mode=$splitMode, selectedApps=${selectedApps.size}, inc=${includePackages.size}, exc=${excludePackages.size}")

            when (splitMode) {
                SplitTunnelStore.MODE_WHITELIST -> {
                    // FAQAT addAllowedApplication
                    val allowed = linkedSetOf<String>()
                    allowed.addAll(includePackages)
                    allowed.addAll(selectedApps)
                    allowed.remove(packageName)  // o'zimizni chiqaramiz

                    for (pkg in allowed) {
                        try {
                            builder.addAllowedApplication(pkg)
                            Log.e(TAG, "★★★★★ WHITELIST allowed: $pkg")
                        } catch (e: Exception) {
                            Log.w(TAG, "WHITELIST fail $pkg: ${e.message}")
                        }
                    }
                }
                SplitTunnelStore.MODE_BLACKLIST -> {
                    // FAQAT addDisallowedApplication
                    val disallowed = linkedSetOf<String>()
                    disallowed.addAll(excludePackages)
                    disallowed.addAll(selectedApps)
                    disallowed.add(packageName)  // o'zimizni chiqaramiz

                    for (pkg in disallowed) {
                        try {
                            builder.addDisallowedApplication(pkg)
                            Log.e(TAG, "★★★★★ BLACKLIST disallowed: $pkg")
                        } catch (e: Exception) {
                            Log.w(TAG, "BLACKLIST fail $pkg: ${e.message}")
                        }
                    }
                }
                else -> {
                    // MODE_ALL — hamma VPN orqali, faqat o'zimiz tashqarida
                    val disallowed = linkedSetOf<String>()
                    disallowed.addAll(excludePackages)
                    disallowed.add(packageName)

                    for (pkg in disallowed) {
                        try {
                            builder.addDisallowedApplication(pkg)
                            Log.e(TAG, "★★★★★ ALL disallowed: $pkg")
                        } catch (e: Exception) {
                            Log.w(TAG, "ALL fail $pkg: ${e.message}")
                        }
                    }
                }
            }
            if (getSharedPreferences("main", Context.MODE_PRIVATE)
                    .getBoolean("kill_switch", false)) {
                builder.setBlocking(true)
            }

            // ★★★ MUHIM: real upstream network'ni o'rnatamiz ★★★
            val un: android.net.Network? = this@NurVpnService.underlyingNetwork
            if (un != null) {
                try {
                    val arr = arrayOf<android.net.Network>(un)
                    builder.setUnderlyingNetworks(arr)
                    Log.e(TAG, "★★★★★ setUnderlyingNetworks($un) OK ★★★★★")
                } catch (t: Throwable) {
                    Log.e(TAG, "setUnderlyingNetworks fail", t)
                }
            } else {
                try {
                    builder.setUnderlyingNetworks(null)
                    Log.e(TAG, "★★★★★ setUnderlyingNetworks(null) ★★★★★")
                } catch (ignored: Throwable) {}
            }
            // ★★★ MUHIM: o'zimizni VPN'dan chiqarish HAR BIR rejimda
            // (MODE_WHITELIST, MODE_BLACKLIST, MODE_ALL)
            // ═══ ENDI YUQORIDAGI when ICHIDA BAJARILDI ═══

            tun = builder.establish() ?: throw Exception("TUN ochilmadi")
            Log.i(TAG, "═══ TUN OCHILDI: fd=${tun!!.fd} ═══")
            return tun!!.fd
        }

        override fun autoDetectInterfaceControl(fd: Int) {
            Log.e(TAG, "★★★★★ autoDetectInterfaceControl(fd=$fd) ★★★★★")
            val ok = protect(fd)
            Log.e(TAG, "★★★★★ protect(fd=$fd) -> $ok ★★★★★")
        }

        override fun clearDNSCache() { }

        private var ifaceCallback: android.net.ConnectivityManager.NetworkCallback? = null

        private fun isTunOrVpn(name: String, caps: android.net.NetworkCapabilities?): Boolean {
            val tun = name.startsWith("tun") || name.startsWith("ppp") ||
                name.startsWith("ipsec")
            val vpn = caps != null && caps.hasTransport(
                android.net.NetworkCapabilities.TRANSPORT_VPN)
            val r = tun || vpn
            Log.i(TAG, "isTunOrVpn(name=$name, tun=$tun, vpn=$vpn) -> $r")
            return r
        }

        private fun pushDefaultInterface(l: InterfaceUpdateListener) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager

                // Eng yaxshi nomzodni topamiz: tun/vpn BO'LMAGAN, INTERNET bor
                var bestName: String? = null
                var bestIdx = 0
                var bestMetered = false

                for (n in cm.allNetworks) {
                    val lp = cm.getLinkProperties(n) ?: continue
                    val name = lp.interfaceName ?: continue
                    val caps = cm.getNetworkCapabilities(n)
                    if (isTunOrVpn(name, caps)) continue
                    if (caps == null) continue
                    if (!caps.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        continue
                    // Validated network afzal
                    bestName = name
                    bestIdx = java.net.NetworkInterface.getByName(name)?.index ?: 0
                    bestMetered = !caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (caps.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        break
                }

                if (bestName.isNullOrEmpty()) {
                    Log.w(TAG, "pushDefaultInterface: mos interfeys topilmadi")
                    return
                }

                Log.i(TAG, "═══ updateDefaultInterface(name=$bestName, idx=$bestIdx, metered=$bestMetered) ═══")
                l.updateDefaultInterface(bestName, bestIdx, bestMetered, false)
            } catch (t: Throwable) {
                Log.e(TAG, "pushDefaultInterface fail", t)
            }
        }

        override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener?) {
            Log.e(TAG, "★★★★★ startDefaultInterfaceMonitor CHAQIRILDI ★★★★★")
            if (l == null) return
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                pushDefaultInterface(l)
                val req = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        Log.i(TAG, "onAvailable: $network")
                        pushDefaultInterface(l)
                    }
                    override fun onLost(network: android.net.Network) {
                        Log.i(TAG, "onLost: $network")
                    }
                    override fun onLinkPropertiesChanged(
                        network: android.net.Network,
                        lp: android.net.LinkProperties
                    ) {
                        pushDefaultInterface(l)
                    }
                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        caps: android.net.NetworkCapabilities
                    ) {
                        pushDefaultInterface(l)
                    }
                }
                ifaceCallback = cb
                cm.registerNetworkCallback(req, cb)
            } catch (t: Throwable) {
                Log.e(TAG, "startDefaultInterfaceMonitor fail", t)
            }
        }

        override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener?) {
            Log.i(TAG, "═══ closeDefaultInterfaceMonitor ═══")
            try {
                ifaceCallback?.let {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                    cm.unregisterNetworkCallback(it)
                }
            } catch (ignored: Throwable) {}
            ifaceCallback = null
        }

        override fun findConnectionOwner(
            protocol: Int, sourceAddress: String?, sourcePort: Int,
            destinationAddress: String?, destinationPort: Int
        ): ConnectionOwner = ConnectionOwner()

        override fun getInterfaces(): NetworkInterfaceIterator {
            val list = ArrayList<io.nekohasekai.libbox.NetworkInterface>()
            try {
                for (jni in java.util.Collections.list(
                        java.net.NetworkInterface.getNetworkInterfaces())) {
                    try {
                        // loopback va past interfeyslarni o'tkazib yuboramiz
                        if (jni.isLoopback) continue
                        if (!jni.isUp) continue

                        val ni = io.nekohasekai.libbox.NetworkInterface()
                        ni.setName(jni.name)
                        ni.setIndex(jni.index)
                        ni.setMTU(if (jni.mtu > 0) jni.mtu else 1500)
                        var flags = 0
                        if (jni.isUp) flags = flags or 0x1
                        if (jni.supportsMulticast()) flags = flags or 0x1000
                        ni.setFlags(flags)

                        // ═══ CIDR formatida manzillar ═══
                        val addrs = ArrayList<String>()
                        for (ifa in jni.interfaceAddresses) {
                            try {
                                val ia = ifa.address ?: continue
                                val ha = ia.hostAddress ?: continue
                                val prefix = ifa.networkPrefixLength.toInt()
                                val clean = if (ha.contains("%"))
                                    ha.substringBefore("%") else ha
                                addrs.add("$clean/$prefix")
                            } catch (t: Throwable) {}
                        }
                        ni.setAddresses(stringIterator(addrs))
                        ni.setDNSServer(stringIterator(emptyList()))
                        ni.setGateway(stringIterator(emptyList()))
                        ni.setMetered(false)

                        val t = when {
                            jni.name.startsWith("wlan") -> 1
                            jni.name.startsWith("rmnet") ||
                                jni.name.startsWith("ccmni") -> 2
                            jni.name.startsWith("eth") -> 3
                            else -> 0
                        }
                        ni.setType(t)
                        list.add(ni)
                        Log.i(TAG, "iface: ${jni.name} idx=${jni.index} addrs=$addrs")
                    } catch (t: Throwable) {
                        Log.w(TAG, "skip ${jni.name}: ${t.message}")
                    }
                }
                Log.i(TAG, "getInterfaces() -> ${list.size} ta (CIDR)")
            } catch (t: Throwable) {
                Log.e(TAG, "getInterfaces fail", t)
            }
            return object : NetworkInterfaceIterator {
                private var i = 0
                override fun hasNext(): Boolean = i < list.size
                override fun next(): io.nekohasekai.libbox.NetworkInterface =
                    list[i++]
            }
        }

        private fun stringIterator(list: List<String>):
            io.nekohasekai.libbox.StringIterator {
            return object : io.nekohasekai.libbox.StringIterator {
                private var i = 0
                override fun hasNext(): Boolean = i < list.size
                override fun next(): String = list[i++]
                override fun len(): Int = list.size
            }
        }

        private fun networkInterfaceIterator(
            list: List<io.nekohasekai.libbox.NetworkInterface>
        ): NetworkInterfaceIterator {
            return object : NetworkInterfaceIterator {
                private var i = 0
                override fun hasNext(): Boolean = i < list.size
                override fun next(): io.nekohasekai.libbox.NetworkInterface =
                    list[i++]
            }
        }

        override fun includeAllNetworks(): Boolean = false

        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun readWIFIState(): WIFIState? = null

        override fun sendNotification(n: io.nekohasekai.libbox.Notification?) { }

        override fun underNetworkExtension(): Boolean = false

        override fun usePlatformAutoDetectInterfaceControl(): Boolean {
            Log.e(TAG, "★★★★★ usePlatformAutoDetect CHAQIRILDI ★★★★★")
            return true
        }

        override fun useProcFS(): Boolean = false

        // ═══ YANGI METODLAR (1.14+) ═══

        override fun cancelNotification(tag: String?, id: Int) { }

        override fun checkPlatformShell() { }

        override fun closeNeighborMonitor(l: NeighborUpdateListener?) { }

        override fun createBridge(options: BridgeOptions?): BridgeSession? = null

        override fun lookupSFTPServer(): String? = null

        override fun lookupUser(username: String?): PlatformUser? = null

        override fun openShellSession(
            user: PlatformUser?, command: String?,
            args: StringIterator?, env: String?,
            rows: Int, cols: Int
        ): ShellSession? = null

        override fun readSystemSSHHostKey(): String? = null

        override fun registerMyInterface(name: String?) { }

        override fun startNeighborMonitor(l: NeighborUpdateListener?) { }

        override fun tailscaleHostname(): String? = null

        override fun usePlatformBridge(): Boolean = false

        override fun usePlatformShell(): Boolean = false
    }

    // ═══════ COMMAND SERVER HANDLER ═══════
    private val handler = object : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus()

        override fun serviceReload() { }

        override fun serviceStop() {
            stopSelf()
        }

        override fun setSystemProxyEnabled(enabled: Boolean) { }

        override fun writeDebugMessage(message: String?) {
            if (message != null) Log.i(TAG, "[box] $message")
        }

        // ═══ YANGI METODLAR (1.14+) ═══

        override fun connectSSHAgent(): Int = -1

        override fun triggerNativeCrash() { }
    }

    // ═══════ LIFECYCLE ═══════
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "═══ onStartCommand: ${intent?.action}")
        // ═══ DEBOUNCE: tez-tez toggle'dan himoya ═══
        // FORCE flag — restartVpn uchun debounce'ni chetlab o'tish
        val forced = intent?.getBooleanExtra(MainActivity.EXTRA_FORCE, false) == true
        val now = System.currentTimeMillis()
        if (!forced && now - lastToggleTime < 1500) {
            Log.w(TAG, "Debounce: toggle juda tez (${now - lastToggleTime}ms), e'tiborsiz")
            return START_STICKY
        }
        if (forced) Log.i(TAG, "FORCE flag — debounce chetlab o'tildi")

        // ═══ PAUSE ═══
        if (intent?.getBooleanExtra(MainActivity.EXTRA_PAUSE, false) == true) {
            Log.i(TAG, "PAUSE so'rovi")
            if (!isPaused) {
                isPaused = true
                try {
                    server?.closeService()
                } catch (t: Throwable) {
                    Log.w(TAG, "pause closeService: ${t.message}")
                }
                try {
                    server?.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "pause close: ${t.message}")
                }
                server = null
                // TUN ham yopiladi (keyin RESUME'da qaytadan ochiladi)
                try { tun?.close() } catch (_: Throwable) {}
                tun = null

                // Cache faylni tozalash (libbox cache-file lock)
                try {
                    val cacheDir = java.io.File(cacheDir, "sing-box")
                    cacheDir.deleteRecursively()
                    Log.i(TAG, "PAUSE: cache tozalandi")
                } catch (t: Throwable) {
                    Log.w(TAG, "pause cache tozalash: ${t.message}")
                }

                broadcast("paused")
                updateNotification(getString(R.string.notif_paused),
                    withActions = true)
                Log.i(TAG, "PAUSE: to'liq to'xtatildi (TUN + cache)")
            }
            return START_STICKY
        }

        // ═══ RESUME ═══
        if (intent?.getBooleanExtra(MainActivity.EXTRA_RESUME, false) == true) {
            Log.i(TAG, "RESUME so'rovi")
            if (isPaused) {
                isPaused = false
                val cfg = lastConfigJson
                val savedIntent = lastConnectIntent
                if (cfg != null && savedIntent != null) {
                    Thread {
                        try {
                            // To'liq qayta ulash (xuddi birinchi marta kabi)
                            connect(savedIntent)
                            Log.i(TAG, "RESUME OK")
                        } catch (t: Throwable) {
                            Log.e(TAG, "resume xato", t)
                            broadcast("error", t.message)
                        }
                    }.start()
                }
            }
            return START_STICKY
        }

        if (intent?.getBooleanExtra(MainActivity.EXTRA_STOP, false) == true) {
            val stopGen = intent?.getLongExtra("generation", 0L) ?: 0L
            Log.i(TAG, "STOP so'rovi gen=$stopGen")
            lastToggleTime = now
            isTransitioning = true
            broadcast("disconnected")
            // Foreground notification'ni DARHOL olib tashlaymiz
            try {
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                Log.i(TAG, "stopForeground OK")
            } catch (t: Throwable) {
                Log.w(TAG, "stopForeground xato: ${t.message}")
            }
            cleanup()
            stopSelf()
            isTransitioning = false
            return START_NOT_STICKY
        }

        // ═══ START: agar transition bo'layotgan bo'lsa, kutamiz ═══
        if (isTransitioning) {
            Log.w(TAG, "Start: transition davom etmoqda, 500ms kuting")
            Thread {
                try { Thread.sleep(500) } catch (_: Throwable) {}
                if (!isTransitioning) {
                    startForegroundWithNotif()
                    connect(intent)
                }
            }.start()
            return START_STICKY
        }

        // ═══ GENERATION check ═══
        val myGen = intent?.getLongExtra("generation", 0L) ?: 0L
        if (myGen > 0L) {
            serviceGeneration = myGen
            android.util.Log.i(TAG, "START gen=$myGen")
        }

        lastToggleTime = now
        isTransitioning = true
        lastConnectIntent = intent
        startForegroundWithNotif()
        Thread {
            try {
                // ═══ Eski serverni tozalash + cache bo'shatish ═══
                runCatching { server?.closeService() }
                runCatching { server?.close() }
                server = null
                // Cache faylni tozalash (lock bo'shash uchun)
                try {
                    val cache = java.io.File(cacheDir, "sing-box")
                    if (cache.exists()) {
                        cache.deleteRecursively()
                        android.util.Log.i(TAG, "Cache tozalandi (START)")
                    }
                } catch (_: Throwable) {}
                // Qisqa kutish (fayl tizimi)
                Thread.sleep(300)

                // ═══ Faqat eng oxirgi gen ishlaydi ═══
                if (myGen > 0L && myGen != serviceGeneration) {
                    android.util.Log.i(TAG,
                        "START[$myGen]: bekor qilindi (yangi gen $serviceGeneration)")
                    return@Thread
                }

                connect(intent)
            } finally {
                isTransitioning = false
            }
        }.start()
        return START_STICKY
    }

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CH_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            })
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_connecting))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(buildPauseAction(false))
            .addAction(buildStopAction())
            .addAction(buildSettingsAction())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            Log.i(TAG, "startForeground OK: enabled=${nm.areNotificationsEnabled()}")
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground FAIL: ${t.message}", t)
        }
    }

    private fun buildPauseAction(isPaused: Boolean): NotificationCompat.Action {
        val label = if (isPaused) getString(R.string.notif_action_resume)
                    else getString(R.string.notif_action_pause)
        val icon = if (isPaused) android.R.drawable.ic_media_play
                   else android.R.drawable.ic_media_pause
        val i = Intent(this, NurVpnService::class.java).apply {
            if (isPaused) putExtra(MainActivity.EXTRA_RESUME, true)
            else putExtra(MainActivity.EXTRA_PAUSE, true)
        }
        val pi = PendingIntent.getService(this, if (isPaused) 3 else 2, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(icon, label, pi).build()
    }

    private fun buildStopAction(): NotificationCompat.Action {
        val i = Intent(this, NurVpnService::class.java).apply {
            putExtra(MainActivity.EXTRA_STOP, true)
        }
        val pi = PendingIntent.getService(this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.notif_action_stop), pi).build()
    }

    private fun buildSettingsAction(): NotificationCompat.Action {
        val i = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 4, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_preferences,
            getString(R.string.notif_action_settings), pi).build()
    }

    private fun broadcast(state: String, err: String? = null) {
        // ═══ TunnelState'ni DARHOL yangilash (tile uchun) ═══
        when (state) {
            "connected" -> TunnelState.isConnected = true
            "disconnected", "error" -> TunnelState.isConnected = false
            "paused" -> TunnelState.isConnected = false
        }

        val i = Intent(MainActivity.ACTION_BROADCAST)
        i.setPackage(packageName)
        i.putExtra(MainActivity.EXTRA_STATE, state)
        if (err != null) i.putExtra(MainActivity.EXTRA_ERR, err)
        sendBroadcast(i)

        // ═══ QS Tile'ni yangilash ═══
        try {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, NurVpnTileService::class.java))
            Log.d(TAG, "Tile refresh requested (state=$state)")
        } catch (t: Throwable) {
            Log.w(TAG, "Tile refresh xato: ${t.message}")
        }
    }

    // ═══════ CONNECT ═══════
    private fun connect(intent: Intent?) {
        val link = intent?.getStringExtra(MainActivity.EXTRA_LINK)
        val awg = intent?.getStringExtra(MainActivity.EXTRA_AWG)
        val name = intent?.getStringExtra(MainActivity.EXTRA_NAME) ?: "NurVPN"
        val dns = getSharedPreferences("main", Context.MODE_PRIVATE)
            .getString("dns", "1.1.1.1") ?: "1.1.1.1"

        try {
            if (link == null && awg == null) {
                throw Exception(getString(R.string.error_no_server))
            }

            val cfg = if (awg != null) {
                Log.i(TAG, "AWG rejimi: ${awg.length} belgi config")
                SingBoxConfig.buildAwgConfig(awg)
            } else {
                val cacheDirSafe = File(filesDir, "cache").apply { mkdirs() }
                val cachePath = File(cacheDirSafe, "cache.db").absolutePath
                runCatching {
                    if (!File(cachePath).exists()) File(cachePath).createNewFile()
                }
                SingBoxConfig.buildFullConfig(link!!, dns, cachePath)
            }
            val cfgJson = cfg.toString(2)
            Log.i(TAG, "Config tayyor: ${cfgJson.length} belgi")

            // Debug uchun faylga ham yozamiz
            runCatching {
                File(filesDir, "config.json").writeText(cfgJson)
            }

            // Libbox setup
            // ═══ MUHIM: cacheDir emas, filesDir ishlatamiz ═══
            // MIUI cacheDir'da flock timeout beradi
            val workDir = File(filesDir, "work").apply { mkdirs() }
            val setup = SetupOptions()
            setup.basePath = filesDir.absolutePath
            setup.workingPath = workDir.absolutePath
            setup.tempPath = workDir.absolutePath
            setup.fixAndroidStack = true
            setup.debug = false
            Libbox.setup(setup)
            Log.i(TAG, "Libbox.setup: workDir=${workDir.absolutePath}")
            Log.i(TAG, "Libbox.setup OK")

            // Command server
            server = Libbox.newCommandServer(handler, platform)
            server!!.start()
            Log.i(TAG, "CommandServer.start OK")

            // Service ishga tushirish — JSON matnini beramiz
            lastConfigJson = cfgJson  // resume uchun
            isPaused = false
            val options = io.nekohasekai.libbox.OverrideOptions()
            server!!.startOrReloadService(cfgJson, options)
            Log.i(TAG, "startOrReloadService OK")

            // Log faylini saqlash (keyingi diagnostika uchun)
            runCatching {
                val logDir = File(filesDir, "logs").apply { mkdirs() }
                val logFile = File(logDir, "nurvpn-box.log")
                if (logFile.exists()) logFile.delete()
                Log.i(TAG, "Log fayl: ${logFile.absolutePath}")
            }

            broadcast("connected")
            updateNotification(getString(R.string.notif_connected),
                withActions = true)
        } catch (t: Throwable) {
            Log.e(TAG, "connect fail", t)
            broadcast("error", t.message ?: "noma'lum xato")
            cleanup()
            stopSelf()
        }
    }

    /** Notification matnini yangilash. */
    private fun updateNotification(text: String, withActions: Boolean = false) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(this, CH_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (withActions) {
                builder.addAction(buildPauseAction(isPaused))
                builder.addAction(buildStopAction())
                builder.addAction(buildSettingsAction())
            }
            val n = builder.build()
            nm.notify(NOTIF_ID, n)
        } catch (t: Throwable) {
            Log.e(TAG, "updateNotification fail: ${t.message}", t)
        }
    }

    private fun cleanup() {
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        runCatching { tun?.close() }
        server = null
        tun = null
    }

    override fun onDestroy() {
        // Notification'ni tozalash (service o'lganda ham)
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {}

        cleanup()
        broadcast("disconnected")
        super.onDestroy()
    }

    override fun onRevoke() {
        cleanup()
        broadcast("disconnected")
        stopSelf()
        super.onRevoke()
    }
}

// ═══════════ BINARY RUNNER (endi kerak emas) ═══════════
object BinaryRunner {
    @Suppress("unused")
    fun ensureBinary(ctx: Context, name: String): File? = null


// ═══════════ ITERATOR HELPERS ═══════════



}
