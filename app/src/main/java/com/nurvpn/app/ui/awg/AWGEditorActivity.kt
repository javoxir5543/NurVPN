package com.nurvpn.app.ui.awg

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nurvpn.app.R
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.parser.AWGParser
import com.nurvpn.app.storage.AWGStore
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.util.AWGEditor

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
        val oldRaw = cfg.rawConf
        cfg.rawConf = buildResult()

        // FIX: Endpoint/address ni qayta parse qilamiz
        val parsed = AWGParser.parse(cfg.rawConf ?: "")
        if (parsed.ok) {
            cfg.endpoint = parsed.endpoint
            cfg.address = parsed.address
        }

        AWGStore.save(this, AWGEditorBus.configs)
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()

        // FIX: Agar joriy config tahrirlangan bo'lsa va VPN ishlayotgan
        // bo'lsa — reconnect chaqiramiz
        val main = getMainActivity()
        if (main != null && oldRaw != cfg.rawConf) {
            if (main.currentAWG?.rawConf == oldRaw ||
                main.currentAWG === cfg) {
                main.currentAWG = cfg
                main.protocol = MainActivity.PROTO_AWG
                AWGEditorBus.init(main.awgConfigs, cfg, MainActivity.PROTO_AWG)

                if (main.isRunning) {
                    android.util.Log.i("NurVPN-AWG",
                        "Editor: config o'zgardi -> reconnect")
                    main.restartVpn(getString(R.string.reason_awg,
                        cfg.name ?: "AWG"))
                }
            }
        }

        finish()
    }

    /**
     * MainActivity'ni topish — deprecated getActivity() o'rniga.
     * AWGEditorActivity alohida Activity bo'lgani uchun bu yerda null qaytaradi.
     * Boshqa yo'l: SharedPreferences listener yoki broadcast.
     */
    private fun getMainActivity(): MainActivity? = null
}
