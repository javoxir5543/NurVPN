package com.nurvpn.app.ai

import com.nurvpn.app.core.ServerMetrics

object SmartScoreEngine {
    @JvmStatic
    fun computeScore(m: ServerMetrics, now: Long): Double {
        val base = ServerMetrics.latencyScore(m.avgLatency.toInt())
        val conf = m.confidence()
        var score = 50.0 * (1 - conf) + base * conf
        score *= (0.4 + 0.6 * m.successRate)
        if (m.lastGoodTime > 0 && now - m.lastGoodTime < 3600_000L) score += 5
        if (m.totalBytesDown > 1_000_000) score += 3
        return Math.max(0.0, Math.min(100.0, score))
    }
}

