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
