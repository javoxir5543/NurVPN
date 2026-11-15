package com.nurvpn.app.core

/**
 * Oxirgi ulanish loglari (sing-box [box] qatorlari + xatolar).
 * Sozlamalar → "Loglarni ulashish" tugmasi orqali yuboriladi — logcat kerak emas.
 */
object LogBuffer {
    private const val MAX_LINES = 400
    private val lines = java.util.ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized
    fun add(line: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(fmt.format(java.util.Date()) + " " + redact(line).take(400))
    }

    private val secretRe = Regex("[A-Za-z0-9+/=_-]{40,}")
    private fun redact(s: String) = secretRe.replace(s, "***")

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun dump(): String {
        val s = lines.joinToString("\n")
        return if (s.length > 30000) s.takeLast(30000) else s
    }
}
