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

    // ═══════ NETWORK RESTART (WiFi ↔ Cellular) ═══════

    @Volatile private var pendingNetworkRestart = false

    // ═══ RESTART LOOP OLDINI OLISH (KUCHLI) ═══
    @Volatile private var isRestarting = false           // restart ketmoqda
    @Volatile private var lastNetworkHandle: Long = 0L   // oxirgi tarmoq
    @Volatile private var lastRestartTime: Long = 0L     // oxirgi restart vaqti
    @Volatile private var restartScheduledTime: Long = 0L // rejalashtirilgan vaqt

    private val MIN_RESTART_INTERVAL_MS = 30_000L  // 30 sekund
    private val NETWORK_STABILIZE_MS = 8_000L      // 8 sekund kutish

    private val networkRestartHandler = Handler(Looper.getMainLooper())
    private val networkRestartRunnable = Runnable { doNetworkRestart() }

    /**
     * Network switch restart logikasi — alohida funksiya.
     * (Runnable ichida o'ziga murojaat qilish Kotlin'da xato beradi)
     */
    private fun doNetworkRestart() {
        if (!pendingNetworkRestart) return
        if (isRestarting) {
            Log.i(TAG, "⏭️  doNetworkRestart — allaqachon ketmoqda")
            return
        }

        pendingNetworkRestart = false
        isRestarting = true
        try {
            Log.i(TAG, "🔄 doNetworkRestart boshlandi")

            // 1. underlying network yangilash
            updateUnderlyingNetworksFromSystem()

            // 2. ★ Validated network kutish (2s dan keyin qayta)
            val active = pickPhysicalNetwork()
            if (active == null) {
                Log.w(TAG, "⚠️ active network null — 2s dan keyin qayta")
                isRestarting = false
                pendingNetworkRestart = true
                networkRestartHandler.postDelayed(networkRestartRunnable, 2000L)
                return
            }
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(active)
            if (caps == null || !caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Log.w(TAG, "⚠️ network hali validated emas — 2s dan keyin qayta")
                isRestarting = false
                pendingNetworkRestart = true
                networkRestartHandler.postDelayed(networkRestartRunnable, 2000L)
                return
            }

            // 3. ★ TO'LIQ RESTART
            val saved = lastConnectIntent
            if (saved != null && server != null) {
                Log.i(TAG, "🔄 VPN TO'LIQ RESTART")

                // a) UI ga xabar — "ulanmoqda"
                broadcast("reconnecting")

                // b) Eski tunnelni yopish
                synchronized(lifecycleLock) {
                    try { server?.closeService() } catch (_: Throwable) {}
                    try { server?.close() } catch (_: Throwable) {}
                    try { tun?.close() } catch (_: Throwable) {}
                    server = null
                    tun = null
                }

                // c) 1 sekund kutish
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}

                // d) Yangi ulanish
                val my = opSeq.incrementAndGet()
                runConnect(saved, my)
                Log.i(TAG, "✅ VPN qayta ulanish boshlandi")
                lastRestartTime = System.currentTimeMillis()
            } else {
                Log.w(TAG, "⚠️ lastConnectIntent yoki server null")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "networkRestart xato", t)
        } finally {
            isRestarting = false
        }
    }

    /**
     * VpnService.setUnderlyingNetworks() ni tizimdan yangilash.
     * WiFi ↔ Cellular almashganda chaqiriladi.
     */
    private fun updateUnderlyingNetworksFromSystem() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val active = pickPhysicalNetwork()
            if (active != null) {
                val result = setUnderlyingNetworks(arrayOf(active))
                underlyingNetwork = active
                Log.i(TAG, "⭐ setUnderlyingNetworks yangilandi: " +
                    "$active (result=$result)")
            } else {
                setUnderlyingNetworks(null)
                Log.w(TAG, "⚠️ activeNetwork null — setUnderlyingNetworks(null)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "updateUnderlyingNetworksFromSystem xato", t)
        }
    }

    /**
     * Tarmoq almashganda tunnelni qayta ulash (2s debounce).
     * Bir nechta onAvailable/onLost chaqirilsa — bitta restart.
     */
    private fun scheduleNetworkRestart(delayMs: Long = 8000L) {
        val now = System.currentTimeMillis()
        // ★ Takroriy rejalashtirishni oldini olish
        if (now - restartScheduledTime < 3000L) {
            Log.i(TAG, "⏭️  scheduleNetworkRestart — allaqachon rejalashtirilgan")
            return
        }
        restartScheduledTime = now
        pendingNetworkRestart = true
        networkRestartHandler.removeCallbacks(networkRestartRunnable)
        networkRestartHandler.postDelayed(networkRestartRunnable, delayMs)
        Log.i(TAG, "⏰ Network restart rejalashtirildi (${delayMs}ms)")
    }

    /**
     * VPN BO'LMAGAN eng yaxshi tarmoqni topadi (Wi-Fi/Ethernet > mobil, validated afzal).
     * VPN ishlayotganda cm.activeNetwork VPN'ning O'ZI bo'ladi — uni underlying
     * qilib bo'lmaydi, shuning uchun allNetworks bo'yicha qidiramiz.
     */
    private fun pickPhysicalNetwork(): android.net.Network? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            var best: android.net.Network? = null
            var bestScore = -1
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                var score = 0
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) score += 2
                else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) score += 1
                if (score > bestScore) { best = n; bestScore = score }
            }
            best
        } catch (t: Throwable) {
            Log.w(TAG, "pickPhysicalNetwork xato: ${t.message}")
            null
        }
    }

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
            var hasV6Addr = false
            val it6 = options.inet6Address
            while (it6.hasNext()) {
                val p = it6.next()
                runCatching { builder.addAddress(p.address(), p.prefix()) }
                    .onSuccess { hasV6Addr = true }
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
                    if (hasV6Addr) {
                        runCatching { builder.addDnsServer("2606:4700:4700::1111") }
                            .onFailure { Log.w(TAG, "addDnsServer v6 xato: ${it.message}") }
                    }
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
            // ★ FIX: MTU validatsiyasi (0 yoki juda kichik bo'lsa crash)
            val safeMtu = if (options.mtu in 576..9000) options.mtu else 1500
            builder.setMtu(safeMtu)
            Log.i(TAG, "TUN MTU = $safeMtu (libbox: ${options.mtu})")
            // ★ FIX: setBlocking OLIB TASHLANDI
            // (sing-box o'zi strict_route ni TUN ichida boshqaradi)
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

            Log.i(TAG, "SPLIT: mode=$splitMode, apps=${selectedApps.size}")

            when (splitMode) {
                SplitTunnelStore.MODE_WHITELIST -> {
                    // FAQAT addAllowedApplication
                    val allowed = linkedSetOf<String>()
                    allowed.addAll(includePackages)
                    allowed.addAll(selectedApps)
                    allowed.remove(packageName)  // o'zimizni chiqaramiz

                    var addedAllowed = 0
                    for (pkg in allowed) {
                        try {
                            builder.addAllowedApplication(pkg)
                            addedAllowed++
                            Log.d(TAG, "WHITELIST: $pkg")
                        } catch (e: Exception) {
                            Log.w(TAG, "WHITELIST fail $pkg: ${e.message}")
                        }
                    }
                    // Bitta ham ilova qo'shilmasa Android HAMMA trafikni VPN'ga yuboradi —
                    // hech bo'lmasa o'zimizni chiqarib qo'yamiz
                    if (addedAllowed == 0) {
                        runCatching { builder.addDisallowedApplication(packageName) }
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
                            Log.d(TAG, "BLACKLIST: $pkg")
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
                            Log.d(TAG, "ALL: $pkg")
                        } catch (e: Exception) {
                            Log.w(TAG, "ALL fail $pkg: ${e.message}")
                        }
                    }
                }
            }
            // ★ setBlocking() kill-switch EMAS (u fd'ni blocking rejimga o'tkazib,
            //   libbox o'qishini qotirib qo'yishi mumkin). Haqiqiy kill-switch:
            //   Android Sozlamalar → VPN → NurVPN → "Always-on" + "VPN'siz ulanishni bloklash".
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            // ★★★ MUHIM: real upstream network'ni o'rnatamiz ★★★
            val un: android.net.Network? =
                this@NurVpnService.underlyingNetwork ?: pickPhysicalNetwork()
            if (un != null) {
                try {
                    val arr = arrayOf<android.net.Network>(un)
                    builder.setUnderlyingNetworks(arr)
                    Log.i(TAG, "setUnderlyingNetworks($un)")
                } catch (t: Throwable) {
                    Log.e(TAG, "setUnderlyingNetworks fail", t)
                }
            } else {
                try {
                    builder.setUnderlyingNetworks(null)
                    Log.i(TAG, "setUnderlyingNetworks(null)")
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
            Log.d(TAG, "protect fd=$fd")
            val ok = protect(fd)
            Log.d(TAG, "protect(fd=$fd) -> $ok")
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
            Log.i(TAG, "startDefaultInterfaceMonitor")
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
                        val handle = network.networkHandle
                        val now = System.currentTimeMillis()
                        Log.i(TAG, "onAvailable: $network (handle=$handle)")
                        pushDefaultInterface(l)

                        // ═══ QAT'IY FILTER ═══
                        // 1. Restart ketmoqda? — skip
                        if (isRestarting) {
                            Log.i(TAG, "⏭️  Skip (restart ketmoqda)")
                            return
                        }
                        // 2. 30 sekund cooldown? — skip
                        if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                            Log.i(TAG, "⏭️  Skip (cooldown ${(now - lastRestartTime) / 1000}s)")
                            return
                        }
                        // 3. Handle bir xil? — skip (bu bizning TUN'imiz)
                        if (handle == lastNetworkHandle) {
                            Log.i(TAG, "⏭️  Skip (bir xil handle)")
                            return
                        }

                        Log.i(TAG, "✅ Yangi tarmoq — restart rejalashtirildi")
                        lastNetworkHandle = handle
                        this@NurVpnService.updateUnderlyingNetworksFromSystem()
                        this@NurVpnService.scheduleNetworkRestart(NETWORK_STABILIZE_MS)
                    }
                    override fun onLost(network: android.net.Network) {
                        val handle = network.networkHandle
                        val now = System.currentTimeMillis()
                        Log.i(TAG, "onLost: $network (handle=$handle)")
                        pushDefaultInterface(l)

                        if (isRestarting) {
                            Log.i(TAG, "⏭️  Skip (restart ketmoqda)")
                            return
                        }
                        if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                            Log.i(TAG, "⏭️  Skip (cooldown)")
                            return
                        }
                        Log.i(TAG, "✅ Tarmoq yo'qoldi — restart rejalashtirildi")
                        lastRestartTime = now
                        this@NurVpnService.scheduleNetworkRestart(NETWORK_STABILIZE_MS)
                    }
                    override fun onLinkPropertiesChanged(
                        network: android.net.Network,
                        lp: android.net.LinkProperties
                    ) {
                        // ★ Faqat interfeys yangilash — RESTART EMAS
                        pushDefaultInterface(l)
                    }
                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        caps: android.net.NetworkCapabilities
                    ) {
                        // ★ Faqat interfeys yangilash — RESTART EMAS
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
                        ni.setMetered(false)

                        // ★ sing-box NetworkInterface.Type:
                        //   0=other, 1=wifi, 2=cellular, 3=ethernet
                        val t = when {
                            jni.name.startsWith("wlan") -> 1   // WiFi
                            jni.name.startsWith("eth") -> 3    // Ethernet
                            jni.name.startsWith("rmnet") ||
                                jni.name.startsWith("ccmni") ||
                                jni.name.startsWith("pdp") -> 2  // Cellular
                            jni.name.startsWith("dummy") ||
                                jni.name.startsWith("tun") -> 0  // Other
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
            Log.i(TAG, "usePlatformAutoDetect")
            return true
        }

        override fun useProcFS(): Boolean = false

        // ═══ YANGI METODLAR (1.14+) ═══


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
            if (message != null) {
                Log.i(TAG, "[box] $message")
                com.nurvpn.app.core.LogBuffer.add("[box] $message")
            }
        }

        // ═══ YANGI METODLAR (1.14+) ═══

        override fun connectSSHAgent(): Int = -1

        override fun triggerNativeCrash() { }
    }

    // ═══════ LIFECYCLE ═══════

    /** start / stop / pause / resume amallari ketma-ket bajariladi. */
    private val lifecycleLock = Any()

    /** Har bir yangi amal raqamni oshiradi — eskisi o'zini bekor qiladi. */
    private val opSeq = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile private var lastState: String = "disconnected"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "═══ onStartCommand: ${intent?.action}")

        // Tizim jarayonni o'ldirib qayta tiklagan (intent = null) — o'zi ulanmaydi
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // ═══ PAUSE ═══
        if (intent.getBooleanExtra(MainActivity.EXTRA_PAUSE, false)) {
            Log.i(TAG, "PAUSE so'rovi")
            if (!isPaused) {
                isPaused = true
                val my = opSeq.incrementAndGet()
                broadcast("paused")
                updateNotification(getString(R.string.notif_paused), withActions = true)
                Thread {
                    synchronized(lifecycleLock) {
                        if (opSeq.get() == my) cleanup()
                    }
                }.start()
            }
            return START_NOT_STICKY
        }

        // ═══ RESUME ═══
        if (intent.getBooleanExtra(MainActivity.EXTRA_RESUME, false)) {
            Log.i(TAG, "RESUME so'rovi")
            if (isPaused) {
                isPaused = false
                val saved = lastConnectIntent
                if (saved != null) runConnect(saved, opSeq.incrementAndGet())
            }
            return START_NOT_STICKY
        }

        // ═══ STOP ═══
        if (intent.getBooleanExtra(MainActivity.EXTRA_STOP, false)) {
            Log.i(TAG, "STOP so'rovi")
            val my = opSeq.incrementAndGet()
            isPaused = false
            hideForeground()
            broadcast("disconnected")
            // Og'ir yopish ishi asosiy oqimda EMAS (ANR bo'lmasin)
            Thread {
                synchronized(lifecycleLock) {
                    if (opSeq.get() == my) cleanup()
                }
                // Agar shu orada yangi START kelgan bo'lsa (startId katta) — service to'xtamaydi
                stopSelf(startId)
            }.start()
            return START_NOT_STICKY
        }

        // ═══ START (ilovadan, tile'dan yoki Always-on VPN'dan) ═══
        isPaused = false
        lastConnectIntent = intent
        startForegroundWithNotif()
        runConnect(intent, opSeq.incrementAndGet())
        return START_NOT_STICKY
    }

    /** Eski serverni yopib, yangisini fon oqimida ishga tushiradi. */
    private fun runConnect(src: Intent?, my: Long) {
        Thread {
            synchronized(lifecycleLock) {
                if (opSeq.get() != my) {
                    Log.i(TAG, "connect[$my] bekor qilindi (yangi amal bor)")
                } else {
                    val hadServer = server != null
                    cleanup()
                    if (hadServer) {
                        try { Thread.sleep(300) } catch (_: InterruptedException) {}
                    }
                    connectLocked(src, my)
                }
            }
        }.start()
    }

    private fun hideForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground xato: ${t.message}")
        }
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
        lastState = state
        when (state) {
            "connected" -> TunnelState.isConnected = true
            "disconnected", "error", "paused" -> TunnelState.isConnected = false
        }

        val i = Intent(MainActivity.ACTION_BROADCAST)
        i.setPackage(packageName)
        i.putExtra(MainActivity.EXTRA_STATE, state)
        if (err != null) i.putExtra(MainActivity.EXTRA_ERR, err)
        sendBroadcast(i)

        try {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, NurVpnTileService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "Tile refresh xato: ${t.message}")
        }
    }

    // ═══════ CONNECT (lifecycleLock ichida chaqiriladi) ═══════
    private fun connectLocked(intent: Intent?, my: Long) {
        val prefs = getSharedPreferences("main", Context.MODE_PRIVATE)
        var link = intent?.getStringExtra(MainActivity.EXTRA_LINK)
        var awg = intent?.getStringExtra(MainActivity.EXTRA_AWG)

        // Always-on VPN yoki tizim ishga tushirgan bo'lsa — oxirgi tanlangan server
        if (link == null && awg == null) {
            if (prefs.getString("protocol", MainActivity.PROTO_XRAY) == MainActivity.PROTO_AWG) {
                awg = prefs.getString("current_awg", null)
            } else {
                link = prefs.getString("current_link", null)
            }
        }

        // DNS faqat IP bo'lishi shart (hostname bo'lsa sing-box DNS'ni hal qila olmaydi)
        var dns = (prefs.getString("dns", "1.1.1.1") ?: "1.1.1.1").trim()
        if (!SingBoxConfig.isIpAddress(dns)) dns = "1.1.1.1"

        try {
            if (link == null && awg == null) {
                throw Exception(getString(R.string.error_no_server))
            }

            val ipv6Blocked = IPv6Blocker.isBlocked(this)
            val cfg = if (awg != null) {
                Log.i(TAG, "AWG rejimi: ${awg.length} belgi config")
                SingBoxConfig.buildAwgConfig(awg, dns, ipv6Blocked)
            } else {
                SingBoxConfig.buildFullConfig(link!!, dns, "", ipv6Blocked, this)
            }
            val cfgJson = cfg.toString(2)
            com.nurvpn.app.core.LogBuffer.clear()
            com.nurvpn.app.core.LogBuffer.add("CONNECT " + describeConfig(cfg))
            Log.i(TAG, "Config tayyor: ${cfgJson.length} belgi")

            // Parollar bor config faqat debug build'da diskka yoziladi
            val debuggable = (applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val cfgFile = File(filesDir, "config.json")
            if (debuggable) runCatching { cfgFile.writeText(cfgJson) }
            else runCatching { cfgFile.delete() }

            // Haqiqiy (VPN bo'lmagan) tarmoq — openTun'dan oldin aniqlaymiz
            underlyingNetwork = pickPhysicalNetwork()

            // filesDir ishlatamiz (MIUI'da cacheDir'da flock timeout beradi)
            val workDir = File(filesDir, "work").apply { mkdirs() }
            val setup = SetupOptions()
            setup.basePath = filesDir.absolutePath
            setup.workingPath = workDir.absolutePath
            setup.tempPath = workDir.absolutePath
            setup.fixAndroidStack = true
            setup.debug = debuggable
            Libbox.setup(setup)
            Log.i(TAG, "Libbox.setup OK: ${workDir.absolutePath}")

            // ★ Config'ni ishga tushirishdan oldin tekshiramiz — aniq xato matni chiqadi
            try {
                Libbox.checkConfig(cfgJson)
            } catch (t: Throwable) {
                throw Exception("Config xato: ${t.message}")
            }

            val srv = Libbox.newCommandServer(handler, platform)
            server = srv
            srv.start()
            Log.i(TAG, "CommandServer.start OK")

            lastConfigJson = cfgJson
            srv.startOrReloadService(cfgJson, io.nekohasekai.libbox.OverrideOptions())
            Log.i(TAG, "startOrReloadService OK")

            // Shu orada STOP / yangi START kelgan bo'lsa — holatni "connected" qilmaymiz
            if (opSeq.get() != my) {
                Log.i(TAG, "connect[$my]: ulanish paytida bekor qilingan")
                return
            }
            broadcast("connected")
            updateNotification(getString(R.string.notif_connected), withActions = true)
        } catch (t: Throwable) {
            Log.e(TAG, "connect fail", t)
            com.nurvpn.app.core.LogBuffer.add("ERROR: ${t.message}")
            cleanup()
            if (opSeq.get() == my) {
                broadcast("error", t.message ?: "noma'lum xato")
                stopSelf()
            }
        }
    }

    /** Config xulosasi (parol/UUID/kalitlarsiz) — log ulashish uchun. */
    private fun describeConfig(cfg: org.json.JSONObject): String {
        return try {
            val ob = cfg.optJSONArray("endpoints")?.optJSONObject(0)
                ?: cfg.optJSONArray("outbounds")?.optJSONObject(0)
                ?: return "?"
            val tls = ob.optJSONObject("tls")
            val tr = ob.optJSONObject("transport")
            val inb = cfg.optJSONArray("inbounds")?.optJSONObject(0)
            val dnsT = cfg.optJSONObject("dns")?.optJSONArray("servers")
                ?.optJSONObject(0)?.optString("type")
            "type=${ob.optString("type")} server=${ob.optString("server")}:${ob.optInt("server_port")} " +
                "flow=${ob.optString("flow")} transport=${tr?.optString("type") ?: "tcp"} " +
                "tls=${tls != null} reality=${tls?.has("reality") == true} " +
                "utls=${tls?.optJSONObject("utls")?.optString("fingerprint") ?: "-"} " +
                "sni=${tls?.optString("server_name") ?: "-"} " +
                "alpn=${tls?.optJSONArray("alpn")?.toString() ?: "-"} " +
                "insecure=${tls?.optBoolean("insecure") ?: false} " +
                "stack=${inb?.optString("stack")} mtu=${inb?.optInt("mtu")} dns=$dnsT"
        } catch (t: Throwable) {
            "?"
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
            nm.notify(NOTIF_ID, builder.build())
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
        opSeq.incrementAndGet()
        networkRestartHandler.removeCallbacksAndMessages(null)
        hideForeground()
        // Og'ir yopish ishi asosiy oqimda emas
        Thread { synchronized(lifecycleLock) { cleanup() } }.start()
        // Xato bildirilgan bo'lsa, uni "disconnected" bilan bosib yubormaymiz
        if (lastState != "error" && lastState != "disconnected") {
            broadcast("disconnected")
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        // Boshqa VPN ilova ulanishni egallab oldi
        opSeq.incrementAndGet()
        broadcast("disconnected")
        Thread {
            synchronized(lifecycleLock) { cleanup() }
            stopSelf()
        }.start()
    }
}

// ═══════════ BINARY RUNNER (endi kerak emas) ═══════════
