# NurVPN — To'liq kod

> Barcha Kotlin fayllar bitta faylda. Avtomatik yaratilgan.

**Jami fayllar:** 45

---

## 📁 Struktura

```
com/nurvpn/app/ai/AIInsights.kt
com/nurvpn/app/ai/AIServerSelector.kt
com/nurvpn/app/ai/SmartScoreEngine.kt
com/nurvpn/app/config/BuiltinAwgConfigs.kt
com/nurvpn/app/config/SingBoxConfig.kt
com/nurvpn/app/core/AWGConfig.kt
com/nurvpn/app/core/AWGEditorBus.kt
com/nurvpn/app/core/AppInfo.kt
com/nurvpn/app/core/Protocol.kt
com/nurvpn/app/core/ServerItem.kt
com/nurvpn/app/core/ServerMetrics.kt
com/nurvpn/app/core/Subscription.kt
com/nurvpn/app/core/TunnelState.kt
com/nurvpn/app/parser/AWGParser.kt
com/nurvpn/app/parser/Base64Util.kt
com/nurvpn/app/parser/ServerLinkParser.kt
com/nurvpn/app/parser/SubscriptionLinkExtractor.kt
com/nurvpn/app/service/BinaryRunner.kt
com/nurvpn/app/service/NurVpnService.kt
com/nurvpn/app/service/NurVpnTileService.kt
com/nurvpn/app/storage/AWGStore.kt
com/nurvpn/app/storage/AppListLoader.kt
com/nurvpn/app/storage/AwgSortStore.kt
com/nurvpn/app/storage/MetricsStore.kt
com/nurvpn/app/storage/OpenSourceSubscriptions.kt
com/nurvpn/app/storage/ServerStore.kt
com/nurvpn/app/storage/SplitTunnelStore.kt
com/nurvpn/app/storage/SubscriptionStore.kt
com/nurvpn/app/ui/MainActivity.kt
com/nurvpn/app/ui/awg/AWGEditorActivity.kt
com/nurvpn/app/ui/home/HomeFragment.kt
com/nurvpn/app/ui/qr/QrScanActivity.kt
com/nurvpn/app/ui/qr/QrShowDialog.kt
com/nurvpn/app/ui/servers/ServersFragment.kt
com/nurvpn/app/ui/settings/SettingsFragment.kt
com/nurvpn/app/ui/split/SplitAppsActivity.kt
com/nurvpn/app/ui/widget/AICardView.kt
com/nurvpn/app/ui/widget/SpeedWaveView.kt
com/nurvpn/app/util/AWGEditor.kt
com/nurvpn/app/util/ClashApiConfig.kt
com/nurvpn/app/util/CountryLookup.kt
com/nurvpn/app/util/LeakTester.kt
com/nurvpn/app/util/PingTester.kt
com/nurvpn/app/util/QrGenerator.kt
com/nurvpn/app/util/ThemeHelper.kt
```

---

## 📄 `com/nurvpn/app/ai/AIInsights.kt`

*10 qator*

```kotlin
package com.nurvpn.app.ai

class AIInsights(
    @JvmField val link: String,
    @JvmField val score: Double,
    @JvmField val name: String,
    @JvmField val summary: String
)

```

---

## 📄 `com/nurvpn/app/ai/AIServerSelector.kt`

*123 qator*

```kotlin
package com.nurvpn.app.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.storage.MetricsStore
import com.nurvpn.app.util.PingTester

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

```

---

## 📄 `com/nurvpn/app/ai/SmartScoreEngine.kt`

*18 qator*

```kotlin
package com.nurvpn.app.ai

import com.nurvpn.app.core.ServerMetrics

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

```

---

## 📄 `com/nurvpn/app/config/BuiltinAwgConfigs.kt`

*73 qator*

```kotlin
package com.nurvpn.app.config

/**
 * Ilova ichida saqlangan AmneziaWG (WARP) config'lar.
 * Foydalanuvchi Sozlamalar → Ochiq manbalar bo'limidan yoqishi mumkin.
 */
object BuiltinAwgConfigs {

    val WARP_CF1: String = """[Interface]
Address = 172.16.0.2
MTU = 1280
PrivateKey = pVx1J46LOHdvnBVT4JGSyzFX9bihA6f+7TdV6zDqmxI=
Jc = 4
Jmin = 40
Jmax = 70
H1 = 1
H2 = 2
H3 = 3
H4 = 4
I1 = <b 0x494e56495445207369703a626f624062696c6f78692e636f6d205349502f322e300d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a4d61782d466f7277617264733a2037300d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e746163743a203c7369703a616c69636540706333332e61746c616e74612e636f6d3e0d0a436f6e74656e742d547970653a206170706c69636174696f6e2f7364700d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>
I2 = <b 0x5349502f322e302031303020547279696e670d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>

[Peer]
Endpoint = 162.159.192.38:1074
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
PersistentKeepalive = 25"""

    val WARP_CF2: String = """[Interface]
Address = 172.16.0.2, 2606:4700:110:8915:6542:5487:4c8a:88dc
MTU = 1280
PrivateKey = xUQ2W9wWbkydQRNEtehnJWTo73SNebzowr5uBhljerI=
Jc = 4
Jmin = 40
Jmax = 70
H1 = 1
H2 = 2
H3 = 3
H4 = 4
I1 = <b 0x494e56495445207369703a626f624062696c6f78692e636f6d205349502f322e300d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a4d61782d466f7277617264733a2037300d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e746163743a203c7369703a616c69636540706333332e61746c616e74612e636f6d3e0d0a436f6e74656e742d547970653a206170706c69636174696f6e2f7364700d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>
I2 = <b 0x5349502f322e302031303020547279696e670d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>

[Peer]
Endpoint = engage.cloudflareclient.com:864
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
PersistentKeepalive = 25"""

    val WARP_CF3: String = """[Interface]
Address = 172.16.0.2, 2606:4700:110:86fb:bdae:3e42:1356:8e8d
MTU = 1380
PrivateKey = 35EbSQSll0ixOC/Gp/MqPkZKxmwtRMklU8tKpSqXGrw=
Jc = 4
Jmin = 40
Jmax = 70
H1 = 1
H2 = 2
H3 = 3
H4 = 4
I1 = <b 0xce000000010897a297ecc34cd6dd000044d0ec2e2e1ea2991f467ace4222129b5a098823784694b4897b9986ae0b7280135fa85e196d9ad980b150122129ce2a9379531b0fd3e871ca5fdb883c369832f730e272d7b8b74f393f9f0fa43f11e510ecb2219a52984410c204cf875585340c62238e14ad04dff382f2c200e0ee22fe743b9c6b8b043121c5710ec289f471c91ee414fca8b8be8419ae8ce7ffc53837f6ade262891895f3f4cecd31bc93ac5599e18e4f01b472362b8056c3172b513051f8322d1062997ef4a383b01706598d08d48c221d30e74c7ce000cdad36b706b1bf9b0607c32ec4b3203a4ee21ab64df336212b9758280803fcab14933b0e7ee1e04a7becce3e2633f4852585c567894a5f9efe9706a151b615856647e8b7dba69ab357b3982f554549bef9256111b2d67afde0b496f16962d4957ff654232aa9e845b61463908309cfd9de0a6abf5f425f577d7e5f6440652aa8da5f73588e82e9470f3b21b27b28c649506ae1a7f5f15b876f56abc4615f49911549b9bb39dd804fde182bd2dcec0c33bad9b138ca07d4a4a1650a2c2686acea05727e2a78962a840ae428f55627516e73c83dd8893b02358e81b524b4d99fda6df52b3a8d7a5291326e7ac9d773c5b43b8444554ef5aea104a738ed650aa979674bbed38da58ac29d87c29d387d80b526065baeb073ce65f075ccb56e47533aef357dceaa8293a523c5f6f790be90e4731123d3c6152a70576e90b4ab5bc5ead01576c68ab633ff7d36dcde2a0b2c68897e1acfc4d6483aaaeb635dd63c96b2b6a7a2bfe042f6aed82e5363aa850aace12ee3b1a93f30d8ab9537df483152a5527faca21efc9981b304f11fc95336f5b9637b174c5a0659e2b22e159a9fed4b8e93047371175b1d6d9cc8ab745f3b2281537d1c75fb9451871864efa5d184c38c185fd203de206751b92620f7c369e031d2041e152040920ac2c5ab5340bfc9d0561176abf10a147287ea90758575ac6a9f5ac9f390d0d5b23ee12af583383d994e22c0cf42383834bcd3ada1b3825a0664d8f3fb678261d57601ddf94a8a68a7c273a18c08aa99c7ad8c6c42eab67718843597ec9930457359dfdfbce024afc2dcf9348579a57d8d3490b2fa99f278f1c37d87dad9b221acd575192ffae1784f8e60ec7cee4068b6b988f0433d96d6a1b1865f4e155e9fe020279f434f3bf1bd117b717b92f6cd1cc9bea7d45978bcc3f24bda631a36910110a6ec06da35f8966c9279d130347594f13e9e07514fa370754d1424c0a1545c5070ef9fb2acd14233e8a50bfc5978b5bdf8bc1714731f798d21e2004117c61f2989dd44f0cf027b27d4019e81ed4b5c31db347c4a3a4d85048d7093cf16753d7b0d15e078f5c7a5205dc2f87e330a1f716738dce1c6180e9d02869b5546f1c4d2748f8c90d9693cba4e0079297d22fd61402dea32ff0eb69ebd65a5d0b687d87e3a8b2c42b648aa723c7c7daf37abcc4bb85caea2ee8f55bec20e913b3324ab8f5c3304f820d42ad1b9f2ffc1a3af9927136b4419e1e579ab4c2ae3c776d293d397d575df181e6cae0a4ada5d67ecea171cca3288d57c7bbdaee3befe745fb7d634f70386d873b90c4d6c6596bb65af68f9e5121e67ebf0d89d3c909ceedfb32ce9575a7758ff080724e1ab5d5f43074ecb53a479af21ed03d7b6899c36631c0166f9d47e5e1d4528a5d3d3f744029c4b1c190cbfbad06f5f83f7ad0429fa9a2719c56ffe3783460e166de2d8>

[Peer]
Endpoint = 8.34.146.9:878
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
PersistentKeepalive = 15"""

    val ALL: List<Pair<String, String>> = listOf(
        "warp_cf1" to WARP_CF1,
        "warp_cf2" to WARP_CF2,
        "warp_cf3" to WARP_CF3
    )

    fun byId(id: String): String? = ALL.firstOrNull { it.first == id }?.second
}
```

---

## 📄 `com/nurvpn/app/config/SingBoxConfig.kt`

*730 qator*

```kotlin
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

```

---

## 📄 `com/nurvpn/app/core/AWGConfig.kt`

*11 qator*

```kotlin
package com.nurvpn.app.core

class AWGConfig(@JvmField var rawConf: String?) {
    var name: String? = null
    var endpoint: String? = null
    var address: String? = null
    var ping: Int = -1
    var favorite: Boolean = false
}

```

---

## 📄 `com/nurvpn/app/core/AWGEditorBus.kt`

*14 qator*

```kotlin
package com.nurvpn.app.core

object AWGEditorBus {
    @JvmField var configs: MutableList<AWGConfig> = ArrayList()
    @JvmField var current: AWGConfig? = null
    @JvmField var protocol: String = "awg"

    fun init(cfg: MutableList<AWGConfig>, cur: AWGConfig?, proto: String) {
        configs = cfg
        current = cur
        protocol = proto
    }
}
```

---

## 📄 `com/nurvpn/app/core/AppInfo.kt`

*12 qator*

```kotlin
package com.nurvpn.app.core

import android.graphics.drawable.Drawable

class AppInfo {
    var label: String = ""
    var packageName: String = ""
    var icon: Drawable? = null
    var selected: Boolean = false
}

```

---

## 📄 `com/nurvpn/app/core/Protocol.kt`

*31 qator*

```kotlin
package com.nurvpn.app.core

enum class PingStrategy {
    TCP_CONNECT,
    HOST_RTT
}

enum class Protocol(
    val isUdp: Boolean,
    val pingStrategy: PingStrategy
) {
    VLESS_REALITY(false, PingStrategy.TCP_CONNECT),
    VMESS(false, PingStrategy.TCP_CONNECT),
    TROJAN(false, PingStrategy.TCP_CONNECT),
    SS_2022(false, PingStrategy.TCP_CONNECT),
    HYSTERIA2(true, PingStrategy.HOST_RTT),
    TUIC(true, PingStrategy.HOST_RTT);

    companion object {
        fun fromUri(uri: String): Protocol = when {
            uri.startsWith("hysteria2://", true) ||
            uri.startsWith("hy2://", true) -> HYSTERIA2
            uri.startsWith("tuic://", true) -> TUIC
            uri.startsWith("vmess://", true) -> VMESS
            uri.startsWith("trojan://", true) -> TROJAN
            uri.startsWith("ss://", true) -> SS_2022
            else -> VLESS_REALITY
        }
    }
}
```

---

## 📄 `com/nurvpn/app/core/ServerItem.kt`

*50 qator*

```kotlin
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
```

---

## 📄 `com/nurvpn/app/core/ServerMetrics.kt`

*43 qator*

```kotlin
package com.nurvpn.app.core

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

```

---

## 📄 `com/nurvpn/app/core/Subscription.kt`

*33 qator*

```kotlin
package com.nurvpn.app.core

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

```

---

## 📄 `com/nurvpn/app/core/TunnelState.kt`

*12 qator*

```kotlin
package com.nurvpn.app.core

import android.util.Log

object TunnelState {
    @Volatile var isConnected: Boolean = false
        set(v) {
            Log.d("NurVPN-PING", "TunnelState.isConnected → $v")
            field = v
        }
}
```

---

## 📄 `com/nurvpn/app/parser/AWGParser.kt`

*47 qator*

```kotlin
package com.nurvpn.app.parser

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

```

---

## 📄 `com/nurvpn/app/parser/Base64Util.kt`

*17 qator*

```kotlin
package com.nurvpn.app.parser

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
```

---

## 📄 `com/nurvpn/app/parser/ServerLinkParser.kt`

*432 qator*

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

```

---

## 📄 `com/nurvpn/app/parser/SubscriptionLinkExtractor.kt`

*39 qator*

```kotlin
package com.nurvpn.app.parser

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

            var chunk = raw.substring(start, end).trim()
            // Chunk oxiridagi ortiqcha whitespace yoki keyingi matnni kesish
            chunk = whitespace.split(chunk).firstOrNull() ?: ""
            if (chunk.isNotEmpty()) result.add(chunk)
        }
        return result
    }
}
```

---

## 📄 `com/nurvpn/app/service/BinaryRunner.kt`

*10 qator*

```kotlin
package com.nurvpn.app.service

import android.content.Context
import java.io.File

