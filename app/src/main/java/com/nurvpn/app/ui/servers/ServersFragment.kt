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
import com.nurvpn.app.util.HwidHelper
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.parser.ServerLinkParser
import com.nurvpn.app.parser.Crypt1Decoder
import com.nurvpn.app.parser.decodeBase64Safely
import com.nurvpn.app.parser.SubscriptionLinkExtractor
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.storage.AwgSortStore
import com.nurvpn.app.storage.OpenSourceCatalog
import com.nurvpn.app.storage.OpenSourceStore
import com.nurvpn.app.storage.ManualSortStore
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.storage.SubscriptionStore
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.ui.awg.AWGEditorActivity
import com.nurvpn.app.ui.qr.QrScanActivity
import com.nurvpn.app.ui.qr.QrShowDialog
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.MaskHelper
import com.nurvpn.app.util.PingTester
import java.util.Locale

class ServersFragment : Fragment() {

    companion object {
        private const val REQ_EDIT_SERVER = 2002

        /** ★ TUZATISH #8: scroll holati kaliti (Servers ekrani). */
        private const val KEY_SCROLL_Y = "scroll_y_servers"

        /** ★ TUZATISH #9: JSON outbound protokollari → link protokollari. */
        private val JSON_PROTOCOLS = setOf(
            "vless", "vmess", "trojan", "shadowsocks",
            "hysteria2", "hy2", "tuic", "anytls"
        )
    }


    private var rv: RecyclerView? = null

    // ★ TUZATISH #4: har bir fragment O'Z scroll holatini saqlaydi.
    // (Home va Servers almashganda scroll aralashib ketmasligi uchun)
    /** ★ TUZATISH #8: shu fragmentning scroll pozitsiyasi (mustaqil). */
    private var savedScrollY: Int = 0

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

