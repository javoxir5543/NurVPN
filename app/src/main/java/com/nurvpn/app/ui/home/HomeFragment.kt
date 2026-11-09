package com.nurvpn.app.ui.home
import com.nurvpn.app.R

import com.nurvpn.app.core.TunnelState
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.HwidHelper
import com.nurvpn.app.util.MaskHelper
import com.nurvpn.app.util.BiometricAuthHelper
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
import com.nurvpn.app.storage.ManualSortStore
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
import com.nurvpn.app.parser.Crypt1Decoder
import com.nurvpn.app.parser.decodeBase64Safely
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.core.Subscription
import com.nurvpn.app.storage.SubscriptionStore

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        /** ★ TUZATISH #4: scroll holati kaliti (Home ekrani). */
        private const val KEY_SCROLL_Y = "scroll_y_home"

        private const val REQ_QR_SCAN = 1001
        private const val REQ_EDIT_SERVER = 1002
        /** Sessiya davomida bir marta urinib ko'rilgan obunalar
         *  (404 bo'lsa infinite retry oldini oladi). */
        private val attemptedSubs: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private var connectBtn: View? = null

    // ★ TUZATISH #4: har bir fragment O'Z scroll holatini saqlaydi.
    // (Home va Servers almashganda scroll aralashib ketmasligi uchun)
    /** ★ TUZATISH #8: shu fragmentning scroll pozitsiyasi (mustaqil). */
    private var savedScrollY: Int = 0

    private var connectIcon: ImageView? = null
    private var statusText: TextView? = null
    private var serverFlag: TextView? = null
    private var serverName: TextView? = null
    private var serverProto: TextView? = null
    private var pingText: TextView? = null
    private var timerText: TextView? = null
    private var downText: TextView? = null
    private var upText: TextView? = null
    private var speedWave: SpeedWaveView? = null

    // Trafik kuzatuvchi
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastTrafficTime: Long = 0L

    // ★ FIX140-HOMERV: `cardsContainer` endi ishlatilmaydi —
    // kartalar `homeRecycler` + `homeAdapter` orqali chiziladi.
    @Suppress("unused")
    private var cardsContainer: LinearLayout? = null
    private var bodyAwg: LinearLayout? = null

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX140-HOMERV: RECYCLERVIEW + ADAPTER
    //
    //  ═══ NEGA ═══
    //
    //  Home `ScrollView` + `LinearLayout` edi. Karta ochilganda 200 ta
    //  `makeServerRow()` = 200 ta XML `inflate()` ≈ 2 SEKUND:
    //
    //      fillBodyIfNeeded(open:radikal_hy2): 1921ms, rows=200
    //      Choreographer: Skipped 135 frames!
    //
    //  Servers bo'limi `RecyclerView` ishlatadi va FRIZ YO'Q — chunki
    //  u faqat KO'RINADIGAN qatorlarni yaratadi va scroll paytida
    //  ularni QAYTA ISHLATADI.
    //
    //  ═══ YECHIM ═══
    //
    //  Butun Home ekrani bitta `RecyclerView`:
    //
    //      item 0    : Header (traffic, timer, tugmalar, AWG kartasi)
    //      item 1..N : Server kartalari va ochilgan qatorlari
    //
    //  ScrollView OLIB TASHLANDI — aks holda scroll konflikti bo'lardi.
    // ═══════════════════════════════════════════════════════════════════

    /** Home RecyclerView. */
    private var homeRecycler: androidx.recyclerview.widget.RecyclerView? = null

    /** Header View — `findViewById` chaqiruvlari uchun. */
    private var homeHeaderView: View? = null
    private var footerView: View? = null   // ★ FIX144-FOOTER

    /** Home adapteri. */
    private var homeAdapter: HomeAdapter? = null

    /**
     * ★ FIX140-HOMERV: Home uchun item modeli.
     *
     * Har bir item — kartaning bir qismi:
     *   • `Card`         — karta sarlavhasi (bosiladi, ochiladi/yopiladi)
     *   • `ServerRow`    — ochilgan kartaning bir server qatori
     *   • `Hint`         — "Ochish uchun bosing" yoki "Serverlar yo'q"
     */
    private sealed class HomeItem {
        /** Karta sarlavhasi. */
        data class Card(
            val icon: String,
            val title: String,
            val count: Int,
            val subId: String?,
            val sub: com.nurvpn.app.core.Subscription?,
            val expanded: Boolean
        ) : HomeItem()

        /** Ochilgan kartaning server qatori. */
        data class ServerRow(val item: ServerItem) : HomeItem()

        /** Yopiq karta uchun ko'rsatma yoki bo'sh karta xabari. */
        data class Hint(val text: String) : HomeItem()
    }
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

    /** ★ FIX133-HOMEFRIZ: rebuild davomida yaratilgan qator soni. */
    private var rowCreateCount = 0

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX136-HOMEPING: PING PAYTIDA FAQAT MATNNI YANGILASH
    //
    //  ═══ ILDIZ SABAB ═══
    //
    //  Ping jarayonida har 400 ms da shu ishlardi:
    //
    //      ui.postDelayed(pingRebuildRunnable, 400)
    //      → rebuildServerCards(force = true)
    //          → container.removeAllViews()
    //          → 227 ta makeServerRow() = 227 ta inflate()!
    //
    //  227 serverli ping ~10 sekund davom etadi → ~25 marta to'liq
    //  qayta qurish → 5 675 ta `inflate()` → KATTA FRIZ va LAG.
    //
    //  ═══ YECHIM ═══
    //
    //  Har bir qatorning `row_ping` TextView'i `link` bo'yicha
    //  ro'yxatga olinadi. Ping kelganda FAQAT o'sha TextView'ning
    //  matni va rangi yangilanadi — hech qanday View qayta
    //  yaratilmaydi.
    // ═══════════════════════════════════════════════════════════════════

    /** link → qatordagi ping TextView (faqat matn yangilash uchun). */
    private val pingViews = HashMap<String, TextView>()

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX137-ROWCHUNK: QATORLARNI BO'LIB YARATISH
    //
    //  ═══ QURILMADA O'LCHANDI ═══
    //
    //    fillBodyIfNeeded(open:hidashimora2): 1921ms, rows=200
    //    fillBodyIfNeeded(open:hidashimora2): 2017ms, rows=200
    //    fillBodyIfNeeded(open:radikal_hy2):  2099ms, rows=200
    //    Choreographer: Skipped 114/121/135 frames!
    //
    //  200 ta `makeServerRow()` = 200 ta XML `inflate()` ≈ 2 SEKUND.
    //  Va bu HAR OCHISHDA takrorlanardi.
    //
    //  ═══ ILDIZ SABAB ═══
    //
    //  `XML inflate()` — eng qimmat UI operatsiyasi: XML parse qilinadi,
    //  har bir teg uchun Java obyekt yaratiladi, atributlar o'qiladi.
    //  200 marta = 2 sekund bloklanish.
    //
    //  ═══ YECHIM ═══
    //
    //  Qatorlarni BO'LIB yaratamiz: birinchi navbatda 30 ta (ekranga
    //  sig'adi), qolganini keyingi kadrlarda qo'shamiz. Shunda karta
    //  bir zumda ochiladi, friz esa sezilmaydi.
    // ═══════════════════════════════════════════════════════════════════

    /** Bir marta qo'shiladigan qator soni. */
    private val ROW_CHUNK = 30

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX139-ROWCACHE: YARATILGAN QATORLARNI KESHLASH
    //
    //  ═══ O'LCHOV ═══
    //
    //    fillBodyIfNeeded: birinchi 30/200 tayyor, 902ms   ← 1-ochish
    //    fillBodyIfNeeded: birinchi 30/200 tayyor, 176ms   ← 2-ochish
    //
    //  `inflate()` ichki keshi qizigani uchun ikkinchi ochish tezroq.
    //  Lekin baribir 176ms va KEYINGI chunk'lar davom etadi.
    //
    //  ═══ YECHIM ═══
    //
    //  Har bir server uchun yaratilgan View'ni `link` bo'yicha
    //  keshlaymiz. Karta yopilganda View'larni O'CHIRMAYMIZ —
    //  `body` ichida qoldiramiz, faqat `visibility` ni GONE qilamiz.
    //  Qayta ochishda esa mavjud View'lar DARHOL ko'rinadi — 0 ta
    //  `inflate()`!
    // ═══════════════════════════════════════════════════════════════════

    /** link → yaratilgan qator View (qayta ochishda qayta ishlatiladi). */
    private val rowCache = HashMap<String, View>()

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX138b-ROWCHUNK: KADRLAR ORASIDA KECHIKISH + KESH
    //
    //  ═══ O'LCHOV ═══
    //
    //    Avval: `body.post { ... }` ishlatilardi.
    //    `post()` navbatni DARHOL bajaradi (UI thread bo'sh bo'lsa) —
    //    natijada 200 ta qator baribir BITTA siklda qo'shilar va
    //    friz qaytar edi:
    //
    //      fillBodyIfNeeded: birinchi 30/200 tayyor, 556ms
    //      addRowsChunked: 200 qator TAYYOR   ← 1.9s keyin, friz!
    //
    //  ═══ YECHIM ═══
    //
    //  `postDelayed(…, 8)` — har chunk orasida 8ms kutamiz. Shu vaqt
    //  ichida UI thread kadr chizishga ulguradi va foydalanuvchi
    //  kartaning ochilishini KO'RADI, friz sezmaydi.
    //
    //  ═══════════════════════════════════════════════════════════════════

    /** ★ FIX138b-ROWCHUNK: qatorlarni kadrlar orasida qo'shadi. */
    private fun addRowsChunked(
        body: LinearLayout,
        list: List<ServerItem>,
        a: MainActivity,
        startIndex: Int = 0
    ) {
        if (!isAdded) return
        val end = (startIndex + ROW_CHUNK).coerceAtMost(list.size)
        for (i in startIndex until end) {
            body.addView(makeServerRow(list[i], a))
        }
        if (end < list.size) {
            // ★ FIX138b: 8ms kechikish — UI thread kadr chizishga ulguradi
            body.postDelayed({
                if (!isAdded) return@postDelayed
                if (body.parent == null) return@postDelayed   // karta yopilgan
                addRowsChunked(body, list, a, end)
            }, 8)
        } else {
            // ★ FIX139-ROWCACHE: to'liq to'ldirilganini belgilaymiz
            body.getChildAt(0)?.tag = "rows"
            android.util.Log.i("NurVPN-PERF",
                "addRowsChunked: ${list.size} qator TAYYOR")
        }
    }

    /**
     * ★ FIX136-HOMEPING: bitta serverning ping matnini yangilaydi.
     *
     * @return `true` — View topilib yangilandi (rebuild KERAK EMAS).
     */
    private fun updatePingView(item: ServerItem): Boolean {
        val tv = pingViews[item.link] ?: return false
        if (!tv.isAttachedToWindow) return false
        tv.text = pingLabel(item)
        tv.setTextColor(pingColor(item))
        return true
    }

    private val pingRebuildRunnable = Runnable {
        pingUpdateScheduled = false
        if (isAdded) rebuildServerCards(force = true)
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
                speedWave?.visibility = android.view.View.VISIBLE
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

        // ★ FIX140-HOMERV: header View — quyidagi `fv`
        // chaqiruvlari uchun. Pastdagi `rv` blokida to'ldiriladi.
        var hdr: View = v

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX140-HOMERV: SCROLLVIEW → RECYCLERVIEW
        //
        //  ═══ NEGA ═══
        //
        //  Avval Home `ScrollView` + `LinearLayout` edi. Karta ochilganda
        //  200 ta `makeServerRow()` chaqirilardi — har biri XML `inflate()`:
        //
        //      fillBodyIfNeeded(open:radikal_hy2): 1921ms, rows=200
        //      Choreographer: Skipped 135 frames!
        //
        //  Servers bo'limi esa `RecyclerView` ishlatadi va FRIZ YO'Q —
        //  faqat ko'rinadigan 10-15 qatorni yaratadi.
        //
        //  ═══ YECHIM ═══
        //
        //  Endi butun Home bitta `RecyclerView`:
        //
        //      item 0    : Header (traffic, timer, tugmalar, AWG)
        //      item 1..N : Server kartalari va ochilgan qatorlari
        //
        //  `home_scroll_root` id saqlangan — scroll pozitsiyasi kodi
        //  ishlashda davom etadi.
        // ═══════════════════════════════════════════════════════════════
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.home_scroll_root)
        if (rv != null) {
            // ★ FIX140-HOMERV: LayoutManager AVVAL qo'yiladi!
            //
            //  ═══ XATO ═══
            //
            //  Header `inflate(..., rv, false)` bilan yaratilsa, `rv`
            //  parent bo'ladi. Lekin RecyclerView'da hali LayoutManager
            //  yo'q — natijada qurilmada CRASH:
            //
            //      IllegalStateException: RecyclerView has no LayoutManager
            //      at RecyclerView.generateLayoutParams(RecyclerView.java:5029)
            //
            //  ═══ YECHIM ═══
            //
            //  1) LayoutManager'ni BIRINCHI qo'yamiz.
            //  2) Header'ni `null` parent bilan inflate qilamiz
            //     (u Adapter ichida qo'lda joylashtiriladi).
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                requireContext())
            rv.setHasFixedSize(false)
            rv.itemAnimator = null          // animatsiya friz qo'shmaydi

            // ★ FIX142-LP: LayoutParams MAJBURAN beriladi!
            //
            //  ═══ MUAMMO ═══
            //
            //  QURILMADA O'LCHANDI:
            //      CREATE_VH type=0 parentW=720 parentH=1248 rvW=720
            //      karta fon: x=110..570  (475px)  ← 720px ekranda!
            //
            //  Sabab: `inflate(..., null, false)` bilan yaratilgan View'da
            //  `LayoutParams` BO'LMAYDI. RecyclerView unga
            //  `generateDefaultLayoutParams()` beradi — bu
            //  `WRAP_CONTENT`! Natijada karta kontenti qadar torayadi.
            //
            //  ═══ YECHIM ═══
            //
            //  Har bir item uchun `RecyclerView.LayoutParams` ni
            //  MAJBURAN `MATCH_PARENT` qilib beramiz.
            // ═══════════════════════════════════════════════════════════
            val header = inflater.inflate(R.layout.item_home_header, null, false)
            header.layoutParams = androidx.recyclerview.widget
                .RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            homeHeaderView = header
            hdr = header   // ★ FIX140: findViewById uchun

            homeAdapter = HomeAdapter(header)
            rv.adapter = homeAdapter
            rv.addOnScrollListener(object : androidx.recyclerview.widget
                .RecyclerView.OnScrollListener() {
                override fun onScrolled(r: androidx.recyclerview.widget.RecyclerView,
                                        dx: Int, dy: Int) {
                    android.util.Log.i("NurVPN-SCROLL",
                        "HOME onScroll: y=${r.computeVerticalScrollOffset()} " +
                            "hidden=$isHidden items=${homeAdapter?.itemCount}")
                }
            })
            homeRecycler = rv
        }

        connectBtn = fv(R.id.connect_btn)
        connectIcon = fv(R.id.connect_icon)
        statusText = fv(R.id.status_text)
        serverFlag = fv(R.id.server_flag)
        serverName = fv(R.id.server_name)
        serverProto = fv(R.id.server_proto)
        pingText = fv(R.id.ping_text)
        timerText = fv(R.id.timer_text)
        downText = fv(R.id.down_text)
        upText = fv(R.id.up_text)
        speedWave = fv(R.id.speed_wave)
        speedWave?.visibility = android.view.View.VISIBLE
        speedWave?.setConnected(TunnelState.isConnected)

        // ★ FIX140-HOMERV: `server_cards_container` YO'Q — endi
        // kartalar RecyclerView adapteri orqali chiziladi.
        bodyAwg = fv(R.id.body_awg)
        awgArrow = fv(R.id.awg_arrow)
        awgCount = fv(R.id.awg_count)

        // ═══ Tanlash rejimi paneli ═══
        homeSelectionBar = fv(R.id.home_selection_bar)
        homeSelCountText = fv(R.id.home_sel_count)
        fv<View>(R.id.home_sel_delete)?.setOnClickListener { deleteHomeSelected() }
        fv<View>(R.id.home_sel_cancel)?.setOnClickListener { exitHomeSelectMode() }
        fv<View>(R.id.home_sel_select_all)?.setOnClickListener { selectAllHome() }

        ai = AIServerSelector.get(requireContext().applicationContext)

        connectBtn?.setOnClickListener { toggleConnection() }
        fv<View>(R.id.btn_ping_home)?.setOnClickListener {
            // UI thread'da darhol javob
            it.isEnabled = false
            it.alpha = 0.5f
            it.postDelayed({ it.isEnabled = true; it.alpha = 1f }, 800)
            pingHomeAll()
        }

        // ══ FIX_V1122_WARP_LINKS ══
        //  🌐 (btn_globe) endi GitHub emas — MASQUE WARP Generator
        //  havolalari ro'yxatini ochadi. Har bir havola tizim brauzerida
        //  (Intent.ACTION_VIEW) ochiladi, pastda esa 3 tilli ogohlantirish.
        //
        //  Nega BottomSheet: Material 3 uslubiga mos, ro'yxat scroll
        //  bo'ladi (kichik ekranda 4 ta havola + ogohlantirish sig'masligi
        //  mumkin), va `AlertDialog` kabi "og'ir" ko'rinmaydi.
        //
        //  Nega `parent`/`resources`: `context` Fragment detach bo'lgach
        //  null bo'lishi mumkin — dialog fonda ochilsa crash bo'lardi.
        fv<View>(R.id.btn_globe)?.setOnClickListener {
            val hostCtx = context ?: return@setOnClickListener
            if (!isAdded) return@setOnClickListener
            showWarpGeneratorSheet(hostCtx)
        }
