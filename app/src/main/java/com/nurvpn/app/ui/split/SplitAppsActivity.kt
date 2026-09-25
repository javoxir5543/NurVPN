package com.nurvpn.app.ui.split

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nurvpn.app.R
import com.nurvpn.app.core.AppInfo
import com.nurvpn.app.storage.AppListLoader
import com.nurvpn.app.storage.SplitTunnelStore
import java.util.Locale

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
