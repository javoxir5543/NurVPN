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