fv<View>(R.id.header_awg)?.setOnClickListener { toggleAwg() }

        // 📶 AWG ping tugmasi (karta sarlavhasida)
        val awgHeaderPing = fv<android.view.ViewGroup>(R.id.header_awg)
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
        val awgHeader = fv<android.view.ViewGroup>(R.id.header_awg)
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

        fv<View>(R.id.chip_ai)?.setOnClickListener { runAIAnalysis() }
        fv<View>(R.id.chip_fast)?.setOnClickListener { fastConnect() }

        fv<View>(R.id.btn_add)?.setOnClickListener { showAddDialog() }
        fv<View>(R.id.btn_paste)?.setOnClickListener { pasteFromClipboard() }
        fv<View>(R.id.btn_scan)?.setOnClickListener {
            val i = Intent(requireContext(), QrScanActivity::class.java)
            startActivityForResult(i, REQ_QR_SCAN)
        }

        refresh()

        // ═══ Versiyani PackageManager dan qo'shish ═══
        try {
            val titleView = fv<TextView>(R.id.app_title_view)
            if (titleView != null) {
                val ver = try {
                    requireContext().packageManager
                        .getPackageInfo(requireContext().packageName, 0)
                        .versionName ?: "?"
                } catch (_: Throwable) { "?" }
                titleView.text = getString(R.string.app_title) + " v" + ver
            }
        } catch (_: Throwable) {}

        return v
    }

    private fun toggleAwg() {
        awgExpanded = !awgExpanded
        bodyAwg?.visibility = if (awgExpanded) View.VISIBLE else View.GONE
        awgArrow?.text = if (awgExpanded) "\u2B06" else "\u2B07"
        if (awgExpanded) rebuildAwgList()
    }

    /** Barcha subscription'larni qayta yuklash. */
    /** FIX: Bitta obunani yangilash (MainActivity navbati uchun public). */
    fun refreshOneSub(subId: String) {
        val a = activity as? MainActivity ?: return
        val sub = a.subscriptions.find { it.id == subId } ?: return
        android.util.Log.i("NurVPN-REFRESH", "refreshOneSub: ${sub.name}")
        loadSubscription(sub.url, sub.name)
    }

    fun refreshSubscriptions() {
        val a = activity as? MainActivity ?: return
        if (a.subscriptions.isEmpty()) return
        for (sub in a.subscriptions) {
            loadSubscription(sub.url, sub.name)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX132-LAG: FAQAT KO'RSATISHNI YANGILASH (TARMOQSIZ)
    //
    //  ═══ ILDIZ SABAB ═══
    //
    //  Avval ochiq manba yoqilganda `refreshSubscriptions()` chaqirilardi
    //  va u BARCHA obunalar uchun HTTP so'rov yuborardi:
    //
    //      for (sub in a.subscriptions) {
    //          loadSubscription(sub.url, sub.name)   // ← TARMOQ!
    //      }
    //
    //  5-10 ta obuna × HTTP so'rov → asosiy ekranda KATTA FRIZ va LAG.
    //  Bo'sh URL bo'lsa → `java.net.URL("")` → CRASH.
    //
    //  ═══ YECHIM ═══
    //
    //  Bu metod HECH QANDAY tarmoq so'rovi qilmaydi — faqat mavjud
    //  ma'lumotdan kartalarni qayta chizadi (arzon, kesh bilan).
    //  Yangi obuna qo'shilganda uning serverlarini yuklash uchun
    //  `loadSubscription()` alohida chaqiriladi.
    // ═══════════════════════════════════════════════════════════════════

    /** ★ FIX132-LAG: kartalarni qayta chizadi — TARMOQQA TEGMAYDI. */
    fun refreshHomeCards() {
        if (!isAdded || view == null) return
        try {
            rebuildServerCards(force = true)
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "refreshHomeCards xato", t)
        }
    }

    /** Yuklanmagan subscriptions'larni yuklash (onResume dan chaqiriladi). */
    private fun autoLoadPendingSubscriptions() {
        try {
            if (!isAdded) return
            val a = activity as? MainActivity ?: return
            // ★ v1.13: 24 soatdan eski obunalar ham yangilanadi.
            val staleMs = 24L * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            val pending = a.subscriptions.filter {
                val never = it.lastUpdated == 0L
                val stale = it.lastUpdated > 0L && (now - it.lastUpdated) > staleMs
                (never || stale) && attemptedSubs.add(it.url)
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
        // Har obunaning order + sortMode + traffic ma'lumotlari
        for (sub in a.subscriptions) {
            h = h * 31 + sub.order
            h = h * 31 + sub.sortMode.hashCode()
            h = h * 31 + sub.trafficTotal.toInt()      // ★ YANGI
            h = h * 31 + sub.trafficUsed.toInt()       // ★ YANGI
            h = h * 31 + sub.expireAt.toInt()          // ★ YANGI
            h = h * 31 + sub.serverCount                // ★ YANGI
        }
        // Favorite serverlar soni
        var favCount = 0
        for (srv in a.servers) if (srv.favorite) favCount++
        h = h * 31 + favCount
        return h
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX140-HOMERV: ADAPTER UCHUN MA'LUMOT QURISH
    //
    //  ═══ AVVAL ═══
    //
    //  `container.removeAllViews()` + 200 ta `makeServerRow()`:
    //
    //      fillBodyIfNeeded(open:radikal_hy2): 1921ms, rows=200
    //      Choreographer: Skipped 135 frames!
    //
    //  ═══ ENDI ═══
    //
    //  Faqat YENGIL ma'lumot ro'yxati quriladi (`HomeItem`), View'lar
    //  esa RecyclerView tomonidan KERAK BO'LGANDA yaratiladi.
    //
    //  Bu amal ~1ms — 2000× tezroq, chunki hech qanday `inflate()`
    //  chaqirilmaydi.
    // ═══════════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX144-FOOTER: `fv` — header YOKI footer'dan View topadi
    //
    //  ═══ NEGA KERAK ═══
    //
    //  FIX144 da AWG kartasi va pastki tugmalar (`btn_add/paste/scan`)
    //  `item_home_header.xml` dan `item_home_footer.xml` ga ko'chirildi —
    //  chunki header 824dp (1648px) bo'lib ekranni to'ldirib, server
    //  kartalari ko'rinmay qolgan edi.
    //
    //  Endi ular FOOTER item'ida. Lekin `onCreateView` da footer hali
    //  yaratilmagan (uni RecyclerView keyin yaratadi) — shuning uchun
    //  `hdr.findViewById(R.id.btn_add)` null qaytaradi.
    //
    //  ═══ YECHIM ═══
    //
    //  `fv()` avval header'dan, topilmasa footer'dan qidiradi.
    //  Footer hali yo'q bo'lsa — `post {}` orqali keyinroq uriniladi.
    // ═══════════════════════════════════════════════════════════════════
    private fun <T : View> fv(id: Int): T? {
        homeHeaderView?.findViewById<T>(id)?.let { return it }
        footerView?.findViewById<T>(id)?.let { return it }
        return null
    }

    private fun rebuildServerCards(force: Boolean = false) {
        val a = activity as? MainActivity ?: return
        val adapter = homeAdapter ?: return
        val recycler = homeRecycler ?: return

        // ═══ Kesh tekshiruvi ═══
        if (!force) {
            val hash = computeDataHash(a)
            if (hash == lastRebuildHash && adapter.itemCount > 1) {
                return
            }
            lastRebuildHash = hash
        }

        val t0 = System.currentTimeMillis()
        pingViews.clear()

        val out = ArrayList<HomeItem>()

        // ═══ 0. SEVIMLILAR KARTASI ═══
        val favServers = a.servers.filter { it.favorite }
        if (favServers.isNotEmpty()) {
            val exp = "fav_card" in homeExpandedSubs
            out.add(HomeItem.Card("\u2B50", getString(R.string.fav_card_title),
                favServers.size, "fav_card", null, exp))
            if (exp) {
                for (si in favServers) out.add(HomeItem.ServerRow(si))
            } else {
                out.add(HomeItem.Hint(getString(R.string.text_expand_to_view,
                    favServers.size)))
            }
        }

        // ═══ 1. HAR BIR SUBSCRIPTION ═══
        val sortedSubs = a.subscriptions.sortedBy { it.order }
        val serversBySub = a.servers.groupBy { it.subId }
        for (sub in sortedSubs) {
            if (sub.hiddenOnHome) continue
            val rawList = serversBySub[sub.id] ?: emptyList()
            val list = sortServers(rawList, sub.sortMode)
            val exp = sub.id in homeExpandedSubs
            out.add(HomeItem.Card("\uD83D\uDCE1", sub.name, list.size,
                sub.id, sub, exp))
            when {
                list.isEmpty() ->
                    out.add(HomeItem.Hint(getString(R.string.text_no_servers)))
                exp -> for (si in list) out.add(HomeItem.ServerRow(si))
                else -> out.add(HomeItem.Hint(
                    getString(R.string.text_expand_to_view, list.size)))
            }
        }

        // ═══ 2. QO'LDA QO'SHILGAN ═══
        val manualRaw = a.servers.filter { it.subId == null }
        val manual = ManualSortStore.sort(manualRaw,
            ManualSortStore.getMode(requireContext()))
        if (manual.isNotEmpty()) {
            val exp = "manual" in homeExpandedSubs
            out.add(HomeItem.Card("\uD83D\uDD27",
                getString(R.string.manual_added), manual.size, "manual", null, exp))
            if (exp) {
                for (si in manual) out.add(HomeItem.ServerRow(si))
            } else {
                out.add(HomeItem.Hint(
                    getString(R.string.text_expand_to_view, manual.size)))
            }
        }

        // ═══ 3. BO'SH HOLAT ═══
        if (out.isEmpty()) {
            out.add(HomeItem.Hint(getString(R.string.text_no_servers_add)))
        }

        adapter.submit(out)

        val __ms = System.currentTimeMillis() - t0
        if (__ms > 16) {
            android.util.Log.w("NurVPN-PERF",
                "⚠️ rebuildServerCards SEKIN: ${__ms}ms, " +
                    "items=${out.size}, servers=${a.servers.size}, " +
                    "expanded=${homeExpandedSubs.size}")
        } else {
            android.util.Log.i("NurVPN-PERF",
                "rebuildServerCards: ${__ms}ms, items=${out.size} " +
                    "(RecyclerView — View'lar talab bo'yicha)")
        }
        // RecyclerView'ni boshiga suramiz (karta ochilganda ko'rinishi uchun)
        if (force) recycler.post { adapter.notifyDataSetChanged() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX140-HOMERV: KARTANI OCHISH/YOPISH
    //
    //  Faqat `homeExpandedSubs` to'plamini o'zgartiradi va Adapter
    //  ma'lumotlarini qayta quradi. View'lar YARATILMAYDI —
    //  RecyclerView faqat ko'rinadiganlarini yangilaydi.
    // ═══════════════════════════════════════════════════════════════════
    private fun toggleHomeCard(subId: String?) {
        if (subId == null) return
        val adapter = homeAdapter ?: return
        val recycler = homeRecycler ?: return
        val t0 = System.currentTimeMillis()
        if (subId in homeExpandedSubs) {
            homeExpandedSubs.remove(subId)
        } else {
            homeExpandedSubs.add(subId)
        }
        // Karta pozitsiyasini eslab qolamiz — scroll sakrab ketmasin
        val pos = adapter.posOfCard(subId)
        lastRebuildHash = -1          // keshni bekor qilamiz
        rebuildServerCards(force = true)
        val ms = System.currentTimeMillis() - t0
        android.util.Log.i("NurVPN-PERF",
            "toggleHomeCard($subId): ${ms}ms, exp=${subId in homeExpandedSubs}, " +
                "items=${adapter.itemCount}, pos=$pos")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX140-HOMERV: SERVER QATORI UCHUN UZOQ BOSISH MENYUSI
    // ═══════════════════════════════════════════════════════════════════
    // ★ FIX140-HOMERV: uzun bosish menyusi — mavjud `showHomeMenu` ni
    // ishlatamiz (u allaqachon barcha amallarni qo'llab-quvvatlaydi).
    private fun showHomeServerMenu(si: ServerItem) {
        val a = activity as? MainActivity ?: return
        showHomeMenu(si, a)
    }

    /** ★ FIX140-HOMERV: sevimlilar kartasi uchun ping. */
    private fun pingFavorites() {
        val a = activity as? MainActivity ?: return
        val favs = a.servers.filter { it.favorite }
        if (favs.isEmpty()) {
            Toast.makeText(context, R.string.text_no_servers,
                Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context,
            getString(R.string.ping_sub_started,
                getString(R.string.fav_card_title), favs.size),
            Toast.LENGTH_SHORT).show()
        PingTester.testAll(favs, object : PingTester.Listener {
            override fun onPingUpdate(item: ServerItem, ping: Int) {
                ui.post { updatePingView(item) }
            }
            override fun onAllDone() {
                pingRunning = false
                if (!isAdded) return
                val a2 = activity as? MainActivity ?: return
                ServerStore.save(a2, a2.servers)
                Toast.makeText(context, R.string.ping_done,
                    Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX140-HOMERV: HOME ADAPTERI
    //
    //  Servers bo'limidagi `ServerAdapter` naqshi asosida.
    //
    //  ═══ ITEM TURLARI ═══
    //
    //    TYPE_HEADER — butun Home header'i (traffic, timer, tugmalar,
    //                  AWG kartasi). BITTA nusxa, `onCreateView` da
    //                  tayyorlanadi.
    //
    //    TYPE_CARD   — karta sarlavhasi (bosiladi, ochiladi/yopiladi)
    //
    //    TYPE_ROW    — ochilgan kartaning bir server qatori
    //
    //    TYPE_HINT   — "Ochish uchun bosing" / "Serverlar yo'q"
    //
    //  ═══ NEGA BU FRIZNI YO'Q QILADI ═══
    //
    //  RecyclerView faqat KO'RINADIGAN itemlarni yaratadi (odatda 10-15).
    //  Karta ochilganda 200 ta View emas, faqat ekranga sig'adiganlari
    //  yaratiladi. Scroll paytida esa View'lar QAYTA ISHLATILADI
    //  (`onBindViewHolder` mavjud View'ga yangi matn yozadi).
    // ═══════════════════════════════════════════════════════════════════
    private inner class HomeAdapter(
        private val headerView: View
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = ArrayList<HomeItem>()
        private var cardIndex = LinkedHashMap<String, Int>()

        private val TYPE_HEADER = 0
        private val TYPE_CARD = 1
        private val TYPE_ROW = 2
        private val TYPE_HINT = 3
        private val TYPE_FOOTER = 4   // ★ FIX144-FOOTER

        /** Itemlar ro'yxatini almashtiradi va qayta chizadi. */
        fun submit(list: List<HomeItem>) {
            items.clear()
            items.addAll(list)
            cardIndex.clear()
            for ((i, it) in items.withIndex()) {
                if (it is HomeItem.Card) cardIndex[it.subId ?: "fav_card"] = i + 1
            }
            notifyDataSetChanged()
        }

        fun itemAt(pos: Int): HomeItem? = items.getOrNull(pos - 1)

        /** `subId` bo'yicha adapter pozitsiyasini topadi (+1: header). */
        fun posOfCard(subId: String): Int = cardIndex[subId] ?: -1

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return TYPE_HEADER
            // ★ FIX144-FOOTER: oxirgi pozitsiya — footer (AWG + tugmalar)
            if (position == items.size + 1) return TYPE_FOOTER
            return when (items[position - 1]) {
                is HomeItem.Card -> TYPE_CARD
                is HomeItem.ServerRow -> TYPE_ROW
                is HomeItem.Hint -> TYPE_HINT
            }
        }

        override fun getItemCount(): Int = items.size + 2   // +1 header, +1 footer

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int):
            RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            // ★ FIX142-LP: har bir itemga MATCH_PARENT kenglik beramiz.
            // `inflate(..., parent, false)` RecyclerView.LayoutParams
            // berishi kerak, lekin ishonch uchun majburlaymiz.
            fun lp(v: View): View {
                v.layoutParams = androidx.recyclerview.widget
                    .RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                return v
            }
            return when (viewType) {
                TYPE_CARD -> CardVH(lp(inf.inflate(
                    R.layout.item_home_card_header, parent, false)))
                TYPE_ROW -> RowVH(lp(inf.inflate(
                    R.layout.item_home_row, parent, false)))
                TYPE_HINT -> HintVH(lp(inf.inflate(
                    R.layout.item_home_hint, parent, false)))
                // ★ FIX144-FOOTER: AWG karta + pastki tugmalar
                TYPE_FOOTER -> FooterVH(footerView
                    ?: lp(inf.inflate(R.layout.item_home_footer, null, false))
                        .also { footerView = it })
                else -> HeaderVH(headerView)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            if (pos == 0) return                      // header — tayyor View
            // ★ FIX144-FOOTER: footer — tayyor View (AWG + tugmalar)
            if (holder is FooterVH) return
            val idx = pos - 1
            val it = items[idx]

            // ═══════════════════════════════════════════════════════════
            //  ★ FIX141-CARDSTYLE: KARTA GURUHINI VIZUAL BIRLASHTIRISH
            //
            //  ═══ MUAMMO ═══
            //
            //  RecyclerView'da karta sarlavhasi va uning qatorlari
            //  ALOHIDA item. Har biriga `bg_card` (16dp yumaloq +
            //  chegara) qo'yilsa, HAR BIR QATOR alohida karta bo'lib
            //  ko'rinadi.
            //
            //  ═══ GURUH TUZILISHI ═══
            //
            //      [Card]  ← guruh boshi (yuqori burchak yumaloq)
            //      [Row]   ← o'rta
            //      [Row]   ← o'rta
            //      [Hint]  ← guruh oxiri (pastki burchak yumaloq)
            //
            //  ═══ ANIQLASH ═══
            //
            //    guruhBoshi = item — `Card` (yoki ro'yxat boshi)
            //    guruhOxiri = keyingi item `Card` (yoki ro'yxat oxiri)
            //
            //  ⚠️ AVVALGI XATO: `items[idx-1] !is Card` ishlatilgan edi —
            //  bu "oldingi item karta emas" degani, ya'ni guruh O'RTASI
            //  ham "boshi" deb hisoblanardi va sarlavha fon olmasdi.
            // ═══════════════════════════════════════════════════════════
            val guruhBoshi = it is HomeItem.Card || idx == 0
            val guruhOxiri = idx == items.size - 1 ||
                items[idx + 1] is HomeItem.Card

            val bgRes = when {
                guruhBoshi && guruhOxiri -> R.drawable.bg_card
                guruhBoshi -> R.drawable.bg_card_top
                guruhOxiri -> R.drawable.bg_card_bottom
                else -> R.drawable.bg_card_mid
            }
            // ═══════════════════════════════════════════════════════
            //  ★ FIX141c: MARGIN ENDI XML'DA
            //
            //  Avval margin shu yerda (kodda) o'rnatilardi, lekin
            //  qurilmada karta TOR bo'lib qolardi:
            //
            //      karta: x=80..535  (455px)  ← 720px ekranda!
            //
            //  Sabab: `layoutParams` ni kodda o'zgartirganda
            //  RecyclerView item o'lchamini QAYTA HISOBLAMAYDI.
            //
            //  Endi margin `item_home_*.xml` fayllarida berilgan —
            //  `inflate()` paytida to'g'ri o'lchanadi.
            //
            //  Bu yerda faqat FON qo'yiladi (guruh holatiga qarab).
            // ═══════════════════════════════════════════════════════
            holder.itemView.setBackgroundResource(bgRes)



            when (it) {
                is HomeItem.Card -> (holder as CardVH).bind(it)
                is HomeItem.ServerRow -> (holder as RowVH).bind(it.item)
                is HomeItem.Hint -> (holder as HintVH).bind(it.text)
            }
        }
    }

    /** Header — tayyor View, hech narsa qilmaydi. */
    private inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)

    /** ★ FIX144-FOOTER: AWG kartasi + pastki tugmalar (oxirgi item). */
    private inner class FooterVH(v: View) : RecyclerView.ViewHolder(v) {
        init {
            // Footer view'i endi mavjud — AWG va tugmalarni bog'laymiz.
            // `onCreateView` paytida footer hali yo'q edi, shuning uchun
            // bog'lanishlar shu yerda o'rnatiladi.
            wireFooter(v)
        }
    }

    /**
     * ★ FIX144-FOOTER: footer view'idagi AWG kartasi va pastki
     * tugmalarni kodga bog'laydi. `onCreateView` dagi bir xil
     * bog'lanishlar footer yaratilganda QAYTA o'rnatiladi.
     */
    private fun wireFooter(fdr: View) {
        bodyAwg = fdr.findViewById(R.id.body_awg)
        awgArrow = fdr.findViewById(R.id.awg_arrow)
        awgCount = fdr.findViewById(R.id.awg_count)

        fdr.findViewById<View>(R.id.header_awg)?.setOnClickListener { toggleAwg() }

        // 📶 AWG ping tugmasi
        val awgHeaderPing = fdr.findViewById<android.view.ViewGroup>(R.id.header_awg)
        if (awgHeaderPing != null &&
            awgHeaderPing.findViewWithTag<View>("awg_ping") == null) {
            val dp = resources.displayMetrics.density
            val pingBtn = TextView(requireContext()).apply {
                tag = "awg_ping"
                text = "\uD83D\uDCF6"
                setTextColor(androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.accent))
                textSize = 14f
                setPadding((8 * dp).toInt(), (8 * dp).toInt(),
                    (8 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            pingBtn.setOnClickListener {
                it.isEnabled = false
                it.postDelayed({ it.isEnabled = true }, 500)
                pingAwgHome()
            }
            awgHeaderPing.addView(pingBtn)
        }

        // ⚙️ AWG Config sozlamalari
        val awgHeader = fdr.findViewById<android.view.ViewGroup>(R.id.header_awg)
        if (awgHeader != null &&
            awgHeader.findViewWithTag<View>("awg_settings") == null) {
            val dp = resources.displayMetrics.density
            val settingsBtn = TextView(requireContext()).apply {
                tag = "awg_settings"
                text = "\u2699"
                setTextColor(androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.accent))
                textSize = 16f
                setPadding((8 * dp).toInt(), (8 * dp).toInt(),
                    (8 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            settingsBtn.setOnClickListener {
                it.isEnabled = false
                it.postDelayed({ it.isEnabled = true }, 300)
                showAwgSettingsDialog()
            }
            awgHeader.addView(settingsBtn)
        }

        fdr.findViewById<View>(R.id.btn_add)?.setOnClickListener { showAddDialog() }
        fdr.findViewById<View>(R.id.btn_paste)?.setOnClickListener { pasteFromClipboard() }
        fdr.findViewById<View>(R.id.btn_scan)?.setOnClickListener {
            startActivityForResult(
                Intent(requireContext(), QrScanActivity::class.java), REQ_QR_SCAN)
        }
        rebuildAwgList()
    }

    /** Karta sarlavhasi. */
    private inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        init {
        }
        fun bind(c: HomeItem.Card) {
            itemView.findViewById<TextView>(R.id.card_icon).text = c.icon
            itemView.findViewById<TextView>(R.id.card_title).text = c.title
            itemView.findViewById<TextView>(R.id.card_count).text =
                c.count.toString()
            itemView.findViewById<TextView>(R.id.card_arrow).text =
                if (c.expanded) "\u2B06" else "\u2B07"

            bindSubInfo(c)

            // 📶 Ping tugmasi
            val pingBtn = itemView.findViewById<TextView>(R.id.card_ping)
            if (c.subId != null && c.subId != "awg_card") {
                pingBtn.visibility = View.VISIBLE
                pingBtn.setOnClickListener {
                    when (c.subId) {
                        "fav_card" -> pingFavorites()
                        else -> pingSubscriptionHome(c.subId, c.title)
                    }
                }
            } else {
                pingBtn.visibility = View.GONE
                pingBtn.setOnClickListener(null)
            }

            // ⚙️ Sozlamalar tugmasi (haqiqiy sozlamalar: showSubSettingsDialog)
            val setBtn = itemView.findViewById<TextView>(R.id.card_settings)
            if (c.subId != null && c.subId != "fav_card") {
                setBtn.visibility = View.VISIBLE
                setBtn.setOnClickListener { showSubSettingsDialog(c.subId, c.title) }
            } else {
                setBtn.visibility = View.GONE
                setBtn.setOnClickListener(null)
            }

            itemView.setOnClickListener {
                toggleHomeCard(c.subId)
            }
            itemView.setOnLongClickListener {
                if (c.subId != null && c.subId != "fav_card") {
                    showHomeSubMenu(c.subId, c.title); true
                } else false
            }
        }

        /**
         * ★ FIX149-SUBINFO: karta sarlavhasidagi metama'lumot qatorlarini
         * to'ldiradi. Ma'lumot yo'q bo'lsa element `GONE` — karta O'SMАЙДИ.
         *
         *  ═══ KO'RSATILADIGAN MA'LUMOT ═══
         *
         *   3-qator : "📅 25 kun qoldi  |  📊 7.62 / 210.63 GB"
         *             (muddati tugagan bo'lsa — "📅 Muddati tugagan")
         *   4-qator : "🌐 domen.com"  +  "🛠 support"
         *   5-qator : "📢 e'lon matni"
         */
        /**
         * ═══════════════════════════════════════════════════════════
         *  ★ FIX150-INCYSTYLE: obuna kartasi metama'lumotlari.
         * ═══════════════════════════════════════════════════════════
         *
         *  ═══ KO'RSATILADIGAN 4 QISM ═══
         *
         *    1) KUN + TRAFIK  ── "Осталось: 1 дн."     "0 B / 50 GB"
         *    2) PROGRESS BAR  ── ishlatilish foizi (4dp chiziq)
         *    3) HAVOLALAR     ── [🛠 Поддержка]  [🌐 Сайт]
         *    4) E'LON         ── sariq ogohlantirish bloki
         *
         *  ⚠️ Har bir qism O'Z mustaqil ko'rinishini boshqaradi —
         *     ma'lumot yo'q bo'lsa `View.GONE`, karta O'SMАЙДИ.
         *     Bu RecyclerView uchun muhim: qayta ishlatilayotgan
         *     View'da eski ma'lumot QOLMASLIGI kerak.
         */
        private fun bindSubInfo(c: HomeItem.Card) {
            val rowTv = itemView.findViewById<View>(R.id.card_info_row)
            val daysTv = itemView.findViewById<TextView>(R.id.card_info_days)
            val trafficTv = itemView.findViewById<TextView>(R.id.card_info_traffic)
            val progBar = itemView.findViewById<android.widget.ProgressBar>(
                R.id.card_info_progress)
            @Suppress("UNUSED_VARIABLE")
            val linksTv = itemView.findViewById<TextView>(R.id.card_info_links)
            val annTv = itemView.findViewById<TextView>(R.id.card_info_announce)
            val sub = c.sub

            // ═══════════════════════════════════════════════════════
            //  QISM 1: KUN + TRAFIK
            // ═══════════════════════════════════════════════════════
            var hasDays = false
            var hasTraffic = false

            if (sub != null && sub.expireAt > 0) {
                hasDays = true
                val days = sub.daysLeft()
                daysTv.text = when {
                    days < 0 -> getString(R.string.sub_expired)
                    days == 0 -> getString(R.string.sub_days_left_one)
                    else -> getString(R.string.sub_days_left, days)
                }
            }

            if (sub != null && sub.trafficTotal > 0) {
                hasTraffic = true
                trafficTv.text = getString(
                    R.string.sub_bytes_used,
                    bytesShort(sub.trafficUsed),
                    bytesShort(sub.trafficTotal))
            }

            // Ko'rinish: kun YOKI trafik bo'lsa qator ko'rinadi
            if (hasDays || hasTraffic) {
                rowTv.visibility = View.VISIBLE
                daysTv.visibility = if (hasDays) View.VISIBLE else View.GONE
                trafficTv.visibility = if (hasTraffic) View.VISIBLE else View.GONE
                // Faqat trafik bo'lsa — kun o'rniga "cheksiz" yozuvi
                if (!hasDays && hasTraffic && sub != null && sub.expireAt <= 0) {
                    daysTv.text = getString(R.string.sub_no_limit)
                    daysTv.visibility = View.VISIBLE
                }
            } else {
                rowTv.visibility = View.GONE
            }

            // ═══════════════════════════════════════════════════════
            //  QISM 2: PROGRESS BAR (faqat cheklangan trafikda)
            // ═══════════════════════════════════════════════════════
            if (sub != null && sub.trafficTotal > 0) {
                val pct = sub.trafficPercent().coerceIn(0, 100)
                progBar.visibility = View.VISIBLE
                progBar.progress = pct
                // 90%+ — qizil ogohlantirish
                val tint = when {
                    pct >= 90 -> 0xFFC0392B.toInt()      // danger
                    pct >= 75 -> 0xFFB8860B.toInt()      // warning
                    else -> 0xFF5A7A00.toInt()           // accent
                }
                progBar.progressTintList =
                    android.content.res.ColorStateList.valueOf(tint)
            } else {
                progBar.visibility = View.GONE
            }

            // ═══════════════════════════════════════════════════════
            //  QISM 3: HAVOLALAR (ikki alohida TUGMA)
            // ═══════════════════════════════════════════════════════
            //  ★ FIX152-INCYPOLISH: avval bitta TextView edi
            //    ("🛠 Поддержка • 🌐 Сайт") — tugmaga o'xshamasdi.
            //    Endi ikkita alohida TextView, har biri o'z foniga ega
            //    (`bg_chip.xml`, 20dp radius) va accent rangda.
            //    Bo'sh bo'lsa `View.GONE` — karta o'smaydi.
            val btnRow = itemView.findViewById<View>(R.id.card_info_btn_row)
            val supBtn = itemView.findViewById<TextView>(R.id.card_info_btn_support)
            val webBtn = itemView.findViewById<TextView>(R.id.card_info_btn_web)

            val hasSupport = sub != null && sub.supportShort().isNotEmpty()
            val hasWeb = sub != null && sub.webPageShort().isNotEmpty()

            if (hasSupport || hasWeb) {
                btnRow.visibility = View.VISIBLE
                supBtn.visibility = if (hasSupport) View.VISIBLE else View.GONE
                webBtn.visibility = if (hasWeb) View.VISIBLE else View.GONE
                supBtn.text = "\uD83D\uDEE0 " + getString(R.string.sub_btn_support)
                webBtn.text = "\uD83C\uDF10 " + getString(R.string.sub_btn_web)

                // ★ FIX153: TUGMALAR ENDI BOSILADI.
                //   URL bo'sh bo'lsa — hech narsa qilmaydi
                //   (tugma baribir GONE bo'ladi, lekin himoya
                //   RecyclerView qayta ishlatishida muhim).
                //
                //   ⚠️ Karta bosilishi (`toggleHomeCard`) buzilmaydi:
                //      bu listener karta ildizidagi listenerdan
                //      CHUQURROQ, va oradagi view'larda `clickable`
                //      yo'q — hodisa yuqoriga chiqmaydi.
                supBtn.setOnClickListener {
                    val u = sub?.supportUrl.orEmpty()
                    if (u.isNotBlank()) openSubUrl(u)
                }
                webBtn.setOnClickListener {
                    val u = sub?.webPage.orEmpty()
                    if (u.isNotBlank()) openSubUrl(u)
                }
            } else {
                btnRow.visibility = View.GONE
            }
            // Eski qator endi ishlatilmaydi (ID saqlanadi — crash bo'lmasin)
            linksTv.visibility = View.GONE

            // ═══════════════════════════════════════════════════════
            //  QISM 4: E'LON (sariq ogohlantirish)
            // ═══════════════════════════════════════════════════════
            val ann = sub?.announce.orEmpty()
            itemView.findViewById<View>(R.id.info_panel).visibility =
                if (ann.isBlank()) View.GONE else View.VISIBLE
            itemView.findViewById<View>(R.id.info_panel).visibility =
                if (ann.isBlank()) View.GONE else View.VISIBLE
            itemView.findViewById<View>(R.id.info_panel).visibility =
                if (ann.isBlank()) View.GONE else View.VISIBLE
            itemView.findViewById<View>(R.id.info_panel).visibility =
                if (ann.isBlank()) View.GONE else View.VISIBLE
            if (ann.isBlank()) {
                annTv.visibility = View.GONE
            } else {
                // ★ FIX151-ANNOUNCE: xabar TO'LIQ ko'rsatiladi.
                //   Avval "\u26A0\uFE0F " prefiksi qo'shilar va matn
                //   3 qatorda kesilardi. Endi prefiks YO'Q (fon allaqachon
                //   ogohlantirish rangi) va qatorlar cheklanmagan.
                annTv.visibility = View.VISIBLE
                annTv.text = ann
            }
        }

        /**
         * ★ FIX153: obuna havolasini brauzerda ochadi.
         *
         *   Support/Sayt tugmalari bosilganda chaqiriladi.
         *
         *   ⚠️ Xato bo'lsa ilova YIQILMAYDI — `try/catch` +
         *      Toast. Brauzer o'rnatilmagan qurilmada ham
         *      xavfsiz (mavjud `btn_globe` namunasi bilan bir xil).
         */
        private fun openSubUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context,
                    "Brauzer topilmadi", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * ★ FIX150-INCYSTYLE: baytlarni qisqa ko'rinishga o'giradi.
         *
         *   Avval HAR DOIM GB da ko'rsatilardi:
         *       0.00 / 50.00 GB      ← kichik qiymatda "0.00" xunuk
         *
         *   Endi birlik HAJMGA moslashadi:
         *       512 MB / 50 GB        ← o'qish oson
         *       1.5 GB / 50 GB
         *
         *   ⚠️ Xalqaro birliklar (B/KB/MB/GB/TB) — tarjima qilinmaydi,
         *      chunki ular texnik belgilar.
         */
        private fun bytesShort(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            val tb = gb * 1024
            val b = bytes.toDouble()
            return when {
                b >= tb -> String.format(java.util.Locale.US, "%.2f TB", b / tb)
                b >= gb -> String.format(java.util.Locale.US, "%.2f GB", b / gb)
                b >= mb -> String.format(java.util.Locale.US, "%.1f MB", b / mb)
                b >= kb -> String.format(java.util.Locale.US, "%.0f KB", b / kb)
                else -> "$bytes B"
            }
        }
    }

    /** Server qatori. */
    private inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(si: ServerItem) {
            val a = activity as? MainActivity ?: return
            itemView.findViewById<TextView>(R.id.row_flag).text = si.flag()
            itemView.findViewById<TextView>(R.id.row_name).text = si.displayName()
            itemView.findViewById<TextView>(R.id.row_host).text = si.host ?: ""
            val proto = itemView.findViewById<TextView>(R.id.row_proto)
            proto.text = fullProtocolLabel(si)
            proto.backgroundTintList = android.content.res.ColorStateList
                .valueOf(protocolColor(si.protocol))
            val pingView = itemView.findViewById<TextView>(R.id.row_ping)
            pingView.text = pingLabel(si)
            pingView.setTextColor(pingColor(si))
            pingViews[si.link] = pingView

            // ⭐ Yulduzcha
            val favView = itemView.findViewById<android.widget.ImageView>(R.id.row_fav)
            favView?.setImageResource(
                if (si.favorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off)
            favView?.setOnClickListener {
                si.favorite = !si.favorite
                (activity as? MainActivity)?.let { ServerStore.save(it, it.servers) }
                favView.setImageResource(
                    if (si.favorite) android.R.drawable.btn_star_big_on
                    else android.R.drawable.btn_star_big_off)
                Toast.makeText(context,
                    if (si.favorite) getString(R.string.toast_added_fav)
                    else getString(R.string.toast_removed_fav),
                    Toast.LENGTH_SHORT).show()
                lastRebuildHash = -1
                rebuildServerCards(force = true)
            }

            // ═══ Tanlash rejimi ═══
            val check = itemView.findViewById<View>(R.id.select_check)
            if (homeSelectMode) {
                check?.visibility = View.VISIBLE
                check?.alpha = if (homeSelectedLinks.contains(si.link)) 1f else 0.25f
            } else {
                check?.visibility = View.GONE
            }

            // ═══ Qatorni bosish ═══
            itemView.setOnClickListener {
                if (homeSelectMode) { toggleHomeSelect(si); return@setOnClickListener }
                a.selectServer(si)
                Toast.makeText(context, si.displayName(), Toast.LENGTH_SHORT).show()
                refresh()
            }
            itemView.setOnLongClickListener {
                if (!homeSelectMode) showHomeMenu(si, a)
                true
            }
        }
    }

    /** Ko'rsatma matni. */
    private inner class HintVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(text: String) {
            itemView.findViewById<TextView>(R.id.hint_text).text = text
        }
    }

    private fun makeServerRow(si: ServerItem, a: MainActivity): View {
        // ★ FIX133-HOMEFRIZ: nechta View yaratilganini hisoblaymiz
        rowCreateCount++
        // ★ FIX138-LIGHTROW: yengil layout — ripple va CheckBox yo'q
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_server_row_light, null, false)
        row.findViewById<TextView>(R.id.row_flag).text = si.flag()
        row.findViewById<TextView>(R.id.row_name).text = si.displayName()
        row.findViewById<TextView>(R.id.row_host).text = si.host ?: ""
        val proto = row.findViewById<TextView>(R.id.row_proto)
        proto.text = fullProtocolLabel(si)
        proto.backgroundTintList = android.content.res.ColorStateList
            .valueOf(protocolColor(si.protocol))
        val pingView = row.findViewById<TextView>(R.id.row_ping)
        pingView.text = pingLabel(si)
        pingView.setTextColor(pingColor(si))
        // ★ FIX136-HOMEPING: ping kelganda faqat shu TextView yangilanadi
        pingViews[si.link] = pingView

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
        // ★ FIX138-LIGHTROW: endi `CheckBox` emas, oddiy `View`.
        // Tanlash holati fon rangi va ko'rinish bilan belgilanadi.
        val check = row.findViewById<View>(R.id.select_check)
        if (homeSelectMode) {
            check?.visibility = View.VISIBLE
            // Tanlangan bo'lsa — to'liq ko'rinadi, aks holda xira
            check?.alpha = if (homeSelectedLinks.contains(si.link)) 1f else 0.25f
            check?.isClickable = false
            check?.setOnClickListener {
                toggleHomeSelect(si)
            }
            // Butun qatorni bosib tanlash mumkin
            row.setOnClickListener { toggleHomeSelect(si) }
        } else {
            check?.visibility = View.GONE
            check?.isClickable = false
            check?.setOnClickListener(null)
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
            if (target.encrypted && !BiometricAuthHelper.isSessionActive(requireContext())) {
                items.add(c.getString(R.string.bio_unlock))
                actions.add {
                    BiometricAuthHelper.authenticate(
                        activity = requireActivity(),
                        subtitle = c.getString(R.string.bio_sub_unlock, target.displayName()),
                        onSuccess = {
                            Toast.makeText(c, R.string.bio_unlocked_toast, Toast.LENGTH_SHORT).show()
                        },
                        onError = { Toast.makeText(c, it, Toast.LENGTH_SHORT).show() }
                    )
                }
            } else {
                items.add("📋 " + c.getString(R.string.srv_menu_copy_link))
                actions.add { homeCopyToClipboard(target.link, c.getString(R.string.clip_label_link)) }
                items.add("📤 " + c.getString(R.string.srv_menu_share))
                actions.add { homeShareLink(target.link, target.displayName()) }
                items.add("📱 " + c.getString(R.string.srv_menu_show_qr))
                actions.add { QrShowDialog.show(c, target.displayName(), target.link) }
                
                if (target.encrypted) {
                    items.add(c.getString(R.string.bio_lock))
                    actions.add {
                        BiometricAuthHelper.clearSession(requireContext())
                        Toast.makeText(c, R.string.bio_locked_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            items.add("✏️ " + c.getString(R.string.editor_server_title))
            actions.add {
                val i = Intent(c, com.nurvpn.app.ui.editor.ServerEditorActivity::class.java)
                i.putExtra(com.nurvpn.app.ui.editor.ServerEditorActivity.EXTRA_LINK, target.link)
                startActivityForResult(i, REQ_EDIT_SERVER)
            }
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
                        refresh()
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
            items.add(if (target.favorite) "💔 " + c.getString(R.string.srv_menu_remove_fav)
                      else "⭐ " + c.getString(R.string.srv_menu_add_fav))
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
                        refresh()   // counter va boshqa UI yangilanadi
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
            // Counter yangilash
            activity?.runOnUiThread { refresh() }
            n += before - a.awgConfigs.size
            AWGStore.save(a, a.awgConfigs)
        }
        Toast.makeText(context, getString(R.string.toast_deleted),
            Toast.LENGTH_SHORT).show()
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
        Thread {
            // Parallel ping (4 thread)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val latch = java.util.concurrent.CountDownLatch(a.awgConfigs.size)
            for (cfg in a.awgConfigs) {
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

        // ⭐ Manual bo'lim uchun maxsus
        if (subId == "manual") {
            val manual = a.servers.filter { it.subId == null }
            if (manual.isEmpty()) {
                Toast.makeText(context, R.string.toast_no_manual_servers,
                    Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(context,
                getString(R.string.ping_sub_started, subName, manual.size),
                Toast.LENGTH_SHORT).show()
            PingTester.testAll(manual, object : PingTester.Listener {
                // ★ FIX136-HOMEPING: FAQAT shu qatorning matni yangilanadi.
                // 227 ta View qayta yaratilmaydi — friz yo'qoladi.
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    ui.post { updatePingView(item) }
                }
                override fun onAllDone() {
                    pingRunning = false
                    if (!isAdded) return
                    ui.removeCallbacks(pingRebuildRunnable)
                    pingUpdateScheduled = false
                    val a2 = activity as? MainActivity ?: return
                    ServerStore.save(a2, a2.servers)
                    rebuildServerCards(force = true)
                    Toast.makeText(context, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            })
            return
        }

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
                // ★ FIX136-HOMEPING: FAQAT shu qatorning matni yangilanadi.
                // 227 ta View qayta yaratilmaydi — friz yo'qoladi.
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    ui.post { updatePingView(item) }
                }
                override fun onAllDone() {
                    pingRunning = false
                    if (!isAdded) return
                    ui.removeCallbacks(pingRebuildRunnable)
                    pingUpdateScheduled = false
                    val a2 = activity as? MainActivity ?: return
                    ServerStore.save(a2, a2.servers)
                    rebuildServerCards(force = true)
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
                    ui.postDelayed(pingRebuildRunnable, 400)
                }
            }
            override fun onAllDone() {
                pingRunning = false
                if (!isAdded) return
                ui.removeCallbacks(pingRebuildRunnable)
                pingUpdateScheduled = false
                val a2 = activity as? MainActivity ?: return
                ServerStore.save(a2, a2.servers)
                rebuildServerCards()
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
        val keys = arrayOf("default", "ping_asc", "ping_desc", "name_asc", "name_desc")
        val labels = arrayOf(
            getString(R.string.sort_default),
            getString(R.string.sort_ping_asc),
            getString(R.string.sort_ping_desc),
            getString(R.string.sort_name_asc),
            getString(R.string.sort_name_desc)
        )
        val idx = keys.indexOf(current).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.awg_settings_title))
            .setSingleChoiceItems(labels, idx) { d, which ->
                AwgSortStore.setMode(ctx, keys[which])
                rebuildAwgList()
                rebuildServerCards(force = true)
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun showSubSettingsDialog(subId: String, subName: String) {
        val ctx = requireContext()
        val a = activity as? MainActivity ?: return

        // ⭐ Manual bo'lim uchun maxsus
        if (subId == "manual") {
            showManualSettingsDialog()
            return
        }

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

    /** Qo'lda qo'shilganlar uchun sozlamalar dialogi (sort + delete). */
    private fun showManualSettingsDialog() {
        val ctx = requireContext()
        val a = activity as? MainActivity ?: return
        val count = a.servers.count { it.subId == null }
        val current = ManualSortStore.getMode(ctx)
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
            .setTitle(getString(R.string.manual_added))
            .setSingleChoiceItems(labels, idx) { d, which ->
                ManualSortStore.setMode(ctx, modes[which].first)
                rebuildServerCards(force = true)
                d.dismiss()
            }
            .setNeutralButton(
                getString(R.string.sel_delete) + " (" + count + ")"
            ) { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.dialog_manual_delete)
                    .setMessage(getString(
                        R.string.dialog_manual_delete_msg, count))
                    .setPositiveButton(R.string.dialog_delete_yes) { _, _ ->
                        a.servers.removeAll { it.subId == null }
                        ServerStore.save(a, a.servers)
                        rebuildServerCards(force = true)
                        Toast.makeText(ctx, R.string.toast_deleted,
                            Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.dialog_no, null)
                    .show()
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
            "\uD83D\uDDD1  " + getString(R.string.sub_menu_delete),
            "\uD83D\uDE48  " + getString(R.string.sub_menu_hide_from_home)
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
                        if (sub.encrypted && !BiometricAuthHelper.isSessionActive(ctx)) {
                            BiometricAuthHelper.authenticate(
                                activity = requireActivity(),
                                subtitle = ctx.getString(R.string.bio_sub_copy, sub.name),
                                onSuccess = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.clip_label_sub_url), sub.url))
                                    Toast.makeText(ctx, R.string.toast_url_copied, Toast.LENGTH_SHORT).show()
                                },
                                onError = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                            )
                            return@setItems
                        }
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager ?: return@setItems
                        cm.setPrimaryClip(android.content.ClipData
                            .newPlainText(getString(R.string.clip_label_sub_url), sub.url))
                        Toast.makeText(ctx, R.string.toast_url_copied,
                            Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        // QR orqali ulashish
                        if (sub.encrypted && !BiometricAuthHelper.isSessionActive(ctx)) {
                            BiometricAuthHelper.authenticate(
                                activity = requireActivity(),
                                subtitle = ctx.getString(R.string.bio_sub_qr, sub.name),
                                onSuccess = {
                                    QrShowDialog.show(ctx, sub.name, sub.url)
                                },
                                onError = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                            )
                            return@setItems
                        }
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
                    4 -> {
                        // Asosiy ekrandan yashirish
                        sub.hiddenOnHome = true
                        SubscriptionStore.save(a, a.subscriptions)
                        rebuildServerCards(force = true)
                        Toast.makeText(ctx,
                            getString(R.string.sub_hidden_toast, sub.name),
                            Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, getString(R.string.toast_json_not_read),
                    Toast.LENGTH_LONG).show()
                return
            }
            // Duplicate tekshiruvi
            if (a.servers.any { it.link == item.link }) {
                Toast.makeText(context, getString(R.string.toast_server_exists),
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
            Toast.makeText(context, getString(R.string.toast_error_fmt, t.message),
                Toast.LENGTH_LONG).show()
        }
    }

    /** JSON array yoki object → ServerItem ro'yxati. */
    /** JSON array yoki object → ServerItem ro'yxati (subId bilan). */
    /**
     * Xray JSON dagi wireguard outbound'ni .conf matniga aylantiradi.
     */
    private fun buildWireGuardConfFromJson(ob: org.json.JSONObject): String? {
        return try {
            val settings = ob.optJSONObject("settings") ?: return null
            val secretKey = settings.optString("secretKey", "")
            if (secretKey.isBlank()) return null

            val peers = settings.optJSONArray("peers") ?: return null
            if (peers.length() == 0) return null
            val peer = peers.optJSONObject(0) ?: return null

            val publicKey = peer.optString("publicKey", "")
            val endpoint = peer.optString("endpoint", "")
            if (publicKey.isBlank() || endpoint.isBlank()) return null

            val mtu = settings.optInt("mtu", 1360)
            val addressArr = settings.optJSONArray("address")
            val addresses = mutableListOf<String>()
            if (addressArr != null) {
                for (j in 0 until addressArr.length()) {
                    addresses.add(addressArr.optString(j))
                }
            }
            if (addresses.isEmpty()) addresses.add("172.16.0.2/32")

            val allowedIpsArr = peer.optJSONArray("allowedIPs")
            val allowedIps = if (allowedIpsArr != null && allowedIpsArr.length() > 0) {
                (0 until allowedIpsArr.length()).map { allowedIpsArr.optString(it) }
                    .joinToString(", ")
            } else "0.0.0.0/0, ::/0"

            buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $secretKey")
                appendLine("Address = " + addresses.joinToString(", "))
                appendLine("MTU = $mtu")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $publicKey")
                appendLine("Endpoint = $endpoint")
                appendLine("AllowedIPs = $allowedIps")
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG",
                "buildWireGuardConfFromJson xato: " + t.message)
            null
        }
    }

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

    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX135-HOMEEXPAND: bitta kartaning `body` qismini to'ldiradi.
    //
    //  `rebuildServerCards()` ning butun konteynerni qayta qurish
    //  mantiqidan AJRATILGAN — faqat kerakli serverni qo'shadi.
    //  Allaqachon to'ldirilgan bo'lsa (body.childCount > 0) — tegmiz.
    // ═══════════════════════════════════════════════════════════════════
    private fun fillBodyIfNeeded(subId: String, body: LinearLayout) {
        val a = activity as? MainActivity ?: run {
            android.util.Log.w("NurVPN-PERF",
                "fillBodyIfNeeded($subId): activity YO'Q")
            return
        }
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX139-ROWCACHE: View'lar `body` ICHIDA QOLADI
        //
        //  Avval `body.removeAllViews()` chaqirilardi — natijada har
        //  ochishda 200 ta `inflate()` qaytadan bajarilardi (2 sekund).
        //
        //  Endi: agar `body` allaqachon to'ldirilgan bo'lsa (Viz
        //  bolalar mavjud), shunchaki KO'RSATAMIZ — hech narsa
        //  yaratilmaydi (0ms).
        // ═══════════════════════════════════════════════════════════════
        if (body.childCount > 0 && body.getChildAt(0)?.tag == "rows") {
            android.util.Log.i("NurVPN-PERF",
                "fillBodyIfNeeded($subId): KESHDAN — ${body.childCount} qator, 0ms")
            return
        }
        body.removeAllViews()
        // ★ FIX137-ROWCHUNK: oldingi bo'lib qo'shish navbatini bekor
        // qilamiz — aks holda yopilgan kartaga qatorlar qo'shilib qoladi.
        body.removeCallbacks(null)
        val t0 = System.currentTimeMillis()
        try {
            val raw = a.servers.filter { it.subId == subId }
            android.util.Log.i("NurVPN-PERF",
                "fillBodyIfNeeded($subId): topildi=${raw.size}, " +
                    "jami_server=${a.servers.size}")
            if (raw.isEmpty()) {
                val tv = TextView(requireContext())
                tv.text = getString(R.string.text_no_servers)
                tv.setTextColor(androidx.core.content.ContextCompat
                    .getColor(requireContext(), R.color.text_tertiary))
                tv.textSize = 12f
                tv.setPadding(0, 12, 0, 12)
                body.addView(tv)
                return
            }
            val sub = a.subscriptions.find { it.id == subId }
            val list = sortServers(raw, sub?.sortMode ?: "default")
            // ★ FIX137-ROWCHUNK: 30 tadan bo'lib qo'shamiz — karta
            // darhol ochiladi, friz sezilmaydi.
            addRowsChunked(body, list, a)
            val ms = System.currentTimeMillis() - t0
            android.util.Log.i("NurVPN-PERF",
                "fillBodyIfNeeded($subId): birinchi ${minOf(ROW_CHUNK, list.size)}/" +
                    "${list.size} tayyor, ${ms}ms")
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-DBG", "fillBodyIfNeeded xato", t)
        }
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
                    parts.add(ctx.getString(R.string.sub_days_left, days))
                }
                if (sub.trafficTotal > 0) {
                    val usedG = sub.trafficUsed / 1024.0 / 1024 / 1024
                    val totalG = sub.trafficTotal / 1024.0 / 1024 / 1024
                    val leftG = (totalG - usedG).coerceAtLeast(0.0)
                    parts.add(ctx.getString(R.string.sub_traffic_used,
                        String.format(java.util.Locale.US, "%.2f", usedG),
                        String.format(java.util.Locale.US, "%.2f", totalG)))
                    parts.add(ctx.getString(R.string.sub_gb_left,
                        String.format(java.util.Locale.US, "%.2f", leftG)))
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
                val wasExpanded = subId in homeExpandedSubs
                if (wasExpanded) {
                    homeExpandedSubs.remove(subId)
                } else {
                    homeExpandedSubs.add(subId)
                }
                // ═══════════════════════════════════════════════════════
                //  ★ FIX135-HOMEEXPAND: FAQAT O'SHA KARTANI YANGILASH
                //
                //  ═══ ILDIZ SABAB ═══
                //
                //  Avval karta bosilganda shunday edi:
                //
                //      ui.post { rebuildServerCards() }
                //
                //  `rebuildServerCards()` BUTUN konteynerni qayta quradi:
                //
                //      container.removeAllViews()          // hammasi o'chadi
                //      for (si in list) {
                //          body.addView(makeServerRow(si, a))  // 227 inflate!
                //      }
                //
                //  Natijada bitta kartani ochish uchun BARCHA ochiq
                //  kartalarning BARCHA qatorlari noldan yaratilardi —
                //  yuzlab `inflate()` → KATTA FRIZ va LAG.
                //
                //  ═══ YECHIM ═══
                //
                //  Faqat shu kartaning `body` qismini to'ldiramiz. Boshqa
                //  kartalar TEGILMAYDI — ular allaqachon chizilgan.
                //
                //  Yopilganda esa `body` shunchaki yashiriladi (View'lar
                //  xotirada qoladi — qayta ochish bir zumda bo'ladi).
                // ═══════════════════════════════════════════════════════
                if (wasExpanded) {
                    // YOPISH — View'larni o'chirmaymiz, yashiramiz
                    body.visibility = android.view.View.GONE
                    arrow.text = "\u2B07"
                } else {
                    // OCHISH — faqat shu karta to'ldiriladi
                    fillBodyIfNeeded(subId, body)
                    body.visibility = android.view.View.VISIBLE
                    arrow.text = "\u2B06"
                }
                // ★ FIX135b: `lastRebuildHash` BEKOR QILINMAYDI!
                //
                // Avval bu yerda `lastRebuildHash = -1` bor edi. Natijada
                // keyingi `rebuildServerCards()` keshni "eskirgan" deb
                // hisoblab, BUTUN konteynerni qayta qurar va bizning
                // nozik ishimizni bekor qilardi (friz + ikki marta ish).
                //
                // Endi `rebuildServerCards()` keshdan foydalanadi va
                // faqat ma'lumot HAQIQATAN o'zgarganda qayta quradi.
            } else {
                // subId yo'q — faqat visibility
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
        // Counter doim yangilanadi
        awgCount?.text = getString(R.string.text_count_ta, a.awgConfigs.size)
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
        val sortMode = AwgSortStore.getMode(requireContext())
        val sortedAwg = AwgSortStore.sort(a.awgConfigs, sortMode)
        for (cfg in sortedAwg) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_server_row, body, false)
            val isMasque1 = cfg.type == "masque"
            row.findViewById<TextView>(R.id.row_flag).text = if (isMasque1) "🌐" else "🔒"
            row.findViewById<TextView>(R.id.row_name).text = cfg.name ?: (if (isMasque1) "MASQUE Config" else "AWG Config")
            row.findViewById<TextView>(R.id.row_host).text = cfg.endpoint ?: ""
            val proto = row.findViewById<TextView>(R.id.row_proto)
            proto.text = if (isMasque1) "MASQUE" else getString(R.string.text_awg_label)
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
                awgCheck?.isClickable = false
                awgCheck?.setOnClickListener {
                    toggleHomeSelect(cfg)
                }
            } else {
                awgCheck?.visibility = View.GONE
                awgCheck?.isClickable = false
                awgCheck?.setOnClickListener(null)
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


            row.setOnClickListener {
                // ★ autoConnect = true — VLESS kabi avtomatik ulanish
                a.selectAWG(cfg, autoConnect = true)
                Toast.makeText(context, cfg.name ?: getString(R.string.text_awg_label), Toast.LENGTH_SHORT).show()
                refresh()
            }
            body.addView(row)
        }
    }

    /** To'liq yorliq: "VLESS · TCP · REALITY" */
    private fun fullProtocolLabel(si: ServerItem): String {
        si.ensureTransport()
        val sb = StringBuilder(protocolLabel(si.protocol))
        if (si.transport.isNotEmpty()) {
            sb.append(" \u00B7 ").append(transportLabel(si.transport))
        }
        if (si.security.isNotEmpty() && si.security != "none") {
            sb.append(" \u00B7 ").append(si.security.uppercase())
        }
        return sb.toString()
    }

    private fun protocolLabel(p: Protocol): String = when (p) {
        Protocol.VLESS_REALITY -> "VLESS"
        Protocol.VMESS -> "VMESS"
        Protocol.TROJAN -> "TROJAN"
        Protocol.SS_2022 -> "SS"
        Protocol.HYSTERIA2 -> "HY2"
        Protocol.TUIC -> "TUIC"
        Protocol.SOCKS -> "SOCKS"
        Protocol.HTTP -> "HTTP"
        Protocol.SSH -> "SSH"
        Protocol.SSR -> "SSR"
        Protocol.SHADOWTLS -> "ShadowTLS"
        Protocol.ANYTLS -> "AnyTLS"
        Protocol.JUICITY -> "Juicity"
        Protocol.SS_LEGACY -> "SS"
    }

    /** Transport turini qisqa ko'rinishda qaytaradi. */
    private fun transportLabel(t: String): String = when (t.lowercase()) {
        "ws", "websocket" -> "WS"
        "grpc" -> "gRPC"
        "xhttp" -> "XHTTP"
        "httpupgrade" -> "HU"
        "split", "splithttp" -> "SPLIT"
        "quic" -> "QUIC"
        "kcp" -> "KCP"
        "http" -> "HTTP"
        "reality" -> "REALITY"
        "tls" -> "TLS"
        "tcp" -> "TCP"
        else -> t.uppercase()
    }

    private fun protocolColor(p: Protocol): Int = when (p) {
        Protocol.VLESS_REALITY -> 0xFF4A9EFF.toInt()
        Protocol.VMESS -> 0xFF4A9EFF.toInt()
        Protocol.TROJAN -> 0xFF4A9EFF.toInt()
        Protocol.SS_2022 -> 0xFFFFC107.toInt()
        Protocol.HYSTERIA2 -> 0xFF9B59B6.toInt()
        Protocol.TUIC -> 0xFFE91E63.toInt()
        Protocol.SOCKS -> 0xFF00BCD4.toInt()
        Protocol.HTTP -> 0xFF4CAF50.toInt()
        Protocol.SSH -> 0xFF795548.toInt()
        Protocol.SSR -> 0xFFFF5722.toInt()
        Protocol.SHADOWTLS -> 0xFF607D8B.toInt()
        Protocol.ANYTLS -> 0xFFE040FB.toInt()
        Protocol.JUICITY -> 0xFF00E676.toInt()
        Protocol.SS_LEGACY -> 0xFFFFC107.toInt()
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
        MaskHelper.attach(input)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_server)
            .setView(input)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton

                // ═══ Shifrlangan link (crypt1)? ═══
                if (Crypt1Decoder.isCryptLink(text)) {
                    val decoded = Crypt1Decoder.decode(requireContext(), text)
                    if (decoded == null) {
                        Toast.makeText(context, getString(R.string.encrypted_decode_failed),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    android.util.Log.i("NurVPN-DBG",
                        "dialog: crypt1 decoded → ${decoded.first.take(60)}")
                    loadSubDialog(decoded.first, wasEncrypted = true)
                    return@setPositiveButton
                }

                // ═══ Subscription URL? ═══
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    val firstLine = text.lines().firstOrNull()?.trim() ?: text
                    if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
                        loadSubDialog(firstLine)
                        return@setPositiveButton
                    }
                }

                val a = activity as? MainActivity ?: return@setPositiveButton

                val regex = Regex("(?:vless|vmess|trojan|ss|ssr|hy2|hysteria2|tuic|anytls|incy|crypt1|nurvpn|juicity|socks5|shadowtls)://.*?(?=(?:vless|vmess|trojan|ss|ssr|hy2|hysteria2|tuic|anytls|incy|crypt1|nurvpn|juicity|socks5|shadowtls)://|\\s|$)")
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

    private fun loadSubDialog(url: String, wasEncrypted: Boolean = false) {
        val c = requireContext()
        val input = EditText(c).apply {
            hint = getString(R.string.hint_name_optional)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat
                .getColor(c, R.color.text_secondary))
        }
        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #11 (BUG #2): URL HAR DOIM TO'LIQ KO'RINADI.
        //
        //  Avval `wasEncrypted=true` bo'lsa dialog "🔒 URL ... yashirilgan"
        //  ko'rsatardi. Foydalanuvchi brauzerdan havola ochganda ham shu
        //  holat yuz berib, obunani yuklab bo'lmasdi.
        //
        //  Endi: brauzer va deep link BIR XIL ko'rinishga ega.
        //  URL foydalanuvchiga doim ko'rsatiladi — u nima yuklayotganini
        //  ko'rishi va tekshirishi kerak.
        //
        //  ⚠️ XAVFSIZLIK: `wasEncrypted` parametri saqlanadi (imzo
        //  o'zgarmasin), lekin endi u faqat `loadSubscription()` ga
        //  uzatiladi — ko'rsatishga ta'sir qilmaydi.
        // ═══════════════════════════════════════════════════════════════
        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_sub_load)
            .setMessage(url)
            .setView(input)
            .setPositiveButton(R.string.dialog_load) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { null }
                loadSubscription(url, name, wasEncrypted)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }


    /**
     * HWID — BARQAROR (qayta o'rnatilsa ham bir xil).
     *
     * ⚠️ Algoritm `HwidHelper` ga ko'chirildi (avval bu funksiya
     * ServersFragment'da bayt-baytga takrorlanardi). Xatti-harakat
     * O'ZGARMAGAN — `HwidHelperTest` buni qulflaydi.
     */
    /**
     * URL'dan qisqa nom chiqarish (ServersFragment dan ko'chirilgan).
     * sub.example.com/path -> "Example"
     * gist.githubusercontent.com/... -> "Githubusercontent"
     */
    private fun extractSubTitle(url: String): String {
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

    private fun getHwid(): String = HwidHelper.fromContext(requireContext())


    /** Subscription header'ni o'qish (base64: prefiksli yoki oddiy). */
    /**
     * ★ FIX149-SUBINFO: obuna header metama'lumotlarini `Subscription` ga
     * yozadi. Faqat MAVJUD va TO'G'RI qiymatlar saqlanadi — eski qiymat
     * noto'g'ri ma'lumot bilan O'CHIRILMAYDI.
     */
    private fun applySubHeaders(
        sub: com.nurvpn.app.core.Subscription,
        updateInt: Int?,
        webPage: String?,
        support: String?,
        announce: String?
    ) {
        try {
            com.nurvpn.app.core.SubscribeHeader.parseIntervalDays(updateInt?.toString())
                .takeIf { it > 0 }?.let { sub.updateIntervalDays = it }
            com.nurvpn.app.core.SubscribeHeader.cleanUrl(webPage)
                ?.let { sub.webPage = it }
            com.nurvpn.app.core.SubscribeHeader.cleanUrl(support)
                ?.let { sub.supportUrl = it }
            com.nurvpn.app.core.SubscribeHeader.cleanAnnounce(announce)
                ?.let { sub.announce = it }
            android.util.Log.i("NurVPN-DBG",
                "sub headers: interval=${sub.updateIntervalDays}k " +
                "web=${sub.webPage.take(40)} support=${sub.supportUrl.take(40)} " +
                "announce=${sub.announce.length} belgi")
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG", "applySubHeaders xato", t)
        }
    }

    private fun decodeSubHeader(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.startsWith("base64:", ignoreCase = true)) {
            return try {
                String(
                    android.util.Base64.decode(
                        s.substring(7).trim(),
                        android.util.Base64.DEFAULT
                    ),
                    Charsets.UTF_8
                )
            } catch (t: Throwable) {
                android.util.Log.w("NurVPN-DBG", "sub header decode: ${t.message}")
                null
            }
        }
        return s
    }

    private fun loadSubscription(url: String, subName: String?, wasEncrypted: Boolean = false) {
        val c = requireContext()
        val a = activity as? MainActivity ?: return
        Toast.makeText(c, R.string.toast_loading, Toast.LENGTH_SHORT).show()
        Thread {
            // Thread-local userInfo (race condition oldini oladi)
            var localUserInfo: UserInfo? = null
            // Provayder metadata (try tashqarisida e'lon qilinadi)
            var headerTitle: String? = null
            var headerUpdateInt: Int? = null
            var headerWebPage: String? = null
            var headerSupport: String? = null
            var headerAnnounce: String? = null

            val body = try {
                val u = java.net.URL(url)
                val conn = u.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "INCY/3.7.0/android")
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
                // ═══ Provayder metadata (Incy kabi) ═══
                headerTitle = decodeSubHeader(conn.getHeaderField("profile-title"))
                headerUpdateInt = conn.getHeaderField("profile-update-interval")?.trim()?.toIntOrNull()
                headerWebPage = conn.getHeaderField("profile-web-page-url")?.trim()
                headerSupport = conn.getHeaderField("support-url")?.trim()
                headerAnnounce = decodeSubHeader(conn.getHeaderField("announce"))
                if (headerTitle != null) {
                    android.util.Log.i("NurVPN-DBG", "profile-title: $headerTitle")
                }
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
                val MAX_BYTES = 2 * 1024 * 1024  // 2 MB limit (base64 yarim qirqilmasin)
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
                    subForJson = Subscription(subIdForJson, url, headerTitle ?: subName ?: extractSubTitle(url))
                    a.subscriptions.add(subForJson)
                }
                if (wasEncrypted) subForJson.encrypted = true
                if (headerTitle != null && subForJson.name != headerTitle) {
                    // Provayder nomi header'dan kelgan bo'lsa — yangilaymiz
                    subForJson.name = headerTitle
                    android.util.Log.i("NurVPN-DBG", "sub name updated: $headerTitle")
                    SubscriptionStore.save(a, a.subscriptions)
                }
                // WireGuard (AWG) JSON import O'CHIRILGAN — foydalanuvchi so'rovi

                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subForJson.id)
                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON serverlar=${jsonServers.size}")
                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {
                        val a2 = activity as? MainActivity ?: return@runOnUiThread
                        var added = 0
                        for (si in jsonServers) {
                            if (a2.servers.any { it.link == si.link }) continue
                            si.encrypted = wasEncrypted
                            a2.servers.add(si)
                            added++
                        }
                        ServerStore.save(a2, a2.servers)

                        // ★ YANGI: userinfo saqlash (JSON branch)
                        val subUpd = a2.subscriptions.find { it.id == subForJson.id }
                        if (subUpd != null) {
                            subUpd.lastUpdated = System.currentTimeMillis()
                            subUpd.serverCount = jsonServers.size
                            localUserInfo?.let { info ->
                                if (info.trafficTotal > 0) {
                                    subUpd.trafficUsed = info.trafficUsed
                                    subUpd.trafficTotal = info.trafficTotal
                                }
                                if (info.expireAt > 0) {
                                    subUpd.expireAt = info.expireAt
                                }
                                android.util.Log.i("NurVPN-DBG",
                                    "JSON userinfo saqlandi: total=${info.trafficTotal}, " +
                                    "expire=${info.expireAt}, used=${info.trafficUsed}")
                                // ★ FIX149-SUBINFO: header metama'lumotlari
                                applySubHeaders(subUpd, headerUpdateInt, headerWebPage,
                                    headerSupport, headerAnnounce)
                            }
                            SubscriptionStore.save(a2, a2.subscriptions)
                        }

                        Toast.makeText(c,
                            "JSON: $added server qo'shildi",
                            Toast.LENGTH_SHORT).show()
                        // ★ YANGI: cache bypass
                        rebuildServerCards(force = true)
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
            // FIX #3: 200 limiti olib tashlandi (RecyclerView uchun xavfsiz)
            val limitedLinks = links
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
                        .setTitle(R.string.sub_limit_title)
                        .setMessage(R.string.sub_limit_msg)
                        .setPositiveButton(R.string.sub_limit_btn, null)
                        .show()
                }
                return@Thread
            }
            activity?.runOnUiThread {
                var sub = a.subscriptions.find { it.url == url }
                if (sub == null) {
                    sub = Subscription(
                        "sub_" + System.currentTimeMillis().toString(36),
                        url, headerTitle ?: subName ?: extractSubTitle(url))
                    a.subscriptions.add(sub)
                } else if (!subName.isNullOrEmpty()) sub.name = subName
                val currentSub = sub ?: return@runOnUiThread
                if (wasEncrypted) currentSub.encrypted = true
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
                // ★ FIX149-SUBINFO: header metama'lumotlarini SAQLAYMIZ.
                //   Avval ular o'qilardi-yu, yo'qolardi — karta qayta
                //   chizilganda "25 kun qoldi | 7.62 / 210.63 GB"
                //   yozuvi ko'rinmasdi.
                applySubHeaders(currentSub, headerUpdateInt, headerWebPage,
                    headerSupport, headerAnnounce)
                a.servers.removeAll { it.subId == currentSub.id }
                val newServers = ArrayList<ServerItem>(limitedLinks.size)
                for (l in limitedLinks) {
                    val si = ServerLinkParser.parse(l, currentSub.id) ?: continue
                    si.encrypted = wasEncrypted || currentSub.encrypted
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
                // ★ YANGI: UI ni majburiy yangilash
                rebuildServerCards(force = true)
                // Og'ir ishlarni background'ga
                Thread {
                    ServerStore.save(a, a.servers)
                    // ★ FIX157: `System.gc()` OLIB TASHLANDI.
                    //   U to'liq GC pause (~50-200ms) keltirardi va
                    //   Android o'zi boshqaradigan xotimani majburan
                    //   tozalar edi — foyda yo'q, zarari bor.
                    activity?.runOnUiThread {
                        rebuildServerCards(force = true)
                    }
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
        MaskHelper.attach(urlEt)
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
                    val regex = Regex("(?:vless|vmess|trojan|ss|ssr|hy2|hysteria2|tuic|anytls|incy|crypt1|nurvpn|juicity|socks5|shadowtls)://.*?(?=(?:vless|vmess|trojan|ss|ssr|hy2|hysteria2|tuic|anytls|incy|crypt1|nurvpn|juicity|socks5|shadowtls)://|\\s|$)")
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

        // ═══ Shifrlangan link (crypt1)? ═══
        if (Crypt1Decoder.isCryptLink(text)) {
            val decoded = Crypt1Decoder.decode(requireContext(), text)
            if (decoded == null) {
                Toast.makeText(context, getString(R.string.encrypted_decode_failed),
                    Toast.LENGTH_LONG).show()
                return
            }
            android.util.Log.i("NurVPN-DBG",
                "pasteFromClipboard: crypt1 decoded → ${decoded.first.take(60)}")
            loadSubDialog(decoded.first, wasEncrypted = true)
            return
        }

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

        val regex = Regex("(?:vless|vmess|trojan|ss|ssr|hy2|hysteria2|tuic|anytls|incy|crypt1|nurvpn|juicity|socks5|shadowtls)://.*?(?=(?:vless|vmess|trojan|ss|ssr|hy2|hysteria2|tuic|anytls|incy|crypt1|nurvpn|juicity|socks5|shadowtls)://|\\s|$)")
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
            PingTester.testAll(a.servers, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    // Debounce — 400ms ichida ko'p marta chaqirilsa, faqat 1 marta rebuild
                    if (!pingUpdateScheduled) {
                        pingUpdateScheduled = true
                        ui.postDelayed(pingRebuildRunnable, 400)
                    }
                }
                override fun onAllDone() {
                    pingRunning = false
                    if (!isAdded) return
                    ui.removeCallbacks(pingRebuildRunnable)
                    pingUpdateScheduled = false
                    val a2 = activity as? MainActivity ?: return
                    ServerStore.save(a2, a2.servers)
                    rebuildServerCards(force = true)
                    Toast.makeText(context, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            pingRunning = false
        }
    }

    private fun toggleConnection() {
        // Ochiq manba ham, obuna ham yo'q — dialog ko'rsatamiz
        val a0 = activity as? MainActivity
        android.util.Log.i("NurVPN-TOGGLE",
            "toggleConnection: subs=${a0?.subscriptions?.size}, servers=${a0?.servers?.size}, awg=${a0?.awgConfigs?.size}, currentServer=${a0?.currentServer?.displayName()}, protocol=${a0?.protocol}")
        if (a0 != null &&
            a0.subscriptions.isEmpty() &&
            a0.servers.isEmpty() &&
            a0.awgConfigs.isEmpty()) {
            // ★ v1.13: obuna yo'q → Ochiq manbalarga yo'naltiramiz.
            // Tumbler OFF holatda qoladi (startVpn() chaqirilmaydi).
            try {
                a0.prefs.edit()
                    .putBoolean("scroll_to_open_sources", true)
                    .apply()
                a0.switchToTab(2)   // 2 = Settings (Ochiq manbalar shu yerda)
                android.widget.Toast.makeText(context,
                    getString(R.string.no_subscription_redirect),
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-DBG",
                    "open sources redirect xato", t)
            }
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
            if (MainActivity.PROTO_XRAY == a.protocol && a.servers.isEmpty()) {
                Toast.makeText(context, R.string.toast_add_server_first,
                    Toast.LENGTH_SHORT).show()
                return
            }
            // FIX v1.13: agar server tanlanmagan bo'lsa, birinchi serverni avtomatik tanlash
            if (MainActivity.PROTO_XRAY == a.protocol && a.currentServer == null) {
                a.selectServer(a.servers.first())
                android.util.Log.i("NurVPN-TOGGLE",
                    "Avtomatik tanlandi: ${a.servers.first().displayName()}")
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
            val isMasque2 = awg?.type == "masque"
            serverFlag?.text = if (isMasque2) "🌐" else "🔒"
            serverName?.text = awg?.name ?: (if (isMasque2) "MASQUE" else "AWG")
            serverProto?.text = if (isMasque2) "MASQUE / HTTP" else "AWG / WireGuard"
            serverProto?.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
            pingText?.text = if (isMasque2) "MASQUE" else getString(R.string.text_awg_label)
            pingText?.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
        } else {
            val srv = a.currentServer
            serverFlag?.text = srv?.flag() ?: "🌍"
            serverName?.text = srv?.displayName() ?: getString(R.string.no_server_selected)
            serverProto?.text = if (srv != null) fullProtocolLabel(srv) else ""
            serverProto?.setTextColor(androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.text_tertiary))
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
        speedWave?.setAnimationEnabled(true)
        // ★ Batareya: fondan qaytganda puls animatsiyasini tiklash
        // (onPause da to'xtatiladi — INFINITE animator fon'da UI thread'ni uyg'otmasin)
        if (TunnelState.isConnected) startPulse()
        refresh()
        if (!tickerRunning) { tickerRunning = true; ui.post(ticker) }
        autoLoadPendingSubscriptions()
        checkPendingDeepLink()
    }

    /**
     * MainActivity'dan kelgan pending deep link'ni tekshirish.
     * `incy://crypt1/...`, `crypt1://...`, `nurvpn://...` linklarni decode qilib,
     * oddiy obuna sifatida yuklaydi.
     */

    private fun checkPendingDeepLink() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("main", android.content.Context.MODE_PRIVATE)
        val pending = prefs.getString("pending_deeplink", null) ?: return
        // Darhol o'chirish — qayta ishlanmasin
        prefs.edit().remove("pending_deeplink").apply()

        android.util.Log.i("NurVPN-DEEPLINK", "Processing: ${pending.take(80)}")

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX: ANIQLIK TARTIBI (specificity order)
        //
        //  1) crypt1:// / nurvpn:// / incy://crypt1/ — eng aniq prefiks
        //  2) http(s):// — obuna manzili
        //  3) vless://, vmess://, ... — oddiy proxy linklar
        //
        //  Avval "http → extract → decode" tartibida edi. extract()
        //  crypt1:// ni ham "oddiy proxy" deb qabul qilardi va decode()
        //  ga yetib bormasdi → shifrlangan obuna buzilardi.
        // ═══════════════════════════════════════════════════════════════

        // ─── 1) SHIFRLANGAN (crypt1:// va h.k.) ───
        if (Crypt1Decoder.isCryptLink(pending)) {
            android.util.Log.i("NurVPN-DEEPLINK", "crypt1 link aniqlandi")
            val decoded = Crypt1Decoder.decode(ctx, pending)
            if (decoded == null) {
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.encrypted_decode_failed),
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }
            android.util.Log.i("NurVPN-DEEPLINK",
                "Decoded → ${decoded.first.take(60)} (name=${decoded.second})")
            loadSubDialog(decoded.first, wasEncrypted = true)
            return
        }

        // ─── 2) HTTP/HTTPS OBUNA ───
        if (pending.startsWith("http://") || pending.startsWith("https://")) {
            loadSubDialog(pending, wasEncrypted = false)
            return
        }

        // ─── 3) ODDIY PROXY LINKLAR (vless://, vmess://, trojan:// ...) ───
        val plainLinks = com.nurvpn.app.parser.SubscriptionLinkExtractor
            .extract(pending)
        if (plainLinks.isNotEmpty()) {
            android.util.Log.i("NurVPN-DEEPLINK",
                "Oddiy link → ${plainLinks.size} ta, to'g'ridan-to'g'ri qo'shiladi")
            val a = activity as? MainActivity ?: return
            var added = 0
            for (l in plainLinks) {
                val si = com.nurvpn.app.parser.ServerLinkParser.parse(l)
                if (si != null) { a.servers.add(si); added++ }
            }
            if (added > 0) {
                com.nurvpn.app.storage.ServerStore.save(a, a.servers)
                rebuildServerCards(force = true)
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.toast_added_count, added),
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.toast_invalid_link),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        // ─── 4) HECH NARSA MOS KELMADI ───
        android.widget.Toast.makeText(ctx,
            ctx.getString(R.string.encrypted_decode_failed),
            android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        tickerRunning = false
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(pingRebuildRunnable)
        speedWave?.setAnimationEnabled(false)
        // ★ Batareya: INFINITE ObjectAnimator fon'da ishlashda davom etsa,
        // har 1500ms da UI thread uyg'onadi (Choreographer frame). Ekran
        // o'chganda/ilova fonda qolganda to'xtatish shart.
        stopPulse()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_QR_SCAN &&
            resultCode == android.app.Activity.RESULT_OK) {
            val text = data?.getStringExtra(QrScanActivity.EXTRA_RESULT) ?: return
            handleQrResult(text)
            return
        }
        if (requestCode == REQ_EDIT_SERVER &&
            resultCode == android.app.Activity.RESULT_OK) {
            val newLink = data?.getStringExtra("new_link") ?: return
            val oldLink = data?.getStringExtra("old_link") ?: return
            val a = activity as? MainActivity ?: return
            val idx = a.servers.indexOfFirst { it.link == oldLink }
            if (idx < 0) return
            val parsed = com.nurvpn.app.parser.ServerLinkParser.parse(newLink)
            if (parsed == null) {
                Toast.makeText(context, getString(R.string.toast_invalid_link),
                Toast.LENGTH_SHORT).show()
                return
            }
            // subId va favorite ni saqlash
            parsed.subId = a.servers[idx].subId
            parsed.favorite = a.servers[idx].favorite
            a.servers[idx] = parsed
            // Agar tanlangan server shu bo'lsa — currentServer yangilash
            if (a.currentServer?.link == oldLink) {
                a.currentServer = parsed
                a.prefs.edit().putString("current_link", newLink).apply()
            }
            ServerStore.save(a, a.servers)
            rebuildServerCards(force = true)
            refresh()
            Toast.makeText(context, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleQrResult(text: String) {
        android.util.Log.i("NurVPN-QR", "QR matni uzunligi=${text.length}")
        android.util.Log.i("NurVPN-QR", "QR matni=${text.take(500)}")
        val a = activity as? MainActivity ?: return

        // ═══ Shifrlangan link (crypt1)? ═══
        if (Crypt1Decoder.isCryptLink(text)) {
            val decoded = Crypt1Decoder.decode(requireContext(), text)
            if (decoded == null) {
                Toast.makeText(context, getString(R.string.encrypted_decode_failed),
                    Toast.LENGTH_LONG).show()
                return
            }
            android.util.Log.i("NurVPN-QR",
                "handleQrResult: crypt1 decoded → ${decoded.first.take(60)}")
            loadSubDialog(decoded.first, wasEncrypted = true)
            return
        }

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
        speedWave?.visibility = android.view.View.INVISIBLE
        speedWave = null
        tickerRunning = false
        ui.removeCallbacksAndMessages(null)
        stopPulse()
        connectBtn = null; connectIcon = null
        statusText = null; serverFlag = null; serverName = null; pingText = null
        timerText = null; downText = null; upText = null
        cardsContainer = null; bodyAwg = null
        homeRecycler = null; homeAdapter = null; homeHeaderView = null; footerView = null
    }




    /**
     * ★ FIX121-SCROLL (BUG #1): tab ko'rsatilganda chaqiriladi.
     *
     * `hide()/show()` da `onResume()` QAYTA chaqirilmaydi, shuning uchun
     * scroll holatini tiklash va UI yangilash shu yerda qilamiz.
     */
    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "HomeFragment: onTabShown")
        // FIX #2: Asosiy tugma bosilganda eng tepaga (VPN tugmasiga) qaytish
        view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.home_scroll_root)
            ?.scrollToPosition(0)
        // Ma'lumotlarni yangilaymiz
        refresh()
    }

    /** ★ FIX121-SCROLL: fragment yashirilganda scroll holatini saqlaymiz. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            android.util.Log.i("NurVPN-DBG", "HomeFragment: yashirildi — scroll saqlanadi")
            persistScrollPosition()
        } else {
            android.util.Log.i("NurVPN-DBG", "HomeFragment: ko'rsatildi — scroll tiklanadi")
            restoreScrollPosition()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #8: TO'LIQ AJRATILGAN scroll holati
    //
    //  Nega oldingi urinish ishlamadi:
    //   1) Home va Servers layoutlarida 5 ta ID bir xil edi
    //      (selection_bar, sel_count, ...) → Android View holatini
    //      ID bo'yicha tiklab, ESKI scroll ni YANGI ekranga o'tkazardi.
    //   2) ScrollView/RecyclerView `android:saveEnabled` bilan O'ZI ham
    //      scroll holatini saqlardi → mening kodim bilan rachkalashardi.
    //
    //  Endi: layout ID lari unique, avtomatik saqlash O'CHIRILGAN,
    //  pozitsiya faqat shu fragment tomonidan boshqariladi.
    // ═══════════════════════════════════════════════════════════════
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        persistScrollPosition()
        outState.putInt(KEY_SCROLL_Y, savedScrollY)
    }

    override fun onViewStateRestored(savedInstanceState: android.os.Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        restoreScrollPosition()
    }

    /** Joriy scroll pozitsiyasini o'qiydi va eslab qoladi. */
    private fun persistScrollPosition() {
        try {
            val c = scrollContainer() ?: return
            savedScrollY = readScroll(c)
            // MainActivity'da saqlaymiz — fragment qayta yaratilsa ham qoladi
            (activity as? MainActivity)?.setScrollMemory("home", savedScrollY)
            // ★ FIX125-SCROLLDBG: diagnostika
            android.util.Log.i("NurVPN-SCROLL",
                "HOME persist: y=$savedScrollY container=${c.javaClass.simpleName}")
        } catch (_: Throwable) { }
    }

    /**
     * Saqlangan pozitsiyani tiklaydi.
     *
     * ★ FIX121-SCROLL: avval `maxOf(savedScrollY, fromActivity)` edi —
     * bu XATO edi: eski katta qiymat yangi kichik qiymatni bosib ketardi
     * va scroll "sakrab" ketardi. Endi fragment'ning o'z qiymati ustuvor,
     * u 0 bo'lsa Activity xotirasidan olinadi.
     */
    private fun restoreScrollPosition() {
        try {
            val fromActivity = (activity as? MainActivity)
                ?.getScrollMemory("home") ?: 0
            val y = if (savedScrollY > 0) savedScrollY else fromActivity
            // ★ FIX125-SCROLLDBG: diagnostika
            android.util.Log.i("NurVPN-SCROLL",
                "HOME restore: saved=$savedScrollY fromActivity=$fromActivity -> y=$y")
            if (y <= 0) return
            savedScrollY = y
            view?.post {
                try {
                    val c = scrollContainer()
                    android.util.Log.i("NurVPN-SCROLL",
                        "HOME restore POST: container=${c?.javaClass?.simpleName} " +
                            "before=${c?.let { readScroll(it) }} target=$y")
                    if (c != null) writeScroll(c, y)
                    android.util.Log.i("NurVPN-SCROLL",
                        "HOME restore POST keyin: actual=${c?.let { readScroll(it) }}")
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    /** Scroll konteynerini qaytaradi. */
    private fun scrollContainer(): android.view.View? =
        view?.findViewById(R.id.home_scroll_root)

    private fun readScroll(c: android.view.View): Int = when (c) {
        is androidx.recyclerview.widget.RecyclerView ->
            c.computeVerticalScrollOffset()
        is android.widget.ScrollView -> c.scrollY
        else -> 0
    }

    private fun writeScroll(c: android.view.View, y: Int) {
        when (c) {
            is androidx.recyclerview.widget.RecyclerView -> {
                // ⚠️ scrollToPositionWithOffset faqat LinearLayoutManager'da.
                //    Boshqa LayoutManager'da scrollToPosition ishlatiladi.
                val lm = c.layoutManager
                if (lm is androidx.recyclerview.widget.LinearLayoutManager) {
                    lm.scrollToPositionWithOffset(y, 0)
                } else {
                    lm?.scrollToPosition(y)
                }
            }
            is android.widget.ScrollView -> c.scrollTo(0, y)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ FIX_V1122_WARP_LINKS: MASQUE WARP Generator havolalari
    //
    //  Fon maydoni VA `btn_globe` yo'q. MASQUE 1-11, Hysteria2 va scroll
    //  mantiqiga ALOQASI YO'Q — faqat yangi dialog.
    // ══════════════════════════════════════════════════════════════════════

    /** Havolalar ro'yxati (tartib UI'da saqlanadi). */
    private val warpGeneratorLinks = listOf(
        "https://warp-generation.github.io/",
        "https://warp-gen.cyb-portal.org/",
        "https://warpgen.net/",
        "https://warp-generator.vercel.app/"
    )

    /**
     * Chiroyli BottomSheet: sarlavha + 4 havola + ogohlantirish.
     *
     * Har bir havola bosilganda tizim brauzeri ochiladi. Brauzer
     * bo'lmasa — Toast (crash EMAS).
     */
    private fun showWarpGeneratorSheet(hostCtx: Context) {
        val dp = resources.displayMetrics.density
        fun dp2px(v: Int) = (v * dp).toInt()

        val content = android.widget.ScrollView(hostCtx).apply {
            isFillViewport = true
        }

        val col = LinearLayout(hostCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(8), dp2px(20), dp2px(4))
        }
        content.addView(col)

        // ── Sarlavha ────────────────────────────────────────────────────
        col.addView(TextView(hostCtx).apply {
            text = getString(R.string.warp_gen_title)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(hostCtx, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp2px(6), 0, dp2px(12))
        })

        // ── 4 ta havola ────────────────────────────────────────────────
        warpGeneratorLinks.forEachIndexed { idx, link ->
            val row = LinearLayout(hostCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                // Material "ripple" effekti — bosilganda ko'rinadi.
                background = android.util.TypedValue().let { tv ->
                    hostCtx.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, tv, true)
                    @Suppress("DEPRECATION")
                    hostCtx.resources.getDrawable(tv.resourceId, hostCtx.theme)
                }
                setPadding(dp2px(4), dp2px(12), dp2px(4), dp2px(12))
            }

            row.addView(TextView(hostCtx).apply {
                text = "${idx + 1}."
                setTextColor(androidx.core.content.ContextCompat
                    .getColor(hostCtx, R.color.accent))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                minWidth = dp2px(24)
            })

            row.addView(TextView(hostCtx).apply {
                text = link
                setTextColor(androidx.core.content.ContextCompat
                    .getColor(hostCtx, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            row.setOnClickListener { openWarpLink(hostCtx, link) }
            col.addView(row)
        }


        // ── FIX_V1123_AUTHOR: Loyiha muallifi (GitHub) ──────────────────────────
        //  Kichik va xira — dialog hajmini oshirmasligi uchun. Bosilganda
        //  mavjud `openWarpLink()` ishlatiladi (yangi kod YO'Q).
        col.addView(TextView(hostCtx).apply {
            text = getString(R.string.warp_gen_author)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(hostCtx, R.color.accent))
            textSize = 12f
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp2px(6)
            layoutParams = lp
            setPadding(dp2px(2), dp2px(6), dp2px(2), dp2px(4))
            setOnClickListener { openWarpLink(hostCtx, "https://github.com/javoxir5543/NurVPN") }
        })

        // ── Ogohlantirish (3 tilda) ─────────────────────────────────────
        col.addView(TextView(hostCtx).apply {
            text = getString(R.string.warp_gen_warning)
            setTextColor(androidx.core.content.ContextCompat
                .getColor(hostCtx, R.color.text_secondary))
            textSize = 12f
            val lp = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp2px(14)
            layoutParams = lp
            setPadding(dp2px(2), 0, dp2px(2), dp2px(6))
        })

        androidx.appcompat.app.AlertDialog.Builder(hostCtx)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Havolani tizim brauzerida ochadi. Brauzer yo'q → Toast. */
    private fun openWarpLink(hostCtx: Context, link: String) {
        try {
            hostCtx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (t: Throwable) {
            Toast.makeText(hostCtx,
                getString(R.string.warp_gen_browser_missing),
                Toast.LENGTH_SHORT).show()
        }
    }

}


// ═══════════════════════════════════════════════════════════════
