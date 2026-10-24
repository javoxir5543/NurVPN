package com.nurvpn.app.ui.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.nurvpn.app.R
import com.nurvpn.app.ai.AIInsights

class AICardView @JvmOverloads constructor(
    ctx: Context, attrs: android.util.AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    interface OnAutoConnect {
        fun onAutoConnect(best: AIInsights?)
        fun onRefreshRequested()
    }

    private var listener: OnAutoConnect? = null
    private var best: AIInsights? = null
    private var dot: TextView
    private var topName: TextView
    private var topMeta: TextView
    private var topSummary: TextView
    private var rankingBox: LinearLayout
    private var btnLabel: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(ctx).inflate(R.layout.view_ai_card, this, true)
        dot = findViewById(R.id.ai_status_dot)
        topName = findViewById(R.id.ai_top_name)
        topMeta = findViewById(R.id.ai_top_meta)
        topSummary = findViewById(R.id.ai_top_summary)
        rankingBox = findViewById(R.id.ai_ranking_box)
        btnLabel = findViewById(R.id.ai_btn_label)
        findViewById<ImageButton>(R.id.ai_refresh)
            .setOnClickListener { listener?.onRefreshRequested() }
        findViewById<View>(R.id.ai_btn).setOnClickListener {
            listener?.onAutoConnect(best)
        }
    }

    fun setListener(l: OnAutoConnect) { listener = l }

    fun showAnalyzing() {
        dot.text = "●"
        dot.setTextColor(androidx.core.content.ContextCompat
            .getColor(context, R.color.warning))
        topName.text = context.getString(R.string.ai_selector)
        topMeta.text = context.getString(R.string.ai_analyzing)
        topSummary.text = ""
        rankingBox.removeAllViews()
        btnLabel.text = context.getString(R.string.ai_analyzing)
    }

    fun showRanked(ranked: List<AIInsights>) {
        rankingBox.removeAllViews()
        if (ranked.isEmpty()) {
            dot.text = "●"
            dot.setTextColor(androidx.core.content.ContextCompat
            .getColor(context, R.color.text_tertiary))
            topMeta.text = context.getString(R.string.ai_add_hint)
            btnLabel.text = context.getString(R.string.ai_auto_connect)
            return
        }
        best = ranked[0]
        dot.text = "●"
        dot.setTextColor(androidx.core.content.ContextCompat
            .getColor(context, R.color.success))
        val b = ranked[0]
        topName.text = "🤖 " + b.name
        topMeta.text = b.summary
        topSummary.text = "#1 • " + b.score.toInt() + "/100"
        val max = Math.min(3, ranked.size)
        for (i in 0 until max) {
            val r = ranked[i]
            val tv = TextView(context)
            tv.textSize = 12f
            tv.setPadding(0, 6, 0, 0)
            val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; else -> "🥉" }
            tv.text = "$medal ${r.name}  —  ${"%.0f".format(r.score)}"
            tv.setTextColor(androidx.core.content.ContextCompat
                .getColor(context, R.color.text_secondary))
            rankingBox.addView(tv)
        }
        btnLabel.text = context.getString(R.string.ai_auto_connect)
    }
}

// ═══════════ SING-BOX CONFIG ═══════════