object BinaryRunner {
    @Suppress("unused")
    fun ensureBinary(ctx: Context, name: String): File? = null
}
```

---

## 📄 `com/nurvpn/app/service/NurVpnService.kt`

*1057 qator*

```kotlin
package com.nurvpn.app.service
import com.nurvpn.app.R
import com.nurvpn.app.core.PingStrategy
import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.ThemeHelper
import com.nurvpn.app.util.ClashApiConfig
import com.nurvpn.app.util.PingTester
import com.nurvpn.app.util.DNSLeakProtection
import com.nurvpn.app.util.IPv6Blocker
import com.nurvpn.app.util.LeakResult
import com.nurvpn.app.util.LeakTester
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.AWGConfig
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.util.AWGEditor
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.ServerMetrics
import com.nurvpn.app.ai.SmartScoreEngine
import com.nurvpn.app.ai.AIInsights
import com.nurvpn.app.ai.AIServerSelector
import com.nurvpn.app.storage.MetricsStore
import com.nurvpn.app.core.AppInfo
import com.nurvpn.app.storage.AppListLoader
import com.nurvpn.app.storage.AwgSortStore
import com.nurvpn.app.storage.SplitTunnelStore
import com.nurvpn.app.service.NurVpnTileService
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.config.BuiltinAwgConfigs
import com.nurvpn.app.config.SingBoxConfig
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceSubscription
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.parser.SubscriptionLinkExtractor
import com.nurvpn.app.parser.ServerLinkParser
import com.nurvpn.app.parser.decodeBase64Safely
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.storage.SubscriptionStore
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

    /** Lifecycle-aware underlying network callback (memory leak oldini olish). */
    private var underlyingCallback: android.net.ConnectivityManager.NetworkCallback? = null

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

            // FIX: Race condition — eski STOP'ni e'tiborsiz qoldirish
            if (stopGen > 0L && stopGen < serviceGeneration) {
                Log.w(TAG, "STOP[$stopGen] eskirgan " +
                    "(hozirgi gen=$serviceGeneration), e'tiborsiz")
                return START_STICKY
            }

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
    /**
     * Underlying network'ni o'rnatish + o'zgarishlarni kuzatish.
     * Wi-Fi <-> Cellular almashganda tunnel avtomatik yangilanadi.
     */
    private fun setupUnderlyingNetwork() {
        if (underlyingCallback != null) {
            Log.i(TAG, "setupUnderlyingNetwork: allaqachon faol")
            return
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            underlyingNetwork = cm.activeNetwork
            Log.i(TAG, "initial underlyingNetwork = $underlyingNetwork")

            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    underlyingNetwork = network
                    Log.i(TAG, "underlyingNetwork yangilandi: $network")
                }
                override fun onLost(network: android.net.Network) {
                    if (underlyingNetwork == network) {
                        underlyingNetwork = null
                        Log.w(TAG, "underlyingNetwork yo'qoldi: $network")
                    }
                }
            }
            underlyingCallback = cb

            val req = android.net.NetworkRequest.Builder()
                .addCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, cb)
            Log.i(TAG, "underlyingNetwork callback ro'yxatga olindi")
        } catch (t: Throwable) {
            Log.e(TAG, "setupUnderlyingNetwork xato", t)
        }
    }

    /** Callback'ni ro'yxatdan chiqarish (memory leak oldini olish). */
    private fun teardownUnderlyingNetwork() {
        val cb = underlyingCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(cb)
            Log.i(TAG, "underlyingNetwork callback olib tashlandi")
        } catch (t: Throwable) {
            Log.w(TAG, "teardown xato: ${t.message}")
        }
        underlyingCallback = null
    }

    private fun connect(intent: Intent?) {
        val link = intent?.getStringExtra(MainActivity.EXTRA_LINK)
        val awg = intent?.getStringExtra(MainActivity.EXTRA_AWG)
        val name = intent?.getStringExtra(MainActivity.EXTRA_NAME) ?: "NurVPN"
        val dns = getSharedPreferences("main", Context.MODE_PRIVATE)
            .getString("dns", "1.1.1.1") ?: "1.1.1.1"

        try {
            // FIX: underlyingNetwork'ni o'rnatish (openTun() dan OLDIN)
            setupUnderlyingNetwork()

            if (link == null && awg == null) {
                throw Exception(getString(R.string.error_no_server))
            }

            val cfg = if (awg != null) {
                Log.i(TAG, "AWG rejimi: ${awg.length} belgi config")
                SingBoxConfig.buildAwgConfig(awg)
            } else {
                // FIX: cachePath olib tashlandi (config'da cache_file yo'q)
                SingBoxConfig.buildFullConfig(link!!, dns)
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
        // FIX: underlyingNetwork callback'ni ham tozalash
        teardownUnderlyingNetwork()
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
```

---

## 📄 `com/nurvpn/app/service/NurVpnTileService.kt`

*127 qator*

```kotlin
package com.nurvpn.app.service

import com.nurvpn.app.R
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.service.NurVpnService
import com.nurvpn.app.core.TunnelState

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings panelidagi NurVPN tugmasi.
 * qWDTT/INCY kabi — VPN ON/OFF toggle.
 */
@RequiresApi(Build.VERSION_CODES.N)
class NurVpnTileService : TileService() {

    /** ActivityManager orqali NurVpnService ishlayaptimi — aniq tekshirish. */
    private fun isVpnServiceRunning(): Boolean {
        try {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (svc in am.getRunningServices(80)) {
                if (svc.service.className == NurVpnService::class.java.name) {
                    return true
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-Tile",
                "isVpnServiceRunning xato: ${t.message}")
        }
        return false
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        // ActivityManager orqali haqiqiy holat (TunnelState'ga ishonchsiz)
        val isRunning = isVpnServiceRunning()
        android.util.Log.i("NurVPN-Tile",
            "onClick: isVpnServiceRunning=$isRunning, TunnelState=${TunnelState.isConnected}")

        if (isRunning) {
            // VPN'ni to'xtatish
            android.util.Log.i("NurVPN-Tile", "onClick: STOP yuborilmoqda")
            val i = Intent(this, NurVpnService::class.java)
            i.putExtra(MainActivity.EXTRA_STOP, true)
            startService(i)
        } else {
            // Oxirgi config bilan to'g'ridan-to'g'ri ulanish
            val prefs = getSharedPreferences("main", MODE_PRIVATE)
            val lastLink = prefs.getString("current_link", null)
            val lastAwg = prefs.getString("current_awg", null)
            val proto = prefs.getString("protocol", MainActivity.PROTO_XRAY)
                ?: MainActivity.PROTO_XRAY

            val hasConfig = if (proto == MainActivity.PROTO_AWG)
                !lastAwg.isNullOrEmpty()
            else !lastLink.isNullOrEmpty()

            if (hasConfig) {
                // Config bor — to'g'ridan-to'g'ri ulanish (ilova ochilmaydi)
                android.util.Log.i("NurVPN-Tile",
                    "Tile: to'g'ridan-to'g'ri ulanish, proto=$proto")
                val i = Intent(this, NurVpnService::class.java).apply {
                    if (proto == MainActivity.PROTO_AWG) {
                        putExtra(MainActivity.EXTRA_AWG, lastAwg)
                        putExtra("protocol", MainActivity.PROTO_AWG)
                    } else {
                        putExtra(MainActivity.EXTRA_LINK, lastLink)
                        putExtra("protocol", MainActivity.PROTO_XRAY)
                    }
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            } else {
                // Config yo'q — MainActivity'ni ochish (server tanlash kerak)
                android.util.Log.i("NurVPN-Tile",
                    "Tile: config yo'q — MainActivity ochilyapti")
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(this, 0, i,
                            android.app.PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(i)
                }
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val connected = isVpnServiceRunning()
        android.util.Log.i("NurVPN-Tile",
            "updateTileState: running=$connected, tile=${if (connected) "ACTIVE" else "INACTIVE"}")
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Icon: shield (fallback — system lock)
        tile.icon = Icon.createWithResource(this,
            if (connected) R.drawable.ic_vpn_lock
            else android.R.drawable.ic_lock_lock)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (connected) R.string.tile_subtitle_on
                else R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }
}
```

---

## 📄 `com/nurvpn/app/storage/AWGStore.kt`

*49 qator*

```kotlin
package com.nurvpn.app.storage

import android.content.Context
import com.nurvpn.app.core.AWGConfig
import org.json.JSONArray
import org.json.JSONObject

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

```

---

## 📄 `com/nurvpn/app/storage/AppListLoader.kt`

*36 qator*

```kotlin
package com.nurvpn.app.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.nurvpn.app.core.AppInfo
import java.util.concurrent.Executors

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

```

---

## 📄 `com/nurvpn/app/storage/AwgSortStore.kt`

*28 qator*

```kotlin
package com.nurvpn.app.storage

import com.nurvpn.app.core.AWGConfig

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

```

---

## 📄 `com/nurvpn/app/storage/MetricsStore.kt`

*52 qator*

```kotlin
package com.nurvpn.app.storage

import android.content.Context
import com.nurvpn.app.core.ServerMetrics
import org.json.JSONObject

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

```

---

## 📄 `com/nurvpn/app/storage/OpenSourceSubscriptions.kt`

*176 qator*

```kotlin
package com.nurvpn.app.storage
import com.nurvpn.app.R

import android.content.Context

/** Ochiq manbalardagi tayyor obuna (GitHub, bepul). */
data class OpenSourceSubscription(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val url: String? = null,
    /** AWG uchun inline config ID (BuiltinAwgConfigs.byId). Null bo'lsa URL ishlatiladi. */
    val awgId: String? = null,
    val flag: String = "\uD83C\uDF10"
) {
    val isAwg: Boolean get() = awgId != null
    fun name(ctx: android.content.Context): String = ctx.getString(nameRes)
    fun description(ctx: android.content.Context): String = ctx.getString(descriptionRes)
}

object OpenSourceCatalog {

    val ALL: List<OpenSourceSubscription> = listOf(
        OpenSourceSubscription(
            id = "nikita29a",
            nameRes = R.string.os_nikita_name,
            descriptionRes = R.string.os_nikita_desc,
            url = "https://raw.githubusercontent.com/nikita29a/FreeProxyList/main/mirror/4.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "hidashimora",
            nameRes = R.string.os_hidashimora_name,
            descriptionRes = R.string.os_hidashimora_desc,
            url = "https://raw.githubusercontent.com/Hidashimora/free-vpn-anti-rkn/main/configs/1.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "igareck",
            nameRes = R.string.os_igareck_name,
            descriptionRes = R.string.os_igareck_desc,
            url = "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "hidashimora2",
            nameRes = R.string.os_hidashimora2_name,
            descriptionRes = R.string.os_hidashimora2_desc,
            url = "https://raw.githubusercontent.com/Hidashimora/free-vpn-anti-rkn/main/configs/2.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "ebrasha",
            nameRes = R.string.os_ebrasha_name,
            descriptionRes = R.string.os_ebrasha_desc,
            url = "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/V2Ray-Config-By-EbraSha.txt",
            flag = "\uD83C\uDF10"
        ),
        OpenSourceSubscription(
            id = "ruk1ng",
            nameRes = R.string.os_ruk1ng_name,
            descriptionRes = R.string.os_ruk1ng_desc,
            url = "https://raw.githubusercontent.com/Ruk1ng001/freeSub/main/v2ray",
            flag = "\uD83C\uDF10"
        ),
        OpenSourceSubscription(
            id = "ermaozi",
            nameRes = R.string.os_ermaozi_name,
            descriptionRes = R.string.os_ermaozi_desc,
            url = "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt",
            flag = "\uD83C\uDF10"
        ),
        OpenSourceSubscription(
            id = "warp_cf1",
            nameRes = R.string.os_warp1_name,
            descriptionRes = R.string.os_warp1_desc,
            awgId = "warp_cf1",
            flag = "\u2601\uFE0F"
        ),
        OpenSourceSubscription(
            id = "warp_cf2",
            nameRes = R.string.os_warp2_name,
            descriptionRes = R.string.os_warp2_desc,
            awgId = "warp_cf2",
            flag = "\u2601\uFE0F"
        ),
        OpenSourceSubscription(
            id = "warp_cf3",
            nameRes = R.string.os_warp3_name,
            descriptionRes = R.string.os_warp3_desc,
            awgId = "warp_cf3",
            flag = "\u2601\uFE0F"
        )
    )

    fun byId(id: String): OpenSourceSubscription? = ALL.firstOrNull { it.id == id }
}

/** Yoqilgan ochiq manba ID'larini saqlaydi (SharedPreferences). */
object OpenSourceStore {
    private const val PREFS = "nurvpn_open_sources"
    private const val KEY_ENABLED = "enabled_ids"
    private const val KEY_DELETED = "deleted_ids"

    fun getEnabled(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
    }

    fun isEnabled(context: Context, id: String): Boolean =
        id in getEnabled(context)

    // ═══ O'chirilgan (doimiy yashirilgan) manbalar ═══
    fun getDeleted(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DELETED, emptySet()) ?: emptySet()
    }

    fun markDeleted(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getDeleted(context).toMutableSet()
        current.add(id)
        // Enabled'dan ham olib tashlaymiz
        val enabled = getEnabled(context).toMutableSet()
        enabled.remove(id)
        prefs.edit()
            .putStringSet(KEY_DELETED, current)
            .putStringSet(KEY_ENABLED, enabled)
            .apply()
    }

    fun restore(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getDeleted(context).toMutableSet()
        current.remove(id)
        prefs.edit().putStringSet(KEY_DELETED, current).apply()
    }

    /**
     * Katalogda yo'q eski ID'larni tozalash (upgrade paytida).
     * Eski obunalar o'chirilgan/yangilangan bo'lsa, orphan yozuvlarni olib tashlaydi.
     */
    fun cleanupOrphans(context: Context) {
        val validIds = OpenSourceCatalog.ALL.map { it.id }.toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // enabled — faqat katalogda mavjudlarni qoldiramiz
        val enabled = getEnabled(context).filter { it in validIds }.toSet()
        // deleted — faqat katalogda mavjudlarni qoldiramiz
        val deleted = getDeleted(context).filter { it in validIds }.toSet()

        prefs.edit()
            .putStringSet(KEY_ENABLED, enabled)
            .putStringSet(KEY_DELETED, deleted)
            .apply()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getEnabled(context).toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        prefs.edit().putStringSet(KEY_ENABLED, current).apply()
    }

    /** "open:nikita29a" kabi prefiksli obuna ID'si. */
    fun subId(openId: String) = "open:$openId"

    /** Berilgan obuna ID'si ochiq manbaniki ekanligini tekshirish. */
    fun isOpenSource(subId: String?): Boolean =
        subId != null && subId.startsWith("open:")

    /** "open:nikita29a" → "nikita29a" */
    fun extractOpenId(subId: String?): String? =
        subId?.takeIf { it.startsWith("open:") }?.removePrefix("open:")
}
```

---

## 📄 `com/nurvpn/app/storage/ServerStore.kt`

*59 qator*

```kotlin
package com.nurvpn.app.storage

import android.content.Context
import android.util.Log
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import org.json.JSONArray
import org.json.JSONObject

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
```

---

## 📄 `com/nurvpn/app/storage/SplitTunnelStore.kt`

*26 qator*

```kotlin
package com.nurvpn.app.storage

import android.content.Context

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

```

---

## 📄 `com/nurvpn/app/storage/SubscriptionStore.kt`

*97 qator*

```kotlin
package com.nurvpn.app.storage

import android.content.Context
import android.util.Log
import com.nurvpn.app.core.Subscription
import org.json.JSONArray
import org.json.JSONObject

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

```

---

## 📄 `com/nurvpn/app/ui/MainActivity.kt`

*482 qator*

```kotlin
package com.nurvpn.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.nurvpn.app.R
import com.nurvpn.app.core.AWGConfig
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.config.BuiltinAwgConfigs
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.storage.SubscriptionStore
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.ThemeHelper
import com.nurvpn.app.ui.home.HomeFragment
import com.nurvpn.app.ui.servers.ServersFragment
import com.nurvpn.app.ui.settings.SettingsFragment
import com.nurvpn.app.service.NurVpnService

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

    // FIX: VPN ruxsat dialog natijasi (zamonaviy API)
    private val vpnPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                android.util.Log.i("NurVPN-DBG",
                    "VPN ruxsat berildi -> doStartVpn()")
                doStartVpn()
            } else {
                android.util.Log.w("NurVPN-DBG", "VPN ruxsat rad etildi")
                Toast.makeText(this,
                    R.string.toast_vpn_denied,
                    Toast.LENGTH_SHORT).show()
            }
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
        // FIX: this.dbgReceiver = dbgReceiver bu yerdan olib tashlandi
        // (lokal o'zgaruvchi hali e'lon qilinmagan edi -> no-op)

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
        // FIX: field'ga saqlash (onDestroy uchun)
        this.dbgReceiver = dbgReceiver

        val dbgFilter = IntentFilter("com.nurvpn.app.DEBUG_START")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dbgReceiver, dbgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dbgReceiver, dbgFilter)
        }

        // FIX: AWG editor signal receiver
        val awgEditedReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                val newRaw = i?.getStringExtra("awg_raw") ?: return
                val cfg = awgConfigs.find { it.rawConf == newRaw } ?: return
                if (isRunning && protocol == PROTO_AWG) {
                    restartVpn(getString(R.string.reason_awg,
                        cfg.name ?: "AWG"))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(awgEditedReceiver,
                IntentFilter("com.nurvpn.app.AWG_EDITED"),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(awgEditedReceiver,
                IntentFilter("com.nurvpn.app.AWG_EDITED"))
        }

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
        // FIX: Zamonaviy ActivityResult API
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
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

```

---

## 📄 `com/nurvpn/app/ui/awg/AWGEditorActivity.kt`

*202 qator*

```kotlin
package com.nurvpn.app.ui.awg

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nurvpn.app.R
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.util.AWGEditor

class AWGEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "awg_index"
        const val EXTRA_RAW = "awg_raw"
    }

    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var jcEt: EditText
    private lateinit var jminEt: EditText
    private lateinit var jmaxEt: EditText
    private lateinit var mtuEt: EditText
    private lateinit var keepEt: EditText
    private lateinit var dnsEt: EditText
    private var preview: TextView? = null
    private var originalRaw = ""
    private var index = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_awg_editor)

        hostEt = findViewById(R.id.edit_host)
        portEt = findViewById(R.id.edit_port)
        jcEt = findViewById(R.id.edit_jc)
        jminEt = findViewById(R.id.edit_jmin)
        jmaxEt = findViewById(R.id.edit_jmax)
        mtuEt = findViewById(R.id.edit_mtu)
        keepEt = findViewById(R.id.edit_keepalive)
        dnsEt = findViewById(R.id.edit_dns)
        preview = findViewById(R.id.preview)

        if (AWGEditorBus.configs.isEmpty()) {
            Toast.makeText(this, R.string.toast_awg_no_config, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (index < 0 || index >= AWGEditorBus.configs.size) index = 0
        originalRaw = intent.getStringExtra(EXTRA_RAW)
            ?: AWGEditorBus.configs[index].rawConf ?: ""

        fillFields()

        findViewById<Button>(R.id.preset_443)
            ?.setOnClickListener { applyRawToFields(AWGEditor.applyPort443(originalRaw)) }
        findViewById<Button>(R.id.preset_beeline)
            ?.setOnClickListener { applyRawToFields(AWGEditor.applyBeelinePreset(originalRaw)) }
        findViewById<Button>(R.id.preset_mts)
            ?.setOnClickListener { applyRawToFields(AWGEditor.applyMtsPreset(originalRaw)) }
        findViewById<Button>(R.id.preset_reset)
            ?.setOnClickListener { applyRawToFields(originalRaw) }

        findViewById<Button>(R.id.btn_cancel)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save)?.setOnClickListener { saveAndReconnect() }

        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                updatePreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        hostEt.addTextChangedListener(w)
        portEt.addTextChangedListener(w)
        jcEt.addTextChangedListener(w)
        jminEt.addTextChangedListener(w)
        jmaxEt.addTextChangedListener(w)
        mtuEt.addTextChangedListener(w)
        keepEt.addTextChangedListener(w)
        dnsEt.addTextChangedListener(w)
        updatePreview()
    }

    private fun fillFields() {
        val d = AWGEditor.parse(originalRaw)
        hostEt.setText(d.host)
        portEt.setText((if (d.port > 0) d.port else 443).toString())
        jcEt.setText(d.jc.toString())
        jminEt.setText(d.jmin.toString())
        jmaxEt.setText(d.jmax.toString())
        mtuEt.setText(d.mtu.toString())
        keepEt.setText(d.keepalive.toString())
        dnsEt.setText(d.dns)
    }

    private fun applyRawToFields(raw: String) {
        val d = AWGEditor.parse(raw)
        originalRaw = raw
        hostEt.setText(d.host)
        portEt.setText((if (d.port > 0) d.port else 443).toString())
        jcEt.setText(d.jc.toString())
        jminEt.setText(d.jmin.toString())
        jmaxEt.setText(d.jmax.toString())
        mtuEt.setText(d.mtu.toString())
        keepEt.setText(d.keepalive.toString())
        dnsEt.setText(d.dns)
        updatePreview()
    }

    private fun safeInt(et: EditText, def: Int): Int =
        try {
            val s = et.text.toString().trim()
            if (s.isEmpty()) def else s.toInt()
        } catch (e: Exception) { def }

    private fun collect(): AWGEditor.Data {
        val d = AWGEditor.Data()
        d.host = hostEt.text.toString().trim()
        d.port = safeInt(portEt, 443)
        d.jc = safeInt(jcEt, 4)
        d.jmin = safeInt(jminEt, 40)
        d.jmax = safeInt(jmaxEt, 70)
        d.mtu = safeInt(mtuEt, 1280)
        d.keepalive = safeInt(keepEt, 25)
        d.dns = dnsEt.text.toString().trim()
        return d
    }

    private fun buildResult(): String = AWGEditor.applyAll(originalRaw, collect())

    private fun updatePreview() {
        val p = preview ?: return
        val r = buildResult()
        p.text = if (r.length > 400) r.substring(0, 400) + "..." else r
    }

    private fun saveAndReconnect() {
        val d = collect()
        if (d.host.isEmpty()) {
            Toast.makeText(this, R.string.editor_empty_host,
                Toast.LENGTH_SHORT).show()
            return
        }
        if (d.port < 1 || d.port > 65535) {
            Toast.makeText(this, R.string.editor_invalid_port,
                Toast.LENGTH_SHORT).show()
            return
        }
        if (index < 0 || index >= AWGEditorBus.configs.size) return
        val cfg = AWGEditorBus.configs[index]
        val oldRaw = cfg.rawConf
        cfg.rawConf = buildResult()

        // FIX: Endpoint/address ni qayta parse qilamiz
        val parsed = AWGParser.parse(cfg.rawConf ?: "")
        if (parsed.ok) {
            cfg.endpoint = parsed.endpoint
            cfg.address = parsed.address
        }

        AWGStore.save(this, AWGEditorBus.configs)
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()

        // FIX: Agar joriy config tahrirlangan bo'lsa va VPN ishlayotgan
        // bo'lsa — reconnect chaqiramiz
        val main = getMainActivity()
        if (main != null && oldRaw != cfg.rawConf) {
            if (main.currentAWG?.rawConf == oldRaw ||
                main.currentAWG === cfg) {
                main.currentAWG = cfg
                main.protocol = MainActivity.PROTO_AWG
                AWGEditorBus.init(main.awgConfigs, cfg, MainActivity.PROTO_AWG)

                if (main.isRunning) {
                    android.util.Log.i("NurVPN-AWG",
                        "Editor: config o'zgardi -> reconnect")
                    main.restartVpn(getString(R.string.reason_awg,
                        cfg.name ?: "AWG"))
                }
            }
        }

        finish()
    }

    /**
     * MainActivity'ni topish — deprecated getActivity() o'rniga.
     * AWGEditorActivity alohida Activity bo'lgani uchun bu yerda null qaytaradi.
     * Boshqa yo'l: SharedPreferences listener yoki broadcast.
     */
    private fun getMainActivity(): MainActivity? = null
}
```

---

## 📄 `com/nurvpn/app/ui/home/HomeFragment.kt`

*2528 qator*

```kotlin
package com.nurvpn.app.ui.home
import com.nurvpn.app.BuildConfig
import com.nurvpn.app.R

import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.ThemeHelper
import com.nurvpn.app.util.ClashApiConfig
import com.nurvpn.app.util.PingTester
import com.nurvpn.app.util.DNSLeakProtection
import com.nurvpn.app.util.IPv6Blocker
import com.nurvpn.app.util.LeakResult
import com.nurvpn.app.util.LeakTester
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.AWGConfig
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.util.AWGEditor
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.ServerMetrics
import com.nurvpn.app.ai.SmartScoreEngine
import com.nurvpn.app.ai.AIInsights
import com.nurvpn.app.ai.AIServerSelector
import com.nurvpn.app.storage.MetricsStore
import com.nurvpn.app.core.AppInfo
import com.nurvpn.app.storage.AppListLoader
import com.nurvpn.app.storage.AwgSortStore
import com.nurvpn.app.storage.SplitTunnelStore
import com.nurvpn.app.ui.qr.QrScanActivity
import com.nurvpn.app.ui.qr.QrShowDialog
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.ui.split.SplitAppsActivity
import com.nurvpn.app.ui.awg.AWGEditorActivity
import com.nurvpn.app.ui.widget.SpeedWaveView
import com.nurvpn.app.ui.widget.AICardView
import com.nurvpn.app.config.BuiltinAwgConfigs
import com.nurvpn.app.config.SingBoxConfig
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceSubscription
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.parser.SubscriptionLinkExtractor
import com.nurvpn.app.parser.ServerLinkParser
import com.nurvpn.app.parser.decodeBase64Safely
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.storage.SubscriptionStore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.Locale

    data class UserInfo(
        val trafficUsed: Long,
        val trafficTotal: Long,
        val expireAt: Long
    )

class HomeFragment : Fragment() {

    companion object {
        private const val REQ_QR_SCAN = 1001
        /** Sessiya davomida bir marta urinib ko'rilgan obunalar
         *  (404 bo'lsa infinite retry oldini oladi). */
        private val attemptedSubs: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private var connectBtn: View? = null
    private var connectIcon: ImageView? = null
    private var statusText: TextView? = null
    private var serverFlag: TextView? = null
    private var serverName: TextView? = null
    private var pingText: TextView? = null
    private var timerText: TextView? = null
    private var downText: TextView? = null
    private var upText: TextView? = null
    private var speedWave: SpeedWaveView? = null

    // Trafik kuzatuvchi
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastTrafficTime: Long = 0L

    private var cardsContainer: LinearLayout? = null
    private var bodyAwg: LinearLayout? = null
    // ═══ Tanlash rejimi ═══
    var homeSelectMode: Boolean = false
    val homeSelectedLinks = mutableSetOf<String>()
    val homeSelectedAwg = mutableSetOf<String>()
    private var homeSelectionBar: View? = null
    private var homeSelCountText: TextView? = null
    private var awgArrow: TextView? = null
    private var awgCount: TextView? = null

    private var awgExpanded = false

    private val ui = Handler(Looper.getMainLooper())
    private var ai: AIServerSelector? = null
    private var tickerRunning = false
    private var pulseX: android.animation.ObjectAnimator? = null
    // Ping test debounce (UI qotmasligi uchun)
    private var pingUpdateScheduled = false
    /** Serverdan kelgan oxirgi userinfo (loadSubscription da saqlanadi). */
    private var pendingUserInfo: UserInfo? = null
    private var pingRunning = false
    /** Asosiy ekranda ochilgan obuna ID'lari (rebuild'da saqlanadi). */
    private val homeExpandedSubs = mutableSetOf<String>()
    private val pingRebuildRunnable = Runnable {
        pingUpdateScheduled = false
        // FIX: View'larni QAYTA YARATMASDAN, faqat ping matnini yangilash.
        // rebuildServerCards(force=true) 200+ serverda telefonni qotiradi.
        if (isAdded) refreshPingViewsInPlace()
    }

    /** Debounce interval — 600+ server uchun 1200ms optimal. */
    private val PING_DEBOUNCE_MS = 1200L

    /**
     * FIX: View'larni QAYTA YARATMASDAN, faqat mavjud ping TextView'larni
     * yangilash. 200+ serverda 100x tezroq (rebuild 3s, bu 30ms).
     */
    private fun refreshPingViewsInPlace() {
        val a = activity as? MainActivity ?: return
        val container = cardsContainer ?: return
        // Link -> ServerItem xaritasi (tez qidirish uchun)
        val byLink = HashMap<String, ServerItem>(a.servers.size)
        for (s in a.servers) byLink[s.link] = s
        walkAndUpdatePing(container, byLink)
    }

    /** Rekursiv ravishda row_ping TextView'larni topib, yangilash. */
    private fun walkAndUpdatePing(view: View, byLink: Map<String, ServerItem>) {
        if (view is TextView && view.id == R.id.row_ping) {
            val link = view.tag as? String ?: return
            val si = byLink[link] ?: return
            view.text = pingLabel(si)
            view.setTextColor(pingColor(si))
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndUpdatePing(view.getChildAt(i), byLink)
            }
        }
    }
    private var pulseY: android.animation.ObjectAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!isAdded) { tickerRunning = false; return }
            val a = activity as? MainActivity
            if (a == null) { ui.postDelayed(this, 1000); return }
            val start = a.connectStart
            if (a.isRunning && start > 0) {
                val sec = (System.currentTimeMillis() - start) / 1000
                timerText?.text = String.format(Locale.US, "%02d:%02d:%02d",
                    sec / 3600, (sec % 3600) / 60, sec % 60)
                updateTraffic()
            } else {
                timerText?.text = "00:00:00"
                resetTrafficCounter()
            }
            ui.postDelayed(this, 1000)
        }
    }

    /** tun0 interfeysidan trafikni o'qib, tezlikni yangilaydi. */
    private fun updateTraffic() {
        val rx = android.net.TrafficStats.getTotalRxBytes()
        val tx = android.net.TrafficStats.getTotalTxBytes()
        val now = android.os.SystemClock.elapsedRealtime()

        if (rx < 0L || tx < 0L) {
            downText?.text = "N/A"
            upText?.text = "N/A"
            return
        }

        if (lastRxBytes >= 0L &&
            lastTxBytes >= 0L &&
            lastTrafficTime > 0L) {
            val elapsedMs = now - lastTrafficTime
            if (elapsedMs > 0L) {
                val downPerSecond =
                    ((rx - lastRxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
                val upPerSecond =
                    ((tx - lastTxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
                if (!isAdded || isDetached || view == null) return
                downText?.text = formatSpeed(downPerSecond)
                upText?.text = formatSpeed(upPerSecond)
                speedWave?.setConnected(TunnelState.isConnected)
                speedWave?.setSpeed(downPerSecond, upPerSecond)
            }
        }
        lastRxBytes = rx
        lastTxBytes = tx
        lastTrafficTime = now
    }

    /** Trafik hisoblagichlarni tiklash. */
    private fun resetTrafficCounter() {
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastTrafficTime = 0L
        downText?.text = "0 B/s"
        upText?.text = "0 B/s"
        speedWave?.setSpeed(0L, 0L)
    }

    /** Baytlarni tezlikka aylantirish. */
    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec < 0) return "0 B/s"
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 ->
                String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            bytesPerSec < 1024L * 1024 * 1024 ->
                String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024))
            else ->
                String.format(Locale.US, "%.2f GB/s", bytesPerSec / (1024.0 * 1024 * 1024))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_home, container, false)

        // Dinamik versiya (BuildConfig dan)
        v.findViewById<android.widget.TextView>(R.id.txt_app_title)?.text =
            "NurVPN v${BuildConfig.VERSION_NAME}"

        connectBtn = v.findViewById(R.id.connect_btn)
        connectIcon = v.findViewById(R.id.connect_icon)
        statusText = v.findViewById(R.id.status_text)
        serverFlag = v.findViewById(R.id.server_flag)
        serverName = v.findViewById(R.id.server_name)
        pingText = v.findViewById(R.id.ping_text)
        timerText = v.findViewById(R.id.timer_text)
        downText = v.findViewById(R.id.down_text)
        upText = v.findViewById(R.id.up_text)
        speedWave = v.findViewById(R.id.speed_wave)
        speedWave?.setConnected(TunnelState.isConnected)

        cardsContainer = v.findViewById(R.id.server_cards_container)
        bodyAwg = v.findViewById(R.id.body_awg)
        awgArrow = v.findViewById(R.id.awg_arrow)
        awgCount = v.findViewById(R.id.awg_count)

        // ═══ Tanlash rejimi paneli ═══
        homeSelectionBar = v.findViewById(R.id.selection_bar)
        homeSelCountText = v.findViewById(R.id.sel_count)
        v.findViewById<View>(R.id.sel_delete)?.setOnClickListener { deleteHomeSelected() }
        v.findViewById<View>(R.id.sel_cancel)?.setOnClickListener { exitHomeSelectMode() }
        v.findViewById<View>(R.id.sel_select_all)?.setOnClickListener { selectAllHome() }

        ai = AIServerSelector.get(requireContext().applicationContext)

        connectBtn?.setOnClickListener { toggleConnection() }
        v.findViewById<View>(R.id.btn_ping_home)?.setOnClickListener {
            // UI thread'da darhol javob
            it.isEnabled = false
            it.alpha = 0.5f
            it.postDelayed({ it.isEnabled = true; it.alpha = 1f }, 800)
            pingHomeAll()
        }
        v.findViewById<View>(R.id.header_awg)?.setOnClickListener { toggleAwg() }

        // 📶 AWG ping tugmasi (karta sarlavhasida)
        val awgHeaderPing = v.findViewById<android.view.ViewGroup>(R.id.header_awg)
        if (awgHeaderPing != null && awgHeaderPing.findViewWithTag<android.view.View>("awg_ping") == null) {
            val dp = resources.displayMetrics.density
            val pingBtn = TextView(requireContext()).apply {
                tag = "awg_ping"
                text = "\uD83D\uDCF6"
                setTextColor(androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.accent))
                textSize = 14f
                setPadding((8*dp).toInt(), (8*dp).toInt(),
                           (8*dp).toInt(), (8*dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            pingBtn.setOnClickListener {
                it.isEnabled = false
                it.postDelayed({ it.isEnabled = true }, 500)
                pingAwgHome()
            }
            awgHeaderPing.addView(pingBtn)
        }

        // ⚙️ AWG Config sozlamalari (programmatic)
        val awgHeader = v.findViewById<android.view.ViewGroup>(R.id.header_awg)
        if (awgHeader != null && awgHeader.findViewWithTag<android.view.View>("awg_settings") == null) {
            val dp = resources.displayMetrics.density
            val settingsBtn = TextView(requireContext()).apply {
                tag = "awg_settings"
                text = "\u2699"
                setTextColor(androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.accent))
                textSize = 16f
                setPadding((8*dp).toInt(), (8*dp).toInt(),
                           (8*dp).toInt(), (8*dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            settingsBtn.setOnClickListener {
                it.isEnabled = false
                it.postDelayed({ it.isEnabled = true }, 300)
                showAwgSettingsDialog()
            }
            awgHeader.addView(settingsBtn)
        }

        v.findViewById<View>(R.id.chip_ai)?.setOnClickListener { runAIAnalysis() }
        v.findViewById<View>(R.id.chip_fast)?.setOnClickListener { fastConnect() }

        v.findViewById<View>(R.id.btn_add)?.setOnClickListener { showAddDialog() }
        v.findViewById<View>(R.id.btn_paste)?.setOnClickListener { pasteFromClipboard() }
        v.findViewById<View>(R.id.btn_scan)?.setOnClickListener {
            val i = Intent(requireContext(), QrScanActivity::class.java)
            startActivityForResult(i, REQ_QR_SCAN)
        }

        refresh()
        return v
    }

    private fun toggleAwg() {
        awgExpanded = !awgExpanded
        bodyAwg?.visibility = if (awgExpanded) View.VISIBLE else View.GONE
        awgArrow?.text = if (awgExpanded) "\u2B06" else "\u2B07"
        if (awgExpanded) rebuildAwgList()
    }

    /** Barcha subscription'larni qayta yuklash. */
    fun refreshSubscriptions() {
        val a = activity as? MainActivity ?: return
        if (a.subscriptions.isEmpty()) return
        for (sub in a.subscriptions) {
            loadSubscription(sub.url, sub.name)
        }
    }

    /** Yuklanmagan subscriptions'larni yuklash (onResume dan chaqiriladi). */
    private fun autoLoadPendingSubscriptions() {
        try {
            if (!isAdded) return
            val a = activity as? MainActivity ?: return
            val pending = a.subscriptions.filter {
                it.lastUpdated == 0L && attemptedSubs.add(it.url)
            }
            if (pending.isEmpty()) return
            android.util.Log.i("NurVPN-DBG",
                "autoLoad: ${pending.size} ta yuklanmagan subscription")
            for (sub in pending) {
                try {
                    loadSubscription(sub.url, sub.name)
                } catch (t: Throwable) {
                    android.util.Log.e("NurVPN-DBG",
                        "autoLoad: sub xato ${sub.url}: ${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "autoLoad xato", t)
        }
    }

    // ═══ Kesh: agar data o'zgarmagan bo'lsa, rebuild qilmaymiz ═══
    private var lastRebuildHash: Int = 0

    /** Data o'zgarganini tekshirish uchun hash. */
    private fun computeDataHash(a: MainActivity): Int {
        var h = a.subscriptions.size
        h = h * 31 + a.servers.size
        h = h * 31 + a.awgConfigs.size
        h = h * 31 + homeExpandedSubs.hashCode()
        // Har obunaning order + sortMode
        for (sub in a.subscriptions) {
            h = h * 31 + sub.order
            h = h * 31 + sub.sortMode.hashCode()
        }
        // Favorite serverlar soni
        var favCount = 0
        for (srv in a.servers) if (srv.favorite) favCount++
        h = h * 31 + favCount
        return h
    }

    private fun rebuildServerCards(force: Boolean = false) {
        val a = activity as? MainActivity ?: return
        val container = cardsContainer ?: return

        // ═══ Kesh tekshiruvi ═══
        if (!force) {
            val hash = computeDataHash(a)
            if (hash == lastRebuildHash && container.childCount > 0) {
                android.util.Log.d("NurVPN-DBG",
                    "rebuildServerCards: data o'zgarmagan, skip")
                return
            }
            lastRebuildHash = hash
        }

        val t0 = System.currentTimeMillis()
        container.removeAllViews()

        if (a.servers.isEmpty() && a.subscriptions.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = getString(R.string.text_no_servers_add)
            tv.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
            tv.textSize = 12f
            tv.setPadding(0, 12, 0, 12)
            container.addView(tv)
            return
        }

        // ═══ 0. SEVIMLILAR KARTASI (birinchi) ═══
        val favServers = a.servers.filter { it.favorite }
        if (favServers.isNotEmpty()) {
            val favCard = makeExpandableCard(
                "\u2B50", getString(R.string.fav_card_title),
                favServers.size, "fav_card")
            val favBody = favCard.getChildAt(1) as LinearLayout
            for (si in favServers) {
                favBody.addView(makeServerRow(si, a))
            }
            container.addView(favCard)
        }

        // ═══ 1. HAR BIR SUBSCRIPTION — ALOHIDA KARTA ═══
        // LIMIT: ko'p serverlar UI thread'ni bloklaydi
        val MAX_CARDS = 5
        var cardCount = 0
        val sortedSubs = a.subscriptions.sortedBy { it.order }
        // ═══ BIR MARTA guruhlash (2000×10 filter o'rniga) ═══
        val serversBySub = a.servers.groupBy { it.subId }
        for (sub in sortedSubs) {
            if (cardCount >= MAX_CARDS) break
            cardCount++
            val rawList = serversBySub[sub.id] ?: emptyList()
            val list = sortServers(rawList, sub.sortMode)
            val card = makeExpandableCard("\uD83D\uDCE1", sub.name, list.size, sub.id, sub)
            val body = card.getChildAt(1) as LinearLayout

            // ═══ LAZY LOADING: faqat ochilgan kartalarda serverlar ═══
            val isExpanded = sub.id in homeExpandedSubs

            if (list.isEmpty()) {
                val tv = TextView(requireContext())
                tv.text = getString(R.string.text_no_servers)
                tv.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
                tv.textSize = 12f
                tv.setPadding(0, 12, 0, 12)
                body.addView(tv)
            } else if (isExpanded) {
                // Faqat ochilgan bo'lsa view yaratamiz
                for (si in list) {
                    body.addView(makeServerRow(si, a))
                }
            } else {
                // Yopiq karta — faqat "ochish uchun" ko'rsatma (yengil)
                val tv = TextView(requireContext())
                tv.text = getString(R.string.text_expand_to_view, list.size)
                tv.setTextColor(androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.text_tertiary))
                tv.textSize = 12f
                tv.setPadding(0, 12, 0, 12)
                body.addView(tv)
            }
            container.addView(card)
        }

        // ═══ 2. QO'LDA QO'SHILGAN — KARTASIZ, TO'G'RIDAN-TO'G'RI ═══
        val manual = a.servers.filter { it.subId == null }
        if (manual.isNotEmpty()) {
            // Kichik sarlavha
            val label = TextView(requireContext())
            label.text = getString(R.string.text_manual_count, manual.size)
            label.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_secondary))
            label.textSize = 12f
            label.setPadding(4, 20, 4, 6)
            container.addView(label)

            // Serverlar to'g'ridan-to'g'ri (kartasiz)
            for (si in manual) {
                container.addView(makeServerRow(si, a))
            }
        }
    }

    private fun makeServerRow(si: ServerItem, a: MainActivity): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_server_row, null, false)
        row.findViewById<TextView>(R.id.row_flag).text = si.flag()
        row.findViewById<TextView>(R.id.row_name).text = si.displayName()
        row.findViewById<TextView>(R.id.row_host).text = si.host ?: ""
        val proto = row.findViewById<TextView>(R.id.row_proto)
        proto.text = protocolLabel(si.protocol)
        proto.backgroundTintList = android.content.res.ColorStateList
            .valueOf(protocolColor(si.protocol))
        val pingView = row.findViewById<TextView>(R.id.row_ping)
        // FIX: tag = link (in-place update uchun)
        pingView.tag = si.link
        pingView.text = pingLabel(si)
        pingView.setTextColor(pingColor(si))

        // ⭐ Yulduzcha
        val favView = row.findViewById<android.widget.ImageView>(R.id.row_fav)
        favView?.setImageResource(
            if (si.favorite) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
        favView?.setOnClickListener {
            si.favorite = !si.favorite
            val act = activity as? MainActivity
            act?.let { ServerStore.save(it, it.servers) }
            favView.setImageResource(
                if (si.favorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            Toast.makeText(context,
                if (si.favorite) getString(R.string.toast_added_fav)
                else getString(R.string.toast_removed_fav),
                Toast.LENGTH_SHORT).show()
            // Sevimlilar kartasini yangilaymiz
            rebuildServerCards()
        }

        // ═══ Tanlash rejimi ═══
        val check = row.findViewById<android.widget.CheckBox>(R.id.select_check)
        if (homeSelectMode) {
            check?.visibility = View.VISIBLE
            check?.isChecked = homeSelectedLinks.contains(si.link)
        } else {
            check?.visibility = View.GONE
        }

        row.setOnClickListener {
            if (homeSelectMode) {
                toggleHomeSelect(si)
                return@setOnClickListener
            }
            a.selectServer(si)
            Toast.makeText(context, si.displayName(), Toast.LENGTH_SHORT).show()
            refresh()
        }
        row.setOnLongClickListener {
            if (!homeSelectMode) showHomeMenu(si, a)
            true
        }
        return row
    }

    /** Home'dagi server uchun long-press menyu. */
    private fun showHomeMenu(target: Any, a: MainActivity) {
        if (!isAdded) return
        val c = context ?: return
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (target is ServerItem) {
            items.add("🚀 " + c.getString(R.string.srv_menu_connect))
            actions.add {
                a.selectServer(target)
                a.protocol = MainActivity.PROTO_XRAY
                Toast.makeText(c, target.displayName(), Toast.LENGTH_SHORT).show()
                refresh()
            }
            items.add(if (target.favorite) "💔 " + c.getString(R.string.srv_menu_remove_fav)
                      else "⭐ " + c.getString(R.string.srv_menu_add_fav))
            actions.add {
                target.favorite = !target.favorite
                ServerStore.save(a, a.servers)
                Toast.makeText(c,
                    if (target.favorite) c.getString(R.string.toast_added_fav)
                    else c.getString(R.string.toast_removed_fav),
                    Toast.LENGTH_SHORT).show()
                rebuildServerCards(force = true)
            }
            items.add("📋 " + c.getString(R.string.srv_menu_copy_link))
            actions.add { homeCopyToClipboard(target.link, c.getString(R.string.clip_label_link)) }
            items.add("📤 " + c.getString(R.string.srv_menu_share))
            actions.add { homeShareLink(target.link, target.displayName()) }
            items.add("📱 " + c.getString(R.string.srv_menu_show_qr))
            actions.add { QrShowDialog.show(c, target.displayName(), target.link) }
            items.add("☑ " + c.getString(R.string.sel_mode))
            actions.add { enterHomeSelectMode() }
            items.add("🗑 " + c.getString(R.string.menu_delete))
            actions.add {
                AlertDialog.Builder(c)
                    .setTitle(R.string.menu_delete)
                    .setMessage(target.displayName())
                    .setPositiveButton(R.string.dialog_yes) { _, _ ->
                        a.servers.remove(target)
                        ServerStore.save(a, a.servers)
                        rebuildServerCards(force = true)
                        Toast.makeText(c, c.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.dialog_no, null)
                    .show()
            }
        } else if (target is AWGConfig) {
            items.add("🚀 " + c.getString(R.string.srv_menu_connect))
            actions.add {
                a.selectAWG(target)
                a.protocol = MainActivity.PROTO_AWG
                AWGEditorBus.init(a.awgConfigs, target, MainActivity.PROTO_AWG)
                Toast.makeText(c, target.name ?: "AWG", Toast.LENGTH_SHORT).show()
                refresh()
            }
            items.add(if (target.favorite) "💔 Sevimlilardan olib tashlash"
                      else "⭐ Sevimlilarga qo'shish")
            actions.add {
                target.favorite = !target.favorite
                AWGStore.save(a, a.awgConfigs)
                Toast.makeText(c,
                    if (target.favorite) c.getString(R.string.toast_added_fav)
                    else c.getString(R.string.toast_removed_fav),
                    Toast.LENGTH_SHORT).show()
                rebuildAwgList()
            }
            items.add("✏️ " + c.getString(R.string.srv_menu_edit))
            val idx = a.awgConfigs.indexOf(target)
            actions.add {
                if (idx >= 0 && isAdded) {
                    val i = Intent(c, AWGEditorActivity::class.java)
                    i.putExtra(AWGEditorActivity.EXTRA_INDEX, idx)
                    i.putExtra(AWGEditorActivity.EXTRA_RAW, target.rawConf)
                    startActivity(i)
                }
            }
            items.add("📋 " + c.getString(R.string.srv_menu_copy_config))
            actions.add { homeCopyToClipboard(target.rawConf ?: "",
                c.getString(R.string.clip_label_awg_config)) }
            items.add("📱 " + c.getString(R.string.srv_menu_show_qr))
            actions.add { QrShowDialog.show(c, target.name ?: "AWG", target.rawConf ?: "") }
            items.add("☑ " + c.getString(R.string.sel_mode))
            actions.add { enterHomeSelectMode() }
            items.add("🗑 " + c.getString(R.string.menu_delete))
            actions.add {
                AlertDialog.Builder(c)
                    .setTitle(R.string.menu_delete)
                    .setMessage(target.name ?: "AWG")
                    .setPositiveButton(R.string.dialog_yes) { _, _ ->
                        a.awgConfigs.remove(target)
                        AWGStore.save(a, a.awgConfigs)
                        rebuildAwgList()
                        Toast.makeText(c, c.getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.dialog_no, null)
                    .show()
            }
        } else return

        AlertDialog.Builder(c)
            .setTitle(when (target) {
                is ServerItem -> target.displayName()
                is AWGConfig -> target.name ?: "AWG"
                else -> "Server"
            })
            .setItems(items.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /** Home menyusi uchun clipboard. */
    private fun homeCopyToClipboard(text: String, label: String) {
        try {
            val cm = requireContext()
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Toast.makeText(context, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(context, getString(R.string.toast_error_fmt, t.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** Home menyusi uchun ulashish. */
    private fun homeShareLink(link: String, name: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
                putExtra(Intent.EXTRA_SUBJECT, name)
            }
            startActivity(Intent.createChooser(i, name))
        } catch (t: Throwable) {
            Toast.makeText(context, getString(R.string.toast_error_fmt, t.message), Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════ HOME TANLASH REJIMI ═══════════

    fun enterHomeSelectMode() {
        homeSelectMode = true
        homeSelectedLinks.clear()
        homeSelectedAwg.clear()
        homeSelectionBar?.visibility = View.VISIBLE
        updateHomeSelCount()
        rebuildServerCards(force = true)
        rebuildAwgList()
    }

    fun exitHomeSelectMode() {
        homeSelectMode = false
        homeSelectedLinks.clear()
        homeSelectedAwg.clear()
        homeSelectionBar?.visibility = View.GONE
        rebuildServerCards(force = true)
        rebuildAwgList()
    }

    fun updateHomeSelCount() {
        homeSelCountText?.text = "${homeSelectedLinks.size + homeSelectedAwg.size} tanlandi"
    }

    fun toggleHomeSelect(target: Any) {
        when (target) {
            is ServerItem -> {
                if (homeSelectedLinks.contains(target.link)) homeSelectedLinks.remove(target.link)
                else homeSelectedLinks.add(target.link)
            }
            is AWGConfig -> {
                val k = target.rawConf ?: ""
                if (homeSelectedAwg.contains(k)) homeSelectedAwg.remove(k)
                else homeSelectedAwg.add(k)
            }
        }
        updateHomeSelCount()
        rebuildServerCards(force = true)
        rebuildAwgList()
    }

    fun selectAllHome() {
        homeSelectedLinks.clear()
        homeSelectedAwg.clear()
        val a = activity as? MainActivity ?: return
        for (si in a.servers) homeSelectedLinks.add(si.link)
        for (cfg in a.awgConfigs) homeSelectedAwg.add(cfg.rawConf ?: "")
        updateHomeSelCount()
        rebuildServerCards(force = true)
        rebuildAwgList()
    }

    fun deleteHomeSelected() {
        val a = activity as? MainActivity ?: return
        var n = 0
        if (homeSelectedLinks.isNotEmpty()) {
            val before = a.servers.size
            a.servers.removeAll { it.link in homeSelectedLinks }
            n += before - a.servers.size
            ServerStore.save(a, a.servers)
        }
        if (homeSelectedAwg.isNotEmpty()) {
            val before = a.awgConfigs.size
            a.awgConfigs.removeAll { (it.rawConf ?: "") in homeSelectedAwg }
            n += before - a.awgConfigs.size
            AWGStore.save(a, a.awgConfigs)
        }
        Toast.makeText(context, "$n o\'chirildi", Toast.LENGTH_SHORT).show()
        exitHomeSelectMode()
    }

    /**
     * Karta yaratadi: header (icon + nom + soni + arrow) + body (yashirin).
     * [0] = header, [1] = body
     */
    /** Serverlarni sortMode bo'yicha saralash. */
    private fun sortServers(list: List<ServerItem>, mode: String): List<ServerItem> {
        return when (mode) {
            "name_asc" -> list.sortedBy { it.displayName().lowercase() }
            "name_desc" -> list.sortedByDescending { it.displayName().lowercase() }
            "ping_asc" -> list.sortedBy {
                if (it.ping < 0) Int.MAX_VALUE else it.ping
            }
            "ping_desc" -> list.sortedByDescending { it.ping }
            else -> list
        }
    }

    /** Obuna sozlamalari dialogi. */
    /** Home'dan AWG configlarni ping qilish. */
    /** Bitta AWG config uchun ping. */
    private fun pingSingleAwg(cfg: AWGConfig) {
        val a = activity as? MainActivity ?: return
        val ep = cfg.endpoint ?: run {
            Toast.makeText(context, R.string.toast_no_endpoint, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, getString(R.string.ping_started),
            Toast.LENGTH_SHORT).show()
        Thread {
            val host = ep.substringBeforeLast(":")
            val port = ep.substringAfterLast(":").toIntOrNull() ?: 0
            var ping = if (port > 0) PingTester.tcpPing(host, port, 3000) else -1
            if (ping <= 0) {
                ping = try { PingTester.icmpPing(host, 2000) } catch (t: Throwable) { -1 }
            }
            if (ping <= 0) {
                ping = try {
                    val start = System.currentTimeMillis()
                    val sock = java.net.DatagramSocket()
                    sock.connect(java.net.InetAddress.getByName(host), port)
                    sock.send(java.net.DatagramPacket(ByteArray(1), 1))
                    sock.close()
                    (System.currentTimeMillis() - start).toInt()
                } catch (t: Throwable) { -1 }
            }
            cfg.ping = ping
            android.util.Log.i("NurVPN-PING",
                "AWG single ${cfg.name}: ping=$ping (host=$host:$port)")
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AWGStore.save(a, a.awgConfigs)
                rebuildAwgList()
                Toast.makeText(context,
                    if (ping > 0) "${cfg.name}: ${ping}ms"
                    else "${cfg.name}: ping yo'q",
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun pingAwgHome() {
        val a = activity as? MainActivity ?: return
        if (a.awgConfigs.isEmpty()) {
            Toast.makeText(context, R.string.text_no_awg,
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.ping_started,
            Toast.LENGTH_SHORT).show()
        // AWG ni sort mode bo'yicha saralash (UI thread da)
        val ctx0 = requireContext()
        val awgSorted = AwgSortStore.sort(a.awgConfigs, AwgSortStore.getMode(ctx0))
        Thread {
            // Parallel ping (4 thread)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val latch = java.util.concurrent.CountDownLatch(awgSorted.size)
        for (cfg in awgSorted) {
                pool.execute {
                    try {
                        val ep = cfg.endpoint ?: return@execute
                        val host = ep.substringBeforeLast(":")
                        val port = ep.substringAfterLast(":").toIntOrNull() ?: 0

                        // ═══ AWG server — TCP + ICMP + UDP ═══
                        var ping = if (port > 0)
                            PingTester.tcpPing(host, port, 3000) else -1
                        if (ping <= 0) {
                            ping = try {
                                PingTester.icmpPing(host, 2000)
                            } catch (t: Throwable) { -1 }
                        }
                        if (ping <= 0) {
                            // UDP fallback (signal uchun)
                            ping = try {
                                val start = System.currentTimeMillis()
                                val sock = java.net.DatagramSocket()
                                sock.connect(java.net.InetAddress.getByName(host), port)
                                sock.send(java.net.DatagramPacket(ByteArray(1), 1))
                                sock.close()
                                (System.currentTimeMillis() - start).toInt()
                            } catch (t: Throwable) { -1 }
                        }
                        cfg.ping = ping
                        android.util.Log.i("NurVPN-PING",
                            "AWG ${cfg.name}: ping=$ping (host=$host:$port)")
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try { latch.await(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
            pool.shutdown()
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AWGStore.save(a, a.awgConfigs)
                awgExpanded = true
                bodyAwg?.visibility = android.view.View.VISIBLE
                rebuildAwgList()
                Toast.makeText(context, R.string.ping_done,
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Faqat shu obuna serverlarini ping qilish (Home ekran). */
    private fun pingSubscriptionHome(subId: String, subName: String) {
        val a = activity as? MainActivity ?: return
        // ⭐ Avtomatik ping_asc
        // ⭐ Sevimlilar kartasi uchun maxsus
        if (subId == "fav_card") {
            val favs = a.servers.filter { it.favorite }
            if (favs.isEmpty()) {
                Toast.makeText(context, R.string.text_no_servers_add,
                    Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(context,
                getString(R.string.ping_sub_started, subName, favs.size),
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("NurVPN-PING",
                "Home ping favorites: ${favs.size}")
            PingTester.testAll(favs, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    if (!pingUpdateScheduled) {
                        pingUpdateScheduled = true
                        ui.postDelayed(pingRebuildRunnable, PING_DEBOUNCE_MS)
                    }
                }
                override fun onAllDone() {
                    pingRunning = false
                    if (!isAdded) return
                    ui.removeCallbacks(pingRebuildRunnable)
                    pingUpdateScheduled = false
                    val a2 = activity as? MainActivity ?: return
                    ServerStore.save(a2, a2.servers)
                    // FIX: rebuild EMAS, faqat ping view'larni yangilash
                    // (200+ serverda 3 sekund freeze oldini oladi)
                    refreshPingViewsInPlace()
                    Toast.makeText(context, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            })
            return
        }

        SubscriptionStore.setSortMode(requireContext(), subId, "ping_asc")
        a.subscriptions = SubscriptionStore.load(a)
        var servers = a.servers.filter { it.subId == subId }
        android.util.Log.i("NurVPN-PING",
            "pingSubHome: subId=$subId, matched=${servers.size}, total=${a.servers.size}")
        if (servers.isEmpty()) {
            val allSubIds = a.servers.mapNotNull { it.subId }.distinct()
            android.util.Log.w("NurVPN-PING",
                "pingSubHome: matched=0, mavjud subIds=$allSubIds")
            val subNameById = a.subscriptions.find { it.id == subId }?.name
            if (subNameById != null) {
                android.util.Log.w("NurVPN-PING",
                    "pingSubHome: fallback — barcha ${a.servers.size} server")
                servers = a.servers
            }
            if (servers.isEmpty()) {
                Toast.makeText(context, R.string.no_servers_in_sub,
                    Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(context,
            getString(R.string.ping_sub_started, subName, servers.size),
            Toast.LENGTH_SHORT).show()
        android.util.Log.i("NurVPN-PING",
            "Home ping sub: $subName (${servers.size})")

        PingTester.testAll(servers, object : PingTester.Listener {
            override fun onPingUpdate(item: ServerItem, ping: Int) {
                // Debounce
                if (!pingUpdateScheduled) {
                    pingUpdateScheduled = true
                    ui.postDelayed(pingRebuildRunnable, PING_DEBOUNCE_MS)
                }
            }
            override fun onAllDone() {
                pingRunning = false
                if (!isAdded) return
                ui.removeCallbacks(pingRebuildRunnable)
                pingUpdateScheduled = false
                val a2 = activity as? MainActivity ?: return
                ServerStore.save(a2, a2.servers)
                // FIX: force=true
                rebuildServerCards(force = true)
                Toast.makeText(context, R.string.ping_done,
                    Toast.LENGTH_SHORT).show()
            }
        })
    }

    /** AWG Config sozlamalari (sort). */
    private fun showAwgSettingsDialog() {
        val ctx = requireContext()
        val a = activity as? MainActivity ?: return
        val current = AwgSortStore.getMode(ctx)
        val modes = arrayOf(
            "default" to getString(R.string.sort_default),
            "ping_asc" to getString(R.string.sort_ping_asc),
            "ping_desc" to getString(R.string.sort_ping_desc),
            "name_asc" to getString(R.string.sort_name_asc),
            "name_desc" to getString(R.string.sort_name_desc)
        )
        val labels = modes.map { it.second }.toTypedArray()
        val idx = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.awg_settings_title))
            .setSingleChoiceItems(labels, idx) { d, which ->
                AwgSortStore.setMode(ctx, modes[which].first)
                rebuildServerCards()
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun showSubSettingsDialog(subId: String, subName: String) {
        val ctx = requireContext()
        val a = activity as? MainActivity ?: return
        val sub = a.subscriptions.find { it.id == subId } ?: return

        val modes = arrayOf(
            "default" to getString(R.string.sort_default),
            "ping_asc" to getString(R.string.sort_ping_asc),
            "ping_desc" to getString(R.string.sort_ping_desc),
            "name_asc" to getString(R.string.sort_name_asc),
            "name_desc" to getString(R.string.sort_name_desc)
        )
        val labels = modes.map { it.second }.toTypedArray()
        val currentIdx = modes.indexOfFirst { it.first == sub.sortMode }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.sub_settings_title, subName))
            .setSingleChoiceItems(labels, currentIdx) { d, which ->
                SubscriptionStore.setSortMode(ctx, subId, modes[which].first)
                a.subscriptions = SubscriptionStore.load(a)
                rebuildServerCards()
                d.dismiss()
            }
            .setNeutralButton(getString(R.string.move_up)) { _, _ ->
                if (SubscriptionStore.moveSubscription(ctx, subId, -1)) {
                    a.subscriptions = SubscriptionStore.load(a)
                    rebuildServerCards()
                } else {
                    Toast.makeText(ctx, R.string.already_top, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(getString(R.string.move_down)) { _, _ ->
                if (SubscriptionStore.moveSubscription(ctx, subId, 1)) {
                    a.subscriptions = SubscriptionStore.load(a)
                    rebuildServerCards()
                } else {
                    Toast.makeText(ctx, R.string.already_bottom, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /** Home ekranda subscription uzoq bosilganda menyu. */
    private fun showHomeSubMenu(subId: String, name: String) {
        val ctx = requireContext()
        val a = activity as? MainActivity ?: return
        val sub = a.subscriptions.find { it.id == subId } ?: return
        val items = arrayOf(
            "\uD83D\uDD04  " + getString(R.string.sub_menu_refresh),
            "\uD83D\uDCD1  " + getString(R.string.sub_menu_copy_url),
            "\uD83D\uDCF1  " + getString(R.string.sub_menu_show_qr),
            "\uD83D\uDDD1  " + getString(R.string.sub_menu_delete)
        )
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        // Yangilash
                        loadSubscription(sub.url, sub.name)
                    }
                    1 -> {
                        // URL nusxalash
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager ?: return@setItems
                        cm.setPrimaryClip(android.content.ClipData
                            .newPlainText(getString(R.string.clip_label_sub_url), sub.url))
                        Toast.makeText(ctx, R.string.toast_url_copied,
                            Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        // QR orqali ulashish
                        QrShowDialog.show(ctx, sub.name, sub.url)
                    }
                    3 -> {
                        // O'chirish — tasdiqlash
                        androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle(R.string.dialog_delete)
                            .setMessage(name)
                            .setPositiveButton(R.string.dialog_delete_yes) { _, _ ->
                                a.servers.removeAll { it.subId == sub.id }
                                a.subscriptions.remove(sub)
                                ServerStore.save(a, a.servers)
                                SubscriptionStore.save(a, a.subscriptions)
                                // OpenSourceStore dan ham o'chirish (agar open:xxx bo'lsa)
                                if (sub.id.startsWith("open:")) {
                                    val openId = sub.id.removePrefix("open:")
                                    OpenSourceStore.setEnabled(ctx, openId, false)
                                }
                                Toast.makeText(ctx,
                                    getString(R.string.open_source_deleted, name),
                                    Toast.LENGTH_SHORT).show()
                                rebuildServerCards()
                            }
                            .setNegativeButton(R.string.dialog_no, null)
                            .show()
                    }
                }
            }
            .show()
    }

    /** Xray JSON config'ni server sifatida qo'shish. */
    private fun addFromJson(json: String) {
        val a = activity as? MainActivity ?: return
        try {
            val item = ServerLinkParser.parse(json, null)
            if (item == null) {
                Toast.makeText(context, "JSON config o\'qilmadi",
                    Toast.LENGTH_LONG).show()
                return
            }
            // Duplicate tekshiruvi
            if (a.servers.any { it.link == item.link }) {
                Toast.makeText(context, "Server allaqachon qo\'shilgan",
                    Toast.LENGTH_SHORT).show()
                return
            }
            a.servers.add(item)
            ServerStore.save(a, a.servers)
            Toast.makeText(context,
                "Server qo\'shildi: ${item.displayName()}",
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("NurVPN-DBG",
                "addFromJson: ${item.displayName()} (${item.host}:${item.port})")
            refresh()
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "addFromJson xato", t)
            Toast.makeText(context, "Xato: ${t.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    /** JSON array yoki object → ServerItem ro'yxati. */
    /** JSON array yoki object → ServerItem ro'yxati (subId bilan). */
    private fun parseJsonConfigsWithSub(json: String, subId: String): List<ServerItem> {
        val out = ArrayList<ServerItem>()
        try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val si = ServerLinkParser.parse(obj.toString(), subId)
                    if (si != null) {
                        si.subId = subId
                        out.add(si)
                        android.util.Log.i("NurVPN-DBG",
                            "JSON[$i]: ${si.displayName()} (${si.host}:${si.port})")
                    } else {
                        android.util.Log.w("NurVPN-DBG", "JSON[$i]: parse null")
                    }
                }
            } else {
                val si = ServerLinkParser.parse(trimmed, subId)
                if (si != null) {
                    si.subId = subId
                    out.add(si)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "parseJsonConfigsWithSub xato", t)
        }
        return out
    }

    private fun parseJsonConfigs(json: String): List<ServerItem> {
        val out = ArrayList<ServerItem>()
        try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val si = ServerLinkParser.parse(obj.toString(), null)
                    if (si != null) {
                        out.add(si)
                        android.util.Log.i("NurVPN-DBG",
                            "JSON[$i]: ${si.displayName()} (${si.host}:${si.port})")
                    } else {
                        android.util.Log.w("NurVPN-DBG", "JSON[$i]: parse null")
                    }
                }
            } else {
                val si = ServerLinkParser.parse(trimmed, null)
                if (si != null) out.add(si)
            }
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "parseJsonConfigs xato", t)
        }
        return out
    }

    private fun makeExpandableCard(icon: String, title: String, count: Int, subId: String? = null, sub: Subscription? = null): LinearLayout {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
        }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14*dp).toInt(), (14*dp).toInt(),
                       (14*dp).toInt(), (14*dp).toInt())
            isClickable = true
            isFocusable = true
        }

        val iconBox = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((40*dp).toInt(), (40*dp).toInt())
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_icon_box)
        }
        val iconTv = TextView(ctx).apply {
            text = icon
            textSize = 16f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        }
        iconBox.addView(iconTv)

        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12*dp).toInt()
            }
        }
        val colorPrimary = androidx.core.content.ContextCompat
            .getColor(ctx, R.color.text_primary)
        val colorTertiary = androidx.core.content.ContextCompat
            .getColor(ctx, R.color.text_tertiary)
        val colorAccent = androidx.core.content.ContextCompat
            .getColor(ctx, R.color.accent)

        val titleTv = TextView(ctx).apply {
            text = title
            setTextColor(colorPrimary)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val countTv = TextView(ctx).apply {
            text = ctx.getString(R.string.text_count_ta, count)
            setTextColor(colorTertiary)
            textSize = 12f
        }
        titles.addView(titleTv)
        titles.addView(countTv)

        // ═══ UserInfo (kun qoldi + GB) ═══
        if (sub != null && (sub.expireAt > 0 || sub.trafficTotal > 0)) {
            val infoTv = TextView(ctx).apply {
                textSize = 11f
                setTextColor(colorTertiary)
                val parts = mutableListOf<String>()
                if (sub.expireAt > 0) {
                    val days = ((sub.expireAt - System.currentTimeMillis()) / 86400_000L).toInt()
                    parts.add("\uD83D\uDCC5 $days kun")
                }
                if (sub.trafficTotal > 0) {
                    val usedG = sub.trafficUsed / 1024.0 / 1024 / 1024
                    val totalG = sub.trafficTotal / 1024.0 / 1024 / 1024
                    parts.add(String.format("\uD83D\uDCCA %.2f / %.2f GB",
                        usedG, totalG))
                }
                text = parts.joinToString("  |  ")
            }
            titles.addView(infoTv)

            // Progress bar (GB foiz)
            if (sub.trafficTotal > 0) {
                val pct = ((sub.trafficUsed * 100) / sub.trafficTotal).coerceIn(0, 100).toInt()
                val pb = android.widget.ProgressBar(ctx, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = pct
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (4 * dp).toInt()
                    ).apply { topMargin = (4 * dp).toInt() }
                }
                titles.addView(pb)
            }
        }

        val arrow = TextView(ctx).apply {
            text = "\u2B07"
            setTextColor(colorAccent)
            textSize = 14f
        }

        // ⚙️ sozlama tugmasi (faqat subId bo'lsa)
        val settingsBtn = TextView(ctx).apply {
            text = "\u2699"
            setTextColor(colorAccent)
            textSize = 16f
            setPadding((6*dp).toInt(), (6*dp).toInt(), (6*dp).toInt(), (6*dp).toInt())
            isClickable = true
            isFocusable = true
        }
        settingsBtn.setOnClickListener {
            if (subId == "awg_card") {
                showAwgSettingsDialog()
            } else if (subId != null) {
                showSubSettingsDialog(subId, title)
            }
        }

        // 📶 Ping tugmasi — faqat subscription uchun
        val pingBtn = TextView(ctx).apply {
            text = "\uD83D\uDCF6"
            setTextColor(colorAccent)
            textSize = 14f
            setPadding((6*dp).toInt(), (6*dp).toInt(),
                       (6*dp).toInt(), (6*dp).toInt())
            isClickable = true
            isFocusable = true
        }
        pingBtn.setOnClickListener {
            if (subId == null || subId == "awg_card") return@setOnClickListener
            it.isEnabled = false
            it.postDelayed({ it.isEnabled = true; it.alpha = 1f }, 800)
            it.alpha = 0.5f
            pingSubscriptionHome(subId, title)
        }

        header.addView(iconBox)
        header.addView(titles)
        if (subId != null && subId != "awg_card") header.addView(pingBtn)
        if (subId != null) header.addView(settingsBtn)
        header.addView(arrow)

        // Body — ochilgan holatni saqlash
        val startExpanded = subId != null && subId in homeExpandedSubs
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (startExpanded) android.view.View.VISIBLE
                         else android.view.View.GONE
            setPadding((14*dp).toInt(), 0, (14*dp).toInt(), (8*dp).toInt())
        }
        arrow.text = if (startExpanded) "\u2B06" else "\u2B07"

        // Uzoq bosish — subscription menyusi
        if (subId != null && subId != "awg_card" && subId != "fav_card") {
            header.setOnLongClickListener {
                showHomeSubMenu(subId, title)
                true
            }
        }

        header.setOnClickListener {
            if (subId != null) {
                // ═══ FIX: rebuildServerCards() OLIB TASHLANDI ═══
                // Sabab: har expand'da 600+ view qayta yaratilardi → freeze.
                // Endi faqat shu kartaning body'si to'ldiriladi (in-place).
                val act = activity as? MainActivity
                if (subId in homeExpandedSubs) {
                    // Collapse — view'larni saqlab, faqat yashiramiz
                    homeExpandedSubs.remove(subId)
                    body.visibility = android.view.View.GONE
                    arrow.text = "\u2B07"
                } else {
                    // Expand — birinchi marta bo'lsa populate
                    homeExpandedSubs.add(subId)
                    if (body.tag != "populated" && act != null) {
                        body.removeAllViews()
                        val raw = act.servers.filter { it.subId == subId }
                        val subSort = act.subscriptions
                            .find { it.id == subId }?.sortMode ?: "default"
                        val list = sortServers(raw, subSort)
                        for (si in list) {
                            body.addView(makeServerRow(si, act))
                        }
                        if (body.childCount == 0) {
                            val tv = TextView(requireContext())
                            tv.text = getString(R.string.text_no_servers)
                            tv.setTextColor(androidx.core.content.ContextCompat
                                .getColor(requireContext(), R.color.text_tertiary))
                            tv.textSize = 12f
                            tv.setPadding(0, 12, 0, 12)
                            body.addView(tv)
                        }
                        body.tag = "populated"
                    }
                    body.visibility = android.view.View.VISIBLE
                    arrow.text = "\u2B06"
                }
            } else {
                // subId yo'q — faqat visibility toggle
                if (body.visibility == android.view.View.VISIBLE) {
                    body.visibility = android.view.View.GONE
                    arrow.text = "\u2B07"
                } else {
                    body.visibility = android.view.View.VISIBLE
                    arrow.text = "\u2B06"
                }
            }
        }

        card.addView(header)
        card.addView(body)
        return card
    }

    private fun rebuildAwgList() {
        val a = activity as? MainActivity ?: return
        val body = bodyAwg ?: return
        body.removeAllViews()
        if (a.awgConfigs.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = getString(R.string.text_no_awg)
            tv.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
            tv.textSize = 12f
            tv.setPadding(0, 12, 0, 12)
            body.addView(tv)
            return
        }
        for (cfg in a.awgConfigs) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_server_row, body, false)
            row.findViewById<TextView>(R.id.row_flag).text = "🔒"
            row.findViewById<TextView>(R.id.row_name).text = cfg.name ?: "AWG Config"
            row.findViewById<TextView>(R.id.row_host).text = cfg.endpoint ?: ""
            val proto = row.findViewById<TextView>(R.id.row_proto)
            proto.text = getString(R.string.text_awg_label)
            proto.backgroundTintList = android.content.res.ColorStateList
                .valueOf(0xFF6C5CE7.toInt())

            // ⭐ Yulduzcha
            val favView = row.findViewById<android.widget.ImageView>(R.id.row_fav)
            favView?.visibility = View.VISIBLE
            favView?.setImageResource(
                if (cfg.favorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            favView?.setOnClickListener {
                cfg.favorite = !cfg.favorite
                AWGStore.save(a, a.awgConfigs)
                rebuildAwgList()
            }

            // ═══ Tanlash rejimi ═══
            val awgCheck = row.findViewById<android.widget.CheckBox>(R.id.select_check)
            if (homeSelectMode) {
                awgCheck?.visibility = View.VISIBLE
                awgCheck?.isChecked = homeSelectedAwg.contains(cfg.rawConf ?: "")
            } else {
                awgCheck?.visibility = View.GONE
            }

            // Uzoq bosish → menyu
            row.setOnLongClickListener {
                if (!homeSelectMode) showHomeMenu(cfg, a)
                true
            }
            // Qisqa bosish
            row.setOnClickListener {
                if (homeSelectMode) {
                    toggleHomeSelect(cfg)
                    return@setOnClickListener
                }
                a.selectAWG(cfg)
                a.protocol = MainActivity.PROTO_AWG
                AWGEditorBus.init(a.awgConfigs, cfg, MainActivity.PROTO_AWG)
                Toast.makeText(context,
                    cfg.name ?: getString(R.string.text_awg_label),
                    Toast.LENGTH_SHORT).show()
                refresh()
            }
            // Ping ko'rsatish
            val pingView = row.findViewById<TextView>(R.id.row_ping)
            when {
                cfg.ping < 0 -> {
                    pingView.text = getString(R.string.text_dash)
                    pingView.setTextColor(androidx.core.content.ContextCompat
                        .getColor(requireContext(), R.color.text_tertiary))
                }
                cfg.ping >= 9999 -> {
                    pingView.text = "\u2715"
                    pingView.setTextColor(0xFFFF5722.toInt())
                }
                cfg.ping < 100 -> {
                    pingView.text = "${cfg.ping}ms"
                    pingView.setTextColor(androidx.core.content.ContextCompat
                        .getColor(requireContext(), R.color.accent))
                }
                cfg.ping < 300 -> {
                    pingView.text = "${cfg.ping}ms"
                    pingView.setTextColor(0xFFFFC107.toInt())
                }
                else -> {
                    pingView.text = "${cfg.ping}ms"
                    pingView.setTextColor(0xFFFF5722.toInt())
                }
            }
            // 📶 Ping o'lchash (row_ping ga bosilsa)
            pingView.setOnClickListener {
                pingSingleAwg(cfg)
            }


            // FIX: 2-chi row.setOnClickListener OLIB TASHLANDI
            // (1-chi listenermi bosib ketardi va tanlash rejimi buzilardi)
            body.addView(row)
        }
    }

    private fun protocolLabel(p: Protocol): String = when (p) {
        Protocol.VLESS_REALITY -> "VLESS"
        Protocol.VMESS -> "VMESS"
        Protocol.TROJAN -> "TROJAN"
        Protocol.SS_2022 -> "SS"
        Protocol.HYSTERIA2 -> "HY2"
        Protocol.TUIC -> "TUIC"
    }

    private fun protocolColor(p: Protocol): Int = when (p) {
        Protocol.VLESS_REALITY -> 0xFF4A9EFF.toInt()
        Protocol.VMESS -> 0xFF4A9EFF.toInt()
        Protocol.TROJAN -> 0xFF4A9EFF.toInt()
        Protocol.SS_2022 -> 0xFFFFC107.toInt()
        Protocol.HYSTERIA2 -> 0xFF9B59B6.toInt()
        Protocol.TUIC -> 0xFFE91E63.toInt()
    }

    private fun pingLabel(s: ServerItem): String = when {
        s.ping > 0 && s.ping < 9999 -> "${s.ping} ms"
        else -> "---"
    }

    private fun pingColor(s: ServerItem): Int = when {
        s.ping <= 0 || s.ping >= 9999 -> androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary)
        s.ping < 100 -> 0xFFC4F82A.toInt()
        s.ping < 300 -> 0xFFFFC107.toInt()
        else -> 0xFFFF5722.toInt()
    }

    private fun fastConnect() {
        val a = activity as? MainActivity ?: return
        if (a.servers.isEmpty()) {
            Toast.makeText(context, R.string.toast_add_server_first, Toast.LENGTH_SHORT).show()
            return
        }
        val best = a.servers.filter { it.ping > 0 }.minByOrNull { it.ping } ?: a.servers[0]
        val wasRunning = a.isRunning
        a.selectServer(best)  // isRunning bo'lsa avtomatik restart qiladi
        a.protocol = MainActivity.PROTO_XRAY
        Toast.makeText(context,
            getString(R.string.toast_ai_selected, best.displayName()),
            Toast.LENGTH_SHORT).show()
        refresh()
        // Faqat VPN o'chiq bo'lsa qo'lda ishga tushiramiz
        if (!wasRunning) {
            a.connectStart = System.currentTimeMillis()
            a.startVpn()
        }
    }

    private fun showAddDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.hint_vless_links)
        input.setMinLines(4)
        input.setMaxLines(8)
        input.setPadding(30, 30, 30, 30)
        input.setTextColor(androidx.core.content.ContextCompat
            .getColor(requireContext(), R.color.text_primary))
        input.setHintTextColor(androidx.core.content.ContextCompat
            .getColor(requireContext(), R.color.text_tertiary))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_server)
            .setView(input)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton

                // ═══ Subscription URL? ═══
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    val firstLine = text.lines().firstOrNull()?.trim() ?: text
                    if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
                        loadSubDialog(firstLine)
                        return@setPositiveButton
                    }
                }

                val a = activity as? MainActivity ?: return@setPositiveButton

                val regex = Regex("(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://.*?(?=(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://|\\s|$)")
                val matches = regex.findAll(text).map { it.value }.toList()
                if (matches.isEmpty()) {
                    Toast.makeText(context,
                        R.string.toast_link_not_found,
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                var added = 0
                for (m in matches) {
                    val si = ServerLinkParser.parse(m) ?: continue
                    a.servers.add(si)
                    added++
                }
                if (added > 0) a.currentServer = a.servers.last()
                ServerStore.save(a, a.servers)
                Toast.makeText(context, getString(R.string.toast_added_count, added),
                    Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun loadSubDialog(url: String) {
        val c = requireContext()
        val input = EditText(c).apply {
            hint = getString(R.string.hint_name_optional)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_secondary))
        }
        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_sub_load)
            .setMessage(url)
            .setView(input)
            .setPositiveButton(R.string.dialog_load) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { null }
                loadSubscription(url, name)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }


    /** HWID — BARQAROR (qayta o'rnatilsa ham bir xil). */
    private fun getHwid(): String {
        val ctx = requireContext()
        // ANDROID_ID — qurilma uchun barqaror
        val androidId = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (t: Throwable) { "unknown" }

        val model = android.os.Build.MODEL ?: "device"
        val packageName = ctx.packageName

        // Barqaror hash
        val raw = "$androidId-$model-$packageName"
        val hash = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

        return hash
    }

    private fun loadSubscription(url: String, subName: String?) {
        val c = requireContext()
        val a = activity as? MainActivity ?: return
        Toast.makeText(c, R.string.toast_loading, Toast.LENGTH_SHORT).show()
        Thread {
            // Thread-local userInfo (race condition oldini oladi)
            var localUserInfo: UserInfo? = null
            val body = try {
                val u = java.net.URL(url)
                val conn = u.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "INCY/1.0.0 (Linux; Android 13)")
                conn.setRequestProperty("Accept", "*/*")
                // HWID (server talab qiladi)
                conn.setRequestProperty("x-hwid", getHwid())
                conn.setRequestProperty("x-device-id", getHwid())
                conn.setRequestProperty("x-platform", "android")
                conn.setRequestProperty("x-client", "incy")
                conn.setRequestProperty("accept", "*/*")
                conn.setRequestProperty("accept-language", "en-US,en;q=0.9")
                conn.setRequestProperty("x-device-os", "Android")
                conn.setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE ?: "13")
                conn.setRequestProperty("x-device-model", android.os.Build.MODEL ?: "SM-S918B")
                conn.setRequestProperty("x-app-version", "3.6.0")
                // Subscription-specific
                conn.setRequestProperty("x-sub-request", "1")
                conn.setRequestProperty("x-ver", "3.6.0")
                // ═══ subscription-userinfo header ═══
                // upload=X; download=Y; total=Z; expire=T
                val userInfo = conn.getHeaderField("subscription-userinfo")
                    ?: conn.getHeaderField("x-subscription-userinfo")
                if (userInfo != null) {
                    android.util.Log.i("NurVPN-DBG", "userinfo: $userInfo")
                    try {
                        var up = 0L; var down = 0L; var total = 0L; var exp = 0L
                        for (part in userInfo.split(";")) {
                            val kv = part.trim().split("=")
                            if (kv.size != 2) continue
                            val v = kv[1].trim().toLongOrNull() ?: 0L
                            when (kv[0].trim().lowercase()) {
                                "upload" -> up = v
                                "download" -> down = v
                                "total" -> total = v
                                "expire" -> exp = v
                            }
                        }
                        localUserInfo = UserInfo(
                            trafficUsed = up + down,
                            trafficTotal = total,
                            expireAt = exp * 1000  // sekund → millisekund
                        )
                        android.util.Log.i("NurVPN-DBG",
                            "userinfo parsed: used=${up+down}B, total=${total}B, expire=$exp")
                    } catch (t: Throwable) {
                        android.util.Log.w("NurVPN-DBG", "userinfo parse xato", t)
                    }
                }

                val br = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                val MAX_BYTES = 500 * 1024  // 500 KB limit
                while (br.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                    if (sb.length > MAX_BYTES) {
                        android.util.Log.w("NurVPN-DBG",
                            "sub fetch: response > 500 KB, truncated")
                        break
                    }
                }
                br.close()
                android.util.Log.i("NurVPN-MEM",
                    "after fetch: ${sb.length} chars, heap=" +
                    "${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB")
                sb.toString()
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-DBG", "sub fetch: ${t.message}", t)
                null
            }
            if (body == null) {
                activity?.runOnUiThread {
                    Toast.makeText(c, R.string.toast_internet_error, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            // ═══ JSON ARRAY (Xray configs ro'yxati) ═══
            val trimmedBody = body.trim()
            if (trimmedBody.startsWith("[") || trimmedBody.startsWith("{")) {
                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON detected, ${trimmedBody.length} belgi")
                // subId yaratamiz yoki mavjudni olamiz
                val subIdForJson = "sub_" + System.currentTimeMillis().toString(36)
                var subForJson = a.subscriptions.find { it.url == url }
                if (subForJson == null) {
                    subForJson = Subscription(subIdForJson, url, subName ?: "JSON Sub")
                    a.subscriptions.add(subForJson)
                    SubscriptionStore.save(a, a.subscriptions)
                }
                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subForJson.id)
                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON serverlar=${jsonServers.size}")
                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {
                        val a2 = activity as? MainActivity ?: return@runOnUiThread
                        var added = 0
                        for (si in jsonServers) {
                            if (a2.servers.any { it.link == si.link }) continue
                            a2.servers.add(si)
                            added++
                        }
                        ServerStore.save(a2, a2.servers)
                        Toast.makeText(c,
                            "JSON: $added server qo'shildi",
                            Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    return@Thread
                }
            }

            // ═══ Linklarni ajratish ═══
            val directLinks = SubscriptionLinkExtractor.extract(body)
            android.util.Log.i("NurVPN-DBG", "loadSub: directLinks=${directLinks.size}")

            val links = if (directLinks.isNotEmpty()) {
                directLinks
            } else {
                val decoded = decodeBase64Safely(body)
                val decodedLinks = SubscriptionLinkExtractor.extract(decoded)
                android.util.Log.i("NurVPN-DBG", "loadSub: decodedLinks=${decodedLinks.size}")
                decodedLinks
            }

            android.util.Log.i("NurVPN-DBG", "loadSub: topilgan linklar=${links.size}")
            android.util.Log.i("NurVPN-MEM",
                "after parse: ${links.size} links, heap=" +
                "${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB")
            // Limit: faqat birinchi 500 link (xotira uchun)
            val limitedLinks = if (links.size > 200) {
                android.util.Log.w("NurVPN-DBG",
                    "loadSub: ${links.size} ta link, 200 taga cheklandi")
                links.take(200)
            } else links
            if (limitedLinks.isEmpty()) {
                activity?.runOnUiThread {
                    Toast.makeText(c,
                        "Subscription'da link yo'q",
                        Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // ═══ PLACEHOLDER TEKSHIRUVI ═══
            val allPlaceholder = limitedLinks.all { l ->
                l.contains("00000000-0000-0000-0000-000000000000") ||
                l.contains("@0.0.0.0:1?")
            }
            if (allPlaceholder) {
                activity?.runOnUiThread {
                    AlertDialog.Builder(c)
                        .setTitle(R.string.dialog_sub_failed)
                        .setMessage(
                            "Provider qurilmalar limitini oshirgan.\n\n" +
                            "Yechim:\n" +
                            "1. Provider botida qurilmalarni reset qiling\n" +
                            "2. Yoki yangi obuna oling\n" +
                            "3. Yoki + Qo'shish orqali linklarni qo'lda kiriting")
                        .setPositiveButton(R.string.dialog_understand, null)
                        .show()
                }
                return@Thread
            }
            activity?.runOnUiThread {
                var sub = a.subscriptions.find { it.url == url }
                if (sub == null) {
                    sub = Subscription(
                        "sub_" + System.currentTimeMillis().toString(36),
                        url, subName ?: "Subscription")
                    a.subscriptions.add(sub)
                } else if (!subName.isNullOrEmpty()) sub.name = subName
                val currentSub = sub ?: return@runOnUiThread
                currentSub.lastUpdated = System.currentTimeMillis()
                currentSub.serverCount = limitedLinks.size
                // userinfo saqlash
                localUserInfo?.let { info ->
                    if (info.trafficTotal > 0) {
                        currentSub.trafficUsed = info.trafficUsed
                        currentSub.trafficTotal = info.trafficTotal
                    }
                    if (info.expireAt > 0) {
                        currentSub.expireAt = info.expireAt
                    }
                }
                a.servers.removeAll { it.subId == currentSub.id }
                val newServers = ArrayList<ServerItem>(limitedLinks.size)
                for (l in limitedLinks) {
                    val si = ServerLinkParser.parse(l, currentSub.id) ?: continue
                    newServers.add(si)
                }
                a.servers.addAll(newServers)
                val added = newServers.size
                android.util.Log.i("NurVPN-MEM",
                    "after add: +${added} servers, total=${a.servers.size}, heap=" +
                    "${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB")
                SubscriptionStore.save(a, a.subscriptions)
                Toast.makeText(c, getString(R.string.toast_sub_loaded, currentSub.name, added),
                    Toast.LENGTH_SHORT).show()
                // Og'ir ishlarni background'ga
                Thread {
                    ServerStore.save(a, a.servers)
                    activity?.runOnUiThread { refresh() }
                }.start()
            }
        }.start()
    }

    private fun showSubDialog() {
        val c = requireContext()
        val container = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }
        val nameEt = EditText(c).apply {
            hint = getString(R.string.hint_name_optional)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_secondary))
        }
        val urlEt = EditText(c).apply {
            hint = getString(R.string.hint_sub_url)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_secondary))
        }
        container.addView(nameEt)
        container.addView(urlEt)
        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_sub_add)
            .setView(container)
            .setPositiveButton(R.string.dialog_load) { _, _ ->
                val url = urlEt.text.toString().trim()
                val name = nameEt.text.toString().trim().ifEmpty { null }
                if (url.isEmpty()) return@setPositiveButton
                // Avtomatik https:// qo'shish
                val fixedUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.contains(".") && !url.contains("://") -> "https://$url"
                    else -> {
                        Toast.makeText(c,
                            R.string.toast_invalid_sub_url,
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }
                val a = activity as? MainActivity ?: return@setPositiveButton
                Toast.makeText(c, R.string.toast_loading, Toast.LENGTH_SHORT).show()
                Thread {
                    val body = try {
                        val u = java.net.URL(url)
                        val conn = u.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val br = BufferedReader(InputStreamReader(conn.inputStream))
                        val sb = StringBuilder()
                        var line: String?
                        while (br.readLine().also { line = it } != null)
                            sb.append(line).append("\n")
                        br.close()
                        sb.toString()
                    } catch (t: Throwable) {
                        android.util.Log.e("NurVPN-DBG", "sub fetch: ${t.message}", t)
                        null
                    }
                    if (body == null) {
                        activity?.runOnUiThread {
                            Toast.makeText(c, R.string.toast_internet_error, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val decoded = try {
                        String(android.util.Base64.decode(body.trim(),
                            android.util.Base64.DEFAULT), Charsets.UTF_8)
                    } catch (ignored: Throwable) { body }
                    val regex = Regex("(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://.*?(?=(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://|\\s|$)")
                    val links = regex.findAll(decoded)
                .map { it.value.trim() }
                .filter { it.length > 15 }
                .toList()
            android.util.Log.i("NurVPN-DBG",
                "loadSub: topilgan linklar=${links.size}")
                    if (links.isEmpty()) {
                        activity?.runOnUiThread {
                            Toast.makeText(c, R.string.toast_no_links_in_sub,
                                Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    activity?.runOnUiThread {
                        var sub = a.subscriptions.find { it.url == url }
                        if (sub == null) {
                            sub = Subscription(
                                "sub_" + System.currentTimeMillis().toString(36),
                                url, name ?: "Subscription")
                            a.subscriptions.add(sub)
                        } else if (!name.isNullOrEmpty()) sub.name = name
                val currentSub = sub ?: return@runOnUiThread
                currentSub.lastUpdated = System.currentTimeMillis()
                currentSub.serverCount = links.size
                        a.servers.removeAll { it.subId == currentSub.id }
                        for (l in links) {
                            val si = ServerLinkParser.parse(l, currentSub.id) ?: continue
                            val cc = CountryLookup.lookup(si.host)
                            si.countryCode = cc[0]
                            si.country = cc[1]
                            si.protocol = Protocol.fromUri(l)
                            si.subId = sub.id
                            a.servers.add(si)
                        }
                        SubscriptionStore.save(a, a.subscriptions)
                        ServerStore.save(a, a.servers)
                        Toast.makeText(c, getString(R.string.toast_sub_loaded, sub.name, links.size),
                            Toast.LENGTH_SHORT).show()
                        refresh()
                                    }
                }.start()
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(context, R.string.toast_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
        android.util.Log.i("NurVPN-DBG", "pasteFromClipboard: uzunlik=${text.length}")

        // ═══ XRAY JSON CONFIG ═══
        if (text.startsWith("{")) {
            android.util.Log.i("NurVPN-DBG", "pasteFromClipboard: JSON detected")
            addFromJson(text)
            return
        }

        // ═══ Subscription URL? ═══
        if (text.startsWith("http://") || text.startsWith("https://")) {
            val firstLine = text.lines().firstOrNull()?.trim() ?: text
            if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
                loadSubDialog(firstLine)
                return
            }
        }

        val a = activity as? MainActivity ?: return

        val regex = Regex("(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://.*?(?=(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://|\\s|$)")
        val matches = regex.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) {
            Toast.makeText(context,
                R.string.toast_clipboard_no_link,
                Toast.LENGTH_LONG).show()
            return
        }
        var added = 0
        for (m in matches) {
            val si = ServerLinkParser.parse(m) ?: continue
            a.servers.add(si)
            added++
        }
        if (added > 0) a.currentServer = a.servers.last()
        ServerStore.save(a, a.servers)
        Toast.makeText(context, getString(R.string.toast_added_count, added),
            Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun runAIAnalysis() {
        val a = activity as? MainActivity ?: return
        if (a.servers.isEmpty()) {
            Toast.makeText(context, R.string.toast_add_server_first, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.toast_ai_analyzing, Toast.LENGTH_SHORT).show()
        ai?.selectBest(a.servers, object : AIServerSelector.Listener {
            override fun onAnalysisStart() {}
            override fun onServerScored(s: ServerItem, score: Double) {}
            override fun onAnalysisComplete(ranked: List<AIInsights>) {
                if (!isAdded) return
                val a2 = activity as? MainActivity ?: return
                ServerStore.save(a2, a2.servers)
                refresh()
                        showTop3Dialog(ranked.take(3))
            }
            override fun onBestSelected(best: ServerItem, insights: AIInsights) {}
        })
    }

    private fun showTop3Dialog(top3: List<AIInsights>) {
        if (top3.isEmpty()) return
        val medals = listOf("\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49")
        val sb = StringBuilder()
        top3.forEachIndexed { i, ins ->
            val m = medals.getOrElse(i) { "  " }
            val ping = ins.summary.substringBefore("\u2022").trim()
            sb.append("$m  ${ins.name}\n")
            sb.append("      $ping  \u00B7  AI ${"%.0f".format(ins.score)}\n\n")
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_ai_top3)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton(R.string.dialog_ai_connect) { _, _ ->
                val a = activity as? MainActivity ?: return@setPositiveButton
                val best = a.servers.find { it.link == top3[0].link }
                if (best != null) {
                    a.selectServer(best)
                    a.protocol = MainActivity.PROTO_XRAY
                    Toast.makeText(context,
                        "AI: ${best.displayName()}", Toast.LENGTH_SHORT).show()
                    refresh()
                    a.connectStart = System.currentTimeMillis()
                    a.startVpn()
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /** Obuna yo'q bo'lganda ogohlantirish dialogi. */
    private fun showNoSubscriptionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.no_subscription_title)
            .setMessage(R.string.no_subscription_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                (activity as? MainActivity)?.switchToTab(2)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Home ekrandan barcha serverlarni ping qilish (debounce bilan). */
    private fun pingHomeAll() {
        val a = activity as? MainActivity ?: return
        if (pingRunning) {
            Toast.makeText(context, R.string.ping_running,
                Toast.LENGTH_SHORT).show()
            return
        }
        if (a.servers.isEmpty() && a.awgConfigs.isEmpty()) {
            Toast.makeText(context, R.string.toast_no_servers,
                Toast.LENGTH_SHORT).show()
            return
        }
        pingRunning = true
        Toast.makeText(context, R.string.ping_started,
            Toast.LENGTH_SHORT).show()
        android.util.Log.i("NurVPN-PING", "Home ping: ${a.servers.size} server")

        if (a.servers.isNotEmpty()) {
            // FIX: 600+ server uchun limit — bir vaqtda max 100 ta
            val MAX_PING = 100
            val serversToPing = if (a.servers.size > MAX_PING) {
                // Eng yaqin (birinchi) 100 tasi — foydalanuvchi kutayotgani
                android.util.Log.w("NurVPN-PING",
                    "pingHomeAll: ${a.servers.size} server, faqat " +
                    "$MAX_PING tasi ping qilinadi")
                a.servers.take(MAX_PING)
            } else {
                a.servers
            }
            PingTester.testAll(serversToPing, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    // Debounce — 400ms ichida ko'p marta chaqirilsa, faqat 1 marta rebuild
                    if (!pingUpdateScheduled) {
                        pingUpdateScheduled = true
                        ui.postDelayed(pingRebuildRunnable, PING_DEBOUNCE_MS)
                    }
                }
                override fun onAllDone() {
                    pingRunning = false
                    if (!isAdded) return
                    ui.removeCallbacks(pingRebuildRunnable)
                    pingUpdateScheduled = false
                    val a2 = activity as? MainActivity ?: return
                    ServerStore.save(a2, a2.servers)
                    // FIX: force=true
                    rebuildServerCards(force = true)
                    // FIX: AWG ham bo'lsa — ularni ham ping qilamiz
                    if (a2.awgConfigs.isNotEmpty()) {
                        pingAwgConfigsInBackground(a2)
                    } else {
                        Toast.makeText(context, R.string.ping_done,
                            Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } else if (a.awgConfigs.isNotEmpty()) {
            // FIX: Faqat AWG config bor foydalanuvchi uchun
            pingAwgConfigsInBackground(a)
        } else {
            pingRunning = false
        }
    }

    /**
     * FIX: AWG configlarni background'da ping qilish.
     * UDP (WireGuard) uchun 3 bosqichli ping: TCP -> ICMP -> UDP.
     */
    private fun pingAwgConfigsInBackground(a: MainActivity) {
        if (a.awgConfigs.isEmpty()) {
            pingRunning = false
            return
        }
        Thread {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val latch = java.util.concurrent.CountDownLatch(a.awgConfigs.size)
            for (cfg in a.awgConfigs) {
                pool.execute {
                    try {
                        val ep = cfg.endpoint ?: return@execute
                        val host = ep.substringBeforeLast(":")
                        val port = ep.substringAfterLast(":").toIntOrNull() ?: 0

                        // 1. TCP ping
                        var ping = if (port > 0)
                            PingTester.tcpPing(host, port, 2000) else -1

                        // 2. ICMP fallback
                        if (ping <= 0) {
                            ping = try {
                                PingTester.icmpPing(host, 2000)
                            } catch (t: Throwable) { -1 }
                        }

                        // 3. UDP fallback (AWG uchun eng ishonchli)
                        if (ping <= 0 && port > 0) {
                            ping = try {
                                val start = System.currentTimeMillis()
                                val sock = java.net.DatagramSocket()
                                sock.connect(java.net.InetAddress.getByName(host), port)
                                sock.send(java.net.DatagramPacket(ByteArray(1), 1))
                                sock.close()
                                (System.currentTimeMillis() - start).toInt()
                            } catch (t: Throwable) { -1 }
                        }

                        cfg.ping = ping
                        android.util.Log.i("NurVPN-PING",
                            "AWG ${cfg.name}: ping=$ping (host=$host:$port)")
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try {
                latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {}
            pool.shutdown()

            activity?.runOnUiThread {
                if (!isAdded) {
                    pingRunning = false
                    return@runOnUiThread
                }
                AWGStore.save(a, a.awgConfigs)
                awgExpanded = true
                bodyAwg?.visibility = View.VISIBLE
                rebuildAwgList()
                pingRunning = false
                Toast.makeText(context, R.string.ping_done,
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleConnection() {
        // Ochiq manba ham, obuna ham yo'q — dialog ko'rsatamiz
        val a0 = activity as? MainActivity
        if (a0 != null &&
            a0.subscriptions.isEmpty() &&
            a0.servers.isEmpty() &&
            a0.awgConfigs.isEmpty()) {
            showNoSubscriptionDialog()
            return
        }
        val a = activity as? MainActivity ?: return
        if (a.isRunning) {
            val curSrv = a.currentServer
            if (MainActivity.PROTO_XRAY == a.protocol && curSrv != null) {
                ai?.onDisconnected(curSrv, true)
            }
            a.stopVpn()
            a.connectStart = 0
        } else {
            if (MainActivity.PROTO_XRAY == a.protocol &&
                a.currentServer == null && a.servers.isEmpty()) {
                Toast.makeText(context, R.string.toast_add_server_first,
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (MainActivity.PROTO_AWG == a.protocol && a.currentAWG == null) {
                Toast.makeText(context, R.string.toast_add_awg_first,
                    Toast.LENGTH_SHORT).show()
                return
            }
            a.startVpn()
            a.connectStart = System.currentTimeMillis()
            val curSrv2 = a.currentServer
            if (MainActivity.PROTO_XRAY == a.protocol && curSrv2 != null) {
                ai?.onConnected(curSrv2)
            }
        }
        ui.postDelayed({ refresh() }, 600)
    }

    private var refreshScheduled = false
    private val refreshRunnable = Runnable {
        refreshScheduled = false
        rebuildServerCards()
    }

    fun refresh() {
        if (!isAdded) return
        val a = activity as? MainActivity ?: return
        connectBtn ?: return
        // Debounce — 100ms ichida ko'p marta chaqirilsa, faqat 1 marta
        if (!refreshScheduled) {
            refreshScheduled = true
            ui.postDelayed(refreshRunnable, 100)
        }

        if (MainActivity.PROTO_AWG == a.protocol) {
            val awg = a.currentAWG
            serverFlag?.text = "🔒"
            serverName?.text = awg?.name ?: "AWG"
            pingText?.text = getString(R.string.text_awg_label)
            pingText?.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
        } else {
            val srv = a.currentServer
            serverFlag?.text = srv?.flag() ?: "🌍"
            serverName?.text = srv?.displayName() ?: getString(R.string.no_server_selected)
            pingText?.text = if (srv != null) pingLabel(srv) else "---"
            pingText?.setTextColor(if (srv != null) pingColor(srv)
                else androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.text_tertiary))
        }

        awgCount?.text = getString(R.string.text_count_ta, a.awgConfigs.size)
        rebuildServerCards()

        if (a.isRunning) {
            statusText?.setText(R.string.status_connected)
            statusText?.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent))
            connectBtn?.setBackgroundResource(R.drawable.bg_power_on)
            connectIcon?.setColorFilter(0xFF0A1410.toInt())
            if (a.connectStart == 0L) a.connectStart = System.currentTimeMillis()
            startPulse()
        } else {
            statusText?.setText(R.string.tap_to_connect)
            statusText?.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary))
            connectBtn?.setBackgroundResource(R.drawable.bg_power_off)
            connectIcon?.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary))
            a.connectStart = 0
            downText?.text = "0 B/s"
            upText?.text = "0 B/s"
            stopPulse()
        }
    }

    private fun startPulse() {
        if (pulseX != null) return
        pulseX = android.animation.ObjectAnimator.ofFloat(connectBtn, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            start()
        }
        pulseY = android.animation.ObjectAnimator.ofFloat(connectBtn, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            start()
        }
    }

    private fun stopPulse() {
        pulseX?.cancel(); pulseX = null
        pulseY?.cancel(); pulseY = null
        connectBtn?.scaleX = 1f
        connectBtn?.scaleY = 1f
    }

    override fun onResume() {
        super.onResume()
        speedWave?.setConnected(TunnelState.isConnected)
        refresh()
        if (!tickerRunning) { tickerRunning = true; ui.post(ticker) }
        autoLoadPendingSubscriptions()
    }

    override fun onPause() {
        super.onPause()
        tickerRunning = false
        ui.removeCallbacks(ticker)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_QR_SCAN &&
            resultCode == android.app.Activity.RESULT_OK) {
            val text = data?.getStringExtra(QrScanActivity.EXTRA_RESULT) ?: return
            handleQrResult(text)
        }
    }

    private fun handleQrResult(text: String) {
        android.util.Log.i("NurVPN-QR", "QR matni uzunligi=${text.length}")
        android.util.Log.i("NurVPN-QR", "QR matni=${text.take(500)}")
        val a = activity as? MainActivity ?: return

        // ═══ 1. AWG / WireGuard config? ═══
        if (text.contains("[Interface]", ignoreCase = true) &&
            text.contains("[Peer]", ignoreCase = true)) {
            addAwgFromQr(text)
            return
        }

        // ═══ 2. Subscription URL ═══
        if (text.startsWith("http://") || text.startsWith("https://")) {
            loadSubDialog(text)
            return
        }

        // ═══ 3. Ko'p linkli matn ═══
        val regex = Regex(
            "(vless|vmess|trojan|ss|hy2|hysteria2|tuic)://[^\\s]+"
        )
        val matches = regex.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) {
            Toast.makeText(context,
                "QR'da link topilmadi\n\n" +
                "Qo'llab-quvvatlanadi:\n" +
                "• vless://, vmess://, hysteria2://\n" +
                "• AWG config ([Interface] ... [Peer])\n" +
                "• Subscription URL (https://...)",
                Toast.LENGTH_LONG).show()
            return
        }
        var added = 0
        for (m in matches) {
            val si = ServerLinkParser.parse(m) ?: continue
            a.servers.add(si)
            added++
        }
        ServerStore.save(a, a.servers)
        Toast.makeText(context, getString(R.string.toast_added_count, added),
            Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** QR dan AWG config qo'shish. */
    private fun addAwgFromQr(conf: String) {
        val a = activity as? MainActivity ?: return
        val r = AWGParser.parse(conf)
        if (!r.ok) {
            Toast.makeText(context,
                getString(R.string.toast_awg_config_error, r.error),
                Toast.LENGTH_LONG).show()
            return
        }
        val cfg = AWGConfig(conf)
        cfg.endpoint = r.endpoint
        cfg.address = r.address
        cfg.name = "AWG " + r.endpoint
        a.awgConfigs.add(cfg)
        AWGStore.save(a, a.awgConfigs)
        a.selectAWG(cfg)
        a.protocol = MainActivity.PROTO_AWG
        AWGEditorBus.init(a.awgConfigs, cfg, MainActivity.PROTO_AWG)
        Toast.makeText(context,
            "AWG config qo'shildi\n${r.endpoint}",
            Toast.LENGTH_SHORT).show()
        refresh()
        rebuildServerCards()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { speedWave?.stop() } catch (_: Throwable) {}
        speedWave = null
        tickerRunning = false
        ui.removeCallbacksAndMessages(null)
        stopPulse()
        connectBtn = null; connectIcon = null
        statusText = null; serverFlag = null; serverName = null; pingText = null
        timerText = null; downText = null; upText = null
        cardsContainer = null; bodyAwg = null
    }
}


// ═══════════════════════════════════════════════════════════════
```

---

## 📄 `com/nurvpn/app/ui/qr/QrScanActivity.kt`

*71 qator*

```kotlin
package com.nurvpn.app.ui.qr

import com.nurvpn.app.R

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "qr_result"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var capture: CaptureManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        barcodeView = findViewById(R.id.barcode_scanner)
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        barcodeView.decodeSingle { result ->
            val text = result.text
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, R.string.toast_qr_empty, Toast.LENGTH_SHORT).show()
                return@decodeSingle
            }
            val i = Intent().apply { putExtra(EXTRA_RESULT, text) }
            setResult(RESULT_OK, i)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
```

---

## 📄 `com/nurvpn/app/ui/qr/QrShowDialog.kt`

*81 qator*

```kotlin
package com.nurvpn.app.ui.qr

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nurvpn.app.R
import com.nurvpn.app.util.QrGenerator

/** QR kodni dialogda ko'rsatadi. Pastda "Nusxalash" tugmasi. */
object QrShowDialog {

    fun show(ctx: Context, title: String, content: String) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val pad = (ctx.resources.displayMetrics.density * 20).toInt()
        val qrSize = (ctx.resources.displayMetrics.widthPixels * 0.72).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        val titleTv = TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, pad)
        }
        root.addView(titleTv)

        val bmp = QrGenerator.generate(content, qrSize)
        if (bmp == null) {
            Toast.makeText(ctx, R.string.toast_qr_gen_error, Toast.LENGTH_SHORT).show()
            return
        }

        val iv = ImageView(ctx).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
            adjustViewBounds = true
        }
        root.addView(iv)

        val copyTv = TextView(ctx).apply {
            text = ctx.getString(R.string.qr_share_copy)
            textSize = 14f
            setTextColor(Color.parseColor("#4A9EFF"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, pad, 0, 0)
            isClickable = true
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(title, content))
                Toast.makeText(ctx, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyTv)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
```

---

## 📄 `com/nurvpn/app/ui/servers/ServersFragment.kt`

*1992 qator*

```kotlin
package com.nurvpn.app.ui.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.annotation.NonNull
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nurvpn.app.R
import com.nurvpn.app.core.AWGConfig
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.config.BuiltinAwgConfigs
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.parser.ServerLinkParser
import com.nurvpn.app.parser.decodeBase64Safely
import com.nurvpn.app.parser.SubscriptionLinkExtractor
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.storage.AwgSortStore
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.storage.SubscriptionStore
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.ui.awg.AWGEditorActivity
import com.nurvpn.app.ui.qr.QrScanActivity
import com.nurvpn.app.ui.qr.QrShowDialog
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.PingTester
import java.util.Locale

class ServersFragment : Fragment() {

    private var rv: RecyclerView? = null
    private var ad: ServerAdapter? = null
    private var search: EditText? = null

    /** AWG .conf fayl tanlash uchun */
    private val awgFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri == null || !isAdded) return@registerForActivityResult
        try {
            val text = requireContext().contentResolver
                .openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            if (text.isNullOrBlank()) {
                Toast.makeText(context, R.string.toast_file_empty, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            android.util.Log.i("NurVPN-AWG",
                "Fayl o\'qildi: ${text.length} belgi, URI=$uri")
            addAWG(text)
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-AWG", "Fayl o\'qish xato", t)
            Toast.makeText(context,
                "Xato: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** JSON fayl tanlash uchun */
    private val jsonFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri == null || !isAdded) return@registerForActivityResult
        try {
            val text = requireContext().contentResolver
                .openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            if (text.isNullOrBlank()) {
                Toast.makeText(context, R.string.toast_file_empty, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "json_import"
            android.util.Log.i("NurVPN-JSON",
                "Fayl o\'qildi: ${text.length} belgi, fayl=$fileName")
            importJsonFile(text, fileName)
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-JSON", "Fayl o\'qish xato", t)
            Toast.makeText(context,
                "Xato: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var selectionBar: View? = null
    private var selCountText: TextView? = null

    fun showSelectionBar() {
        selectionBar?.visibility = View.VISIBLE
        updateSelectionCount(0)
    }

    fun hideSelectionBar() {
        selectionBar?.visibility = View.GONE
    }

    fun updateSelectionCount(n: Int) {
        selCountText?.text = "$n tanlandi"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_servers, container, false)

        // ═══ Tanlash rejimi paneli ═══
        selectionBar = v.findViewById(R.id.selection_bar)
        selCountText = v.findViewById(R.id.sel_count)
        v.findViewById<View>(R.id.sel_delete)?.setOnClickListener {
            ad?.deleteSelected()
        }
        v.findViewById<View>(R.id.sel_cancel)?.setOnClickListener {
            ad?.exitSelectMode()
        }
        v.findViewById<View>(R.id.sel_select_all)?.setOnClickListener {
            ad?.selectAllVisible()
        }
        rv = v.findViewById(R.id.server_list)
        search = v.findViewById(R.id.search_input)

        val a = activity as? MainActivity
        ad = ServerAdapter(this, a)
        rv?.layoutManager = LinearLayoutManager(context)
        rv?.setHasFixedSize(true)
        rv?.adapter = ad

        v.findViewById<FloatingActionButton>(R.id.add_fab)
            ?.setOnClickListener { showAddDialog() }
        v.findViewById<FloatingActionButton>(R.id.sub_fab)
            ?.setOnClickListener { showSubDialog() }
        v.findViewById<FloatingActionButton>(R.id.ping_fab)
            ?.setOnClickListener { pingAll() }
        v.findViewById<FloatingActionButton>(R.id.awg_fab)
            ?.setOnClickListener { showAWGDialog() }

        search?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                ad?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ═══ Chip filter ═══
        v.findViewById<TextView>(R.id.chip_all)?.setOnClickListener {
            setFilter("all", v)
        }
        v.findViewById<TextView>(R.id.chip_fav)?.setOnClickListener {
            setFilter("fav", v)
        }
        v.findViewById<TextView>(R.id.chip_vless)?.setOnClickListener {
            setFilter("vless", v)
        }
        v.findViewById<TextView>(R.id.chip_hy2)?.setOnClickListener {
            setFilter("hy2", v)
        }
        v.findViewById<TextView>(R.id.chip_awg)?.setOnClickListener {
            setFilter("awg", v)
        }
        v.findViewById<TextView>(R.id.chip_vmess)?.setOnClickListener {
            setFilter("vmess", v)
        }
        v.findViewById<TextView>(R.id.chip_trojan)?.setOnClickListener {
            setFilter("trojan", v)
        }
        v.findViewById<TextView>(R.id.chip_tuic)?.setOnClickListener {
            setFilter("tuic", v)
        }

        // Mavjud protokollarga qarab chiplarni ko'rsatish
        refreshProtocolChips(v)
        return v
    }

    private var currentFilter: String = "all"

    /** Mavjud protokollarni aniqlab, chiplarni ko'rsatish. */
    private fun refreshProtocolChips(root: View) {
        val a = activity as? MainActivity ?: return
        val present = a.servers.mapNotNull { protocolFilterKey(it) }.toSet()

        val chipMap = mapOf(
            "vless" to root.findViewById<TextView>(R.id.chip_vless),
            "vmess" to root.findViewById<TextView>(R.id.chip_vmess),
            "hy2" to root.findViewById<TextView>(R.id.chip_hy2),
            "trojan" to root.findViewById<TextView>(R.id.chip_trojan),
            "tuic" to root.findViewById<TextView>(R.id.chip_tuic),
            "awg" to root.findViewById<TextView>(R.id.chip_awg)
        )

        for ((key, chip) in chipMap) {
            chip ?: continue
            chip.visibility = if (key in present) View.VISIBLE else View.GONE
        }

        // Tanlangan protokol endi mavjud bo'lmasa — "all" ga qaytamiz
        if (currentFilter != "all" && currentFilter !in present) {
            currentFilter = "all"
            setFilter("all", root)
        }
    }

    /** Protokol → chip key. */
    private fun protocolFilterKey(si: ServerItem): String? =
        when (si.protocol) {
            Protocol.VLESS_REALITY -> "vless"
            Protocol.VMESS -> "vmess"
            Protocol.TROJAN -> "trojan"
            Protocol.SS_2022 -> null  // chip yo'q
            Protocol.HYSTERIA2 -> "hy2"
            Protocol.TUIC -> "tuic"
        }

    /** Adapterni yangilash (ochiq manba o'zgarganda chaqiriladi). */
    fun refreshServers() {
        ad?.notifyDataSetChanged()
        view?.let { refreshProtocolChips(it) }
    }

    private fun setFilter(key: String, root: View) {
        currentFilter = key
        val chips = mapOf(
            "all" to root.findViewById<TextView>(R.id.chip_all),
            "fav" to root.findViewById<TextView>(R.id.chip_fav),
            "vless" to root.findViewById<TextView>(R.id.chip_vless),
            "vmess" to root.findViewById<TextView>(R.id.chip_vmess),
            "hy2" to root.findViewById<TextView>(R.id.chip_hy2),
            "trojan" to root.findViewById<TextView>(R.id.chip_trojan),
            "tuic" to root.findViewById<TextView>(R.id.chip_tuic),
            "awg" to root.findViewById<TextView>(R.id.chip_awg)
        )
        val cOn = androidx.core.content.ContextCompat
            .getColor(requireContext(), R.color.on_accent)
        val cOff = androidx.core.content.ContextCompat
            .getColor(requireContext(), R.color.text_secondary)
        for ((k, tv) in chips) {
            tv ?: continue
            if (k == key) {
                tv.setBackgroundResource(R.drawable.bg_filter_on)
                tv.setTextColor(cOn)
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setBackgroundResource(R.drawable.bg_filter_off)
                tv.setTextColor(cOff)
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        ad?.setProtoFilter(key)
    }

    fun refresh() { ad?.notifyDataChanged() }

    fun loadSubFromHeader(url: String, name: String?) {
        loadSub(url, name)
    }

    fun refreshSubscriptions() {
        val a = activity as? MainActivity ?: return
        if (a.subscriptions.isEmpty()) {
            Toast.makeText(context, R.string.toast_no_servers,
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.toast_loading,
            Toast.LENGTH_SHORT).show()
        for (sub in a.subscriptions) {
            loadSub(sub.url, sub.name)
        }
    }

    private fun pingAll() {
        if (!isAdded) return
        val a = activity as? MainActivity ?: return
        if (a.servers.isEmpty() && a.awgConfigs.isEmpty()) {
            Toast.makeText(context, R.string.toast_no_servers, Toast.LENGTH_SHORT).show()
            return
        }

        // FIX: 600+ server uchun limit — bir vaqtda max 100
        val MAX_PING = 100
        val pingable = if (a.servers.size > MAX_PING) {
            android.util.Log.w("NurVPN-PING",
                "ServersFragment.pingAll: ${a.servers.size} server, " +
                "faqat $MAX_PING tasi")
            a.servers.take(MAX_PING)
        } else a.servers

        if (pingable.isNotEmpty()) {
            var scheduled = false
            val runnable = Runnable { ad?.notifyDataSetChanged() }
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            PingTester.testAll(pingable, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    if (!scheduled) {
                        scheduled = true
                        h.postDelayed({ scheduled = false; runnable.run() }, 1200)
                    }
                }
                override fun onAllDone() {
                    if (!isAdded) return
                    h.removeCallbacksAndMessages(null)
                    val a2 = activity as? MainActivity ?: return
                    ServerStore.save(a2, a2.servers)
                    ad?.notifyDataSetChanged()
                }
            })
        }

        // ═══ AWG configlar (ICMP) ═══
        if (a.awgConfigs.isNotEmpty()) {
            Thread {
                for (cfg in a.awgConfigs) {
                    val ep = cfg.endpoint ?: continue
                    val host = ep.substringBeforeLast(":")
                    val ping = try {
                        PingTester.icmpPing(host, 4000)
                    } catch (t: Throwable) { -1 }
                    cfg.ping = ping
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    AWGStore.save(a, a.awgConfigs)
                    ad?.notifyDataSetChanged()
                    Toast.makeText(context, R.string.toast_ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            }.start()
        } else {
            Toast.makeText(context, R.string.toast_ping_done,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddDialog() {
        if (!isAdded) return
        val options = arrayOf(
            "\u270D " + getString(R.string.add_manual),
            "\uD83D\uDCC1 " + getString(R.string.add_from_json)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_xray)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddManualDialog()
                    1 -> jsonFilePicker.launch("application/json")
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun showAddManualDialog() {
        if (!isAdded) return
        val et = EditText(requireContext())
        et.hint = getString(R.string.hint_mixed_links)
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                       android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.setHorizontallyScrolling(false)
        et.minLines = 4
        et.maxLines = 8
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_xray)
            .setView(et)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val text = et.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                autoDetectAndAdd(text)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /** sing-box "endpoints" array → WireGuard .conf larni import qiladi. */
    private fun importWireGuardEndpoints(json: String, fileName: String) {
        val a = activity as? MainActivity ?: return
        Thread {
            var added = 0
            try {
                val root = org.json.JSONObject(json)
                val endpoints = root.optJSONArray("endpoints") ?: return@Thread
                android.util.Log.i("NurVPN-JSON",
                    "WireGuard endpoints: ${endpoints.length()} ta")
                for (i in 0 until endpoints.length()) {
                    val ep = endpoints.getJSONObject(i)
                    val type = ep.optString("type", "")
                    if (type != "wireguard") {
                        android.util.Log.w("NurVPN-JSON",
                            "endpoints[$i]: type=$type, skip")
                        continue
                    }
                    val conf = buildWireGuardConf(ep) ?: continue
                    val r = AWGParser.parse(conf)
                    if (!r.ok) {
                        android.util.Log.w("NurVPN-JSON",
                            "endpoints[$i]: AWGParser xato: ${r.error}")
                        continue
                    }
                    val cfg = AWGConfig(conf)
                    cfg.endpoint = r.endpoint
                    cfg.address = r.address
                    cfg.name = ep.optString("tag", "WG ${i + 1}")
                    activity?.runOnUiThread {
                        a.awgConfigs.add(cfg)
                    }
                    added++
                }
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-JSON", "WG parse xato", t)
            }
            val finalAdded = added
            activity?.runOnUiThread {
                if (finalAdded == 0) {
                    Toast.makeText(context, getString(R.string.toast_wg_not_found),
                        Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AWGStore.save(a, a.awgConfigs)
                ad?.notifyDataChanged()
                Toast.makeText(context,
                    "$finalAdded WireGuard qo\'shildi",
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** sing-box endpoint JSON → WireGuard .conf matni. */
    private fun buildWireGuardConf(ep: org.json.JSONObject): String? {
        val privateKey = ep.optString("private_key", "")
        if (privateKey.isEmpty()) return null
        val peers = ep.optJSONArray("peers") ?: return null
        if (peers.length() == 0) return null
        val peer = peers.getJSONObject(0)

        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        val addrArr = ep.optJSONArray("address")
        if (addrArr != null && addrArr.length() > 0) {
            val list = (0 until addrArr.length()).map { addrArr.getString(it) }
            sb.appendLine("Address = ${list.joinToString(", ")}")
        }
        val mtu = ep.optInt("mtu", 0)
        if (mtu > 0) sb.appendLine("MTU = $mtu")
        val dnsArr = ep.optJSONArray("dns")
        if (dnsArr != null && dnsArr.length() > 0) {
            val list = (0 until dnsArr.length()).map { dnsArr.getString(it) }
            sb.appendLine("DNS = ${list.joinToString(", ")}")
        }
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = ${peer.optString("public_key")}")
        val paddr = peer.optString("address", "")
        val pport = peer.optInt("port", 51820)
        if (paddr.isEmpty()) return null
        sb.appendLine("Endpoint = $paddr:$pport")
        val allowed = peer.optJSONArray("allowed_ips")
        if (allowed != null && allowed.length() > 0) {
            val list = (0 until allowed.length()).map { allowed.getString(it) }
            sb.appendLine("AllowedIPs = ${list.joinToString(", ")}")
        } else {
            sb.appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        }
        // Reserved (base64 → [1, 2, 3])
        val reserved = peer.optString("reserved", "")
        if (reserved.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(
                    reserved, android.util.Base64.DEFAULT)
                val nums = bytes.joinToString(",", "[", "]") {
                    (it.toInt() and 0xFF).toString()
                }
                sb.appendLine("Reserved = $nums")
            } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    private fun importJsonFile(text: String, fileName: String) {
        val a = activity as? MainActivity ?: return
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            Toast.makeText(context, getString(R.string.toast_not_json), Toast.LENGTH_LONG).show()
            return
        }
        // ═══ sing-box endpoints (WireGuard) ═══
        if (trimmed.startsWith("{") && trimmed.contains("\"endpoints\"")) {
            importWireGuardEndpoints(trimmed, fileName)
            return
        }
        val subId = "jsonfile_" + System.currentTimeMillis().toString(36)
        val subName = fileName.removeSuffix(".json").ifBlank { "JSON Fayl" }

        Thread {
            val servers = ArrayList<ServerItem>()
            try {
                if (trimmed.startsWith("[")) {
                    val arr = org.json.JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val si = ServerLinkParser.parse(obj.toString(), subId)
                        if (si != null) {
                            si.subId = subId
                            servers.add(si)
                        }
                    }
                } else {
                    val single = ServerLinkParser.parse(trimmed, subId)
                    if (single != null) {
                        single.subId = subId
                        servers.add(single)
                    } else {
                        var depth = 0
                        var start = -1
                        for (i in trimmed.indices) {
                            when (trimmed[i]) {
                                '{' -> { if (depth == 0) start = i; depth++ }
                                '}' -> {
                                    depth--
                                    if (depth == 0 && start >= 0) {
                                        val chunk = trimmed.substring(start, i + 1)
                                        val si = ServerLinkParser.parse(chunk, subId)
                                        if (si != null) {
                                            si.subId = subId
                                            servers.add(si)
                                        }
                                        start = -1
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-JSON", "Parse xato", t)
            }

            activity?.runOnUiThread {
                if (servers.isEmpty()) {
                    Toast.makeText(context, getString(R.string.toast_server_not_found), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val sub = Subscription(subId, "file://" + fileName, subName)
                a.subscriptions.add(sub)
                SubscriptionStore.save(a, a.subscriptions)

                var added = 0
                for (si in servers) {
                    if (a.servers.any { it.link == si.link }) continue
                    a.servers.add(si)
                    added++
                }
                ServerStore.save(a, a.servers)
                ad?.notifyDataChanged()
                Toast.makeText(context, "$added server qo\'shildi", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun autoDetectAndAdd(text: String) {
        val trimmed = text.trim()
        // ═══ 1) Subscription URL? ═══
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val firstLine = trimmed.lines().firstOrNull()?.trim() ?: trimmed
            if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
                loadSub(firstLine, null)
                return
            }
        }
        // ═══ 2) Link(lar) ═══
        addLink(trimmed)
    }

    private fun showAWGDialog() {
        if (!isAdded) return
        val options = arrayOf(
            getString(R.string.awg_manual_input),
            getString(R.string.awg_from_file)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_awg)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAWGManualDialog()
                    1 -> awgFilePicker.launch("*/*")
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun showAWGManualDialog() {
        if (!isAdded) return
        val et = EditText(requireContext())
        et.hint = getString(R.string.hint_awg_config)
        et.minLines = 10
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_awg)
            .setView(et)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val conf = et.text.toString().trim()
                if (conf.isNotEmpty()) addAWG(conf)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun addAWG(conf: String) {
        val a = activity as? MainActivity ?: return
        val r = AWGParser.parse(conf)
        if (!r.ok) {
            Toast.makeText(context, getString(R.string.toast_awg_config_error, r.error), Toast.LENGTH_LONG).show()
            return
        }
        val cfg = AWGConfig(conf)
        cfg.endpoint = r.endpoint
        cfg.address = r.address
        cfg.name = "AWG " + r.endpoint
        a.awgConfigs.add(cfg)
        AWGStore.save(a, a.awgConfigs)
        a.selectAWG(cfg)
        a.protocol = MainActivity.PROTO_AWG
        AWGEditorBus.init(a.awgConfigs, cfg, MainActivity.PROTO_AWG)
        ad?.notifyDataChanged()
        Toast.makeText(context, getString(R.string.toast_awg_added, r.version), Toast.LENGTH_SHORT).show()
    }

    private fun showSubDialog() {
        if (!isAdded) return
        val c = requireContext()
        val container = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }
        val nameEt = EditText(c).apply {
            hint = getString(R.string.hint_name_optional)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_secondary))
        }
        val urlEt = EditText(c).apply {
            hint = getString(R.string.hint_sub_url)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_secondary))
        }
        container.addView(nameEt)
        container.addView(urlEt)

        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_add_sub)
            .setView(container)
            .setPositiveButton(R.string.dialog_load) { _, _ ->
                val url = urlEt.text.toString().trim()
                val name = nameEt.text.toString().trim().ifEmpty { null }
                if (url.isEmpty()) {
                    Toast.makeText(c, R.string.toast_url_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // ═══ DIRECT NODE LINK? ═══
                val isDirectNode = url.startsWith("vless://") ||
                                   url.startsWith("vmess://") ||
                                   url.startsWith("trojan://") ||
                                   url.startsWith("ss://") ||
                                   url.startsWith("hysteria2://") ||
                                   url.startsWith("hy2://") ||
                                   url.startsWith("tuic://")

                if (isDirectNode) {
                    // To'g'ridan-to'g'ri link sifatida qo'shamiz
                    val a = activity as? MainActivity ?: return@setPositiveButton
                    val si = ServerLinkParser.parse(url)
                    if (si == null) {
                        Toast.makeText(c,
                            "Link parse qilinmadi: ${url.take(50)}",
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    a.servers.add(si)
                    ServerStore.save(a, a.servers)
                    ad?.notifyDataChanged()
                    Toast.makeText(c, R.string.toast_server_added_one,
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // ═══ SUBSCRIPTION URL ═══
                val fixedUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.contains(".") && !url.contains("://") -> "https://$url"
                    else -> {
                        Toast.makeText(c,
                            "Subscription: https://example.com/sub/xxx\n" +
                            "Yoki: vless://, vmess://, hysteria2://",
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }
                loadSub(fixedUrl, name)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }


    /** HWID — BARQAROR (qayta o'rnatilsa ham bir xil). */
    private fun getHwid(): String {
        val ctx = requireContext()
        // ANDROID_ID — qurilma uchun barqaror
        val androidId = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (t: Throwable) { "unknown" }

        val model = android.os.Build.MODEL ?: "device"
        val packageName = ctx.packageName

        // Barqaror hash
        val raw = "$androidId-$model-$packageName"
        val hash = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

        return hash
    }

    private fun loadSub(url: String, subName: String?) {
        Toast.makeText(context, R.string.toast_loading, Toast.LENGTH_SHORT).show()
        Thread {
            android.util.Log.i("NurVPN-DBG", "loadSub: URL=$url")
            val body = fetchSub(url)
            android.util.Log.i("NurVPN-DBG", "loadSub: body uzunligi=${body.length}")
            if (body.isEmpty()) {
                activity?.runOnUiThread {
                    Toast.makeText(context,
                        R.string.toast_server_no_response,
                        Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // ═══ Linklarni ajratish ═══
            val directLinks = SubscriptionLinkExtractor.extract(body)
            android.util.Log.i("NurVPN-DBG", "loadSub: directLinks=${directLinks.size}")

            val links = if (directLinks.isNotEmpty()) {
                directLinks
            } else {
                val decoded = decodeBase64Safely(body)
                val decodedLinks = SubscriptionLinkExtractor.extract(decoded)
                android.util.Log.i("NurVPN-DBG", "loadSub: decodedLinks=${decodedLinks.size}")
                decodedLinks
            }

            android.util.Log.i("NurVPN-DBG", "loadSub: topilgan linklar=${links.size}")
            if (!isAdded) return@Thread
            activity?.runOnUiThread {
                val a = activity as? MainActivity ?: return@runOnUiThread

                // Subscription yaratish yoki mavjudni topish
                var sub = a.subscriptions.find { it.url == url }
                if (sub == null) {
                    val id = "sub_" + System.currentTimeMillis().toString(36)
                    sub = Subscription(id, url, subName ?: extractSubTitle(url))
                    a.subscriptions.add(sub)
                } else if (!subName.isNullOrEmpty()) {
                    sub.name = subName
                }
                val currentSub = sub ?: return@runOnUiThread
                currentSub.lastUpdated = System.currentTimeMillis()
                currentSub.serverCount = links.size

                // Eski serverlarni olib tashlash
                a.servers.removeAll { it.subId == currentSub.id }

                var added = 0
                for (l in links) {
                    val si = ServerLinkParser.parse(l, currentSub.id) ?: continue
                    a.servers.add(si)
                    added++
                }
                SubscriptionStore.save(a, a.subscriptions)
                ServerStore.save(a, a.servers)
                ad?.notifyDataChanged()
                Toast.makeText(context,
                    "${currentSub.name}: $added ta yuklandi",
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun extractSubTitle(url: String): String {
        // URL'dan qisqa nom chiqarish: sub.example.com/path -> "sub"
        return try {
            val host = java.net.URL(url).host
            val parts = host.split(".")
            when {
                parts.size >= 2 && parts[0].length < 4 -> parts[1]
                parts.isNotEmpty() -> parts[0]
                else -> "Subscription"
            }.replaceFirstChar { it.uppercase() }
        } catch (t: Throwable) { "Subscription" }
    }

    private fun fetchSub(urlStr: String): String {
        android.util.Log.i("NurVPN-DBG", "fetchSub: boshlanishi URL=$urlStr")
        return try {
            val u = java.net.URL(urlStr)
            val c = u.openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "v2rayTun/3.6.0 (Linux; Android 13; SM-S918B)")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("x-hwid", getHwid())
            c.setRequestProperty("x-device-os", "Android")
            c.setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE ?: "13")
            c.setRequestProperty("x-device-model", android.os.Build.MODEL ?: "SM-S918B")
            c.setRequestProperty("x-app-version", "3.6.0")
            c.setRequestProperty("x-sub-request", "1")
            c.setRequestProperty("x-ver", "3.6.0")

            c.connect()
            val code = c.responseCode
            val encoding = (c.contentEncoding ?: "").lowercase()
            android.util.Log.i("NurVPN-DBG",
                "HTTP code=$code, encoding=$encoding, contentLength=${c.contentLength}, contentType=${c.contentType}")

            if (code !in 200..299) {
                android.util.Log.e("NurVPN-DBG", "HTTP xato: $code")
                c.disconnect()
                return ""
            }

            val compressedBytes = c.inputStream.use { it.readBytes() }
            val bodyBytes = when {
                encoding.contains("gzip") -> java.util.zip.GZIPInputStream(
                    java.io.ByteArrayInputStream(compressedBytes)
                ).use { it.readBytes() }
                encoding.contains("deflate") -> java.util.zip.InflaterInputStream(
                    java.io.ByteArrayInputStream(compressedBytes)
                ).use { it.readBytes() }
                else -> compressedBytes
            }
            val body = String(bodyBytes, Charsets.UTF_8)
            android.util.Log.i("NurVPN-DBG", "fetchSub: body uzunligi=${bodyBytes.size}")
            c.disconnect()
            body
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "fetchSub XATO: ${t.message}", t)
            ""
        }
    }


    private fun addLink(text: String) {
        android.util.Log.i("NurVPN-DBG", "addLink: text uzunligi=${text.length}")
        android.util.Log.i("NurVPN-DBG", "addLink: birinchi 200 belgi=${text.take(200)}")
        val a = activity as? MainActivity ?: return
        val regex = Regex("(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://.*?(?=(?:vless|vmess|trojan|ss|hy2|hysteria2|tuic)://|\\s|$)")
        val matches = regex.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) {
            Toast.makeText(context, R.string.toast_invalid_link,
                Toast.LENGTH_SHORT).show()
            return
        }
        var added = 0
        for (link in matches) {
            android.util.Log.i("NurVPN-DBG", "addLink topdi: ${link.take(80)}")
            val si = parseServer(link)
            if (si == null) {
                android.util.Log.w("NurVPN-DBG", "addLink parseServer null qaytardi")
                continue
            }
            a.servers.add(si)
            added++
        }
        ServerStore.save(a, a.servers)
        ad?.notifyDataChanged()
        android.util.Log.i("NurVPN-DBG", "addLink natija: $added ta qo'shildi (jami: ${a.servers.size})")
        Toast.makeText(context, getString(R.string.toast_added_count, added), Toast.LENGTH_SHORT).show()
    }

    private fun parseServer(link: String?, subId: String? = null): ServerItem? {
        if (link.isNullOrEmpty()) return null
        if (link.length > 8192) return null
        if (link.contains("\n") || link.contains("\r")) return null
        return ServerLinkParser.parse(link, subId)
    }

    // ───────── ADAPTER (sectioned) ─────────

    // Har bir qator: sarlavha yoki server
    private sealed class Row {
        companion object {
            const val AWG_HEADER_ID = "__awg__"
        }
        class Header(
            val subId: String?,
            val name: String,
            val count: Int,
            val isAwg: Boolean,
            val isExpanded: Boolean = false,
            val isManual: Boolean = false
        ) : Row()
        class Item(val data: Any) : Row()
    }

    private inner class ServerAdapter(
        private val frag: ServersFragment,
        private val act: MainActivity?
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = ArrayList<Row>()
        private var currentQuery = ""
        private var protoFilter = "all"
        private val expandedSubscriptions = mutableSetOf<String>()

        // ═══ Tanlash rejimi ═══
        var selectMode: Boolean = false
        val selectedLinks = mutableSetOf<String>()
        val selectedAwg = mutableSetOf<String>()

        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        fun enterSelectMode() {
            selectMode = true
            selectedLinks.clear()
            selectedAwg.clear()
            frag.showSelectionBar()
            rebuild()
        }

        fun exitSelectMode() {
            selectMode = false
            selectedLinks.clear()
            selectedAwg.clear()
            frag.hideSelectionBar()
            rebuild()
        }

        fun toggleSelect(item: Any) {
            when (item) {
                is ServerItem -> {
                    if (selectedLinks.contains(item.link)) selectedLinks.remove(item.link)
                    else selectedLinks.add(item.link)
                }
                is AWGConfig -> {
                    val k = item.rawConf ?: ""
                    if (selectedAwg.contains(k)) selectedAwg.remove(k)
                    else selectedAwg.add(k)
                }
            }
            frag.updateSelectionCount(selectedLinks.size + selectedAwg.size)
            notifyDataSetChanged()
        }

        fun selectAllVisible() {
            selectedLinks.clear()
            selectedAwg.clear()
            for (row in rows) {
                if (row is Row.Item) {
                    when (val d = row.data) {
                        is ServerItem -> selectedLinks.add(d.link)
                        is AWGConfig -> selectedAwg.add(d.rawConf ?: "")
                    }
                }
            }
            frag.updateSelectionCount(selectedLinks.size + selectedAwg.size)
            notifyDataSetChanged()
        }

        fun deleteSelected() {
            val a = act ?: return
            var n = 0
            if (selectedLinks.isNotEmpty()) {
                val before = a.servers.size
                a.servers.removeAll { it.link in selectedLinks }
                n += before - a.servers.size
                ServerStore.save(a, a.servers)
            }
            if (selectedAwg.isNotEmpty()) {
                val before = a.awgConfigs.size
                a.awgConfigs.removeAll { (it.rawConf ?: "") in selectedAwg }
                n += before - a.awgConfigs.size
                AWGStore.save(a, a.awgConfigs)
            }
            try {
                Toast.makeText(frag.requireContext(), frag.getString(R.string.toast_n_deleted_fmt, n), Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            exitSelectMode()
        }

        init { rebuild() }

        fun rebuild() {
            rows.clear()
            val a = act ?: return

            // Filter protokol bo'yicha
            val allServers = a.servers.filter { si ->
                val okProto = when (protoFilter) {
                    "vless" -> si.protocol == Protocol.VLESS_REALITY
                    "vmess" -> si.protocol == Protocol.VMESS
                    "trojan" -> si.protocol == Protocol.TROJAN
                    "hy2" -> si.protocol == Protocol.HYSTERIA2
                    "tuic" -> si.protocol == Protocol.TUIC
                    "awg" -> false
                    "fav" -> si.favorite
                    else -> true
                }
                okProto && matchesQuery(si.displayName())
            }

            val awgAll = if (protoFilter in setOf(
                    "vless", "vmess", "trojan", "hy2", "tuic", "fav")) {
                emptyList<AWGConfig>()
            } else AwgSortStore.sort(
                a.awgConfigs.filter { matchesQuery(it.name ?: "AWG") },
                AwgSortStore.getMode(act?.applicationContext ?: frag.requireContext())
            )

            val grouped = LinkedHashMap<String?, MutableList<ServerItem>>()
            for (si in allServers) {
                val key = si.subId
                grouped.getOrPut(key) { ArrayList() }.add(si)
            }

            // Har bir guruhni obunaning sortMode bo'yicha saralash
            for ((subId, list) in grouped) {
                val subSort = if (subId != null)
                    a.subscriptions.find { it.id == subId }?.sortMode ?: "default"
                    else "default"
                when (subSort) {
                    "name_asc" -> list.sortBy { it.displayName().lowercase() }
                    "name_desc" -> list.sortByDescending { it.displayName().lowercase() }
                    "ping_asc" -> list.sortBy {
                        if (it.ping < 0) Int.MAX_VALUE else it.ping
                    }
                    "ping_desc" -> list.sortByDescending { it.ping }
                }
            }

            // FIX: Obuna tartibini `order` field bo'yicha (Home bilan bir xil)
            val sortedSubIds = a.subscriptions
                .sortedBy { it.order }
                .map { it.id }

            for (subId in sortedSubIds) {
                val list = grouped[subId] ?: continue
                val sub = a.subscriptions.find { it.id == subId } ?: continue
                val isExpanded = expandedSubscriptions.contains(subId)
                rows.add(Row.Header(subId, "📡 ${sub.name}", list.size, false, isExpanded))
                if (isExpanded) {
                    for (si in list) rows.add(Row.Item(si))
                }
            }

            // Qo'lda qo'shilgan serverlar — oxirida
            grouped[null]?.let { manualListRaw ->
                // Manual sort mode qo'llash
                val manualSortMode = frag.requireContext()
                    .getSharedPreferences("manual_sort", android.content.Context.MODE_PRIVATE)
                    .getString("mode", "default") ?: "default"
                val manualList = when (manualSortMode) {
                    "name_asc" -> manualListRaw.sortedBy { it.displayName().lowercase() }
                    "name_desc" -> manualListRaw.sortedByDescending { it.displayName().lowercase() }
                    "ping_asc" -> manualListRaw.sortedBy { if (it.ping < 0) Int.MAX_VALUE else it.ping }
                    "ping_desc" -> manualListRaw.sortedByDescending { it.ping }
                    else -> manualListRaw
                }
                val isExpanded = expandedSubscriptions.contains("manual")
                rows.add(Row.Header(null,
                    "🔧 ${frag.getString(R.string.manual_added)}",
                    manualList.size, false, isExpanded, isManual = true))
                if (isExpanded) {
                    for (si in manualList) rows.add(Row.Item(si))
                }
            }

            // 2) AWG bo'limi (oxirida)
            if (awgAll.isNotEmpty()) {
                val awgExp = expandedSubscriptions.contains(Row.AWG_HEADER_ID)
                rows.add(Row.Header(Row.AWG_HEADER_ID, "🛡 AWG Config", awgAll.size, true, awgExp))
                if (awgExp) {
                    for (awg in awgAll) rows.add(Row.Item(awg))
                }
            }

            notifyDataSetChanged()
        }

        fun toggleSubscription(subId: String?) {
            val key = subId ?: "manual"
            if (expandedSubscriptions.contains(key)) {
                expandedSubscriptions.remove(key)
            } else {
                expandedSubscriptions.add(key)
            }
            rebuild()
        }

        private fun matchesQuery(name: String?): Boolean {
            if (currentQuery.isEmpty()) return true
            return name?.lowercase()?.contains(currentQuery.lowercase()) == true
        }

        fun filter(q: String) {
            currentQuery = q
            rebuild()
        }

        fun setProtoFilter(key: String) {
            protoFilter = key
            rebuild()
        }

        fun notifyDataChanged() { rebuild() }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        @NonNull
        override fun onCreateViewHolder(@NonNull p: ViewGroup, v: Int): RecyclerView.ViewHolder {
            return if (v == TYPE_HEADER) {
                HeaderVH(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_section_header, p, false))
            } else {
                ItemVH(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_server, p, false))
            }
        }

        override fun onBindViewHolder(@NonNull h: RecyclerView.ViewHolder, pos: Int) {
            when (val row = rows[pos]) {
                is Row.Header -> (h as HeaderVH).bind(row)
                is Row.Item -> {
                    val vh = h as ItemVH
                    when (val data = row.data) {
                        is ServerItem -> bindServer(vh, data)
                        is AWGConfig -> bindAWG(vh, data)
                    }
                }
            }
        }

        override fun getItemCount(): Int = rows.size

        private fun bindServer(h: ItemVH, s: ServerItem) {
            // ═══ Tanlash rejimi ═══
            if (selectMode) {
                h.selectCheck?.visibility = View.VISIBLE
                h.selectCheck?.isChecked = selectedLinks.contains(s.link)
            } else {
                h.selectCheck?.visibility = View.GONE
            }
            h.fav?.visibility = View.VISIBLE

            // ⭐ Yulduzcha

            h.fav?.setImageResource(
                if (s.favorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            h.fav?.setOnClickListener {
                s.favorite = !s.favorite
                act?.let { a2 -> ServerStore.save(a2, a2.servers) }
                notifyDataSetChanged()
            }

            h.flag.text = s.flag()
            h.name.text = s.displayName()
            h.addr.text = (s.host ?: "?") + (if (s.port > 0) ":${s.port}" else "")

            // Protocol chip
            if (h.aiScore != null) {
                h.aiScore.visibility = View.VISIBLE
                when (s.protocol) {
                    Protocol.VLESS_REALITY -> {
                        h.aiScore.text = "VLESS"
                        h.aiScore.setTextColor(0xFF4A9EFF.toInt())
                    }
                    Protocol.VMESS -> {
                        h.aiScore.text = "VMESS"
                        h.aiScore.setTextColor(0xFF4A9EFF.toInt())
                    }
                    Protocol.TROJAN -> {
                        h.aiScore.text = "TROJAN"
                        h.aiScore.setTextColor(0xFF4A9EFF.toInt())
                    }
                    Protocol.HYSTERIA2 -> {
                        h.aiScore.text = "HY2"
                        h.aiScore.setTextColor(0xFF9B59B6.toInt())
                    }
                    Protocol.TUIC -> {
                        h.aiScore.text = "TUIC"
                        h.aiScore.setTextColor(0xFFE91E63.toInt())
                    }
                    Protocol.SS_2022 -> {
                        h.aiScore.text = "SS"
                        h.aiScore.setTextColor(0xFFFFC107.toInt())
                    }
                }
            }

            // Ping
            when {
                s.ping < 0 -> {
                    h.ping.text = "---"
                    h.ping.setTextColor(androidx.core.content.ContextCompat.getColor(frag.requireContext(), R.color.text_secondary))
                }
                s.ping >= 9999 -> {
                    h.ping.text = "✕"
                    h.ping.setTextColor(0xFFFF5722.toInt())
                }
                s.ping < 100 -> {
                    h.ping.text = "${s.ping}ms"
                    h.ping.setTextColor(androidx.core.content.ContextCompat.getColor(frag.requireContext(), R.color.accent))
                }
                s.ping < 300 -> {
                    h.ping.text = "${s.ping}ms"
                    h.ping.setTextColor(0xFFFFC107.toInt())
                }
                else -> {
                    h.ping.text = "${s.ping}ms"
                    h.ping.setTextColor(0xFFFF5722.toInt())
                }
            }

            val selected = act?.currentServer?.link == s.link &&
                MainActivity.PROTO_XRAY == act?.protocol
            h.itemView.setBackgroundResource(
                if (selected) R.drawable.item_selected_bg else R.drawable.item_bg)

            h.itemView.setOnClickListener {
                if (selectMode) {
                    toggleSelect(s)
                    return@setOnClickListener
                }
                act?.let {
                    it.selectServer(s)
                    it.protocol = MainActivity.PROTO_XRAY
                }
                notifyDataSetChanged()
                frag.refresh()
            }
            h.itemView.setOnLongClickListener { showMenu(s); true }
        }

        private fun bindAWG(h: ItemVH, awg: AWGConfig) {
            // ═══ Tanlash rejimi ═══
            if (selectMode) {
                h.selectCheck?.visibility = View.VISIBLE
                h.selectCheck?.isChecked = selectedAwg.contains(awg.rawConf ?: "")
            } else {
                h.selectCheck?.visibility = View.GONE
            }

            // ⭐ Yulduzcha
            h.fav?.visibility = View.VISIBLE
            h.fav?.setImageResource(
                if (awg.favorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            h.fav?.setOnClickListener {
                awg.favorite = !awg.favorite
                act?.let { a2 -> AWGStore.save(a2, a2.awgConfigs) }
                notifyDataSetChanged()
            }

            h.flag.text = "🔒"
            h.name.text = awg.name ?: "AWG"
            h.addr.text = awg.endpoint ?: ""
            if (awg.ping > 0 && awg.ping < 9999) {
                h.ping.text = "${awg.ping}ms"
                h.ping.setTextColor(
                    if (awg.ping < 100) 0xFFC4F82A.toInt()
                    else if (awg.ping < 300) 0xFFFFC107.toInt()
                    else 0xFFFF5722.toInt()
                )
            } else {
                h.ping.text = "AWG"
                h.ping.setTextColor(0xFF6C5CE7.toInt())
            }
            h.aiScore?.visibility = View.GONE

            val selected = act?.currentAWG?.rawConf == awg.rawConf &&
                MainActivity.PROTO_AWG == act?.protocol
            h.itemView.setBackgroundResource(
                if (selected) R.drawable.item_selected_bg else R.drawable.item_bg)

            h.itemView.setOnClickListener {
                if (selectMode) {
                    toggleSelect(awg)
                    return@setOnClickListener
                }
                act?.let {
                    it.selectAWG(awg)
                    it.protocol = MainActivity.PROTO_AWG
                    AWGEditorBus.init(it.awgConfigs, awg, MainActivity.PROTO_AWG)
                }
                notifyDataSetChanged()
                frag.refresh()
            }
            h.itemView.setOnLongClickListener { showMenu(awg); true }
        }

        // ═══════ ViewHolders ═══════

        /** Faqat shu obuna serverlarini ping qilish. */
        fun pingSubscription(subId: String, subName: String) {
            val a = act ?: return
            val ctx = context ?: return

            // FIX: Debounce — 400ms ichida bir marta rebuild (UI freeze oldini olish)
            var scheduled = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val rebuildRunnable = Runnable { notifyDataSetChanged() }

            // Sevimlilar kartasi uchun maxsus
            if (subId == "fav_card") {
                val favs = a.servers.filter { it.favorite }
                if (favs.isEmpty()) {
                    Toast.makeText(ctx, R.string.text_no_servers_add,
                        Toast.LENGTH_SHORT).show()
                    return
                }
                Toast.makeText(ctx,
                    ctx.getString(R.string.ping_sub_started, subName, favs.size),
                    Toast.LENGTH_SHORT).show()
                android.util.Log.i("NurVPN-PING",
                    "Ping favorites: ${favs.size}")
                PingTester.testAll(favs, object : PingTester.Listener {
                    override fun onPingUpdate(item: ServerItem, ping: Int) {
                        // FIX: debounce
                        if (!scheduled) {
                            scheduled = true
                            handler.postDelayed({
                                scheduled = false
                                rebuildRunnable.run()
                            }, 1200)
                        }
                    }
                    override fun onAllDone() {
                        handler.removeCallbacksAndMessages(null)
                        ServerStore.save(a, a.servers)
                        notifyDataSetChanged()
                        Toast.makeText(ctx, R.string.ping_done,
                            Toast.LENGTH_SHORT).show()
                    }
                })
                return
            }

            // FIX: Auto ping_asc sort OLIB TASHLANDI (ping tugagach qo'llaniladi).
            // Sort ping davomida UI'ni qotiradi.
            a.subscriptions = SubscriptionStore.load(a)
            var servers = a.servers.filter { it.subId == subId }

            // FIX: 600+ server uchun limit — bir vaqtda max 100
            val MAX_PING_SUB = 100
            if (servers.size > MAX_PING_SUB) {
                android.util.Log.w("NurVPN-PING",
                    "pingSub: ${servers.size} ta, faqat $MAX_PING_SUB tasi")
                servers = servers.take(MAX_PING_SUB)
            }

            android.util.Log.i("NurVPN-PING",
                "pingSub: subId=$subId, matched=${servers.size}, total=${a.servers.size}")
            if (servers.isEmpty()) {
                // Fallback 1: barcha null bo'lmagan subId larni ko'rish
                val allSubIds = a.servers.mapNotNull { it.subId }.distinct()
                android.util.Log.w("NurVPN-PING",
                    "pingSub: matched=0, mavjud subIds=$allSubIds")
                // Fallback 2: subName bo'yicha qidiramiz (agar subId o'zgargan bo'lsa)
                val subNameById = a.subscriptions.find { it.id == subId }?.name
                if (subNameById != null) {
                    // Bu obunaga tegishli serverlarni topib bo'lmaydi — barchasini olamiz
                    android.util.Log.w("NurVPN-PING",
                        "pingSub: fallback — barcha ${a.servers.size} server")
                    servers = a.servers
                }
                if (servers.isEmpty()) {
                    Toast.makeText(ctx, R.string.no_servers_in_sub,
                        Toast.LENGTH_SHORT).show()
                    return
                }
            }
            Toast.makeText(ctx,
                ctx.getString(R.string.ping_sub_started, subName, servers.size),
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("NurVPN-PING",
                "Ping sub: $subName (${servers.size})")

            PingTester.testAll(servers, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    // FIX: debounce — 400ms ichida bir marta
                    if (!scheduled) {
                        scheduled = true
                        handler.postDelayed({
                            scheduled = false
                            rebuildRunnable.run()
                        }, 1200)
                    }
                }
                override fun onAllDone() {
                    handler.removeCallbacksAndMessages(null)
                    ServerStore.save(a, a.servers)
                    // FIX: Ping tugagach — sort qilib yangilash (rebuild EMAS)
                    SubscriptionStore.setSortMode(ctx, subId, "ping_asc")
                    a.subscriptions = SubscriptionStore.load(a)
                    // Yengil yangilash — adapter'ning hozirgi view'larini yangilash
                    notifyDataSetChanged()
                    Toast.makeText(ctx, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            })
        }

        /** Barcha AWG configlarini ping qilish. */
        fun pingAwgAll() {
            val a = act ?: return
            val ctx = context ?: return
            if (a.awgConfigs.isEmpty()) {
                Toast.makeText(ctx, R.string.text_no_awg,
                    Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(ctx, R.string.ping_started,
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("NurVPN-PING",
                "AWG ping: ${a.awgConfigs.size} config")
            Thread {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                val latch = java.util.concurrent.CountDownLatch(a.awgConfigs.size)
                for (cfg in a.awgConfigs) {
                    pool.execute {
                        try {
                            val ep = cfg.endpoint ?: return@execute
                            val host = ep.substringBeforeLast(":")
                            val port = ep.substringAfterLast(":").toIntOrNull() ?: 0
                            var ping = if (port > 0)
                                PingTester.tcpPing(host, port, 3000) else -1
                            if (ping <= 0) {
                                ping = try {
                                    PingTester.icmpPing(host, 2000)
                                } catch (t: Throwable) { -1 }
                            }
                            if (ping <= 0) {
                                ping = try {
                                    val start = System.currentTimeMillis()
                                    val sock = java.net.DatagramSocket()
                                    sock.connect(java.net.InetAddress.getByName(host), port)
                                    sock.send(java.net.DatagramPacket(ByteArray(1), 1))
                                    sock.close()
                                    (System.currentTimeMillis() - start).toInt()
                                } catch (t: Throwable) { -1 }
                            }
                            cfg.ping = ping
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                try { latch.await(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
                pool.shutdown()
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    AWGStore.save(a, a.awgConfigs)
                    rebuild()
                    Toast.makeText(ctx, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        /** Manual serverlarni ping qilish. */
        fun pingManualAll() {
            val a = act ?: return
            val ctx = context ?: return
            val manual = a.servers.filter { it.subId == null }
            if (manual.isEmpty()) {
                Toast.makeText(ctx, R.string.text_no_servers,
                    Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(ctx,
                ctx.getString(R.string.ping_sub_started,
                    ctx.getString(R.string.manual_added), manual.size),
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("NurVPN-PING", "Manual ping: ${manual.size}")
            PingTester.testAll(manual, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    notifyDataSetChanged()
                }
                override fun onAllDone() {
                    ServerStore.save(a, a.servers)
                    notifyDataSetChanged()
                    Toast.makeText(ctx, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            })
        }

        /** Manual serverlar uchun sozlama dialogi (sort). */
        private fun showManualSettingsDialog() {
            val ctx = context ?: return
            val current = AwgSortStore.getMode(ctx)
            val modes = arrayOf(
                "default" to ctx.getString(R.string.sort_default),
                "ping_asc" to ctx.getString(R.string.sort_ping_asc),
                "ping_desc" to ctx.getString(R.string.sort_ping_desc),
                "name_asc" to ctx.getString(R.string.sort_name_asc),
                "name_desc" to ctx.getString(R.string.sort_name_desc)
            )
            val labels = modes.map { it.second }.toTypedArray()
            val idx = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.sort_title)
                .setSingleChoiceItems(labels, idx) { d, which ->
                    // Manual uchun alohida sort mode saqlaymiz
                    ctx.getSharedPreferences("manual_sort", android.content.Context.MODE_PRIVATE)
                        .edit().putString("mode", modes[which].first).apply()
                    rebuild()
                    d.dismiss()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        private fun showAwgSettingsDialogServer() {
            val ctx = context ?: return
            val current = AwgSortStore.getMode(ctx)
            val modes = arrayOf(
                "default" to ctx.getString(R.string.sort_default),
                "ping_asc" to ctx.getString(R.string.sort_ping_asc),
                "ping_desc" to ctx.getString(R.string.sort_ping_desc),
                "name_asc" to ctx.getString(R.string.sort_name_asc),
                "name_desc" to ctx.getString(R.string.sort_name_desc)
            )
            val labels = modes.map { it.second }.toTypedArray()
            val idx = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.awg_settings_title))
                .setSingleChoiceItems(labels, idx) { d, which ->
                    AwgSortStore.setMode(ctx, modes[which].first)
                    rebuild()
                    d.dismiss()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        private fun showSubSettingsDialogServer(subId: String, subName: String) {
            val ctx = context ?: return
            val a = act ?: return
            val sub = a.subscriptions.find { it.id == subId } ?: return

            val modes = arrayOf(
                "default" to ctx.getString(R.string.sort_default),
                "ping_asc" to ctx.getString(R.string.sort_ping_asc),
                "ping_desc" to ctx.getString(R.string.sort_ping_desc),
                "name_asc" to ctx.getString(R.string.sort_name_asc),
                "name_desc" to ctx.getString(R.string.sort_name_desc)
            )
            val labels = modes.map { it.second }.toTypedArray()
            val currentIdx = modes.indexOfFirst { it.first == sub.sortMode }
                .coerceAtLeast(0)

            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.sub_settings_title, subName))
                .setSingleChoiceItems(labels, currentIdx) { d, which ->
                    SubscriptionStore.setSortMode(ctx, subId, modes[which].first)
                    a.subscriptions = SubscriptionStore.load(a)
                    rebuild()
                    d.dismiss()
                }
                .setNeutralButton(ctx.getString(R.string.move_up)) { _, _ ->
                    if (SubscriptionStore.moveSubscription(ctx, subId, -1)) {
                        a.subscriptions = SubscriptionStore.load(a)
                        rebuild()
                    } else {
                        Toast.makeText(ctx, R.string.already_top, Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton(ctx.getString(R.string.move_down)) { _, _ ->
                    if (SubscriptionStore.moveSubscription(ctx, subId, 1)) {
                        a.subscriptions = SubscriptionStore.load(a)
                        rebuild()
                    } else {
                        Toast.makeText(ctx, R.string.already_bottom, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.sec_icon)
            val title: TextView = v.findViewById(R.id.sec_title)
            val count: TextView = v.findViewById(R.id.sec_count)
            val action: TextView = v.findViewById(R.id.sec_action)
            val settings: TextView? = v.findViewById(R.id.sec_settings)
            val ping: android.widget.ImageView? = v.findViewById(R.id.sec_ping)

            fun bind(row: Row.Header) {
                val parts = row.name.split(" ", limit = 2)
                icon.text = parts.getOrElse(0) { "📡" }
                title.text = parts.getOrElse(1) { row.name }
                count.text = itemView.context.getString(R.string.text_count_ta, row.count)

                // ⬆️/⬇️ arrow
                val arrowView = itemView.findViewById<TextView>(R.id.sec_arrow)
                arrowView?.text = if (row.isExpanded) "\u2B06" else "\u2B07"

                // 🔃 refresh — faqat subscription uchun
                action.text = if (row.isAwg) "" else "\uD83D\uDD03"
                action.setOnClickListener {
                    if (!row.isAwg) {
                        frag.refreshSubscriptions()
                    }
                }

                // ⚙️ sozlama (sub, AWG yoki manual uchun)
                val showSettings = row.subId != null || row.isManual
                settings?.visibility = if (showSettings) View.VISIBLE else View.GONE
                settings?.setOnClickListener {
                    when {
                        row.isManual -> showManualSettingsDialog()
                        row.subId == Row.AWG_HEADER_ID -> showAwgSettingsDialogServer()
                        row.subId != null -> showSubSettingsDialogServer(row.subId, row.name)
                    }
                }

                // 📶 ping — sub, AWG yoki manual uchun
                val showPing = row.subId != null || row.isManual
                ping?.visibility = if (showPing) View.VISIBLE else View.GONE
                ping?.setOnClickListener {
                    when {
                        row.isManual -> pingManualAll()
                        row.subId == Row.AWG_HEADER_ID -> pingAwgAll()
                        row.subId != null -> pingSubscription(row.subId, row.name)
                    }
                }

                // Sarlavha click — expand/collapse
                itemView.setOnClickListener {
                    toggleSubscription(row.subId)
                }

                // Long-press — subscription menyusi
                itemView.setOnLongClickListener {
                    if (row.subId != null && row.subId != Row.AWG_HEADER_ID) {
                        showSubMenu(row.subId, row.name)
                    }
                    true
                }
            }
        }

        private fun showSubMenu(subId: String, name: String) {
            val c = frag.context ?: return
            val sub = act?.subscriptions?.find { it.id == subId } ?: return
            val items = arrayOf(
                "\uD83D\uDD04  " + c.getString(R.string.sub_menu_refresh),
                "\uD83D\uDCD1  " + c.getString(R.string.sub_menu_copy_url),
                "\uD83D\uDDD1  " + c.getString(R.string.sub_menu_delete)
            )
            AlertDialog.Builder(c)
                .setTitle(name)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> frag.loadSubFromHeader(sub.url, sub.name)
                        1 -> {
                            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager ?: return@setItems
                            cm.setPrimaryClip(android.content.ClipData
                                .newPlainText(c.getString(R.string.clip_label_sub_url), sub.url))
                            Toast.makeText(c, R.string.toast_url_copied,
                                Toast.LENGTH_SHORT).show()
                        }
                        2 -> confirmDeleteSub(sub)
                    }
                }
                .show()
        }

        private fun confirmDeleteSub(sub: Subscription) {
            val c = frag.context ?: return
            val serverCount = act?.servers?.count { it.subId == sub.id } ?: 0
            AlertDialog.Builder(c)
                .setTitle(R.string.dialog_sub_delete)
                .setMessage(getString(R.string.dialog_sub_delete_msg, sub.name, serverCount))
                .setPositiveButton(R.string.dialog_delete_yes) { _, _ ->
                    act?.let { a ->
                        a.servers.removeAll { it.subId == sub.id }
                        ServerStore.save(a, a.servers)
                        a.subscriptions.remove(sub)
                        SubscriptionStore.save(a, a.subscriptions)
                    }
                    rebuild()
                    frag.refresh()
                    Toast.makeText(c, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        private fun confirmDeleteAllManual() {
            val c = frag.context ?: return
            val manual = act?.servers?.filter { it.subId == null } ?: return
            if (manual.isEmpty()) {
                Toast.makeText(c, R.string.toast_no_manual_servers,
                    Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(c)
                .setTitle(R.string.dialog_manual_delete)
                .setMessage(getString(R.string.dialog_manual_delete_msg, manual.size))
                .setPositiveButton(R.string.dialog_delete_yes) { _, _ ->
                    act?.let { a ->
                        a.servers.removeAll { it.subId == null }
                        ServerStore.save(a, a.servers)
                    }
                    rebuild()
                    frag.refresh()
                    Toast.makeText(c, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val selectCheck: android.widget.CheckBox? = v.findViewById(R.id.select_check)
            val flag: TextView = v.findViewById(R.id.flag)
            val name: TextView = v.findViewById(R.id.name)
            val addr: TextView = v.findViewById(R.id.addr)
            val ping: TextView = v.findViewById(R.id.ping)
            val aiScore: TextView? = v.findViewById(R.id.ai_score)
            val fav: ImageView? = v.findViewById(R.id.fav)
        }

        // ═══════ ShowMenu (long-press) ═══════

        private fun showMenu(target: Any) {
            if (!frag.isAdded) return
            val c = frag.context ?: return
            val items = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()

            if (target is ServerItem) {
                items.add("🚀 " + c.getString(R.string.srv_menu_connect))
                actions.add {
                    act?.let {
                        it.selectServer(target)
                        it.protocol = MainActivity.PROTO_XRAY
                        Toast.makeText(c, target.displayName(), Toast.LENGTH_SHORT).show()
                    }
                    notifyDataSetChanged()
                    frag.refresh()
                }
                // ⭐ Sevimlilar toggle
                items.add(if (target.favorite) "💔 " + c.getString(R.string.srv_menu_remove_fav)
                          else "⭐ " + c.getString(R.string.srv_menu_add_fav))
                actions.add {
                    target.favorite = !target.favorite
                    act?.let { a2 -> ServerStore.save(a2, a2.servers) }
                    notifyDataSetChanged()
                    frag.refresh()
                    Toast.makeText(c,
                        if (target.favorite) c.getString(R.string.toast_added_fav)
                        else c.getString(R.string.toast_removed_fav),
                        Toast.LENGTH_SHORT).show()
                }
                items.add("✏️ " + c.getString(R.string.srv_menu_rename))
                actions.add { editServer(target) }
                items.add("📋 " + c.getString(R.string.srv_menu_copy_link))
                actions.add { copyToClipboard(target.link, c.getString(R.string.clip_label_link)) }
                items.add("📤 " + c.getString(R.string.srv_menu_share))
                actions.add { shareLink(target.link, target.displayName()) }
                items.add("📱 " + c.getString(R.string.srv_menu_show_qr))
                actions.add { QrShowDialog.show(c, target.displayName(), target.link) }
            } else if (target is AWGConfig) {
                items.add("🚀 " + c.getString(R.string.srv_menu_connect))
                actions.add {
                    act?.let {
                        it.selectAWG(target)
                        it.protocol = MainActivity.PROTO_AWG
                        AWGEditorBus.init(it.awgConfigs, target, MainActivity.PROTO_AWG)
                        Toast.makeText(c, target.name ?: getString(R.string.text_awg_label), Toast.LENGTH_SHORT).show()
                    }
                    notifyDataSetChanged()
                    frag.refresh()
                }
                items.add("✏️ " + c.getString(R.string.srv_menu_edit))
                val idx = act?.awgConfigs?.indexOf(target) ?: -1
                actions.add {
                    if (idx >= 0 && frag.isAdded) {
                        val i = Intent(c, AWGEditorActivity::class.java)
                        i.putExtra(AWGEditorActivity.EXTRA_INDEX, idx)
                        i.putExtra(AWGEditorActivity.EXTRA_RAW, target.rawConf)
                        frag.startActivity(i)
                    }
                }
                items.add("📋 " + c.getString(R.string.srv_menu_copy_config))
                actions.add { copyToClipboard(target.rawConf ?: "", c.getString(R.string.clip_label_awg_config)) }
                items.add("📱 " + c.getString(R.string.srv_menu_show_qr))
                actions.add { QrShowDialog.show(c, target.name ?: "AWG", target.rawConf ?: "") }
                items.add(c.getString(R.string.dialog_rename_awg))
                actions.add { editAwgName(target) }
            }

            // ═══ Tanlash rejimi ═══
            items.add("☑ " + c.getString(R.string.sel_mode))
            actions.add { enterSelectMode() }

            items.add("🗑 " + c.getString(R.string.menu_delete))
            actions.add { confirmDelete(target) }

            AlertDialog.Builder(c)
                .setTitle(if (target is ServerItem) target.displayName()
                          else (target as AWGConfig).name)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which in actions.indices) actions[which]()
                }
                .show()
        }

        private fun copyToClipboard(text: String, label: String) {
            if (text.isEmpty()) return
            val c = frag.context ?: return
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Toast.makeText(c, getString(R.string.toast_copied, label), Toast.LENGTH_SHORT).show()
        }

        private fun shareLink(link: String, name: String) {
            val c = frag.context ?: return
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_TEXT, link)
            }
            c.startActivity(Intent.createChooser(i, c.getString(R.string.share_via)))
        }

        private fun editServer(s: ServerItem) {
            if (!frag.isAdded) return
            val c = frag.context ?: return
            val container = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 30, 40, 10)
            }
            val nameIn = EditText(c).apply {
                hint = getString(R.string.hint_name)
                setText(s.remark ?: s.host ?: "")
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.text_secondary))
            }
            val hostIn = EditText(c).apply {
                hint = getString(R.string.hint_host)
                setText(s.host ?: "")
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.text_secondary))
            }
            val portIn = EditText(c).apply {
                hint = getString(R.string.hint_port)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(s.port.toString())
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.text_secondary))
            }
            container.addView(nameIn)
            container.addView(hostIn)
            container.addView(portIn)
            AlertDialog.Builder(c)
                .setTitle(R.string.dialog_edit_title)
                .setView(container)
                .setPositiveButton(R.string.dialog_save) { _, _ ->
                    s.remark = nameIn.text.toString().trim().ifEmpty { null }
                    s.host = hostIn.text.toString().trim().ifEmpty { null }
                    s.port = portIn.text.toString().toIntOrNull() ?: 0
                    act?.let { ServerStore.save(it, it.servers) }
                    notifyDataChanged()
                    frag.refresh()
                    Toast.makeText(c, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        private fun editAwgName(awg: AWGConfig) {
            if (!frag.isAdded) return
            val c = frag.context ?: return
            val input = EditText(c).apply {
                hint = getString(R.string.hint_awg_name)
                setText(awg.name ?: "")
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.text_secondary))
            }
            AlertDialog.Builder(c)
                .setTitle(R.string.dialog_rename_awg)
                .setView(input)
                .setPositiveButton(R.string.dialog_save) { _, _ ->
                    awg.name = input.text.toString().trim().ifEmpty { null }
                    act?.let { AWGStore.save(it, it.awgConfigs) }
                    notifyDataChanged()
                    frag.refresh()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        private fun confirmDelete(target: Any) {
            if (!frag.isAdded) return
            val c = frag.context ?: return
            AlertDialog.Builder(c)
                .setTitle(R.string.dialog_delete)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    act?.let { a ->
                        when (target) {
                            is ServerItem -> {
                                a.servers.remove(target)
                                if (a.currentServer === target) {
                                    a.currentServer =
                                        if (a.servers.isEmpty()) null
                                        else a.servers[0]
                                    val newCur = a.currentServer
                                    if (newCur != null) a.selectServer(newCur)
                                    else a.prefs.edit().remove("current_link").apply()
                                }
                                ServerStore.save(a, a.servers)
                            }
                            is AWGConfig -> {
                                a.awgConfigs.remove(target)
                                if (a.currentAWG === target) {
                                    a.currentAWG =
                                        if (a.awgConfigs.isEmpty()) null
                                        else a.awgConfigs[0]
                                    val newAwg = a.currentAWG
                                    if (newAwg != null) a.selectAWG(newAwg)
                                    else a.prefs.edit().remove("current_awg").apply()
                                }
                                AWGStore.save(a, a.awgConfigs)
                                AWGEditorBus.init(a.awgConfigs, a.currentAWG, a.protocol)
                            }
                        }
                    }
                    notifyDataChanged()
                    frag.refresh()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }
    }
}
```

---

## 📄 `com/nurvpn/app/ui/settings/SettingsFragment.kt`

*603 qator*

```kotlin
package com.nurvpn.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.nurvpn.app.R
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.core.AWGConfig
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceSubscription
import com.nurvpn.app.storage.SubscriptionStore
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.storage.MetricsStore
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.config.BuiltinAwgConfigs
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.ui.split.SplitAppsActivity
import com.nurvpn.app.ui.awg.AWGEditorActivity
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.storage.SplitTunnelStore
import com.nurvpn.app.util.DNSLeakProtection
import com.nurvpn.app.util.IPv6Blocker
import com.nurvpn.app.util.LeakResult
import com.nurvpn.app.util.LeakTester
import com.nurvpn.app.util.ThemeHelper
import com.nurvpn.app.ui.MainActivity
import java.io.File
import java.io.FileWriter

class SettingsFragment : Fragment() {

    private var themeGroup: android.widget.RadioGroup? = null
    private var langGroup: android.widget.RadioGroup? = null
    private var protoGroup: android.widget.RadioGroup? = null
    private var splitModeGroup: android.widget.RadioGroup? = null
    private var dnsInput: EditText? = null
    private var killSwitch: MaterialSwitch? = null
    private var ipv6Switch: MaterialSwitch? = null
    private var dnsLeakSwitch: MaterialSwitch? = null
    private var leakResultBox: android.widget.LinearLayout? = null
    private var binding = false

    // ═══════════ OCHIQ MANBALAR ═══════════

    /** Ochiq manbani butunlay o'chirish dialogi. */
    private fun showDeleteOpenSourceDialog(open: OpenSourceSubscription) {
        val ctx = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.open_source_delete_title, open.name(requireContext())))
            .setMessage(R.string.open_source_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                val subId = OpenSourceStore.subId(open.id)
                // 1. Doimiy o'chirish (deleted ro'yxatiga)
                OpenSourceStore.markDeleted(ctx, open.id)
                // 2. SubscriptionStore dan o'chirish
                val list = SubscriptionStore.load(ctx)
                val found = list.firstOrNull { it.id == subId }
                if (found != null) {
                    SubscriptionStore.delete(ctx, found)
                }
                // 3. Serverlarni o'chirish + VPN stop
                val act = activity as? MainActivity
                if (act != null) {
                    act.subscriptions = SubscriptionStore.load(act)
                    act.servers.removeAll { it.subId == subId }
                    ServerStore.save(act, act.servers)

                    // VPN STOP agar joriy server shu obunadan bo'lsa
                    if (act.currentServer?.subId == subId) {
                        android.util.Log.i("NurVPN-DBG",
                            "delete: currentServer shu obunadan — VPN to'xtatilmoqda")
                        if (act.isRunning) act.stopVpn()
                        act.currentServer = null
                        act.prefs.edit().remove("current_link").apply()
                    }
                }
                Toast.makeText(ctx,
                    getString(R.string.open_source_deleted, open.name(requireContext())),
                    Toast.LENGTH_SHORT).show()
                // UI yangilash
                val v = view
                if (v != null) setupOpenSources(v)
                (activity as? MainActivity)?.refreshAllTabs()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupOpenSources(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.open_source_container) ?: return
        val inflater = LayoutInflater.from(requireContext())
        container.removeAllViews()

        val deleted = OpenSourceStore.getDeleted(requireContext())
        for (open in OpenSourceCatalog.ALL) {
            if (open.id in deleted) continue  // O'chirilgan — ko'rsatmaymiz
            val item = inflater.inflate(R.layout.item_open_source, container, false)
            item.findViewById<TextView>(R.id.os_flag).text = open.flag
            item.findViewById<TextView>(R.id.os_name).text = open.name(requireContext())
            item.findViewById<TextView>(R.id.os_desc).text = open.description(requireContext())

            val sw = item.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.os_switch)
            // ═══ FIX: State saqlashni o'chirish (duplicate ID muammosi) ═══
            sw.isSaveEnabled = false
            sw.isSaveFromParentEnabled = false
            sw.isChecked = OpenSourceStore.isEnabled(requireContext(), open.id)
            sw.setOnCheckedChangeListener { _, checked ->
                val ctx = requireContext()
                val subId = OpenSourceStore.subId(open.id)
                val act = activity as? MainActivity

                OpenSourceStore.setEnabled(ctx, open.id, checked)

                // ═══ AWG CONFIG (WARP) ═══
                if (open.isAwg) {
                    val awgRaw = BuiltinAwgConfigs.byId(open.awgId!!)
                    if (awgRaw != null) {
                        val awgList = AWGStore.load(ctx)
                        if (checked) {
                            if (awgList.none { it.rawConf == awgRaw }) {
                                val cfg = AWGConfig(awgRaw)
                                cfg.name = open.name(ctx)
                                // ═══ AWGParser orqali endpoint/address ni to'ldiramiz ═══
                                val parsed = AWGParser.parse(awgRaw)
                                if (parsed.ok) {
                                    cfg.endpoint = parsed.endpoint
                                    cfg.address = parsed.address
                                    android.util.Log.i("NurVPN-DBG",
                                        "AWG parsed: endpoint=${parsed.endpoint}")
                                } else {
                                    android.util.Log.w("NurVPN-DBG",
                                        "AWG parse xato: ${parsed.error}")
                                }
                                awgList.add(cfg)
                                AWGStore.save(ctx, awgList)
                                android.util.Log.i("NurVPN-DBG",
                                    "AWG ON: ${open.id} qo'shildi")
                            }
                        } else {
                            awgList.removeAll { it.rawConf == awgRaw }
                            AWGStore.save(ctx, awgList)
                            android.util.Log.i("NurVPN-DBG",
                                "AWG OFF: ${open.id} o'chirildi")
                        }
                        if (act != null) {
                            act.awgConfigs = AWGStore.load(act)
                            // Agar joriy AWG shu config bo'lsa — VPN stop
                            if (!checked && act.currentAWG?.rawConf == awgRaw) {
                                if (act.isRunning) act.stopVpn()
                                act.currentAWG = null
                                act.prefs.edit().remove("current_awg").apply()
                            }
                        }
                    }
                    (activity as? MainActivity)?.refreshAllTabs()
                    return@setOnCheckedChangeListener
                }

                // ═══ SUBSCRIPTION ═══
                if (checked) {
                    val list = SubscriptionStore.load(ctx)
                    if (list.none { it.id == subId }) {
                        val sub = Subscription(subId, open.url ?: "", open.name(requireContext()))
                        list.add(sub)
                        SubscriptionStore.save(ctx, list)
                    }
                    if (act != null) {
                        act.subscriptions = SubscriptionStore.load(act)
                    }
                } else {
                    val list = SubscriptionStore.load(ctx)
                    val found = list.firstOrNull { it.id == subId }
                    if (found != null) {
                        SubscriptionStore.delete(ctx, found)
                    }
                    if (act != null) {
                        act.subscriptions = SubscriptionStore.load(act)
                        val before = act.servers.size
                        act.servers.removeAll { it.subId == subId }
                        ServerStore.save(act, act.servers)
                        android.util.Log.i("NurVPN-DBG",
                            "open OFF: ${open.id} — ${before - act.servers.size} server o'chirildi")

                        // ═══ VPN STOP: agar joriy server shu obunadan bo'lsa ═══
                        if (act.currentServer?.subId == subId) {
                            android.util.Log.i("NurVPN-DBG",
                                "open OFF: currentServer shu obunadan — VPN to'xtatilmoqda")
                            if (act.isRunning) act.stopVpn()
                            act.currentServer = null
                            act.prefs.edit().remove("current_link").apply()
                        }
                    }
                }
                (activity as? MainActivity)?.refreshAllTabs()
            }

            // Uzoq bosish — o'chirish (delete) dialogi
            item.setOnLongClickListener {
                showDeleteOpenSourceDialog(open)
                true
            }

            container.addView(item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        if (!isAdded) return v

        themeGroup = v.findViewById(R.id.theme_group)
        langGroup = v.findViewById(R.id.lang_group)
        protoGroup = v.findViewById(R.id.proto_group)
        splitModeGroup = v.findViewById(R.id.split_mode_group)
        dnsInput = v.findViewById(R.id.dns_input)
        killSwitch = v.findViewById(R.id.kill_switch)
        ipv6Switch = v.findViewById(R.id.ipv6_switch)
        dnsLeakSwitch = v.findViewById(R.id.dns_leak_switch)
        leakResultBox = v.findViewById(R.id.leak_result)

        val a = activity as? MainActivity ?: return v
        val ctx = requireContext()

        binding = true
        val tm = ThemeHelper.getThemeMode(ctx)
        val tid = when (tm) {
            "light" -> R.id.theme_light
            "dark" -> R.id.theme_dark
            else -> R.id.theme_system
        }
        themeGroup?.check(tid)

        val lt = ThemeHelper.getLanguage(ctx)
        val lid = when (lt) {
            "uz" -> R.id.lang_uz
            "ru" -> R.id.lang_ru
            "en" -> R.id.lang_en
            else -> R.id.lang_system
        }
        langGroup?.check(lid)

        dnsInput?.setText(a.prefs.getString("dns", "1.1.1.1"))

        val pid = if (MainActivity.PROTO_AWG == a.protocol)
            R.id.proto_awg else R.id.proto_xray
        protoGroup?.check(pid)

        killSwitch?.isChecked = a.prefs.getBoolean("kill_switch", false)
        ipv6Switch?.isChecked = IPv6Blocker.isBlocked(ctx)
        dnsLeakSwitch?.isChecked = DNSLeakProtection.isEnabled(ctx)

        val splitMode = SplitTunnelStore.getMode(ctx)
        val splitId = when (splitMode) {
            SplitTunnelStore.MODE_WHITELIST -> R.id.split_whitelist
            SplitTunnelStore.MODE_BLACKLIST -> R.id.split_blacklist
            else -> R.id.split_all
        }
        splitModeGroup?.check(splitId)

        val ver = v.findViewById<TextView>(R.id.about_version)
        if (ver != null) {
            try {
                val vn = ctx.packageManager
                    .getPackageInfo(ctx.packageName, 0).versionName
                ver.text = getString(R.string.settings_version) + " " + vn
            } catch (ignored: Throwable) {}
        }
        binding = false
        setupOpenSources(v)

        themeGroup?.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val mode = when (id) {
                R.id.theme_light -> "light"
                R.id.theme_dark -> "dark"
                else -> "system"
            }
            ThemeHelper.setThemeMode(requireContext(), mode)
        }

        langGroup?.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val tag = when (id) {
                R.id.lang_uz -> "uz"
                R.id.lang_ru -> "ru"
                R.id.lang_en -> "en"
                else -> ""
            }
            ThemeHelper.setLanguage(requireContext(), tag)
            val list = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                       else LocaleListCompat.forLanguageTags(tag)
            AppCompatDelegate.setApplicationLocales(list)
            activity?.recreate()
        }

        protoGroup?.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            a.protocol = if (id == R.id.proto_awg)
                MainActivity.PROTO_AWG else MainActivity.PROTO_XRAY
        }

        splitModeGroup?.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val mode = when (id) {
                R.id.split_whitelist -> SplitTunnelStore.MODE_WHITELIST
                R.id.split_blacklist -> SplitTunnelStore.MODE_BLACKLIST
                else -> SplitTunnelStore.MODE_ALL
            }
            SplitTunnelStore.setMode(requireContext(), mode)
        }

        dnsInput?.setOnFocusChangeListener { _, has ->
            if (!has) {
                val d = dnsInput?.text?.toString()?.trim().orEmpty()
                if (d.isNotEmpty()) a.prefs.edit().putString("dns", d).apply()
            }
        }
        // DNS dropdown — uzoq bosish orqali
        dnsInput?.setOnLongClickListener {
            showDnsDialog(a)
            true
        }
        // DNS dropdown — ⚙️ tugma orqali
        v.findViewById<android.widget.ImageButton>(R.id.dns_picker_btn)
            ?.setOnClickListener {
                showDnsDialog(a)
            }

        killSwitch?.setOnCheckedChangeListener { _, ch ->
            if (binding) return@setOnCheckedChangeListener
            a.prefs.edit().putBoolean("kill_switch", ch).apply()
        }

        ipv6Switch?.setOnCheckedChangeListener { _, ch ->
            if (binding) return@setOnCheckedChangeListener
            IPv6Blocker.setBlocked(requireContext(), ch)
        }

        dnsLeakSwitch?.setOnCheckedChangeListener { _, ch ->
            if (binding) return@setOnCheckedChangeListener
            DNSLeakProtection.setEnabled(requireContext(), ch)
        }

        v.findViewById<Button>(R.id.leak_test_btn)
            ?.setOnClickListener { runLeakTest() }
        v.findViewById<Button>(R.id.split_select_btn)
            ?.setOnClickListener {
                startActivity(Intent(requireContext(),
                    SplitAppsActivity::class.java))
            }
        v.findViewById<Button>(R.id.awg_editor_btn)
            ?.setOnClickListener {
                if (AWGEditorBus.configs.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.toast_awg_no_config,
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val i = Intent(requireContext(), AWGEditorActivity::class.java)
                i.putExtra(AWGEditorActivity.EXTRA_INDEX, 0)
                i.putExtra(AWGEditorActivity.EXTRA_RAW,
                    AWGEditorBus.configs[0].rawConf)
                startActivity(i)
            }

        v.findViewById<Button>(R.id.dedup_btn)?.setOnClickListener { runDeduplication() }
        v.findViewById<Button>(R.id.ai_reset)?.setOnClickListener {
            MetricsStore.clear(requireContext())
            Toast.makeText(requireContext(), R.string.toast_metrics_reset,
                Toast.LENGTH_SHORT).show()
        }
        v.findViewById<Button>(R.id.ai_export)?.setOnClickListener {
            exportMetrics()
        }
        return v
    }

    private fun showDnsDialog(a: MainActivity) {
        val dnsList = arrayOf(
            "1.1.1.1 (Cloudflare)",
            "1.0.0.1 (Cloudflare 2)",
            "8.8.8.8 (Google)",
            "8.8.4.4 (Google 2)",
            "9.9.9.9 (Quad9)",
            "77.88.8.8 (Yandex)",
            "223.5.5.5 (AliDNS)",
            getString(R.string.dns_manual)
        )
        val values = arrayOf(
            "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4",
            "9.9.9.9", "77.88.8.8",
            "223.5.5.5", "__custom__"
        )
        val current = a.prefs.getString("dns", "1.1.1.1") ?: "1.1.1.1"
        val idx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_dns))
            .setSingleChoiceItems(dnsList, idx) { dialog, which ->
                if (values[which] == "__custom__") {
                    dialog.dismiss()
                    val et = EditText(requireContext())
                    et.setText(current)
                    et.hint = "1.1.1.1"
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_dns)
                        .setView(et)
                        .setPositiveButton(R.string.dialog_yes) { _, _ ->
                            val d = et.text.toString().trim()
                            if (d.isNotEmpty()) {
                                a.prefs.edit().putString("dns", d).apply()
                                dnsInput?.setText(d)
                            }
                        }
                        .setNegativeButton(R.string.dialog_no, null)
                        .show()
                } else {
                    a.prefs.edit().putString("dns", values[which]).apply()
                    dnsInput?.setText(values[which])
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun runLeakTest() {
        if (!isAdded) return
        leakResultBox?.visibility = View.VISIBLE
        val rootView = view ?: return
        rootView.findViewById<TextView>(R.id.leak_ipv4)?.text = "IPv4: ..."
        rootView.findViewById<TextView>(R.id.leak_ipv6)?.text = "IPv6: ..."
        rootView.findViewById<TextView>(R.id.leak_dns)?.text = "DNS: ..."

        // LeakTester ga VPN va IPv6 holatini uzatamiz
        try {
            LeakTester.lastVpnActive = TunnelState.isConnected
            LeakTester.lastIpv6Blocked = IPv6Blocker.isBlocked(requireContext())
            android.util.Log.i("NurVPN-LEAK",
                "test: vpnActive=${LeakTester.lastVpnActive}, " +
                "ipv6Blocked=${LeakTester.lastIpv6Blocked}")
        } catch (_: Throwable) {}

        LeakTester.test { r ->
            if (!isAdded) return@test
            activity?.runOnUiThread {
                val v2 = view ?: return@runOnUiThread
                v2.findViewById<TextView>(R.id.leak_ipv4)?.text =
                    "IPv4: ${r.ipv4}" + if (r.ipv4Ok) " ✅" else " ⚠️"
                val ipv6Icon = when {
                    r.ipv6Ok -> " ✅"
                    !LeakTester.lastVpnActive -> " ⚠️"   // VPN off — normal holat
                    else -> " ❌"                          // VPN on + IPv6 bor — LEAK
                }
                val ipv6Text = when (r.ipv6) {
                    "blocked" -> getString(R.string.leak_ipv6_blocked)
                    "not_found", "topilmadi" -> getString(R.string.leak_ipv6_not_found)
                    else -> r.ipv6
                }
                v2.findViewById<TextView>(R.id.leak_ipv6)?.text =
                    "IPv6: $ipv6Text$ipv6Icon"
                // DNS — status bo'yicha tarjima
                val dnsText = when (r.dnsStatus) {
                    "vpn_off" -> getString(R.string.leak_dns_vpn_off)
                    "timeout" -> getString(R.string.leak_dns_timeout)
                    "error" -> getString(R.string.leak_dns_error)
                    "empty" -> getString(R.string.leak_dns_empty)
                    "leaked" -> "${r.dns} ❌"
                    "ok" -> "${r.dns} ✅"
                    else -> r.dns + if (r.dnsOk) " ✅" else " ⚠️"
                }
                v2.findViewById<TextView>(R.id.leak_dns)?.text =
                    getString(R.string.leak_dns_label) + ": " + dnsText
            }
        }
    }

    /** Dublikatlarni topish va o'chirish. */
    private fun runDeduplication() {
        if (!isAdded) return
        val a = activity as? MainActivity ?: return
        val c = requireContext()

        // 1) ServerItem lar — link bo'yicha
        val seenLinks = HashMap<String, ServerItem>()
        val exactDupServers = mutableListOf<ServerItem>()
        for (si in a.servers) {
            val prev = seenLinks[si.link]
            if (prev != null) {
                // Favorite ni saqlab qolamiz
                if (!prev.favorite && si.favorite) {
                    prev.favorite = true
                }
                exactDupServers.add(si)
            } else {
                seenLinks[si.link] = si
            }
        }

        // 2) AWG — rawConf bo'yicha
        val seenConf = HashMap<String, AWGConfig>()
        val exactDupAwg = mutableListOf<AWGConfig>()
        for (cfg in a.awgConfigs) {
            val key = cfg.rawConf ?: ""
            val prev = seenConf[key]
            if (prev != null) {
                if (!prev.favorite && cfg.favorite) prev.favorite = true
                exactDupAwg.add(cfg)
            } else {
                seenConf[key] = cfg
            }
        }

        // 3) host:port bo'yicha shubhali (lekin link boshqacha)
        val seenHostPort = HashMap<String, ServerItem>()
        val suspicious = mutableListOf<ServerItem>()
        for (si in a.servers) {
            if (exactDupServers.contains(si)) continue
            val key = "${si.host}:${si.port}"
            if (key == "null:0") continue
            if (seenHostPort.containsKey(key)) {
                suspicious.add(si)
            } else {
                seenHostPort[key] = si
            }
        }

        val total = exactDupServers.size + exactDupAwg.size
        if (total == 0 && suspicious.isEmpty()) {
            Toast.makeText(c, R.string.dedup_none, Toast.LENGTH_SHORT).show()
            return
        }

        // Xabar tuzish
        var msg = c.getString(R.string.dedup_confirm_msg_fmt,
            exactDupServers.size, exactDupAwg.size)
        if (suspicious.isNotEmpty()) {
            msg += c.getString(R.string.dedup_suspicious_fmt, suspicious.size)
        }

        AlertDialog.Builder(c)
            .setTitle(R.string.dedup_confirm_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var n = 0
                if (exactDupServers.isNotEmpty()) {
                    a.servers.removeAll(exactDupServers)
                    n += exactDupServers.size
                }
                if (exactDupAwg.isNotEmpty()) {
                    a.awgConfigs.removeAll(exactDupAwg)
                    n += exactDupAwg.size
                }
                if (suspicious.isNotEmpty()) {
                    a.servers.removeAll(suspicious)
                    n += suspicious.size
                }
                ServerStore.save(a, a.servers)
                AWGStore.save(a, a.awgConfigs)
                Toast.makeText(c,
                    c.getString(R.string.dedup_done_fmt, n),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun exportMetrics() {
        try {
            val ctx = requireContext()
            val dir = ctx.getExternalFilesDir(null) ?: return
            val out = File(dir, "nurvpn_metrics_" +
                System.currentTimeMillis() + ".json")
            FileWriter(out).use { it.write(MetricsStore.exportJson(ctx)) }
            Toast.makeText(ctx,
                getString(R.string.toast_exported, out.absolutePath),
                Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), getString(R.string.toast_export_error, t.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }
}
```

---

## 📄 `com/nurvpn/app/ui/split/SplitAppsActivity.kt`

*144 qator*

```kotlin
package com.nurvpn.app.ui.split

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nurvpn.app.R
import com.nurvpn.app.core.AppInfo
import com.nurvpn.app.storage.AppListLoader
import com.nurvpn.app.storage.SplitTunnelStore
import java.util.Locale

class SplitAppsActivity : AppCompatActivity() {

    private var rv: RecyclerView? = null
    private var search: EditText? = null
    private var count: TextView? = null
    private var ad: Adapter? = null
    private var all: List<AppInfo> = ArrayList()
    private val selected = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_split_apps)

        rv = findViewById(R.id.app_list)
        search = findViewById(R.id.search)
        count = findViewById(R.id.count)

        selected.clear()
        selected.addAll(SplitTunnelStore.getApps(this))

        findViewById<ImageButton>(R.id.back_btn)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.save_btn)?.setOnClickListener {
            SplitTunnelStore.setApps(this, selected)
            Toast.makeText(this, R.string.split_save, Toast.LENGTH_SHORT).show()
            finish()
        }

        ad = Adapter()
        rv?.layoutManager = LinearLayoutManager(this)
        rv?.adapter = ad

        search?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                ad?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        AppListLoader.loadAsync(this) { list ->
            all = list ?: ArrayList()
            for (ai in all) ai.selected = selected.contains(ai.packageName)
            runOnUiThread {
                ad?.rebuild()
                updateCount()
            }
        }
    }

    private fun updateCount() {
        count?.text = getString(R.string.split_apps_count, selected.size)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val shown = ArrayList<AppInfo>()

        fun rebuild() {
            shown.clear()
            shown.addAll(all)
            notifyDataSetChanged()
            updateCount()
        }

        fun filter(q: String) {
            shown.clear()
            val query = q.trim()
            if (query.isEmpty()) shown.addAll(all)
            else {
                val s = query.lowercase(Locale.US)
                for (ai in all) {
                    if (ai.label.lowercase(Locale.US).contains(s) ||
                        ai.packageName.lowercase(Locale.US).contains(s)) {
                        shown.add(ai)
                    }
                }
            }
            notifyDataSetChanged()
        }

        @NonNull
        override fun onCreateViewHolder(@NonNull p: ViewGroup, v: Int): VH {
            val item = LayoutInflater.from(p.context)
                .inflate(R.layout.item_app, p, false)
            return VH(item)
        }

        override fun onBindViewHolder(@NonNull h: VH, pos: Int) {
            val ai = shown[pos]
            h.label.text = ai.label
            h.pkg.text = ai.packageName
            if (ai.icon != null) h.icon.setImageDrawable(ai.icon)
            else h.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            h.check.isChecked = selected.contains(ai.packageName)

            val toggle = View.OnClickListener {
                if (selected.contains(ai.packageName)) {
                    selected.remove(ai.packageName)
                    h.check.isChecked = false
                } else {
                    selected.add(ai.packageName)
                    h.check.isChecked = true
                }
                updateCount()
            }
            h.itemView.setOnClickListener(toggle)
            h.check.setOnClickListener(toggle)
        }

        override fun getItemCount(): Int = shown.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.app_icon)
            val label: TextView = v.findViewById(R.id.app_label)
            val pkg: TextView = v.findViewById(R.id.app_pkg)
            val check: CheckBox = v.findViewById(R.id.app_check)
        }
    }
}
```

---

## 📄 `com/nurvpn/app/ui/widget/AICardView.kt`

*94 qator*

```kotlin
package com.nurvpn.app.ui.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.nurvpn.app.R
import com.nurvpn.app.ai.AIInsights

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
```

---

## 📄 `com/nurvpn/app/ui/widget/SpeedWaveView.kt`

*535 qator*

```kotlin
package com.nurvpn.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * NurVPN — SpeedWaveView v17 "Cosmic Aurora" FINAL
 *
 * v16 → v17:
 *  - Markaz kichraytirildi (0.72 → 0.55, 0.42 → 0.30) — chuqurlik
 *  - Shield aura qo'shildi — tugma ham pulsatsiya qiladi
 *  - Zarrachalar VPN off da yorqinroq (0.30 → 0.42)
 *  - Butun halqa tizimi sekin aylanadi (dynamizm)
 *  - Yulduzlar zichligi chetlarga ko'proq
 *  - Bir xil markazda kichik yorqin "yadro"
 */
class SpeedWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SpeedWave"
        private const val RING_COUNT = 3
        private const val PARTICLES_PER_RING = 8
        private const val TRAIL_LENGTH = 12
        private const val STAR_COUNT = 70

        private const val MAX_SPEED_DEFAULT = 100L * 1024L
        private const val AUTO_DISCONNECT_FRAMES = 600  // 10 sekund @ 60fps
        private const val SPEED_THRESHOLD = 200L

        private const val COLOR_BG_CENTER = 0xFF14281A.toInt()
        private const val COLOR_BG_EDGE   = 0xFF04090A.toInt()

        private const val COLOR_LIME        = 0xFFC4F82A.toInt()
        private const val COLOR_LIME_BRIGHT = 0xFFE8FF80.toInt()
        private const val COLOR_TEAL        = 0xFF5FEFE0.toInt()
        private const val COLOR_TEAL_BRIGHT = 0xFF80FFFF.toInt()
        private const val COLOR_WHITE       = 0xFFFFFFFF.toInt()
    }

    private val density = resources.displayMetrics.density
    private val TWO_PI: Float = (PI * 2.0).toFloat()
    private fun dp(v: Float): Float = v * density

    // ═══ State ═══
    private var connected = false
    private var currentSpeed = 0f
    private var targetSpeed = 0f
    private var maxSpeed = MAX_SPEED_DEFAULT.toFloat()
    private var animationTime = 0f
    private var lastFrameNanos = 0L
    private var zeroSpeedFrames = 0
    private var running = false
    private var permanentlyStopped = false

    // ═══ Geometry ═══
    private var cx = 0f; private var cy = 0f; private var radius = 0f
    private val clipPath = Path()

    // ═══ Fon ═══
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var bgGradient: RadialGradient? = null

    // ═══ Markaz glow (3 qatlam) ═══
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var outerGlowGradient: RadialGradient? = null

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var innerGlowGradient: RadialGradient? = null

    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_WHITE
    }

    // ═══ Shield aura ═══
    private val shieldAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME_BRIGHT
        strokeCap = Paint.Cap.ROUND
    }

    // ═══ Halqalar ═══
    private val ringLimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME
    }
    private val ringTealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_TEAL
    }

    // ═══ Sweep ═══
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var sweepGradient: SweepGradient? = null

    // ═══ Zarrachalar ═══
    private val particleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME
    }
    private val particleMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME_BRIGHT
    }
    private val particleTealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_TEAL_BRIGHT
    }
    private val particleCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_WHITE
    }

    // ═══ Yulduzlar ═══
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_LIME_BRIGHT
    }
    private val starTealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEAL_BRIGHT
    }

    // ═══ Inner pulse ═══
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = COLOR_LIME_BRIGHT
        strokeCap = Paint.Cap.ROUND
    }

    // ═══ Data ═══
    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)
    private val starIsTeal = BooleanArray(STAR_COUNT)

    private val ringPhase = FloatArray(RING_COUNT)
    private val ringSpeed = floatArrayOf(0.30f, -0.20f, 0.14f)
    private val ringRadiusFactor = floatArrayOf(0.42f, 0.62f, 0.82f)

    private var sweepAngle = 0f
    private var pulsePhase = 0f
    private var ringRotation = 0f     // v17: butun tizim aylanishi

    // ═══ Choreographer ═══
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private var frameCount = 0L
    private var lastLogTimeMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || permanentlyStopped) return
            val dt = if (lastFrameNanos == 0L) 1f / 60f
                     else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f)
                         .coerceIn(0.008f, 0.05f)
            lastFrameNanos = frameTimeNanos
            updateAnimation(dt)
            invalidate()
            frameCount++
            val now = SystemClock.uptimeMillis()
            if (now - lastLogTimeMs >= 1000L) {
                lastLogTimeMs = now
                val ratio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
                Log.d(TAG, "frame=$frameCount speed=${"%.0f".format(currentSpeed)} " +
                    "ratio=${"%.2f".format(ratio)} connected=$connected")
            }
            if (running && !permanentlyStopped) choreographer.postFrameCallback(this)
        }
    }

    // ═══ Lifecycle ═══
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttached connected=$connected")
        permanentlyStopped = false
        startAnimation()
    }
    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
    private fun startAnimation() {
        if (running || permanentlyStopped) return
        running = true; lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }
    private fun stopAnimation() {
        if (!running) return
        running = false; lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
    }
    fun stop() {
        permanentlyStopped = true
        stopAnimation()
        currentSpeed = 0f; targetSpeed = 0f; animationTime = 0f
        invalidate()
    }

    // ═══ API ═══
    fun setConnected(connected: Boolean) {
        if (this.connected != connected) Log.d(TAG, "setConnected($connected)")
        this.connected = connected
        if (!connected) { targetSpeed = 0f; zeroSpeedFrames = 0 }
        if (isAttachedToWindow && !permanentlyStopped) startAnimation()
        invalidate()
    }
    fun setSpeed(downloadBytesPerSec: Long, uploadBytesPerSec: Long) {
        val total = (downloadBytesPerSec + uploadBytesPerSec).coerceAtLeast(0L)
        targetSpeed = total.toFloat()
        if (total > SPEED_THRESHOLD) {
            zeroSpeedFrames = 0
            if (!connected) {
                connected = true
                Log.d(TAG, "setSpeed auto-CONNECT total=$total")
            }
        }
        if (isAttachedToWindow && !permanentlyStopped) startAnimation()
    }
    fun setMaxSpeed(bytesPerSec: Long) {
        maxSpeed = bytesPerSec.coerceAtLeast(1L).toFloat()
    }

    // ═══ Layout ═══
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f; cy = h / 2f
        radius = min(w, h) / 2f - dp(4f)
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        createGradients()
        initStars()
        Log.d(TAG, "onSizeChanged radius=$radius")
    }

    private fun createGradients() {
        bgGradient = RadialGradient(
            cx, cy, radius,
            intArrayOf(COLOR_BG_CENTER, COLOR_BG_EDGE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = bgGradient

        // v17: Markaz kichraytirildi + alpha pasaytirildi
        // Tashqi yumshoq lime (0.72 → 0.55)
        outerGlowGradient = RadialGradient(
            cx, cy, radius * 0.55f,
            intArrayOf(
                Color.argb(95, 196, 248, 42),
                Color.argb(50, 196, 248, 42),
                Color.argb(18, 196, 248, 42),
                Color.argb(0, 196, 248, 42)
            ),
            floatArrayOf(0f, 0.42f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        outerGlowPaint.shader = outerGlowGradient

        // Ichki yorqin (0.42 → 0.30)
        innerGlowGradient = RadialGradient(
            cx, cy, radius * 0.30f,
            intArrayOf(
                Color.argb(180, 232, 255, 128),
                Color.argb(115, 196, 248, 42),
                Color.argb(30, 196, 248, 42),
                Color.argb(0, 196, 248, 42)
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        innerGlowPaint.shader = innerGlowGradient

        // Sweep gradient
        sweepGradient = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(30, 232, 255, 128),
                Color.argb(200, 232, 255, 128),
                Color.argb(255, 255, 255, 255),
                Color.argb(200, 196, 248, 42),
                Color.argb(30, 196, 248, 42),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.60f, 0.72f, 0.78f, 0.85f, 0.95f, 1f)
        )
        sweepPaint.shader = sweepGradient
    }

    private fun initStars() {
        var seed = 0xABCD1234
        fun nextF(): Float {
            seed = seed * 1_103_515_245 + 12_345
            return ((seed ushr 8) and 0xFFFFFF) / 0xFFFFFF.toFloat()
        }
        for (i in 0 until STAR_COUNT) {
            val angle = nextF() * TWO_PI
            // v17: chetlarga ko'proq (sqrt distribution)
            val rNorm = kotlin.math.sqrt(nextF())  // 0..1, ko'proq chetlarga
            val r = dp(90f) + rNorm * (radius - dp(95f))
            starX[i] = cx + cos(angle) * r
            starY[i] = cy + sin(angle) * r
            starSize[i] = dp(0.7f) + nextF() * dp(1.6f)
            starPhase[i] = nextF() * TWO_PI
            starIsTeal[i] = nextF() > 0.65f
        }
    }

    // ═══ Animation ═══
    private fun updateAnimation(dt: Float) {
        currentSpeed += (targetSpeed - currentSpeed) * (1f - exp(-dt * 5.5f))

        if (connected && currentSpeed < SPEED_THRESHOLD) {
            zeroSpeedFrames++
            if (zeroSpeedFrames > AUTO_DISCONNECT_FRAMES) {
                connected = false
                zeroSpeedFrames = 0
                Log.d(TAG, "auto-DISCONNECT (10s trafik yo'q)")
            }
        } else if (currentSpeed >= SPEED_THRESHOLD) {
            zeroSpeedFrames = 0
        }

        val speedRatio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        val motion = if (connected) 0.40f + speedRatio * 1.80f else 0.12f
        animationTime += dt * motion
        if (animationTime > 100_000f) animationTime -= 100_000f

        for (i in 0 until RING_COUNT) {
            val ringMult = if (connected) (0.6f + speedRatio * 1.6f) else 0.30f
            ringPhase[i] += dt * ringSpeed[i] * ringMult
        }

        // v17: butun tizim sekin aylanadi (0.08 rad/s)
        val rotMult = if (connected) (0.7f + speedRatio * 0.5f) else 0.3f
        ringRotation = (ringRotation + dt * 0.08f * rotMult) % TWO_PI

        val sweepMult = if (connected) (0.55f + speedRatio * 0.65f) else 0.20f
        sweepAngle = (sweepAngle + dt * 1.4f * sweepMult) % TWO_PI

        pulsePhase += dt * (if (connected) 0.7f + speedRatio * 0.5f else 0.35f)
        if (pulsePhase > 1f) pulsePhase -= 1f
    }

    // ═══ Drawing ═══
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius < 10f) return

        val sc = canvas.save()
        canvas.clipPath(clipPath)

        // 1. Fon
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 2. Markaz glow (3 qatlam)
        val pulse = 1f + 0.08f * sin(animationTime * 1.8f)
        canvas.save()
        canvas.scale(pulse, pulse, cx, cy)
        canvas.drawCircle(cx, cy, radius * 0.55f, outerGlowPaint)
        canvas.drawCircle(cx, cy, radius * 0.30f, innerGlowPaint)
        canvas.restore()

        // 3. Yadro — kichik yorqin nuqta
        val speedRatio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        coreGlowPaint.alpha = if (connected) (180 + speedRatio * 60).toInt() else 90
        canvas.drawCircle(cx, cy, dp(3f + speedRatio * 2f), coreGlowPaint)

        // 4. Yulduzlar
        drawStars(canvas)

        // 5. Shield aura (pulsatsiya qiluvchi halqa)
        drawShieldAura(canvas, speedRatio)

        // 6. Inner pulse
        drawInnerPulse(canvas, speedRatio)

        // 7. Butun tizim aylanadi
        canvas.save()
        canvas.rotate(ringRotation * 180f / PI.toFloat(), cx, cy)

        // 8. Orbit tizim
        drawOrbitSystem(canvas, speedRatio)

        canvas.restore()

        // 9. Sweep (aylanadi, lekin tizim rotatsiyasidan tashqarida)
        drawSweep(canvas, speedRatio)

        canvas.restoreToCount(sc)
    }

    private fun drawStars(canvas: Canvas) {
        val ratio = (currentSpeed / maxSpeed).coerceIn(0f, 1f)
        val brightness = if (connected) 0.60f + ratio * 0.40f else 0.42f  // v17: 0.30 → 0.42
        for (i in 0 until STAR_COUNT) {
            val twinkle = 0.5f + 0.5f * sin(animationTime * 2.2f + starPhase[i])
            val alpha = ((80f + 175f * twinkle) * brightness).toInt().coerceIn(0, 255)
            val p = if (starIsTeal[i]) starTealPaint else starPaint
            p.alpha = alpha
            canvas.drawCircle(starX[i], starY[i], starSize[i], p)
        }
    }

    /**
     * v17: Shield aura — tugma atrofida pulsatsiya qiluvchi halqa.
     * Radius ~ 0.38 — tugma chegarasida.
     */
    private fun drawShieldAura(canvas: Canvas, speedRatio: Float) {
        val pulse = 0.5f + 0.5f * sin(animationTime * 2.8f)
        val auraR = radius * (0.36f + 0.04f * pulse)
        val intensity = if (connected) 0.75f + speedRatio * 0.25f else 0.30f
        val alpha = ((120f + 100f * pulse) * intensity).toInt().coerceIn(0, 255)

        shieldAuraPaint.strokeWidth = dp(1.5f + pulse * 0.8f)
        shieldAuraPaint.alpha = alpha
        canvas.drawCircle(cx, cy, auraR, shieldAuraPaint)
    }

    private fun drawInnerPulse(canvas: Canvas, speedRatio: Float) {
        val maxR = radius * 0.55f
        val r = dp(60f) + pulsePhase * (maxR - dp(60f))
        val alpha = ((1f - pulsePhase) * 130f *
            (if (connected) 0.75f + speedRatio * 0.25f else 0.30f)).toInt().coerceIn(0, 255)
        pulsePaint.strokeWidth = dp(1.6f)
        pulsePaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, pulsePaint)
    }

    private fun drawOrbitSystem(canvas: Canvas, speedRatio: Float) {
        val intensity = if (connected) 0.78f + speedRatio * 0.22f else 0.38f
        val speedBoost = speedRatio * 0.4f

        for (ring in 0 until RING_COUNT) {
            val ringR = radius * ringRadiusFactor[ring]
            val useTeal = ring == 1
            val paint = if (useTeal) ringTealPaint else ringLimePaint
            paint.strokeWidth = dp(if (useTeal) 2.8f else 2.2f)
            val baseAlpha = if (useTeal) 80f else 70f
            paint.alpha = ((baseAlpha + speedBoost * 100f) * intensity)
                .toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringR, paint)

            for (p in 0 until PARTICLES_PER_RING) {
                val baseAngle = (p.toFloat() / PARTICLES_PER_RING) * TWO_PI
                val angle = baseAngle + ringPhase[ring]
                val wobble = sin(animationTime * (1.2f + ring * 0.3f) + baseAngle) *
                    dp(2f + speedRatio * 4f)
                val effR = ringR + wobble
                val px = cx + cos(angle) * effR
                val py = cy + sin(angle) * effR
                val pSize = dp(1.8f + ring * 0.4f) * (0.9f + speedRatio * 0.7f)

                val isTeal = (p + ring) % 3 == 1
                val midPaint = if (isTeal) particleTealPaint else particleMidPaint

                // Kometa dumi
                for (t in 1..TRAIL_LENGTH) {
                    val trailAngle = angle - t * 0.038f * (1f + speedRatio * 1.8f)
                    val tx = cx + cos(trailAngle) * effR
                    val ty = cy + sin(trailAngle) * effR
                    val fade = 1f - t.toFloat() / TRAIL_LENGTH
                    val trailAlpha = (200f * fade * fade * intensity).toInt().coerceIn(0, 255)
                    particleHaloPaint.alpha = (trailAlpha * 0.35f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(tx, ty, pSize * 0.9f, particleHaloPaint)
                }

                // 4 qatlamli glow
                particleHaloPaint.alpha = (90f * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, pSize * 3.5f, particleHaloPaint)
                particleHaloPaint.alpha = (160f * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, pSize * 2.0f, particleHaloPaint)
                midPaint.alpha = (230f * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, pSize * 1.25f, midPaint)
                particleCorePaint.alpha = 255
                canvas.drawCircle(px, py, pSize * 0.65f, particleCorePaint)
            }
        }
    }

    private fun drawSweep(canvas: Canvas, ratio: Float) {
        val outerR = radius * 0.90f
        canvas.save()
        canvas.rotate(sweepAngle * 180f / PI.toFloat(), cx, cy)
        sweepPaint.strokeWidth = dp(3.5f + ratio * 2f)
        sweepPaint.alpha = (140 + 115 * ratio).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outerR, sweepPaint)
        canvas.restore()
    }
}
```

---

## 📄 `com/nurvpn/app/util/AWGEditor.kt`

*122 qator*

```kotlin
package com.nurvpn.app.util

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
        // FIX: H1-H4 qo'shildi — AmneziaWG magic header'lari
        var h1: Int = 1
        var h2: Int = 2
        var h3: Int = 3
        var h4: Int = 4
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
                "persistentkeepalive" -> d.keepalive = v.toIntOrNull() ?: 25
                "dns" -> d.dns = v
                // FIX: H1-H4 parsing
                "h1" -> d.h1 = v.toIntOrNull() ?: 1
                "h2" -> d.h2 = v.toIntOrNull() ?: 2
                "h3" -> d.h3 = v.toIntOrNull() ?: 3
                "h4" -> d.h4 = v.toIntOrNull() ?: 4
            }
        }
        return d
    }

    fun setValue(raw: String, key: String, value: String): String {
        val sb = StringBuilder()
        var replaced = false
        for (line in raw.lines()) {
            val t = line.trim()
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
        // FIX: H1-H4 ham tahrirlanadi
        out = setValue(out, "H1", d.h1.toString())
        out = setValue(out, "H2", d.h2.toString())
        out = setValue(out, "H3", d.h3.toString())
        out = setValue(out, "H4", d.h4.toString())
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
        // FIX: H1-H4 Beeline uchun
        out = setValue(out, "H1", "4")
        out = setValue(out, "H2", "5")
        out = setValue(out, "H3", "6")
        out = setValue(out, "H4", "7")
        return applyPort443(out)
    }

    fun applyMtsPreset(raw: String): String {
        var out = setValue(raw, "Jc", "8")
        out = setValue(out, "Jmin", "20")
        out = setValue(out, "Jmax", "50")
        out = setValue(out, "MTU", "1280")
        // FIX: H1-H4 MTS uchun
        out = setValue(out, "H1", "1")
        out = setValue(out, "H2", "2")
        out = setValue(out, "H3", "3")
        out = setValue(out, "H4", "4")
        return applyPort443(out)
    }
}
```

---

## 📄 `com/nurvpn/app/util/ClashApiConfig.kt`

*16 qator*

```kotlin
package com.nurvpn.app.util

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
```

---

## 📄 `com/nurvpn/app/util/CountryLookup.kt`

*64 qator*

```kotlin
package com.nurvpn.app.util

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
```

---

## 📄 `com/nurvpn/app/util/LeakTester.kt`

*239 qator*

```kotlin
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

    /**
     * DNS test statusi (tarjima uchun):
     * - "ok"         — muvaffaqiyatli
     * - "vpn_off"    — VPN o'chiq
     * - "timeout"    — vaqt tugadi
     * - "error"      — xato
     * - "empty"      — javob bo'sh
     * - "leaked"     — leak aniqlandi
     */
    var dnsStatus: String = ""
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


    /** HTTP GET (timeout va UA bilan). */
    private fun httpGet(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText()
            }
            conn.disconnect()
            body
        } catch (t: Throwable) {
            Log.e(TAG, "httpGet xato ($urlStr): ${t.message}")
            null
        }
    }

    /**
     * HAQIQIY DNS LEAK TESTI (bash.ws orqali).
     * - Timeout 5s (connect va read)
     * - User-Agent (server bloklamasin)
     * - VPN holati tekshiruvi
     */

    private fun realDnsLeakTest(): Triple<String, Boolean, String> {
        // VPN o'chiq bo'lsa — test ma'nosiz
        if (!lastVpnActive) {
            return Triple("", false, "vpn_off")
        }

        return try {
            // 1. Token olish
            val token = httpGet("https://bash.ws/id")?.trim()
                ?: return Triple("", false, "error")
            if (token.isEmpty()) {
                Log.w(TAG, "bash.ws: token bo'sh")
                return Triple("", false, "error")
            }
            Log.i(TAG, "bash.ws token: $token")

            // 2. 5 ta DNS so'rov
            for (i in 1..5) {
                try {
                    InetAddress.getByName("$i.$token.bash.ws")
                } catch (_: Throwable) {}
            }

            // 3. Tarqalish uchun kutish
            Thread.sleep(3000)

            // 4. Natija olish
            val jsonText = httpGet("https://bash.ws/dnsleak/test/$token?json")
                ?: return Triple("", false, "timeout")
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

            if (resolvers.isEmpty()) return Triple("", false, "empty")

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
            Triple(summary, ok, if (ok) "ok" else "leaked")
        } catch (t: Throwable) {
            Log.e(TAG, "realDnsLeakTest xato: ${t.message}", t)
            Triple("", false, "error")
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
                val (dnsSummary, dnsOk, dnsStatus) = realDnsLeakTest()
                r.dns = dnsSummary
                r.dnsOk = dnsOk
                r.dnsStatus = dnsStatus
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
```

---

## 📄 `com/nurvpn/app/util/PingTester.kt`

*142 qator*

```kotlin
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
```

---

## 📄 `com/nurvpn/app/util/QrGenerator.kt`

*39 qator*

```kotlin
package com.nurvpn.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Matn → QR bitmap (zxing yordamida). */
object QrGenerator {

    /**
     * @param text QR ichiga yoziladigan matn (link, config, URL)
     * @param sizePx kvadrat bitmap o'lchami (piksel)
     * @return Bitmap yoki null (xato bo'lsa)
     */
    fun generate(text: String, sizePx: Int = 768): Bitmap? {
        if (text.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (t: Throwable) {
            null
        }
    }
}
```

---

## 📄 `com/nurvpn/app/util/ThemeHelper.kt`

*36 qator*

```kotlin
package com.nurvpn.app.util

import android.content.Context

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
```

---


**Jami qatorlar:** 10802
