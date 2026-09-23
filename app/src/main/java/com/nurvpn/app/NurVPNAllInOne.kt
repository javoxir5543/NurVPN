// ═══════════════════════════════════════════════════════════════
// NurVPN UI — Fragmentlar, Activitylar (tuzatilgan)
// ═══════════════════════════════════════════════════════════════
package com.nurvpn.app

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

// ═══════════════════════════════════════════════════════════════
// AWGEditorActivity ↔ MainActivity ma'lumot almashish
// ═══════════════════════════════════════════════════════════════
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

// ═══════════════════════════════════════════════════════════════
// 1. HomeFragment
// ═══════════════════════════════════════════════════════════════
    /** Serverdan kelgan obuna ma'lumotlari (header'dan). */
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
        if (isAdded) rebuildServerCards()
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
                downText?.text = formatSpeed(downPerSecond)
                upText?.text = formatSpeed(upPerSecond)
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
        connectBtn = v.findViewById(R.id.connect_btn)
        connectIcon = v.findViewById(R.id.connect_icon)
        statusText = v.findViewById(R.id.status_text)
        serverFlag = v.findViewById(R.id.server_flag)
        serverName = v.findViewById(R.id.server_name)
        pingText = v.findViewById(R.id.ping_text)
        timerText = v.findViewById(R.id.timer_text)
        downText = v.findViewById(R.id.down_text)
        upText = v.findViewById(R.id.up_text)

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
            items.add("☑ Tanlash rejimi")
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
                        Toast.makeText(c, "O'chirildi", Toast.LENGTH_SHORT).show()
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
            items.add("☑ Tanlash rejimi")
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
                        Toast.makeText(c, "O'chirildi", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Nusxalandi", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(context, "Xato: ${t.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Xato: ${t.message}", Toast.LENGTH_SHORT).show()
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
        // ⭐ Avtomatik ping_asc
        SubscriptionStore.setSortMode(requireContext(), subId, "ping_asc")
        a.subscriptions = SubscriptionStore.load(a)
        val servers = a.servers.filter { it.subId == subId }
        if (servers.isEmpty()) {
            Toast.makeText(context, R.string.no_servers_in_sub,
                Toast.LENGTH_SHORT).show()
            return
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
                if (subId in homeExpandedSubs) {
                    homeExpandedSubs.remove(subId)
                } else {
                    homeExpandedSubs.add(subId)
                }
                // Qayta chizish — lazy loading ishlashi uchun
                ui.post { rebuildServerCards() }
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
                a.currentAWG = cfg
                a.protocol = MainActivity.PROTO_AWG
                Toast.makeText(context, cfg.name ?: getString(R.string.text_awg_label), Toast.LENGTH_SHORT).show()
                refresh()
            }
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
                    System.gc()
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
                    rebuildServerCards()
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
// 2. ServersFragment
// ═══════════════════════════════════════════════════════════════
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

        // ═══ Serverlar (barcha protokollar: TCP + ICMP) ═══
        val pingable = a.servers

        if (pingable.isNotEmpty()) {
            var scheduled = false
            val runnable = Runnable { ad?.notifyDataSetChanged() }
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            PingTester.testAll(pingable, object : PingTester.Listener {
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    if (!scheduled) {
                        scheduled = true
                        h.postDelayed({ scheduled = false; runnable.run() }, 400)
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
        val options = arrayOf("\u270D Qo\'lda kiritish", "\uD83D\uDCC1 JSON fayldan")
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
                    Toast.makeText(context, "WireGuard topilmadi",
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
            Toast.makeText(context, "JSON fayl emas", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "Server topilmadi", Toast.LENGTH_LONG).show()
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
            val isExpanded: Boolean = false
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
                Toast.makeText(frag.requireContext(), "$n o'chirildi", Toast.LENGTH_SHORT).show()
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

            for ((subId, list) in grouped) {
                val sub = if (subId != null) a.subscriptions.find { it.id == subId } else null
                val title = sub?.name ?: "Qo'lda qo'shilgan"
                val icon = if (sub != null) "📡" else "🔧"
                val key = subId ?: "manual"
                val isExpanded = expandedSubscriptions.contains(key)
                rows.add(Row.Header(subId, "$icon $title", list.size, false, isExpanded))
                if (isExpanded) {
                    for (si in list) rows.add(Row.Item(si))
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
            // ⭐ Avtomatik ping_asc — eng tez birinchi
            SubscriptionStore.setSortMode(ctx, subId, "ping_asc")
            a.subscriptions = SubscriptionStore.load(a)
            val servers = a.servers.filter { it.subId == subId }
            if (servers.isEmpty()) {
                Toast.makeText(ctx, R.string.no_servers_in_sub,
                    Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(ctx,
                ctx.getString(R.string.ping_sub_started, subName, servers.size),
                Toast.LENGTH_SHORT).show()
            android.util.Log.i("NurVPN-PING",
                "Ping sub: $subName (${servers.size})")

            PingTester.testAll(servers, object : PingTester.Listener {
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

                // ⚙️ sozlama (faqat sub uchun)
                settings?.visibility = if (row.subId != null) View.VISIBLE else View.GONE
                settings?.setOnClickListener {
                    val sid = row.subId ?: return@setOnClickListener
                    if (sid == Row.AWG_HEADER_ID) {
                        showAwgSettingsDialogServer()
                    } else {
                        showSubSettingsDialogServer(sid, row.name)
                    }
                }

                // 📶 ping — sub uchun yoki AWG uchun
                ping?.visibility = if (row.subId != null) View.VISIBLE else View.GONE
                ping?.setOnClickListener {
                    val sid = row.subId ?: return@setOnClickListener
                    if (sid == Row.AWG_HEADER_ID) {
                        pingAwgAll()
                    } else {
                        pingSubscription(sid, row.name)
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
                items.add(c.getString(R.string.dialog_rename_awg))
                actions.add { editAwgName(target) }
            }

            // ═══ Tanlash rejimi ═══
            items.add("☑ Tanlash rejimi")
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

// ═══════════════════════════════════════════════════════════════
// 3. SettingsFragment
// ═══════════════════════════════════════════════════════════════
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
            "Qo'lda kiritish…"
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
                v2.findViewById<TextView>(R.id.leak_ipv6)?.text =
                    "IPv6: ${r.ipv6}$ipv6Icon"
                v2.findViewById<TextView>(R.id.leak_dns)?.text =
                    "DNS: ${r.dns}" + if (r.dnsOk) " ✅" else " ⚠️"
            }
        }
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

// ═══════════════════════════════════════════════════════════════
// 4. AWGEditorActivity
// ═══════════════════════════════════════════════════════════════
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
        cfg.rawConf = buildResult()
        AWGStore.save(this, AWGEditorBus.configs)
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

// ═══════════════════════════════════════════════════════════════
// 5. SplitAppsActivity
// ═══════════════════════════════════════════════════════════════
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
