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
            grouped[null]?.let { manualList ->
                val isExpanded = expandedSubscriptions.contains("manual")
                rows.add(Row.Header(null,
                    "🔧 ${frag.getString(R.string.manual_added)}",
                    manualList.size, false, isExpanded))
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