    /** YAML fayl tanlash uchun (.yaml, .yml) */
    private val yamlFilePicker = registerForActivityResult(
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
            android.util.Log.i("NurVPN-AWG", "YAML o'qildi: ${text.length} belgi")
            addAWG(text)
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-AWG", "YAML o'qish xato", t)
            Toast.makeText(context, getString(R.string.toast_error_fmt, t.message),
                Toast.LENGTH_LONG).show()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EDIT_SERVER &&
            resultCode == android.app.Activity.RESULT_OK) {
            val newLink = data?.getStringExtra("new_link") ?: return
            val oldLink = data?.getStringExtra("old_link") ?: return
            val a = activity as? MainActivity ?: return
            val idx = a.servers.indexOfFirst { it.link == oldLink }
            if (idx < 0) return
            val parsed = ServerLinkParser.parse(newLink) ?: return
            parsed.subId = a.servers[idx].subId
            parsed.favorite = a.servers[idx].favorite
            a.servers[idx] = parsed
            if (a.currentServer?.link == oldLink) {
                a.currentServer = parsed
                a.prefs.edit().putString("current_link", newLink).apply()
            }
            ServerStore.save(a, a.servers)
            ad?.notifyDataChanged()
            Toast.makeText(context, R.string.editor_saved,
                Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_servers, container, false)

        // ═══ Tanlash rejimi paneli ═══
        selectionBar = v.findViewById(R.id.srv_selection_bar)
        selCountText = v.findViewById(R.id.srv_sel_count)
        v.findViewById<View>(R.id.srv_sel_delete)?.setOnClickListener {
            ad?.deleteSelected()
        }
        v.findViewById<View>(R.id.srv_sel_cancel)?.setOnClickListener {
            ad?.exitSelectMode()
        }
        v.findViewById<View>(R.id.srv_sel_select_all)?.setOnClickListener {
            ad?.selectAllVisible()
        }
        rv = v.findViewById(R.id.server_list)
        search = v.findViewById(R.id.search_input)

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX112-BUG1: nested scrolling O'CHIRILADI (false).
        //
        //  ⚠️ MUHIM: `true` va `false` ma'nosi bu yerda TESKARI tuyuladi.
        //
        //    nestedScrollingEnabled = true
        //       → RecyclerView "nested scrolling" oilasiga qo'shiladi va
        //         scroll'ni OTA (parent) konteyner bilan BO'LISHADI.
        //         Eski ekran (Home ScrollView) ham scroll bo'ladi.  ❌
        //
        //    nestedScrollingEnabled = false
        //       → RecyclerView o'z scroll'ini O'ZI boshqaradi,
        //         parent'ga uzatmaydi. Mustaqil ishlaydi.        ✓
        //
        //  Bu — BUG#1 ning aynan sababi edi: "Servers'da scroll qilsam
        //  orqadagi Home ekrani scroll bo'ladi".
        // ═══════════════════════════════════════════════════════════════
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX126-SERVERSCROLL: TOUCH EVENT USHLAB QOLINISHI (ILDIZ)
        //
        //  QURILMADA TASDIQLANDI (00:58:47):
        //    SERVERS onScrolled: dy=0 offset=0 range=12851 extent=592
        //                                     ↑
        //    Ro'yxat 12851px uzun, lekin scroll BO'LMADI — offset 0 da
        //    qotib qoldi. Foydalanuvchi: "server bo'limida joyidan
        //    qimirlamayapti, asosiy ekranga ta'sir qilyapti".
        //
        //  ═══ ILDIZ SABAB ═══
        //
        //  `isFocusableInTouchMode = true` — RecyclerView TOUCH MODE'da
        //  FOKUS OLADI. Bu quyidagiga olib keladi:
        //
        //    1) RecyclerView `requestFocus()` qiladi va
        //       `OnTouchListener`/gesture detector "fokus olish"
        //       rejimiga o'tadi — scroll gesture YUTILADI.
        //    2) `descendantFocusability = FOCUS_AFTER_DESCENDANTS` bilan
        //       birgalikda touch event zanjiri buziladi.
        //    3) Touch event OTA ViewGroup'ga (Home ScrollView) o'tib
        //       ketadi — shuning uchun "asosiy ekranga ta'sir qilyapti".
        //
        //  ═══ YECHIM ═══
        //
        //  1) `isFocusableInTouchMode = true` OLIB TASHLANADI — RecyclerView
        //     touch mode'da fokus olmasligi kerak, u faqat scroll qiladi.
        //  2) `descendantFocusability = FOCUS_BLOCK_DESCENDANTS` — touch
        //     event bolalarga emas, RecyclerView'ning O'ZIGA boradi.
        //  3) `isNestedScrollingEnabled` OLIB TASHLANADI — Servers layout
        //     ildizi `FrameLayout`, ichida ScrollView YO'Q. Nested scrolling
        //     oilasiga qo'shilishning hojati yo'q, u faqat gesture'ni
        //     OTA konteynerga uzatishga urinadi (aynan shu "asosiy ekranga
        //     ta'sir qilish" muammosini keltirib chiqaradi).
        // ═══════════════════════════════════════════════════════════════
        rv?.overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
        rv?.isFocusable = false
        rv?.isFocusableInTouchMode = false
        rv?.descendantFocusability =
            android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val a = activity as? MainActivity
        ad = ServerAdapter(this, a)
        // ★ TUZATISH #10: standart LinearLayoutManager (mustaqil scroll)
        rv?.layoutManager = LinearLayoutManager(context)
        rv?.setHasFixedSize(true)
        rv?.adapter = ad
        // ★ TUZATISH #10: itemAnimator — ro'yxat almashganda sakrashni oldini oladi
        rv?.itemAnimator = null

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX125-SCROLLDBG: REAL-VAQT SCROLL KUZATUVI
        //
        //  Bu diagnostika uchun: har bir scroll harakati logga yoziladi.
        //  Biz shu bilan ANIQLAYMIZ:
        //    • Servers scroll bo'lganda Home ham scroll bo'ladimi?
        //    • Fragment yashirin bo'lganda ham listener ishga tushadimi?
        // ═══════════════════════════════════════════════════════════════
        rv?.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(r: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val off = r.computeVerticalScrollOffset()
                val range = r.computeVerticalScrollRange()
                val ext = r.computeVerticalScrollExtent()
                android.util.Log.i("NurVPN-SCROLL",
                    "SERVERS onScrolled: dy=$dy offset=$off range=$range extent=$ext " +
                        "hidden=$isHidden viewVis=${view?.visibility} " +
                        "attached=${isAdded} activityVis=${activity?.hasWindowFocus()}")
            }
        })

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
            Protocol.SS_2022 -> "ss"
            Protocol.HYSTERIA2 -> "hy2"
            Protocol.TUIC -> "tuic"
            Protocol.SOCKS -> "socks"
            Protocol.HTTP -> "http"
            Protocol.SSH -> "ssh"
            Protocol.SSR -> "ssr"
            Protocol.SHADOWTLS -> "shadowtls"
            Protocol.ANYTLS -> "anytls"
            Protocol.JUICITY -> "juicity"
            Protocol.SS_LEGACY -> "ss"
        }

    /** Adapterni yangilash (ochiq manba o'zgarganda chaqiriladi). */
    fun refreshServers() {
        // ★ FIX127-ADAPTER: rebuild() ham kerak — aks holda yangi
        // serverlar `rows` ga tushmaydi va ro'yxat bo'sh qoladi
        ad?.rebuildAndNotify()
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
        // ═══ sing-box endpoints (WireGuard) import O'CHIRILGAN ═══
        // if (trimmed.startsWith("{") && trimmed.contains("\"endpoints\"")) {
        //     importWireGuardEndpoints(trimmed, fileName)
        //     return
        // }
        val subId = "jsonfile_" + System.currentTimeMillis().toString(36)
        val subName = fileName.removeSuffix(".json").ifBlank { "JSON Fayl" }

        Thread {
            val servers = ArrayList<ServerItem>()
            var awgAdded = 0
            try {
                // ═══ WireGuard (AWG) outbound import O'CHIRILGAN ═══
                // JSON fayldan avtomatik AWG qo'shilmaydi (foydalanuvchi talabi).
                // Faqat QR va .conf fayl orqali qo'shiladi.

                // ═══ Mavjud parsing (Xray proxy linklar) ═══
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
                if (servers.isEmpty()) {  // awgAdded endi doim 0 (JSON import o'chirilgan)
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
                Toast.makeText(context,
                    getString(R.string.toast_n_servers_added_fmt, added),
                    Toast.LENGTH_SHORT).show()
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
            getString(R.string.awg_from_file),
            getString(R.string.awg_from_yaml)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_awg)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAWGManualDialog()
                    1 -> awgFilePicker.launch("*/*")
                    2 -> yamlFilePicker.launch("*/*")
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

        // ★ YAML → MASQUE / WireGuard .conf konvertatsiya
        var finalConf = conf
        if (isClashYamlMasque(conf)) {
            val converted = convertYamlMasqueToConf(conf)
            if (converted == null) {
                Toast.makeText(context, getString(R.string.toast_yaml_masque_error),
                    Toast.LENGTH_LONG).show()
                return
            }
            finalConf = converted
            android.util.Log.i("NurVPN-AWG", "YAML MASQUE → conf:\n$converted")
        } else if (conf.contains("type: wireguard") && conf.contains("private-key")) {
            // ★ WireGuard YAML → .conf (bir yoki bir nechta proxy)
            val confs = try {
                com.nurvpn.app.parser.ClashWgYaml.convert(conf)
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-AWG", "WG YAML convert xato", t)
                emptyList<String>()
            }
            if (confs.isEmpty()) {
                Toast.makeText(context, getString(R.string.toast_yaml_wg_error),
                    Toast.LENGTH_LONG).show()
                return
            }
            if (confs.size == 1) {
                finalConf = confs[0]
                android.util.Log.i("NurVPN-AWG", "YAML WireGuard → conf:\n${confs[0]}")
            } else {
                // Ko'p proxy — hammasini ketma-ket qo'shish
                var added = 0
                var lastCfg: AWGConfig? = null
                for (c in confs) {
                    val r = AWGParser.parse(c)
                    if (!r.ok) continue
                    val cfg = AWGConfig(c)
                    cfg.endpoint = r.endpoint
                    cfg.address = r.address
                    cfg.name = "AWG " + r.endpoint
                    a.awgConfigs.add(cfg)
                    lastCfg = cfg
                    added++
                }
                val cfg = lastCfg ?: return
                AWGStore.save(a, a.awgConfigs)
                a.selectAWG(cfg)
                a.protocol = MainActivity.PROTO_AWG
                AWGEditorBus.init(a.awgConfigs, cfg, MainActivity.PROTO_AWG)
                ad?.notifyDataChanged()
                Toast.makeText(context,
                    getString(R.string.toast_n_wg_added_fmt, added),
                    Toast.LENGTH_SHORT).show()
                return
            }
        }

        // ★ MASQUE bypass
        if (isMasqueConfText(finalConf)) {
            val cfg = AWGConfig(finalConf)
            val pair = extractMasqueServer(finalConf)
            cfg.type = "masque"
            cfg.endpoint = pair.first + ":" + pair.second
            cfg.address = ""
            cfg.name = "MASQUE " + pair.first
            a.awgConfigs.add(cfg)
            AWGStore.save(a, a.awgConfigs)
            a.selectAWG(cfg)
            a.protocol = MainActivity.PROTO_AWG
            AWGEditorBus.init(a.awgConfigs, cfg, MainActivity.PROTO_AWG)
            ad?.notifyDataChanged()
            Toast.makeText(context,
                    getString(R.string.toast_awg_added_endpoint, pair.first),
                    Toast.LENGTH_SHORT).show()
            return
        }

        val r = AWGParser.parse(finalConf)
        if (!r.ok) {
            Toast.makeText(context, getString(R.string.toast_awg_config_error, r.error), Toast.LENGTH_LONG).show()
            return
        }
        val cfg = AWGConfig(finalConf)
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

    private fun isClashYamlMasque(text: String): Boolean {
        return text.contains("type: masque") ||
               (text.contains("proxies:") && text.contains("&msq"))
    }

    private fun isMasqueConfText(text: String): Boolean {
        if (text.contains("[Interface]") || text.contains("[Peer]")) return false
        if (!text.contains("PrivateKey") || !text.contains("PublicKey")) return false
        if (!text.contains("Server") && !text.contains("Endpoint")) return false
        return true
    }

    private fun extractMasqueServer(conf: String): Pair<String, Int> {
        var srv = ""
        var prt = 443
        for (line in conf.lines()) {
            val t = line.trim()
            if (t.startsWith("Server") && t.contains("=")) {
                srv = t.substringAfter("=").trim().substringBefore(":")
            } else if (t.startsWith("Endpoint") && t.contains("=")) {
                srv = t.substringAfter("=").trim().substringBefore(":")
            } else if (t.startsWith("Port") && t.contains("=")) {
                prt = t.substringAfter("=").trim().toIntOrNull() ?: 443
            }
        }
        return Pair(srv, prt)
    }

    private fun convertYamlMasqueToConf(yaml: String): String? {
        val lines = yaml.lines()
        var privateKey = ""
        var publicKey = ""
        var ip = ""
        var ipv6 = ""
        var mtu = "1280"
        var server = ""
        var port = "443"
        var sni = ""

        // 1) Anchor (&msq) blokni o qish
        var inAnchor = false
        var anchorIndent = -1
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (trimmed.startsWith("msq:") || trimmed.contains("&msq")) {
                inAnchor = true
                anchorIndent = indent
                continue
            }
            if (inAnchor) {
                if (indent <= anchorIndent) { inAnchor = false; continue }
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim().trim('"')
                    when (k) {
                        "private-key", "private_key" -> privateKey = v
                        "public-key", "public_key" -> publicKey = v
                        "ip" -> ip = v
                        "ipv6" -> ipv6 = v
                        "mtu" -> mtu = v
                    }
                }
            }
        }

        // 2) proxies: bo limidan birinchi MASQUE yozuvni o qish
        var inProxies = false
        var inFirstProxy = false
        var proxyIndent = -1
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "proxies:") { inProxies = true; continue }
            if (!inProxies) continue

            if (trimmed.startsWith("- ") && !inFirstProxy) {
                val first = trimmed.removePrefix("- ").trim()
                val parts = first.split(":", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim().trim('"')
                    when (k) {
                        "server" -> server = v
                        "port" -> port = v
                        "sni" -> sni = v
                        "private-key", "private_key" -> privateKey = v
                        "public-key", "public_key" -> publicKey = v
                        "ip" -> ip = v
                        "ipv6" -> ipv6 = v
                        "mtu" -> mtu = v
                    }
                }
                inFirstProxy = true
                proxyIndent = line.indexOfFirst { !it.isWhitespace() }
                continue
            }

            if (inFirstProxy) {
                if (trimmed.isEmpty()) continue
                val indent = line.indexOfFirst { !it.isWhitespace() }
                if (trimmed.startsWith("- ") && indent <= proxyIndent + 2) break
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim().trim('"')
                    when (k) {
                        "server" -> server = v
                        "port" -> port = v
                        "sni" -> sni = v
                        "private-key", "private_key" -> privateKey = v
                        "public-key", "public_key" -> publicKey = v
                        "ip" -> ip = v
                        "ipv6" -> ipv6 = v
                        "mtu" -> mtu = v
                    }
                }
            }
        }

        if (privateKey.isEmpty() || publicKey.isEmpty() || server.isEmpty()) return null
        val addr = if (ipv6.isNotEmpty()) ip + ", " + ipv6 else ip

        return "PrivateKey = " + privateKey + "\n" +
               "PublicKey = " + publicKey + "\n" +
               "Address = " + addr + "\n" +
               "MTU = " + mtu + "\n" +
               "Server = " + server + "\n" +
               "Port = " + port + "\n" +
               "SNI = " + sni + "\n" +
               "Profile = cloudflare\n"
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
        MaskHelper.attach(urlEt)
        container.addView(nameEt)
        container.addView(urlEt)

        AlertDialog.Builder(c)
            .setTitle(R.string.dialog_add_sub)
            .setView(container)
            .setPositiveButton(R.string.dialog_load) { _, _ ->
                var url = urlEt.text.toString().trim()
                var name = nameEt.text.toString().trim().ifEmpty { null }
                if (url.isEmpty()) {
                    Toast.makeText(c, R.string.toast_url_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // ═══ SHIFRLANGAN LINK (crypt1) → OBUNA URL ═══
                var wasEncrypted = false
                if (Crypt1Decoder.isCryptLink(url)) {
                    val decoded = Crypt1Decoder.decode(c, url)
                    if (decoded == null) {
                        Toast.makeText(c,
                            c.getString(R.string.encrypted_decode_failed),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    url = decoded.first
                    if (name.isNullOrEmpty()) name = decoded.second
                    wasEncrypted = true
                    android.util.Log.i("NurVPN-CRYPT1",
                        "Decoded → ${url.take(60)}... (name=$name)")
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
                loadSub(fixedUrl, name, wasEncrypted)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }


    /**
     * HWID — BARQAROR (qayta o'rnatilsa ham bir xil).
     *
     * ⚠️ Algoritm `HwidHelper` ga ko'chirildi (avval bu funksiya
     * HomeFragment'da bayt-baytga takrorlanardi). Xatti-harakat
     * O'ZGARMAGAN — `HwidHelperTest` buni qulflaydi.
     */
    private fun getHwid(): String = HwidHelper.fromContext(requireContext())

    private fun loadSub(url: String, subName: String?, wasEncrypted: Boolean = false) {
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

            // ═══════════════════════════════════════════════════════════════
            //  ★ TUZATISH #9: JSON OBUNA (Xray/sing-box konfiguratsiyasi)
            //
            //  Ba'zi obunalar (masalan sub.dark-bit.cc) base64 emas, balki
            //  to'g'ridan-to'g'ri Xray JSON qaytaradi:
            //     [{"dns":{...},"outbounds":[{"protocol":"vless",...}]}]
            //
            //  Avval bu shox FAQAT HomeFragment'da bor edi — Servers
            //  ekranidan qo'shilsa "link yo'q" xatosi chiqardi.
            // ═══════════════════════════════════════════════════════════════
            val trimmedBody = body.trim()
            if (trimmedBody.startsWith("[") || trimmedBody.startsWith("{")) {
                val jsonAdded = importJsonSubscription(trimmedBody, url)
                if (jsonAdded > 0) {
                    activity?.runOnUiThread {
                        Toast.makeText(context,
                            getString(R.string.toast_added_count, jsonAdded),
                            Toast.LENGTH_SHORT).show()
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
                if (wasEncrypted) currentSub.encrypted = true

                // Eski serverlarni olib tashlash
                a.servers.removeAll { it.subId == currentSub.id }

                var added = 0
                for (l in links) {
                    val si = ServerLinkParser.parse(l, currentSub.id) ?: continue
                    si.encrypted = wasEncrypted || currentSub.encrypted
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
            c.setRequestProperty("User-Agent", "INCY/3.7.0/android")
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

        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #3: yagona, TO'LIQ parser ishlatiladi (14 protokol)
        //    + base64 obuna avtomatik dekodlanadi.
        //
        //  Avval bu yerda 7 protolik shaxsiy regex bor edi va base64
        //  umuman qo'llab-quvvatlanmasdi → foydalanuvchi base64 obuna
        //  joylashtirsa "noto'g'ri link" xatosi chiqardi.
        // ═══════════════════════════════════════════════════════════════
        var matches = SubscriptionLinkExtractor.extract(text)

        // Base64 obuna bo'lishi mumkin (masalan `dmxlc3M6Ly8...`)
        if (matches.isEmpty()) {
            val decoded = com.nurvpn.app.parser.decodeBase64Safely(text)
            if (decoded.isNotEmpty()) {
                matches = SubscriptionLinkExtractor.extract(decoded)
                android.util.Log.i("NurVPN-DBG",
                    "addLink: base64 dekodlandi → ${matches.size} link")
            }
        }

        // Bitta link bo'lib, u URL (http/https) bo'lsa — obuna sifatida qo'shamiz
        if (matches.isEmpty()) {
            val t = text.trim()
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
                Toast.makeText(context, R.string.toast_sub_url_hint,
                    Toast.LENGTH_LONG).show()
                return
            }
        }

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
            rebuildAndNotify()
        }

        fun exitSelectMode() {
            selectMode = false
            selectedLinks.clear()
            selectedAwg.clear()
            frag.hideSelectionBar()
            rebuildAndNotify()
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

        init { rebuildAndNotify() }

        fun rebuild() {
            // ★ FIX131-FRIZ: vaqt o'lchovi (friz diagnostikasi)
            val __t0 = System.currentTimeMillis()
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

            // ═══════════════════════════════════════════════════════════
            //  ★ FIX130-ORDER: OBUNA TARTIBI (↑ Вверх / ↓ Вниз) ISHLAMASLIGI
            //
            //  ═══ ILDIZ SABAB ═══
            //
            //  Avval `grouped` LINKED HASH MAP edi va u guruhlarni
            //  `a.servers` ro'yxatidagi SERVER PAYDO BO'LISH tartibida
            //  qo'shardi:
            //
            //      for (si in allServers) {
            //          grouped.getOrPut(si.subId) { ArrayList() }.add(si)
            //      }
            //
            //  `moveSubscription()` esa `a.SUBSCRIPTIONS` ro'yxatidagi
            //  `order` maydonini o'zgartiradi (SubscriptionStore.save).
            //
            //  ⚠️ Bu IKKI XIL ro'yxat:
            //     • `a.subscriptions` (SubscriptionStore) — order o'zgaradi ✓
            //     • `a.servers`       (ServerStore)       — o'zgarmaydi  ✗
            //
            //  Natijada "↑ Вверх" bosilganda `order` yangilanardi, lekin
            //  ko'rsatish tartibi `a.servers` dan olinardi → KARTA
            //  JOYIDAN QIMIRLAMASDI.
            //
            //  ═══ YECHIM ═══
            //
            //  Guruhlarni `a.subscriptions` ning `order` maydoni bo'yicha
            //  tartiblaymiz. Manual serverlar (`subId == null`) eng oxirida.
            // ═══════════════════════════════════════════════════════════
            // ═══════════════════════════════════════════════════════════
            //  ★ FIX131-FRIZ-2: O(N²) → O(N) — FRIZNI KAMAYTIRISH
            //
            //  ═══ ILDIZ SABAB ═══
            //
            //  Avvalgi kod HAR BIR obuna uchun butun server ro'yxatini
            //  qaytadan aylanardi:
            //
            //      for (sub in a.subscriptions) {
            //          val list = allServers.filter { it.subId == sub.id }
            //      }                                   // 286 × N tekshiruv
            //
            //  Ustiga `a.subscriptions.find { it.id == subId }` ham HAR
            //  guruh uchun chiziqli qidiruv edi.
            //
            //  ═══ YECHIM ═══
            //
            //  Bitta o'tishda serverlarni subId bo'yicha guruhlaymiz,
            //  obunalarni esa `order` bo'yicha tartiblangan holda
            //  bitta Map'ga joylaymiz (O(1) qidiruv).
            // ═══════════════════════════════════════════════════════════

            // subId → ServerItem ro'yxati (BITTA o'tish, O(N))
            val bySub = HashMap<String?, MutableList<ServerItem>>()
            for (si in allServers) {
                bySub.getOrPut(si.subId) { ArrayList() }.add(si)
            }

            // id → Subscription (O(1) qidiruv uchun)
            val subById = HashMap<String, com.nurvpn.app.core.Subscription>(
                a.subscriptions.size * 2)
            for (sub in a.subscriptions) subById[sub.id] = sub

            val grouped = LinkedHashMap<String?, MutableList<ServerItem>>()
            // 1) Obunalar — `order` bo'yicha (O(1) qidiruv)
            for (sub in a.subscriptions.sortedBy { it.order }) {
                val list = bySub[sub.id] ?: continue
                if (list.isNotEmpty()) grouped[sub.id] = list
            }
            // 2) Katalogda yo'q obunalar (order'da topilmaganlar)
            for (si in allServers) {
                val key = si.subId ?: continue
                if (grouped.containsKey(key)) continue
                grouped[key] = bySub[key] ?: continue
            }
            // 3) Manual serverlar — ENG OXIRIDA
            bySub[null]?.let { if (it.isNotEmpty()) grouped[null] = it }

            // Har bir guruhni sortMode bo'yicha saralash
            val manualSortMode = ManualSortStore.getMode(
                act?.applicationContext ?: frag.requireContext())
            for ((subId, list) in grouped) {
                // ★ FIX131-FRIZ-2: O(1) qidiruv (avval chiziqli `find` edi)
                val subSort = if (subId != null)
                    subById[subId]?.sortMode ?: "default"
                    else manualSortMode
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
                // ★ FIX131-FRIZ-2: O(1) qidiruv
                val sub = if (subId != null) subById[subId] else null
                val title = sub?.name ?: frag.getString(R.string.manual_added)
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

            // ★ FIX131-FRIZ: rebuild vaqti (UI thread'ni bloklaydi)
            val __ms = System.currentTimeMillis() - __t0
            if (__ms > 8) {
                android.util.Log.w("NurVPN-PERF",
                    "⚠️ rebuild() SEKIN: ${__ms}ms, rows=${rows.size}, " +
                        "servers=${a.servers.size}")
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
            // ═══════════════════════════════════════════════════════════
            //  ★ FIX129-EXPAND: OBUNA KARTASI OCHILMAY/ YOPILMAY QOLISHI
            //
            //  ═══ ILDIZ SABAB ═══
            //
            //  Avval bu yerda faqat `rebuild()` chaqirilardi. `rebuild()`
            //  `rows` ro'yxatini qayta quradi, LEKIN RecyclerView'ga
            //  "ma'lumot o'zgardi" deb XABAR BERMAYDI.
            //
            //  RecyclerView `getItemCount()` ni qayta so'ramagani uchun
            //  ESKI holatda qoladi — obuna kartasini bosganingizda
            //  serverlar ro'yxati PASTGA/TEPAGA CHIQMAYDI.
            //
            //  Aynan foydalanuvchi shikoyati: "scroll emas, u obuna
            //  kartasini pastga tepaga chiqarish".
            //
            //  ═══ YECHIM ═══
            //
            //  `rebuildAndNotify()` — rebuild + notifyDataSetChanged().
            //  Shunda karta bosilganda serverlar darhol ochiladi/yopiladi.
            // ═══════════════════════════════════════════════════════════
            rebuildAndNotify()
        }

        private fun matchesQuery(name: String?): Boolean {
            if (currentQuery.isEmpty()) return true
            return name?.lowercase()?.contains(currentQuery.lowercase()) == true
        }

        fun filter(q: String) {
            currentQuery = q
            rebuildAndNotify()
        }

        fun setProtoFilter(key: String) {
            protoFilter = key
            // ★ FIX127-ADAPTER: rebuild + notify — filtr o'zgarganda
            // ro'yxat qayta hisoblanib, darhol chizilishi kerak
            rebuildAndNotify()
        }

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX127-ADAPTER: `notifyDataSetChanged()` YETISHMAYOTGAN EDI
        //
        //  QURILMADA TASDIQLANDI (01:03:53):
        //    SERVERS onScrolled: range=264 extent=264
        //                                  ↑        ↑
        //                        range == extent → RO'YXAT BO'SH
        //    Ekranda faqat sarlavha, filtr chiplar va FAB'lar ko'rinardi.
        //    Foydalanuvchi: "server bo'limida joyidan qimirlamayapti".
        //
        //  ═══ ILDIZ SABAB ═══
        //
        //  Avval bu metod faqat `rebuild()` ni chaqirardi. `rebuild()`
        //  `rows` ro'yxatini TO'LDIRADI, lekin RecyclerView'ga
        //  "ma'lumot o'zgardi" deb XABAR BERMAYDI. RecyclerView esa
        //  `getItemCount()` ni qayta so'ramaydi — natijada eski (bo'sh)
        //  holatda qoladi va item'lar CHIZILMAYDI.
        //
        //  ═══ YECHIM ═══
        //
        //  `rebuild()` dan keyin `notifyDataSetChanged()` — RecyclerView
        //  itemCount'ni qayta hisoblab, barcha item'larni chizadi.
        // ═══════════════════════════════════════════════════════════════
        // ★ FIX127-ADAPTER: rebuild + chizish.
        // ⚠️ DIQQAT: `rebuildAndNotify()` allaqachon notify qiladi,
        // ikkinchi marta chaqirish shart emas.
        fun notifyDataChanged() {
            rebuildAndNotify()
        }

        // ★ FIX131-FRIZ: rebuild + chizish (diagnostika bilan).
        fun rebuildAndNotify() {
            rebuild()
            notifyDataSetChanged()
            android.util.Log.i("NurVPN-SCROLL",
                "SERVERS rebuildAndNotify: rows=${rows.size} " +
                    "servers=${act?.servers?.size} awg=${act?.awgConfigs?.size} " +
                    "itemCount=$itemCount")
        }

        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX131-FRIZ: PING PAYTIDAGI KATTA FRIZNI YO'Q QILISH
        //
        //  ═══ ILDIZ SABAB ═══
        //
        //  Ping jarayonida HAR BIR server uchun shunday chaqirilardi:
        //
        //      override fun onPingUpdate(item: ServerItem, ping: Int) {
        //          notifyDataSetChanged()          // ← 200 MARTA!
        //      }
        //
        //  `notifyDataSetChanged()` RecyclerView'ga "HAMMASI o'zgardi"
        //  deydi va u BARCHA ko'rinadigan qatorlarni NOLDAN qayta
        //  yaratadi: `onCreateViewHolder` + `onBindViewHolder` har biri
        //  uchun qaytadan ishlaydi.
        //
        //  203 qatorli ro'yxatda 200 ta ping javobi kelsa:
        //      200 × 203 = 40 600 ta bind operatsiyasi!
        //
        //  Natijada asosiy oqim (UI thread) to'lib ketadi → FRIZ va LAG.
        //
        //  ═══ YECHIM ═══
        //
        //  Faqat O'SHA serverning qatori yangilanadi:
        //
        //      notifyItemChanged(positionIndex)
        //
        //  Bitta ping keldi → bitta qator qayta chiziladi (203 emas).
        //  Bu ~200 BAROBAR tezroq.
        //
        //  ═══════════════════════════════════════════════════════════════

        /**
         * ★ FIX131-FRIZ: faqat shu serverning qatorini yangilaydi.
         *
         * @return `true` — qator topilib yangilandi,
         *         `false` — qator ko'rinmayapti (rebuild kerak).
         */
        fun notifyPingChanged(item: ServerItem): Boolean {
            for (i in rows.indices) {
                val r = rows[i]
                if (r is Row.Item) {
                    val d = r.data
                    if (d is ServerItem && (d === item || d.link == item.link)) {
                        notifyItemChanged(i)
                        return true
                    }
                }
            }
            return false
        }

        /** ★ FIX131-FRIZ: faqat shu AWG configning qatorini yangilaydi. */
        fun notifyAwgPingChanged(cfg: AWGConfig): Boolean {
            for (i in rows.indices) {
                val r = rows[i]
                if (r is Row.Item) {
                    val d = r.data
                    if (d is AWGConfig && d === cfg) {
                        notifyItemChanged(i)
                        return true
                    }
                }
            }
            return false
        }

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

        /** Qisqa protokol nomi. */
        private fun protocolShort(p: Protocol): String = when (p) {
            Protocol.VLESS_REALITY -> "VLESS"
            Protocol.VMESS -> "VMESS"
            Protocol.TROJAN -> "TROJAN"
            Protocol.SS_2022 -> "SS"
            Protocol.SS_LEGACY -> "SS"
            Protocol.HYSTERIA2 -> "HY2"
            Protocol.TUIC -> "TUIC"
            Protocol.SOCKS -> "SOCKS"
            Protocol.HTTP -> "HTTP"
            Protocol.SSH -> "SSH"
            Protocol.SSR -> "SSR"
            Protocol.SHADOWTLS -> "ShadowTLS"
            Protocol.ANYTLS -> "AnyTLS"
            Protocol.JUICITY -> "Juicity"
        }

        /** Qisqa transport nomi. */
        private fun transportShort(t: String): String = when (t.lowercase()) {
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
            s.ensureTransport()
            // Faqat host:port (transport/security chip ichida)
            h.addr.text = (s.host ?: "?") + (if (s.port > 0) ":${s.port}" else "")

            // Protocol chip — Home uslubida: "VLESS · gRPC · REALITY"
            if (h.aiScore != null) {
                h.aiScore.visibility = View.VISIBLE
                s.ensureTransport()
                val parts = mutableListOf(protocolShort(s.protocol))
                if (s.transport.isNotEmpty() && s.transport != "tcp") {
                    parts.add(transportShort(s.transport))
                }
                if (s.security.isNotEmpty() && s.security != "none") {
                    parts.add(s.security.uppercase())
                }
                h.aiScore.text = parts.joinToString(" \u00B7 ")
                h.aiScore.setTextColor(when (s.protocol) {
                    Protocol.VLESS_REALITY, Protocol.VMESS, Protocol.TROJAN -> 0xFF4A9EFF.toInt()
                    Protocol.SS_2022, Protocol.SS_LEGACY -> 0xFFFFC107.toInt()
                    Protocol.HYSTERIA2 -> 0xFF9B59B6.toInt()
                    Protocol.TUIC -> 0xFFE91E63.toInt()
                    Protocol.SOCKS -> 0xFF00BCD4.toInt()
                    Protocol.HTTP -> 0xFF4CAF50.toInt()
                    Protocol.SSH -> 0xFF795548.toInt()
                    Protocol.SSR -> 0xFFFF5722.toInt()
                    Protocol.SHADOWTLS -> 0xFF607D8B.toInt()
                    Protocol.ANYTLS -> 0xFFE040FB.toInt()
                    Protocol.JUICITY -> 0xFF00E676.toInt()
                })
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

            val isMasque = awg.type == "masque"
            h.flag.text = if (isMasque) "🌐" else "🔒"
            h.name.text = awg.name ?: (if (isMasque) "MASQUE" else "AWG")
            h.addr.text = awg.endpoint ?: ""
            if (awg.ping > 0 && awg.ping < 9999) {
                h.ping.text = "${awg.ping}ms"
                h.ping.setTextColor(
                    if (awg.ping < 100) 0xFFC4F82A.toInt()
                    else if (awg.ping < 300) 0xFFFFC107.toInt()
                    else 0xFFFF5722.toInt()
                )
            } else {
                h.ping.text = if (awg.type == "masque") "MASQUE" else "AWG"
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
                // ★ autoConnect = true — VLESS kabi avtomatik ulanish
                act?.selectAWG(awg, autoConnect = true)
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
            // ⭐ Sevimlilar kartasi uchun maxsus
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
                    // ★ FIX131-FRIZ: faqat shu qator yangilanadi (203 emas!)
                    override fun onPingUpdate(item: ServerItem, ping: Int) {
                        if (!notifyPingChanged(item)) {
                            // Qator ko'rinmayapti — ehtiyotkorlik bilan
                            notifyDataSetChanged()
                        }
                    }
                    override fun onAllDone() {
                        ServerStore.save(a, a.servers)
                        notifyDataSetChanged()
                        Toast.makeText(ctx, R.string.ping_done,
                            Toast.LENGTH_SHORT).show()
                    }
                })
                return
            }

            SubscriptionStore.setSortMode(ctx, subId, "ping_asc")
            a.subscriptions = SubscriptionStore.load(a)
            var servers = a.servers.filter { it.subId == subId }
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
                // ★ FIX131-FRIZ: faqat shu qator yangilanadi (203 emas!)
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    if (!notifyPingChanged(item)) {
                        notifyDataSetChanged()
                    }
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
            val keys = arrayOf("default", "ping_asc", "ping_desc", "name_asc", "name_desc")
            val labels = arrayOf(
                ctx.getString(R.string.sort_default),
                ctx.getString(R.string.sort_ping_asc),
                ctx.getString(R.string.sort_ping_desc),
                ctx.getString(R.string.sort_name_asc),
                ctx.getString(R.string.sort_name_desc)
            )
            val idx = keys.indexOf(current).coerceAtLeast(0)

            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.awg_settings_title))
                .setSingleChoiceItems(labels, idx) { d, which ->
                    AwgSortStore.setMode(ctx, keys[which])
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
                    rebuildAndNotify()
                    d.dismiss()
                }
                .setNeutralButton(ctx.getString(R.string.move_up)) { _, _ ->
                    if (SubscriptionStore.moveSubscription(ctx, subId, -1)) {
                        a.subscriptions = SubscriptionStore.load(a)
                        rebuildAndNotify()
                    } else {
                        Toast.makeText(ctx, R.string.already_top, Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton(ctx.getString(R.string.move_down)) { _, _ ->
                    if (SubscriptionStore.moveSubscription(ctx, subId, 1)) {
                        a.subscriptions = SubscriptionStore.load(a)
                        rebuildAndNotify()
                    } else {
                        Toast.makeText(ctx, R.string.already_bottom, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        /** Qo'lda qo'shilgan serverlarni ping qilish. */
        fun pingManualAll() {
            val a = act ?: return
            val ctx = context ?: return
            val manual = a.servers.filter { it.subId == null }
            if (manual.isEmpty()) {
                Toast.makeText(ctx, R.string.toast_no_manual_servers,
                    Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(ctx,
                ctx.getString(R.string.ping_sub_started,
                    ctx.getString(R.string.manual_added), manual.size),
                Toast.LENGTH_SHORT).show()
            PingTester.testAll(manual, object : PingTester.Listener {
                // ★ FIX131-FRIZ: faqat shu qator yangilanadi (203 emas!)
                override fun onPingUpdate(item: ServerItem, ping: Int) {
                    if (!notifyPingChanged(item)) {
                        notifyDataSetChanged()
                    }
                }
                override fun onAllDone() {
                    ServerStore.save(a, a.servers)
                    notifyDataSetChanged()
                    Toast.makeText(ctx, R.string.ping_done,
                        Toast.LENGTH_SHORT).show()
                }
            })
        }

        /** Qo'lda qo'shilganlar uchun sozlamalar dialogi (sort + delete). */
        private fun showManualSettingsDialogServer() {
            val ctx = context ?: return
            val a = act ?: return
            val count = a.servers.count { it.subId == null }
            val current = ManualSortStore.getMode(ctx)
            val modes = arrayOf(
                "default" to ctx.getString(R.string.sort_default),
                "ping_asc" to ctx.getString(R.string.sort_ping_asc),
                "ping_desc" to ctx.getString(R.string.sort_ping_desc),
                "name_asc" to ctx.getString(R.string.sort_name_asc),
                "name_desc" to ctx.getString(R.string.sort_name_desc)
            )
            val labels = modes.map { it.second }.toTypedArray()
            val idx = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.manual_added))
                .setSingleChoiceItems(labels, idx) { d, which ->
                    ManualSortStore.setMode(ctx, modes[which].first)
                    rebuildAndNotify()
                    d.dismiss()
                }
                .setNeutralButton(
                    ctx.getString(R.string.sel_delete) + " (" + count + ")"
                ) { _, _ ->
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.dialog_manual_delete)
                        .setMessage(ctx.getString(
                            R.string.dialog_manual_delete_msg, count))
                        .setPositiveButton(R.string.dialog_delete_yes) { _, _ ->
                            a.servers.removeAll { it.subId == null }
                            ServerStore.save(a, a.servers)
                            rebuildAndNotify()
                            Toast.makeText(ctx, R.string.toast_deleted,
                                Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.dialog_no, null)
                        .show()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.sec_icon)
            val title: TextView = v.findViewById(R.id.sec_title)
            val count: TextView = v.findViewById(R.id.sec_count)
            val action: TextView = v.findViewById(R.id.sec_action)
            val settings: android.widget.ImageView? = v.findViewById(R.id.sec_settings)
            val ping: android.widget.ImageView? = v.findViewById(R.id.sec_ping)

            fun bind(row: Row.Header) {
                val parts = row.name.split(" ", limit = 2)
                icon.text = parts.getOrElse(0) { "📡" }
                title.text = parts.getOrElse(1) { row.name }
                // Asosiy matn: "N ta" + kun/GB
                var infoText = itemView.context.getString(R.string.text_count_ta, row.count)
                val sid = row.subId
                if (sid != null && sid != Row.AWG_HEADER_ID) {
                    val sub = act?.subscriptions?.find { it.id == sid }
                    if (sub != null) {
                        val extra = mutableListOf<String>()
                        if (sub.expireAt > 0) {
                            val days = ((sub.expireAt - System.currentTimeMillis()) / 86400_000L).toInt()
                            if (days < 0) extra.add("\uD83D\uDCC5 \u26D4")
                            else extra.add("\uD83D\uDCC5 ${days}d")
                        }
                        if (sub.trafficTotal > 0) {
                            val leftBytes = (sub.trafficTotal - sub.trafficUsed).coerceAtLeast(0L)
                            val leftG = leftBytes / 1024.0 / 1024 / 1024
                            extra.add(String.format(java.util.Locale.US, "\u2705 %.1fG", leftG))
                        }
                        if (extra.isNotEmpty()) infoText += "  " + extra.joinToString("  ")
                    }
                }
                count.text = infoText

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

                // ⚙️ sozlama — sub, AWG yoki manual
                settings?.visibility = View.VISIBLE
                settings?.setOnClickListener {
                    val sid = row.subId
                    when {
                        sid == Row.AWG_HEADER_ID -> showAwgSettingsDialogServer()
                        sid == null -> showManualSettingsDialogServer()
                        else -> showSubSettingsDialogServer(sid, row.name)
                    }
                }

                // 📶 ping — sub, AWG yoki manual
                ping?.visibility = View.VISIBLE
                ping?.setOnClickListener {
                    val sid = row.subId
                    when {
                        sid == Row.AWG_HEADER_ID -> pingAwgAll()
                        sid == null -> pingManualAll()
                        else -> pingSubscription(sid, row.name)
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
            val hideLabel = if (sub.hiddenOnHome)
                R.string.sub_menu_show_on_home
            else R.string.sub_menu_hide_from_home
            val hideIcon = if (sub.hiddenOnHome) "\uD83D\uDC41" else "\uD83D\uDE48"
            val items = arrayOf(
                "\uD83D\uDD04  " + c.getString(R.string.sub_menu_refresh),
                "\uD83D\uDCD1  " + c.getString(R.string.sub_menu_copy_url),
                "\uD83D\uDDD1  " + c.getString(R.string.sub_menu_delete),
                "$hideIcon  " + c.getString(hideLabel)
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
                        3 -> {
                            sub.hiddenOnHome = !sub.hiddenOnHome
                            act?.let { a -> SubscriptionStore.save(a, a.subscriptions) }
                            rebuildAndNotify()
                            val msg = if (sub.hiddenOnHome)
                                c.getString(R.string.sub_hidden_toast, sub.name)
                            else c.getString(R.string.sub_shown_toast, sub.name)
                            Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
                        }
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
                    rebuildAndNotify()
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
                    rebuildAndNotify()
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
                items.add("✏️ " + c.getString(R.string.editor_server_title))
                actions.add {
                    val i = Intent(c, com.nurvpn.app.ui.editor.ServerEditorActivity::class.java)
                    i.putExtra(com.nurvpn.app.ui.editor.ServerEditorActivity.EXTRA_LINK, target.link)
                    frag.startActivityForResult(i, REQ_EDIT_SERVER)
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




    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #10 (BUG #1): view tozalash
    //
    //  Fragment almashganda eski view daraxti yo'q qilinadi. Agar
    //  RecyclerView/LayoutManager havolalari qolsa, eski scroll holati
    //  yangi ekranga ta'sir qilishi mumkin. Shuning uchun tozalaymiz.
    // ═══════════════════════════════════════════════════════════════
    override fun onDestroyView() {
        try {
            // Scroll pozitsiyasini eslab qolamiz (keyin tiklash uchun)
            persistScrollPosition()
            // RecyclerView'ni bo'shatamiz
            rv?.adapter = null
            rv?.layoutManager = null
        } catch (_: Throwable) { }
        rv = null
        search = null
        selectionBar = null
        selCountText = null
        super.onDestroyView()
    }


    /**
     * ★ FIX121-SCROLL (BUG #1): tab ko'rsatilganda chaqiriladi.
     */
    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "ServersFragment: onTabShown")
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX128-SERVERLIST: `refreshServers()` QO'SHILDI
        //
        //  QURILMADA TASDIQLANDI (01:05:23):
        //    SERVERS onScrolled: range=264 extent=264  ← ro'yxat BO'SH
        //    286 ta server saqlangan, lekin ekranda ko'rinmaydi.
        //
        //  ═══ ILDIZ SABAB ═══
        //
        //  Avval bu metod FAQAT `restoreScrollPosition()` chaqirardi.
        //  Fragment birinchi marta `show()` qilinganda adapter allaqachon
        //  `init { rebuild() }` bilan qurilgan, LEKIN o'sha paytda
        //  `MainActivity.servers` hali YUKLANMAGAN bo'lishi mumkin
        //  (ServerStore.load() asinxron yoki keyinroq chaqiriladi).
        //
        //  Natijada `rows` BO'SH qoladi va tab'ga har o'tilganda ham
        //  qayta yuklash bo'lmagani uchun ro'yxat hech qachon
        //  to'lmaydi — foydalanuvchi "server bo'limida joyidan
        //  qimirlamayapti" deb ko'radi.
        //
        //  ═══ YECHIM ═══
        //
        //  Tab ko'rsatilganda `refreshServers()` — `rows` qayta
        //  quriladi va RecyclerView yangilanadi.
        // ═══════════════════════════════════════════════════════════════
        refreshServers()
        restoreScrollPosition()
    }

    /** ★ FIX121-SCROLL: fragment yashirilganda scroll holatini saqlaymiz. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            android.util.Log.i("NurVPN-DBG", "ServersFragment: yashirildi — scroll saqlanadi")
            persistScrollPosition()
        } else {
            android.util.Log.i("NurVPN-DBG", "ServersFragment: ko'rsatildi — scroll tiklanadi")
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
            (activity as? MainActivity)?.setScrollMemory("servers", savedScrollY)
            // ★ FIX125-SCROLLDBG: diagnostika
            android.util.Log.i("NurVPN-SCROLL",
                "SERVERS persist: y=$savedScrollY container=${c.javaClass.simpleName}")
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
                ?.getScrollMemory("servers") ?: 0
            val y = if (savedScrollY > 0) savedScrollY else fromActivity
            // ★ FIX125-SCROLLDBG: diagnostika
            android.util.Log.i("NurVPN-SCROLL",
                "SERVERS restore: saved=$savedScrollY fromActivity=$fromActivity -> y=$y")
            if (y <= 0) return
            savedScrollY = y
            view?.post {
                try {
                    val c = scrollContainer()
                    android.util.Log.i("NurVPN-SCROLL",
                        "SERVERS restore POST: container=${c?.javaClass?.simpleName} " +
                            "before=${c?.let { readScroll(it) }} target=$y")
                    if (c != null) writeScroll(c, y)
                    android.util.Log.i("NurVPN-SCROLL",
                        "SERVERS restore POST keyin: actual=${c?.let { readScroll(it) }}")
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    /** Scroll konteynerini qaytaradi. */
    private fun scrollContainer(): android.view.View? =
        view?.findViewById(R.id.server_list)

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

    /**
     * ★ TUZATISH #9: Xray/sing-box JSON obunasini import qiladi.
     *
     * Qo'llab-quvvatlanadi:
     *   • JSON massiv:  [ {outbounds:[{protocol:"vless",...}]}, ... ]
     *   • Bitta obyekt: {outbounds:[...]}
     *   • `remarks` maydoni server nomi sifatida olinadi
     *
     * @return qo'shilgan serverlar soni
     */
    private fun importJsonSubscription(json: String, subUrl: String): Int {
        val a = activity as? MainActivity ?: return 0
        var added = 0
        try {
            val configs: List<org.json.JSONObject> = when {
                json.startsWith("[") -> {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
                else -> listOf(org.json.JSONObject(json))
            }

            for (cfg in configs) {
                val outbounds = cfg.optJSONArray("outbounds") ?: continue
                val remark = cfg.optString("remarks").ifBlank { null }

                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val proto = ob.optString("protocol")

                    // ═══ WireGuard (AWG) JSON import O'CHIRILGAN ═══
                    if (proto == "wireguard") continue

                    if (proto !in JSON_PROTOCOLS) continue

                    val link = buildLinkFromJsonOutbound(ob, remark) ?: continue
                    if (link.length > 8192) continue

                    val si = parseServer(link, subUrl) ?: continue
                    if (a.servers.any { it.link == si.link }) continue
                    a.servers.add(si)
                    added++
                }
            }

            if (added > 0) {
                com.nurvpn.app.storage.ServerStore.save(a, a.servers)
                activity?.runOnUiThread { ad?.notifyDataChanged() }
            }
            android.util.Log.i("NurVPN-DBG",
                "importJsonSubscription: $added server qo'shildi")
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG",
                "importJsonSubscription xato: ${t.message}")
        }
        return added
    }

    /** JSON outbound → proxy link. */
    /**
     * Xray JSON dagi wireguard outbound'ni .conf matniga aylantiradi.
     * Maydonlar: secretKey, publicKey, endpoint (host:port), allowedIPs.
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

    private fun buildLinkFromJsonOutbound(
        ob: org.json.JSONObject, remark: String?
    ): String? {
        val proto = ob.optString("protocol")
        val server = ob.optString("address").ifEmpty {
            ob.optJSONObject("settings")
                ?.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
                ?: ob.optJSONObject("settings")
                    ?.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
                ?: return null
        }
        if (server.isEmpty()) return null
        val port = ob.optInt("port").takeIf { it > 0 }
            ?: ob.optJSONObject("settings")
                ?.optJSONArray("vnext")?.optJSONObject(0)?.optInt("port")
                ?.takeIf { it > 0 }
            ?: ob.optJSONObject("settings")
                ?.optJSONArray("servers")?.optJSONObject(0)?.optInt("port")
                ?.takeIf { it > 0 }
            ?: 443

        val tag = java.net.URLEncoder.encode(remark ?: server, "UTF-8")
        val stream = ob.optJSONObject("streamSettings")
        val security = stream?.optString("security").orEmpty()
        val sni = stream?.optJSONObject("tlsSettings")?.optString("serverName")
            .orEmpty().ifEmpty {
                stream?.optJSONObject("realitySettings")?.optString("serverName")
                    .orEmpty()
            }
        val pbk = stream?.optJSONObject("realitySettings")
            ?.optString("publicKey").orEmpty()

        return try {
            when (proto) {
                "vless" -> {
                    val id = ob.optJSONObject("settings")
                        ?.optJSONArray("vnext")?.optJSONObject(0)
                        ?.optJSONArray("users")?.optJSONObject(0)?.optString("id")
                        ?: return null
                    if (id.isEmpty()) return null
                    val q = StringBuilder("security=")
                    q.append(if (security.isEmpty()) "none" else security)
                    if (sni.isNotEmpty()) q.append("&sni=$sni")
                    if (pbk.isNotEmpty()) q.append("&pbk=$pbk")
                    "vless://$id@$server:$port?$q#$tag"
                }
                "vmess" -> {
                    val vnext = ob.optJSONObject("settings")
                        ?.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                    val id = vnext.optJSONArray("users")
                        ?.optJSONObject(0)?.optString("id") ?: return null
                    if (id.isEmpty()) return null
                    val json = org.json.JSONObject()
                        .put("v", "2").put("ps", remark ?: server)
                        .put("add", server).put("port", port.toString())
                        .put("id", id).put("aid", "0").put("net", "tcp")
                        .put("tls", if (security == "tls") "tls" else "")
                    if (sni.isNotEmpty()) json.put("sni", sni)
                    "vmess://" + android.util.Base64.encodeToString(
                        json.toString().toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP)
                }
                "trojan" -> {
                    val pw = ob.optJSONObject("settings")
                        ?.optJSONArray("servers")?.optJSONObject(0)
                        ?.optString("password") ?: return null
                    val q = if (sni.isNotEmpty()) "?sni=$sni" else ""
                    "trojan://$pw@$server:$port$q#$tag"
                }
                "shadowsocks" -> {
                    val s = ob.optJSONObject("settings")
                        ?.optJSONArray("servers")?.optJSONObject(0) ?: return null
                    val pw = s.optString("password")
                    val method = s.optString("method")
                    if (pw.isEmpty() || method.isEmpty()) return null
                    val ui = android.util.Base64.encodeToString(
                        "$method:$pw".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
                    "ss://$ui@$server:$port#$tag"
                }
                "hysteria2", "hy2" -> {
                    val pw = ob.optJSONObject("settings")?.optString("password")
                        ?: ob.optString("password").takeIf { it.isNotEmpty() }
                        ?: return null
                    val q = StringBuilder()
                    ob.optJSONObject("obfs")?.let {
                        q.append("obfs=${it.optString("type")}")
                        q.append("&obfs-password=${it.optString("password")}")
                    }
                    "hysteria2://$pw@$server:$port?$q#$tag"
                }
                "tuic" -> {
                    val s = ob.optJSONObject("settings") ?: return null
                    val uuid = s.optString("uuid")
                    val pw = s.optString("password")
                    if (uuid.isEmpty()) return null
                    "tuic://$uuid:$pw@$server:$port#$tag"
                }
                "anytls" -> {
                    val pw = ob.optJSONObject("settings")?.optString("password")
                        ?: return null
                    "anytls://$pw@$server:$port#$tag"
                }
                else -> null
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG", "buildLink xato ($proto): ${t.message}")
            null
        }
    }

}
