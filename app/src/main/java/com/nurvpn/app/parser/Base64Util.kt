package com.nurvpn.app.parser

/**
 * Base64 (oddiy YOKI URL-safe, padding bor YOKI yo'q) matnni decode qiladi.
 * Xato bo'lsa null qaytaradi.
 */
fun decodeBase64Lenient(value: String): String? {
    return try {
        var t = value.filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        if (t.isEmpty() || t.length % 4 == 1) return null
        while (t.length % 4 != 0) t += "="
        String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }
}

/** Base64 matnni xavfsiz decode qiladi. Plain matn uchun "" qaytaradi. */
fun decodeBase64Safely(value: String): String {
    val compact = value.filterNot { it.isWhitespace() }
    val looksLikeBase64 = compact.length >= 16 &&
        compact.length % 4 != 1 &&
        Regex("^[A-Za-z0-9+/=_-]+$").matches(compact)
    if (!looksLikeBase64) return ""
    return decodeBase64Lenient(compact) ?: ""
}
