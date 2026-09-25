package com.nurvpn.app.parser

/** Base64 matnni xavfsiz decode qiladi. Plain matn uchun "" qaytaradi. */
fun decodeBase64Safely(value: String): String {
    val compact = value.filterNot { it.isWhitespace() }
    val looksLikeBase64 = compact.length >= 16 &&
        compact.length % 4 != 1 &&
        Regex("^[A-Za-z0-9+/=_-]+$").matches(compact)
    if (!looksLikeBase64) return ""
    return try {
        String(
            android.util.Base64.decode(compact, android.util.Base64.DEFAULT),
            Charsets.UTF_8
        )
    } catch (t: Throwable) { "" }
}
