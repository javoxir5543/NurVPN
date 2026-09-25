package com.nurvpn.app.core

class Subscription(
    @JvmField var id: String,
    @JvmField var url: String,
    @JvmField var name: String
) {
    @JvmField var lastUpdated: Long = 0
    /** Tartib — pastdan yuqoriga (0 = eng yuqori). */
    @JvmField var order: Int = 0
    /** Saralash rejimi: default, ping_asc, ping_desc, name_asc, name_desc */
    @JvmField var sortMode: String = "default"
    @JvmField var updateIntervalHours: Int = 24
    @JvmField var expireAt: Long = 0
    @JvmField var trafficUsed: Long = 0
    @JvmField var trafficTotal: Long = 0
    @JvmField var serverCount: Int = 0

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expireAt > 0 && now > expireAt

    fun daysLeft(now: Long = System.currentTimeMillis()): Int {
        if (expireAt <= 0) return -1
        return ((expireAt - now) / 86400_000L).toInt()
    }

    fun trafficPercent(): Int {
        if (trafficTotal <= 0) return -1
        return ((trafficUsed * 100) / trafficTotal).toInt()
    }
}

