# `app/src/main/java/com/nurvpn/app/ui/editor/ServerEditorActivity.kt`

```kotlin
package com.nurvpn.app.ui.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nurvpn.app.R
import com.nurvpn.app.core.LinkBuilder
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerConfig
import com.nurvpn.app.core.ServerItem
import com.nurvpn.app.storage.ServerStore
import com.nurvpn.app.ui.MainActivity

class ServerEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LINK = "server_link"
    }

    private lateinit var cfg: ServerConfig
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    // Maydonlar
    private lateinit var nameEt: EditText
    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var uuidEt: EditText
    private lateinit var passEt: EditText
    private lateinit var methodEt: EditText
    private lateinit var flowEt: EditText
    private lateinit var alterIdEt: EditText
    private lateinit var vmessSecEt: EditText
    private lateinit var transportSp: Spinner
    private lateinit var pathEt: EditText
    private lateinit var hostHdrEt: EditText
    private lateinit var serviceEt: EditText
    private lateinit var xhttpModeSp: Spinner
    private lateinit var tlsSp: Spinner
    private lateinit var sniEt: EditText
    private lateinit var alpnEt: EditText
    private lateinit var fpSp: Spinner
    private lateinit var pbkEt: EditText
    private lateinit var sidEt: EditText
    private lateinit var allowInsecureSw: Switch
    private lateinit var obfsSp: Spinner
    private lateinit var obfsPassEt: EditText
    private lateinit var upMbpsEt: EditText
    private lateinit var downMbpsEt: EditText
    private lateinit var ccSp: Spinner
    private lateinit var udpModeSp: Spinner
    private lateinit var muxSw: Switch
    private lateinit var muxConcEt: EditText

    // Guruhlar (visibility uchun)
    private lateinit var uuidGroup: LinearLayout
    private lateinit var passGroup: LinearLayout
    private lateinit var methodGroup: LinearLayout
    private lateinit var flowGroup: LinearLayout
    private lateinit var alterIdGroup: LinearLayout
    private lateinit var vmessSecGroup: LinearLayout
    private lateinit var pathGroup: LinearLayout
    private lateinit var hostHdrGroup: LinearLayout
    private lateinit var serviceGroup: LinearLayout
    private lateinit var xhttpModeGroup: LinearLayout
    private lateinit var tlsGroup: LinearLayout
    private lateinit var sniGroup: LinearLayout
    private lateinit var alpnGroup: LinearLayout
    private lateinit var fpGroup: LinearLayout
    private lateinit var pbkGroup: LinearLayout
    private lateinit var sidGroup: LinearLayout
    private lateinit var insecureGroup: LinearLayout
    private lateinit var obfsGroup: LinearLayout
    private lateinit var obfsPassGroup: LinearLayout
    private lateinit var upMbpsGroup: LinearLayout
    private lateinit var downMbpsGroup: LinearLayout
    private lateinit var ccGroup: LinearLayout
    private lateinit var udpModeGroup: LinearLayout
    private lateinit var muxConcGroup: LinearLayout

    private val transports = listOf("tcp", "ws", "grpc", "xhttp", "httpupgrade")
    private val xhttpModes = listOf("auto", "packet-up", "stream-up", "stream-one")
    private val tlsOptions = listOf("none", "tls", "reality")
    private val fingerprints = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized")
    private val obfsOptions = listOf("", "salamander")
    private val ccOptions = listOf("bbr", "cubic", "new_reno")
    private val udpModes = listOf("native", "quic")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val link = intent.getStringExtra(EXTRA_LINK)
        if (link.isNullOrEmpty()) { finish(); return }
        cfg = LinkBuilder.parse(link)

        buildUI()
        setupButtons()
        fillFields()
        applyVisibility()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun makeSection(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(color(R.color.accent))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16f), 0, dp(6f))
        }
    }

    private fun makeFieldRow(label: String, field: View): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_secondary))
            textSize = 12f
        }
        field.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4f) }
        row.addView(tv)
        row.addView(field)
        return row
    }

    private fun makeEt(hint: String = "", numeric: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_tertiary))
            textSize = 14f
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
                        else InputType.TYPE_CLASS_TEXT
            background = ContextCompat.getDrawable(this@ServerEditorActivity,
                R.drawable.item_bg)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
    }

    private fun makeSpinner(items: List<String>): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@ServerEditorActivity,
                android.R.layout.simple_spinner_dropdown_item, items)
            background = ContextCompat.getDrawable(this@ServerEditorActivity,
                R.drawable.item_bg)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
    }

    private fun makeSwitch(): Switch {
        return Switch(this).apply {
            buttonTintList = ColorStateList.valueOf(color(R.color.accent))
        }
    }

    private fun buildUI() {
        scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(32f))
        }
        scroll.addView(root)

        // ═══ Header ═══
        val title = TextView(this).apply {
            text = getString(R.string.editor_server_title)
            setTextColor(color(R.color.text_primary))
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val protoLabel = TextView(this).apply {
            text = cfg.protocol.name
            setTextColor(color(R.color.accent))
            textSize = 13f
            setPadding(0, dp(4f), 0, 0)
        }
        root.addView(protoLabel)

        // ═══ ASOSIY ═══
        root.addView(makeSection(getString(R.string.editor_section_main)))

        nameEt = makeEt(cfg.remark)
        root.addView(makeFieldRow(getString(R.string.editor_field_name), nameEt))

        hostEt = makeEt("example.com")
        root.addView(makeFieldRow(getString(R.string.editor_field_host), hostEt))

        portEt = makeEt("443", numeric = true)
        root.addView(makeFieldRow(getString(R.string.editor_field_port), portEt))

        uuidGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        uuidEt = makeEt("UUID")
        uuidGroup.addView(makeFieldRow(getString(R.string.editor_field_uuid), uuidEt))
        root.addView(uuidGroup)

        flowGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        flowEt = makeEt("xtls-rprx-vision")
        flowGroup.addView(makeFieldRow(getString(R.string.editor_field_flow), flowEt))
        root.addView(flowGroup)

        passGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        passEt = makeEt("password")
        passGroup.addView(makeFieldRow(getString(R.string.editor_field_password), passEt))
        root.addView(passGroup)

        methodGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        methodEt = makeEt("aes-256-gcm")
        methodGroup.addView(makeFieldRow(getString(R.string.editor_field_method), methodEt))
        root.addView(methodGroup)

        alterIdGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alterIdEt = makeEt("0", numeric = true)
        alterIdGroup.addView(makeFieldRow(getString(R.string.editor_field_alter_id), alterIdEt))
        root.addView(alterIdGroup)

        vmessSecGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vmessSecEt = makeEt("auto")
        vmessSecGroup.addView(makeFieldRow(getString(R.string.editor_field_vmess_security), vmessSecEt))
        root.addView(vmessSecGroup)

        // ═══ TRANSPORT ═══
        root.addView(makeSection(getString(R.string.editor_section_transport)))

        transportSp = makeSpinner(transports)
        root.addView(makeFieldRow(getString(R.string.editor_field_transport), transportSp))

        pathGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pathEt = makeEt("/")
        pathGroup.addView(makeFieldRow(getString(R.string.editor_field_path), pathEt))
        root.addView(pathGroup)

        hostHdrGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hostHdrEt = makeEt("example.com")
        hostHdrGroup.addView(makeFieldRow(getString(R.string.editor_field_host_header), hostHdrEt))
        root.addView(hostHdrGroup)

        serviceGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        serviceEt = makeEt("grpc-service")
        serviceGroup.addView(makeFieldRow(getString(R.string.editor_field_service_name), serviceEt))
        root.addView(serviceGroup)

        xhttpModeGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        xhttpModeSp = makeSpinner(xhttpModes)
        xhttpModeGroup.addView(makeFieldRow(getString(R.string.editor_field_xhttp_mode), xhttpModeSp))
        root.addView(xhttpModeGroup)

        // ═══ XAVFSIZLIK ═══
        root.addView(makeSection(getString(R.string.editor_section_security)))

        tlsGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tlsSp = makeSpinner(tlsOptions)
        tlsGroup.addView(makeFieldRow(getString(R.string.editor_field_tls), tlsSp))
        root.addView(tlsGroup)

        sniGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sniEt = makeEt("example.com")
        sniGroup.addView(makeFieldRow(getString(R.string.editor_field_sni), sniEt))
        root.addView(sniGroup)

        alpnGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alpnEt = makeEt("h2,http/1.1")
        alpnGroup.addView(makeFieldRow(getString(R.string.editor_field_alpn), alpnEt))
        root.addView(alpnGroup)

        fpGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fpSp = makeSpinner(fingerprints)
        fpGroup.addView(makeFieldRow(getString(R.string.editor_field_fingerprint), fpSp))
        root.addView(fpGroup)

        pbkGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pbkEt = makeEt("Reality public key")
        pbkGroup.addView(makeFieldRow(getString(R.string.editor_field_pbk), pbkEt))
        root.addView(pbkGroup)

        sidGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sidEt = makeEt("Short ID")
        sidGroup.addView(makeFieldRow(getString(R.string.editor_field_sid), sidEt))
        root.addView(sidGroup)

        insecureGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        val incLabel = TextView(this).apply {
            text = getString(R.string.editor_field_allow_insecure)
            setTextColor(color(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        allowInsecureSw = makeSwitch()
        insecureGroup.addView(incLabel)
        insecureGroup.addView(allowInsecureSw)
        root.addView(insecureGroup)

        // Hysteria2 obfs
        obfsGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        obfsSp = makeSpinner(obfsOptions)
        obfsGroup.addView(makeFieldRow(getString(R.string.editor_field_obfs), obfsSp))
        root.addView(obfsGroup)

        obfsPassGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        obfsPassEt = makeEt("obfs password")
        obfsPassGroup.addView(makeFieldRow(getString(R.string.editor_field_obfs_password), obfsPassEt))
        root.addView(obfsPassGroup)

        upMbpsGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        upMbpsEt = makeEt("", numeric = true)
        upMbpsGroup.addView(makeFieldRow(getString(R.string.editor_field_up_mbps), upMbpsEt))
        root.addView(upMbpsGroup)

        downMbpsGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        downMbpsEt = makeEt("", numeric = true)
        downMbpsGroup.addView(makeFieldRow(getString(R.string.editor_field_down_mbps), downMbpsEt))
        root.addView(downMbpsGroup)

        ccGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ccSp = makeSpinner(ccOptions)
        ccGroup.addView(makeFieldRow(getString(R.string.editor_field_congestion), ccSp))
        root.addView(ccGroup)

        udpModeGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        udpModeSp = makeSpinner(udpModes)
        udpModeGroup.addView(makeFieldRow(getString(R.string.editor_field_udp_mode), udpModeSp))
        root.addView(udpModeGroup)

        // ═══ MUX ═══
        root.addView(makeSection(getString(R.string.editor_section_mux)))

        val muxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        val muxLabel = TextView(this).apply {
            text = getString(R.string.editor_mux_enabled)
            setTextColor(color(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        muxSw = makeSwitch()
        muxRow.addView(muxLabel)
        muxRow.addView(muxSw)
        root.addView(muxRow)

        muxConcGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        muxConcEt = makeEt("8", numeric = true)
        muxConcGroup.addView(makeFieldRow(getString(R.string.editor_mux_concurrency), muxConcEt))
        root.addView(muxConcGroup)

        // Transport o'zgarsa — visibility yangilash
        transportSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyVisibility()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        tlsSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyVisibility()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        muxSw.setOnCheckedChangeListener { _, _ -> applyVisibility() }

        setContentView(scroll)
    }

    private fun setupButtons() {
        // Orqaga tugmasi (title yonida)
        // Buni soddaroq qilish uchun toolbar o'rniga pastda 3 tugma qo'shamiz
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20f), 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = getString(R.string.dialog_no)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cancelBtn.setOnClickListener { finish() }

        val saveBtn = Button(this).apply {
            text = getString(R.string.dialog_save)
            backgroundTintList = ColorStateList.valueOf(color(R.color.accent))
            setTextColor(color(R.color.on_accent))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
            }
        }
        saveBtn.setOnClickListener { saveAndExit() }

        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        root.addView(btnRow)
    }

    private fun fillFields() {
        nameEt.setText(cfg.remark)
        hostEt.setText(cfg.host)
        portEt.setText(cfg.port.toString())
        uuidEt.setText(cfg.uuid)
        flowEt.setText(cfg.flow)
        passEt.setText(cfg.password)
        methodEt.setText(cfg.method)
        alterIdEt.setText(cfg.alterId.toString())
        vmessSecEt.setText(cfg.vmessSecurity)

        val tIdx = transports.indexOf(cfg.transport).coerceAtLeast(0)
        transportSp.setSelection(tIdx)
        pathEt.setText(cfg.path)
        hostHdrEt.setText(cfg.hostHeader)
        serviceEt.setText(cfg.serviceName)
        xhttpModeSp.setSelection(xhttpModes.indexOf(cfg.xhttpMode).coerceAtLeast(0))

        val tlsIdx = tlsOptions.indexOf(cfg.tlsSecurity).coerceAtLeast(0)
        tlsSp.setSelection(tlsIdx)
        sniEt.setText(cfg.sni)
        alpnEt.setText(cfg.alpn)
        fpSp.setSelection(fingerprints.indexOf(cfg.fingerprint).coerceAtLeast(0))
        pbkEt.setText(cfg.publicKey)
        sidEt.setText(cfg.shortId)
        allowInsecureSw.isChecked = cfg.allowInsecure

        obfsSp.setSelection(obfsOptions.indexOf(cfg.obfs).coerceAtLeast(0))
        obfsPassEt.setText(cfg.obfsPassword)
        upMbpsEt.setText(cfg.upMbps)
        downMbpsEt.setText(cfg.downMbps)
        ccSp.setSelection(ccOptions.indexOf(cfg.congestionControl).coerceAtLeast(0))
        udpModeSp.setSelection(udpModes.indexOf(cfg.udpRelayMode).coerceAtLeast(0))

        muxSw.isChecked = cfg.muxEnabled
        muxConcEt.setText(cfg.muxConcurrency.toString())
    }

    private fun applyVisibility() {
        val p = cfg.protocol
        val t = transports[transportSp.selectedItemPosition]
        val sec = tlsOptions[tlsSp.selectedItemPosition]

        val isVless = p == Protocol.VLESS_REALITY
        val isVmess = p == Protocol.VMESS
        val isTrojan = p == Protocol.TROJAN
        val isSs = p == Protocol.SS_2022 || p == Protocol.SS_LEGACY
        val isHy2 = p == Protocol.HYSTERIA2
        val isTuic = p == Protocol.TUIC

        fun setV(v: View, show: Boolean) { v.visibility = if (show) View.VISIBLE else View.GONE }

        setV(uuidGroup, isVless || isVmess || isTuic)
        setV(flowGroup, isVless)
        setV(passGroup, isTrojan || isHy2 || isTuic)
        setV(methodGroup, isSs)
        setV(alterIdGroup, isVmess)
        setV(vmessSecGroup, isVmess)

        // Transport
        val transportSection = isVless || isVmess || isTrojan
        setV(transportSp.parent as View, transportSection)
        setV(pathGroup, transportSection && (t == "ws" || t == "xhttp" || t == "httpupgrade"))
        setV(hostHdrGroup, transportSection && (t == "ws" || t == "xhttp" || t == "httpupgrade"))
        setV(serviceGroup, transportSection && t == "grpc")
        setV(xhttpModeGroup, transportSection && t == "xhttp")

        // Security
        val tlsSection = !isSs && !isHy2 && !isTuic  // hy2/tuic majburiy TLS
        setV(tlsSp.parent as View, tlsSection)
        setV(sniGroup, tlsSection || isHy2 || isTuic)
        setV(alpnGroup, tlsSection || isTuic)
        setV(fpGroup, tlsSection && sec != "none")
        setV(pbkGroup, tlsSection && sec == "reality")
        setV(sidGroup, tlsSection && sec == "reality")
        setV(insecureGroup, !isSs || isHy2 || isTuic)

        // Hysteria2
        setV(obfsGroup, isHy2)
        setV(obfsPassGroup, isHy2)
        setV(upMbpsGroup, isHy2)
        setV(downMbpsGroup, isHy2)

        // TUIC
        setV(ccGroup, isTuic)
        setV(udpModeGroup, isTuic)

        // Mux
        setV(muxConcGroup, muxSw.isChecked)
    }

    private fun saveAndExit() {
        cfg.remark = nameEt.text.toString().trim()
        cfg.host = hostEt.text.toString().trim()
        cfg.port = portEt.text.toString().trim().toIntOrNull() ?: 443
        cfg.uuid = uuidEt.text.toString().trim()
        cfg.flow = flowEt.text.toString().trim()
        cfg.password = passEt.text.toString().trim()
        cfg.method = methodEt.text.toString().trim()
        cfg.alterId = alterIdEt.text.toString().trim().toIntOrNull() ?: 0
        cfg.vmessSecurity = vmessSecEt.text.toString().trim().ifEmpty { "auto" }

        cfg.transport = transports[transportSp.selectedItemPosition]
        cfg.path = pathEt.text.toString().trim().ifEmpty { "/" }
        cfg.hostHeader = hostHdrEt.text.toString().trim()
        cfg.serviceName = serviceEt.text.toString().trim()
        cfg.xhttpMode = xhttpModes[xhttpModeSp.selectedItemPosition]

        cfg.tlsSecurity = tlsOptions[tlsSp.selectedItemPosition]
        cfg.sni = sniEt.text.toString().trim()
        cfg.alpn = alpnEt.text.toString().trim()
        cfg.fingerprint = fingerprints[fpSp.selectedItemPosition]
        cfg.publicKey = pbkEt.text.toString().trim()
        cfg.shortId = sidEt.text.toString().trim()
        cfg.allowInsecure = allowInsecureSw.isChecked

        cfg.obfs = obfsOptions[obfsSp.selectedItemPosition]
        cfg.obfsPassword = obfsPassEt.text.toString().trim()
        cfg.upMbps = upMbpsEt.text.toString().trim()
        cfg.downMbps = downMbpsEt.text.toString().trim()
        cfg.congestionControl = ccOptions[ccSp.selectedItemPosition]
        cfg.udpRelayMode = udpModes[udpModeSp.selectedItemPosition]

        cfg.muxEnabled = muxSw.isChecked
        cfg.muxConcurrency = muxConcEt.text.toString().trim().toIntOrNull() ?: 8

        if (cfg.host.isEmpty()) {
            Toast.makeText(this, R.string.editor_empty_host, Toast.LENGTH_SHORT).show()
            return
        }
        if (cfg.port !in 1..65535) {
            Toast.makeText(this, R.string.editor_invalid_port, Toast.LENGTH_SHORT).show()
            return
        }

        val newLink = LinkBuilder.build(cfg)

        // AsosiyActivity dagi serverni yangilash
        val app = applicationContext as? android.app.Application
        val act = this
        // MainActivity ni topish qiyin — SharedPreferences orqali ishlaymiz
        // Yaxshiroq: result bilan qaytaramiz
        val data = android.content.Intent().apply {
            putExtra("new_link", newLink)
            putExtra("old_link", cfg.originalLink)
        }
        setResult(RESULT_OK, data)

        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

```
