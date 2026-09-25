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
import com.nurvpn.app.HomeFragment
import com.nurvpn.app.ServersFragment
import com.nurvpn.app.SettingsFragment
import com.nurvpn.app.NurVpnService

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

