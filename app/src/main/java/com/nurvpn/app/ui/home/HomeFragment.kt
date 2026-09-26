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
