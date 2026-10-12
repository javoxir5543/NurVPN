# `app/src/main/java/com/nurvpn/app/ui/awg/MasqueEditorActivity.kt`

```kotlin
package com.nurvpn.app.ui.awg

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nurvpn.app.R
import com.nurvpn.app.core.AWGEditorBus
import com.nurvpn.app.storage.AWGStore

class MasqueEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "masque_index"
    }

    private lateinit var serverEt: EditText
    private lateinit var portEt: EditText
    private lateinit var sniEt: EditText
    private lateinit var networkEt: EditText
    private lateinit var mtuEt: EditText
    private lateinit var privateKeyEt: EditText
    private lateinit var publicKeyEt: EditText
    private lateinit var addressEt: EditText
    private var preview: TextView? = null
    private var index = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_masque_editor)

        serverEt = findViewById(R.id.edit_server)
        portEt = findViewById(R.id.edit_port)
        sniEt = findViewById(R.id.edit_sni)
        networkEt = findViewById(R.id.edit_network)
        mtuEt = findViewById(R.id.edit_mtu)
        privateKeyEt = findViewById(R.id.edit_private_key)
        publicKeyEt = findViewById(R.id.edit_public_key)
        addressEt = findViewById(R.id.edit_address)
        preview = findViewById(R.id.preview)

        if (AWGEditorBus.configs.isEmpty()) {
            Toast.makeText(this, R.string.toast_awg_no_config, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (index < 0 || index >= AWGEditorBus.configs.size) index = 0

        fillFields(AWGEditorBus.configs[index].rawConf ?: "")

        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) {}
        }
        listOf(serverEt, portEt, sniEt, networkEt, mtuEt, privateKeyEt, publicKeyEt, addressEt)
            .forEach { it.addTextChangedListener(w) }

        findViewById<Button>(R.id.btn_cancel)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save)?.setOnClickListener { save() }
    }

    private fun parseMasque(raw: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = t.indexOf('=')
            if (eq < 0) continue
            val k = t.substring(0, eq).trim().lowercase()
            val v = t.substring(eq + 1).trim()
            m[k] = v
        }
        return m
    }

    private fun fillFields(raw: String) {
        val m = parseMasque(raw)
        serverEt.setText(m["server"] ?: m["endpoint"] ?: "")
        portEt.setText(m["port"] ?: "443")
        sniEt.setText(m["sni"] ?: "")
        networkEt.setText(m["network"] ?: "h3")
        mtuEt.setText(m["mtu"] ?: "1280")
        privateKeyEt.setText(m["privatekey"] ?: "")
        publicKeyEt.setText(m["publickey"] ?: "")
        addressEt.setText(m["address"] ?: "")
        updatePreview()
    }

    private fun buildRaw(): String {
        val sb = StringBuilder()
        sb.append("PrivateKey = ").append(privateKeyEt.text.toString().trim()).append('\n')
        sb.append("PublicKey = ").append(publicKeyEt.text.toString().trim()).append('\n')
        sb.append("Address = ").append(addressEt.text.toString().trim()).append('\n')
        sb.append("MTU = ").append(mtuEt.text.toString().trim()).append('\n')
        sb.append("Server = ").append(serverEt.text.toString().trim()).append('\n')
        sb.append("Port = ").append(portEt.text.toString().trim()).append('\n')
        val sni = sniEt.text.toString().trim()
        if (sni.isNotEmpty()) sb.append("SNI = ").append(sni).append('\n')
        sb.append("Network = ").append(networkEt.text.toString().trim()).append('\n')
        sb.append("Profile = cloudflare").append('\n')
        return sb.toString()
    }

    private fun updatePreview() {
        val p = preview ?: return
        val r = buildRaw()
        p.text = if (r.length > 400) r.substring(0, 400) + "..." else r
    }

    private fun save() {
        val srv = serverEt.text.toString().trim()
        if (srv.isEmpty()) {
            Toast.makeText(this, "Server bo'sh", Toast.LENGTH_SHORT).show()
            return
        }
        if (index < 0 || index >= AWGEditorBus.configs.size) return
        val cfg = AWGEditorBus.configs[index]
        cfg.rawConf = buildRaw()
        cfg.endpoint = srv + ":" + portEt.text.toString().trim()
        cfg.type = "masque"
        cfg.name = "MASQUE " + srv
        AWGStore.save(this, AWGEditorBus.configs)
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

```
