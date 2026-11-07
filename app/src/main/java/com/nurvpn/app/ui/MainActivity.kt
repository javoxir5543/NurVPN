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

        // ★ FIX121-SCROLL: tab identifikatorlari
        const val TAB_HOME = "home"
        const val TAB_SERVERS = "servers"
        const val TAB_SETTINGS = "settings"
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

    /** VPN ruxsati dialogi natijasi — ruxsat berilgach ulanishni DAVOM ettiradi. */
    private val vpnPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK) doStartVpn()
        }

    private var stateReceiver: BroadcastReceiver? = null
    private var dbgReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // LANG_AUTO_FIX_V113: Tilni avtomatik aniqlash (EN zaxira bilan)
        run {
            val saved = com.nurvpn.app.util.ThemeHelper.getLanguage(this)
            val targetLang = if (saved.isNotEmpty()) saved else {
                val sys = java.util.Locale.getDefault().language
                if (sys == "uz" || sys == "ru" || sys == "en") sys else "en"
            }
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(targetLang)
            )
        }

        super.onCreate(savedInstanceState)
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX133-STARTUP: BOSQICHMA-BOSQICH VAQT O'LCHOVI
        //
        //  Ilova ochilishi 9 sekund — friz aynan qaysi qatorda
        //  ekanini aniqlash uchun har bir bosqich vaqti log qilinadi.
        // ═══════════════════════════════════════════════════════════════
        var __t = System.currentTimeMillis()
        android.util.Log.i("NurVPN-PERF", "onCreate[0] boshlandi")
        // Fon rejimida yangilanishni tekshirish
        com.nurvpn.app.util.UpdateManager.checkForUpdates(this)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[1] checkForUpdates: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
        ThemeHelper.applyTheme(this)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[2] applyTheme: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
        prefs = getSharedPreferences("main", Context.MODE_PRIVATE)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[3] prefs: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()

        // ═══ Deep link handler (incy://, crypt1://, nurvpn://) ═══
        handleDeepLink(intent)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[4] deepLink: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX133-STARTUP: ILOVA OCHILISHIDAGI 8-9 SEKUNDLIK FRIZ
        //
        //  ═══ QURILMADA O'LCHANDI ═══
        //
        //    01:43:55.096  Start proc
        //    01:44:03.395  "Avto-start o'chirilgan"   ← 8.3 SEKUND KEYIN!
        //    01:44:03.861  Displayed MainActivity: +8s805ms
        //
        //  Friz `onCreate` ICHIDA, `setContentView` dan OLDIN edi.
        //
        //  ═══ ILDIZ SABABLAR ═══
        //
        //  1) `ServerStore.load(this)` IKKI MARTA chaqirilardi:
        //       112-satr: val allServers = ServerStore.load(this)
        //       141-satr: servers = ServerStore.load(this)
        //     Har biri 686 ta serverni SharedPreferences'dan o'qib,
        //     JSON'ni QAYTADAN PARSE qiladi. Ikkalasi = 1372 ta obyekt!
        //
        //  2) PURGE blokida har bir `sub_xxx` obuna uchun:
        //       allServers.any { it.subId == sub.id }   // 686 ta tekshiruv
        //     N ta obuna × 686 ta server = O(N×686) ta oraliq tekshiruv.
        //
        //  3) `OpenSourceStore.isEnabled()` har chaqiruvda
        //     `getSharedPreferences` + `getStringSet` (fayl o'qish) qiladi.
        //
        //  ═══ YECHIM ═══
        //
        //  • ServerStore FAQAT BIR MARTA o'qiladi (pastda qayta ishlatiladi)
        //  • PURGE uchun subId'lar to'plami bir marta quriladi → O(1) qidiruv
        //  • `getEnabled()` bir marta o'qib, to'plamda saqlanadi
        // ═══════════════════════════════════════════════════════════════

        // ═══ STALE SUBSCRIPTIONS PURGE ═══
        try {
            OpenSourceStore.cleanupOrphans(this)
            val subs = SubscriptionStore.load(this)
            // ★ FIX133-STARTUP: serverlar BITTA marta o'qiladi
            val allServers = ServerStore.load(this)

            // ★ FIX133-STARTUP: subId'lar to'plami — O(1) qidiruv
            val subIdsWithServers = HashSet<String>(allServers.size * 2)
            for (s in allServers) {
                val sid = s.subId
                if (sid != null) subIdsWithServers.add(sid)
            }

            // ★ FIX133-STARTUP: yoqilgan ochiq manbalar — BITTA o'qish
            val enabledOpenIds = OpenSourceStore.getEnabled(this)

            val cleaned = subs.filter { sub ->
                when {
                    // 1) open:xxx — katalogda bor + yoqilgan + AWG emas
                    sub.id.startsWith("open:") -> {
                        val openId = sub.id.removePrefix("open:")
                        val open = OpenSourceCatalog.byId(openId)
                        open != null &&
                            !open.isAwg &&
                            openId in enabledOpenIds    // ★ O(1)
                    }
                    // 2) sub_xxx — legacy race artifact (server yo'q + yuklanmagan)
                    sub.id.startsWith("sub_") -> {
                        sub.id in subIdsWithServers || sub.lastUpdated > 0  // ★ O(1)
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

        // ★ FIX133-STARTUP: `servers` bir marta o'qiladi (yuqorida qayta
        // o'qilmaydi — bu 686 ta serverning ikkinchi JSON parse'ini
        // yo'q qiladi).
        servers = ServerStore.load(this)
        awgConfigs = AWGStore.load(this)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[5] PURGE+load(servers=${servers.size}): ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()

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
        android.util.Log.i("NurVPN-PERF",
            "onCreate[5a] WARP restore: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
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
        android.util.Log.i("NurVPN-PERF",
            "onCreate[5b] proto migratsiya (fixed=$protoFixed): ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
        if (protoFixed > 0) {
            ServerStore.save(this, servers)
            android.util.Log.i("NurVPN-DBG", "Migratsiya: $protoFixed ta server protokoli tuzatildi")
        }
        android.util.Log.i("NurVPN-PERF",
            "onCreate[5c] ServerStore.save: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
        for (si in servers) {
            val cc = CountryLookup.lookup(si.host)
            si.countryCode = cc[0]
            si.country = cc[1]
        }
        android.util.Log.i("NurVPN-PERF",
            "onCreate[5d] CountryLookup (${servers.size} server): ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
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
        android.util.Log.i("NurVPN-PERF",
            "onCreate[5e] current topish: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()

        AWGEditorBus.init(awgConfigs, currentAWG, protocol)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[6] AWGEditorBus.init: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()

        setContentView(R.layout.activity_main)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[7] setContentView: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()

        // ⚠️ MUHIM: VpnService.prepare() bu yerda CHAQIRILMAYDI!
        // Sabab: ilova ochilishi bilan Android tizim NurVPN ni "faol VPN"
        // deb belgilaydi va boshqa VPN larni o'chiradi.
        // prepare() faqat foydalanuvchi "Ulanish" tugmasini bosganda
        // startVpn() ichida chaqiriladi.

        val bottom = findViewById<com.google.android.material.bottomnavigation
            .BottomNavigationView>(R.id.bottom_nav)
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX121-SCROLL (BUG #1): fragment'larni SAQLAB QOLAMIZ.
        //
        //  Avval har safar YANGI nusxa yaratilardi (HomeFragment()).
        //  Endi `switchTab()` orqali ishlaymiz — u fragment'ni bir marta
        //  yaratib, keyin faqat hide/show qiladi.
        // ═══════════════════════════════════════════════════════════════
        bottom?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTab(TAB_HOME)
                R.id.nav_servers -> switchTab(TAB_SERVERS)
                R.id.nav_settings -> switchTab(TAB_SETTINGS)
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
                    "reconnecting" -> {
                        // ★ Tarmoq almashganda — "Ulanmoqda..." holati
                        isRunning = false; connectStart = 0; TunnelState.isConnected = false
                        android.util.Log.i("NurVPN-DBG", "UI: qayta ulanmoqda (network switch)")
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
                // ★ TUZATISH #12: broadcast keldi — UI flag'ini ochamiz
                endStateUpdate()
                refreshHome()
            }
        }

        stateReceiver = receiver

        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_BROADCAST),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        // (adb debug receiver xavfsizlik uchun olib tashlandi)
        // TEST KOD OLIB TASHLANDI

        // ★ Avto-start O'CHIRILDI — foydalanuvchi qo'lda bosadi
        android.util.Log.i("NurVPN-DBG", "Avto-start o'chirilgan, qo'lda bosishni kuting")

        // ★ FIX121-SCROLL: switchTab ishlatamiz (fragment keshlanadi).
        // Recreate bo'lsa FragmentManager o'zi tiklaydi.
        android.util.Log.i("NurVPN-PERF",
            "onCreate[8] receiver+switchTab oldidan: ${System.currentTimeMillis() - __t}ms")
        __t = System.currentTimeMillis()
        if (savedInstanceState == null) switchTab(TAB_HOME)

        // ★ v1.13: Ochiq manbalarni 24 soatda avtomatik yangilash.
        // `KEEP` policy — bir marta rejalashtiriladi.
        com.nurvpn.app.worker.OpenSourcesRefreshWorker.schedule(this)
        android.util.Log.i("NurVPN-PERF",
            "onCreate[9] switchTab(TAB_HOME): ${System.currentTimeMillis() - __t}ms")

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

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX132-LAG: OCHIQ MANBA YOQILGANDA KATTA FRIZ + CRASH
    //
    //  ═══ ILDIZ SABAB ═══
    //
    //  Sozlamalarda ochiq manba (open source) yoqilganda `refreshAllTabs()`
    //  chaqirilardi va u HAMMA obunani QAYTADAN yuklardi:
    //
    //      fun refreshSubscriptions() {
    //          for (sub in a.subscriptions) {          // ← BARCHASI!
    //              loadSubscription(sub.url, sub.name) // ← TARMOQ so'rovi
    //          }
    //      }
    //
    //  Har bir ochiq manba yoqilganda 5-10 ta obuna uchun 5-10 ta
    //  HTTP so'rov ketardi. Har biri:
    //    • `Thread { ... }` — yangi oqim yaratadi
    //    • `Toast` ko'rsatadi
    //    • natijani UI thread'da qayta ishlaydi (JSON parse + adapter)
    //
    //  Natijada ASOSIY EKRANDA katta friz va lag paydo bo'lardi.
    //
    //  Yana: agar `open.url` null bo'lsa, `Subscription(subId, "", ...)`
    //  yaratilardi — bo'sh URL bilan `java.net.URL("")` → CRASH.
    //
    //  ═══ YECHIM ═══
    //
    //  `refreshAllTabs()` — faqat KO'RSATISHNI yangilaydi (arzon).
    //  Tarmoqdan yuklash kerak bo'lsa, aniq obuna uchun alohida
    //  `reloadOneSubscription(subId)` chaqiriladi.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ★ FIX132-LAG: FAQAT ko'rsatishni yangilaydi — TARMOQQA tegmaydi.
     *
     * Ochiq manba yoqilganda/o'chirilganda ishlatiladi. Arzon operatsiya:
     * adapter qayta quriladi, lekin hech qanday HTTP so'rov ketmaydi.
     */
    fun refreshTabsUiOnly() {
        subscriptions = SubscriptionStore.load(this)
        for (f in supportFragmentManager.fragments) {
            when (f) {
                is ServersFragment -> try { f.refreshServers() } catch (_: Throwable) {}
                is HomeFragment -> try { f.refreshHomeCards() } catch (_: Throwable) {}
            }
        }
    }

    /** Tab almashtirish: 0=home, 1=servers, 2=settings. */
    // ═══ FIX v1.13: Ochiq manbalar yangilash navbati (2s interval) ═══
    private val refreshQueue = ArrayDeque<String>()
    private var refreshRunning = false
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Ochiq manba yoqilganda navbatga qo'shish (2s interval bilan). */
    fun enqueueRefresh(subId: String) {
        if (refreshQueue.contains(subId)) {
            android.util.Log.i("NurVPN-REFRESH", "enqueue: $subId allaqachon navbatda")
            return
        }
        refreshQueue.addLast(subId)
        android.util.Log.i("NurVPN-REFRESH", "enqueue: $subId, navbat=${refreshQueue.size}")
        if (!refreshRunning) runNextRefresh()
    }

    private fun runNextRefresh() {
        val subId = refreshQueue.removeFirstOrNull() ?: run {
            refreshRunning = false
            return
        }
        refreshRunning = true
        val f = supportFragmentManager.findFragmentByTag("tab_home") as? HomeFragment
        if (f != null && f.isAdded) {
            f.refreshOneSub(subId)
        } else {
            android.util.Log.w("NurVPN-REFRESH", "HomeFragment topilmadi, skip: $subId")
        }
        refreshHandler.postDelayed({ runNextRefresh() }, 2000L)
    }

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




    /**
     * ★ TUZATISH #12 (BUG #3): UI holat yangilanish flag'i.
     *
     * Muammo: tugma bosilishi va broadcast bir vaqtda kelib, UI holati
     * "ON → OFF → ON" bo'lib miltillardi (loop taassuroti).
     *
     * Yechim: holat faqat BROADCAST orqali o'zgaradi; tugma bosilganda
     * biz shunchaki "kutish" rejimiga o'tamiz va takroriy bosishni
     * bloklaymiz.
     */
    @Volatile
    private var isUpdatingState = false

    /** Tugma bosilishini qabul qilish mumkinmi? */
    fun beginStateUpdate(): Boolean {
        synchronized(stateUpdateLock) {
            if (isUpdatingState) {
                android.util.Log.i("NurVPN-DBG", "⏭️  Holat yangilanmoqda — kutamiz")
                return false
            }
            isUpdatingState = true
        }
        // 4 sekunddan keyin avtomatik ochiladi (broadcast kelmasa ham)
        mainHandler.postDelayed({
            synchronized(stateUpdateLock) { isUpdatingState = false }
        }, 4000L)
        return true
    }

    /** Broadcast keldi — flag'ni ochamiz. */
    fun endStateUpdate() {
        synchronized(stateUpdateLock) { isUpdatingState = false }
    }

    private val stateUpdateLock = Any()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun refreshHome() {
        // FIX: add/hide/show arxitekturasida container'da 3 ta fragment bor.
        // findFragmentById birinchisini qaytaradi (ko'pincha noto'g'ri).
        // Tag bilan ANIQ qidiramiz — switchTab() shu tag bilan qo'shadi.
        val f = supportFragmentManager.findFragmentByTag(tagFor(TAB_HOME))
        if (f is HomeFragment && f.isAdded) f.refresh()
    }


    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #8: Scroll pozitsiyalari xotirasi
    //
    //  `showFrag()` har safar YANGI fragment yaratadi
    //  (showFrag(HomeFragment())), shuning uchun fragment ichidagi
    //  `savedScrollY` yo'qoladi. Bu yerda Activity darajasida
    //  saqlaymiz — Home va Servers uchun ALOHIDA kalitlar.
    // ═══════════════════════════════════════════════════════════════
    private val scrollMemory = HashMap<String, Int>()

    fun setScrollMemory(key: String, y: Int) {
        scrollMemory[key] = if (y < 0) 0 else y
    }

    fun getScrollMemory(key: String): Int = scrollMemory[key] ?: 0

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX121-SCROLL (BUG #1): TAB ALMASHISH — hide/show
    //
    //  ILDIZ SABAB: `replace(R.id.container, f)` + har safar yangi nusxa
    //  → eski fragment view'i darhol yo'q qilinmasdi → ikkala view
    //  daraxti bir vaqtda mavjud bo'lib, touch event noto'g'ri ekranga
    //  borardi ("ikkinchi ekranda scroll qilsam birinchi ekran scroll
    //  bo'ladi").
    //
    //  YECHIM: fragment nusxalarini keshlab, faqat hide/show qilamiz.
    //    • Faqat BITTA fragment ko'rinadi → touch faqat unga boradi
    //    • View daraxti SAQLANADI → scroll pozitsiyasi o'z joyida qoladi
    //    • Har tab o'z scroll holatini mustaqil eslab qoladi
    // ═══════════════════════════════════════════════════════════════════
    private var homeFragment: Fragment? = null
    private var serversFragment: Fragment? = null
    private var settingsFragment: Fragment? = null
    private var activeTab: String = TAB_HOME

    private fun fragmentFor(tab: String): Fragment = when (tab) {
        TAB_HOME -> homeFragment ?: HomeFragment().also { homeFragment = it }
        TAB_SERVERS -> serversFragment ?: ServersFragment().also { serversFragment = it }
        else -> settingsFragment ?: SettingsFragment().also { settingsFragment = it }
    }

    fun switchTab(tab: String) {
        if (isFinishing || isDestroyed) return
        // ⚠️ Recreate bo'lgandan keyin eski havolalar yaroqsiz bo'lishi mumkin
        if (tab != activeTab) {
            android.util.Log.i("NurVPN-DBG", "switchTab: $activeTab → $tab")
        }
        activeTab = tab

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        // Barcha boshqa fragment'larni yashiramiz
        for (t in listOf(TAB_HOME, TAB_SERVERS, TAB_SETTINGS)) {
            if (t == tab) continue
            val f = fm.findFragmentByTag(tagFor(t))
            if (f != null && !f.isHidden) tx.hide(f)
        }

        val target = fm.findFragmentByTag(tagFor(tab))
        if (target == null) {
            // Birinchi marta — qo'shamiz
            tx.add(R.id.container, fragmentFor(tab), tagFor(tab))
        } else {
            // Allaqachon bor — faqat ko'rsatamiz
            tx.show(target)
            fragmentFor(tab) // keshni sinxronlaymiz
        }

        tx.commitAllowingStateLoss()
        fm.executePendingTransactions()

        // ★ Muhim: view daraxti tayyor bo'lgach, scroll holatini tiklaymiz
        fm.findFragmentByTag(tagFor(tab))?.let { f ->
            f.view?.post { notifyTabShown(f) }
        }
    }

    private fun tagFor(tab: String): String = "tab_$tab"

    private fun notifyTabShown(f: Fragment) {
        try {
            when (f) {
                is HomeFragment -> f.onTabShown()
                is ServersFragment -> f.onTabShown()
            }
        } catch (_: Throwable) { }
    }

    fun selectServer(s: ServerItem) {
        android.util.Log.i("NurVPN-SELECT",
            "selectServer: ${s.displayName()}, isRunning=$isRunning, current=${currentServer?.displayName()}")
        val changed = currentServer?.link != s.link || protocol != PROTO_XRAY
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

    fun selectAWG(c: AWGConfig, autoConnect: Boolean = false) {
        val changed = currentAWG?.rawConf != c.rawConf || protocol != PROTO_AWG
        currentAWG = c
        protocol = PROTO_AWG
        prefs.edit()
            .putString("current_awg", c.rawConf)
            .putString("protocol", PROTO_AWG)
            .apply()
        AWGEditorBus.init(awgConfigs, c, protocol)
        when {
            // VPN yoniq + boshqa config → restart
            isRunning && changed -> {
                restartVpn(getString(R.string.reason_awg, c.name ?: "Config"))
            }
            // VPN o'chiq + foydalanuvchi config'ni bosdi → avtomatik ulanish
            autoConnect && !isRunning -> {
                android.util.Log.i("NurVPN-DBG",
                    "selectAWG: autoConnect → startVpn() [${c.name}]")
                startVpn()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX112-BUG3: RESTART COOLDOWN
    //
    //  Muammo (logcat bilan tasdiqlangan):
    //     sing-box started (0.38s), (0.33s), (0.39s)  → 2-3s sikl
    //
    //  Sabab: `restartVpn()` da `generation` bor edi, lekin COOLDOWN
    //  YO'Q. Har chaqiruvda yangi generation boshlanib, 2500ms dan
    //  keyin yangi ulanish ishga tushardi. Bir necha so'rov ketma-ket
    //  kelsa — ON/OFF/ON sikl hosil bo'lardi.
    //
    //  Yechim: 5 sekundlik cooldown. Cooldown ichida kelgan so'rov
    //  YO'QOLMAYDI — "pending" sifatida eslab qolinadi va cooldown
    //  tugagach BIR MARTA bajariladi.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var lastRestartAt = 0L
    private val RESTART_COOLDOWN_MS = 5000L

    /** Cooldown ichida kelgan so'rov (yo'qolmasin). */
    @Volatile private var pendingRestartReason: String? = null
    private val restartHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingRestartRunnable = Runnable {
        val r = pendingRestartReason
        pendingRestartReason = null
        if (r != null) {
            android.util.Log.i("NurVPN-DBG",
                "restartVpn: cooldown tugadi — kechiktirilgan so'rov bajariladi ($r)")
            restartVpn(r)
        }
    }

    /** VPN ishlab turganda server o'zgarsa — qayta ulanish. */
    fun restartVpn(reason: String) {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastRestartAt

        // ═══ COOLDOWN TEKSHIRUVI ═══
        if (lastRestartAt != 0L && sinceLast < RESTART_COOLDOWN_MS) {
            android.util.Log.w("NurVPN-DBG",
                "restartVpn: COOLDOWN (${sinceLast}ms < ${RESTART_COOLDOWN_MS}ms) " +
                "— bekor qilindi. Sabab: $reason")
            // So'rovni eslab qolamiz — cooldown tugagach bajariladi
            pendingRestartReason = reason
            restartHandler.removeCallbacks(pendingRestartRunnable)
            restartHandler.postDelayed(
                pendingRestartRunnable, RESTART_COOLDOWN_MS - sinceLast)
            return
        }
        lastRestartAt = now

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
        restartHandler.postDelayed({
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
            if (PROTO_AWG == protocol) {
                currentAWG?.let {
                    si2.putExtra(EXTRA_AWG, it.rawConf)
                    si2.putExtra(EXTRA_NAME, it.name ?: "AWG")
                }
            } else {
                currentServer?.let {
                    si2.putExtra(EXTRA_LINK, it.link)
                    si2.putExtra(EXTRA_NAME, it.displayName())
                }
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
            vpnPermissionLauncher.launch(vpnIntent)
            return
        }
        doStartVpn()
    }

    private fun doStartVpn() {
        val i = Intent(this, NurVpnService::class.java)
        if (PROTO_AWG == protocol) {
            val awg = currentAWG ?: run {
                Toast.makeText(this, R.string.error_no_server, Toast.LENGTH_SHORT).show()
                return
            }
            i.putExtra(EXTRA_AWG, awg.rawConf)
            i.putExtra(EXTRA_NAME, awg.name ?: "AWG")
        } else {
            val s = currentServer ?: run {
                Toast.makeText(this, R.string.error_no_server, Toast.LENGTH_SHORT).show()
                return
            }
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * `incy://`, `crypt1://`, `nurvpn://` deep link'larni qabul qilish.
     * URI'ni SharedPreferences'ga saqlaydi — Fragment onResume'da o'qiydi.
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val uri = data.toString()
        if (uri.isEmpty()) return

        android.util.Log.i("NurVPN-DEEPLINK", "Received: ${uri.take(80)}")

        // ═══ YANGI: incy://import/ (to'g'ridan-to'g'ri obuna) ═══
        if (uri.startsWith("incy://import/")) {
            val importUrl = uri.removePrefix("incy://import/")
            prefs.edit().putString("pending_deeplink", importUrl).apply()
            android.util.Log.i("NurVPN-DEEPLINK", "Saved import URL: $importUrl")
            return
        }

        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #5: endi ODDIY linklar ham qabul qilinadi.
        //
        //  Avval faqat crypt linklar o'tardi:
        //      if (!isCryptLink(uri)) { return }   ← vless:// yo'qolardi
        //
        //  Endi: crypt1:// + incy:// + BARCHA proxy protokollari +
        //        http(s) obuna URL'lari qabul qilinadi.
        //  Boshqa hamma narsa (file://, javascript:// va h.k.) rad etiladi.
        // ═══════════════════════════════════════════════════════════════
        val isProxy = com.nurvpn.app.parser.SubscriptionLinkExtractor
            .extract(uri).isNotEmpty()
        val isHttp = uri.startsWith("http://", true) ||
                     uri.startsWith("https://", true)
        val isCrypt = com.nurvpn.app.parser.Crypt1Decoder.isCryptLink(uri)

        if (!isCrypt && !isProxy && !isHttp) {
            android.util.Log.w("NurVPN-DEEPLINK",
                "Qo'llab-quvvatlanmaydigan URI, rad etildi: ${uri.take(60)}")
            return
        }

        android.util.Log.i("NurVPN-DEEPLINK",
            "Qabul qilindi (crypt=$isCrypt proxy=$isProxy http=$isHttp)")

        // Pending link sifatida saqlash
        prefs.edit().putString("pending_deeplink", uri).apply()
        android.util.Log.i("NurVPN-DEEPLINK", "Saved pending_deeplink")
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
