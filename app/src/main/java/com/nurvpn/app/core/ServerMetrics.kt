package com.nurvpn.app.core

class ServerMetrics(@JvmField val link: String) {
    var avgLatency: Double = 0.0
    var successRate: Double = 0.5
    var samples: Int = 0
    var fails: Int = 0
    var totalBytesUp: Long = 0
    var totalBytesDown: Long = 0
    var lastGoodTime: Long = 0

    fun recordPing(latencyMs: Int, ok: Boolean, now: Long) {
        samples++
        if (ok) {
            avgLatency = if (samples == 1) latencyMs.toDouble()
                         else avgLatency * 0.8 + latencyMs * 0.2
            lastGoodTime = now
            val succ = 1.0 - fails.toDouble() / samples
            successRate = successRate * 0.7 + succ * 0.3
        } else {
            fails++
            successRate = successRate * 0.7 +
                (1.0 - fails.toDouble() / samples) * 0.3
        }
    }

    fun addTraffic(up: Long, down: Long) {
        totalBytesUp += up
        totalBytesDown += down
    }

    fun confidence(): Double = Math.min(1.0, samples / 20.0)

    companion object {
        @JvmStatic fun latencyScore(lat: Int): Double = when {
            lat < 0 -> 0.0
            lat >= 500 -> 0.0
            else -> 100.0 * (1.0 - lat / 500.0)
        }
    }
}

