package com.nurvpn.app.util

object ClashApiConfig {
    @JvmField @Volatile var baseUrl: String = "http://127.0.0.1:9090"
    @JvmField @Volatile var secret: String = ""
    @JvmField @Volatile var ready: Boolean = false

    /** Har safar random secret — config bilan bir xil bo'lishi uchun bir marta. */
    fun ensureSecret(): String {
        if (secret.isEmpty()) {
            secret = "nurvpn-" + (100000..999999).random()
        }
        return secret
    }
}
