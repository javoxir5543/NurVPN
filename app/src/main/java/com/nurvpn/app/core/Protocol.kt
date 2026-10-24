package com.nurvpn.app.core

/**
 * Ping strategiyasi:
 *  - TCP_CONNECT: TCP ulanish orqali (VLESS, VMess, Trojan, SS, ...)
 *  - HOST_RTT:    ICMP/host RTT orqali (UDP asosidagi protokollar)
 */
enum class PingStrategy { TCP_CONNECT, HOST_RTT }

/**
 * NurVPN qo'llab-quvvatlaydigan 14 protokol.
 * AWG (WireGuard) alohida — u VpnService ichida boshqariladi.
 */
enum class Protocol(
    val isUdp: Boolean,
    val pingStrategy: PingStrategy
) {
    // ═══ Asosiy 6 protokol ═══
    VLESS_REALITY(false, PingStrategy.TCP_CONNECT),
    VMESS(false, PingStrategy.TCP_CONNECT),
    TROJAN(false, PingStrategy.TCP_CONNECT),
    SS_2022(false, PingStrategy.TCP_CONNECT),
    HYSTERIA2(true, PingStrategy.HOST_RTT),
    TUIC(true, PingStrategy.HOST_RTT),

    // ═══ YANGI 8 protokol ═══
    SOCKS(false, PingStrategy.TCP_CONNECT),
    HTTP(false, PingStrategy.TCP_CONNECT),
    SSH(false, PingStrategy.TCP_CONNECT),
    SSR(false, PingStrategy.TCP_CONNECT),
    SHADOWTLS(false, PingStrategy.TCP_CONNECT),
    ANYTLS(false, PingStrategy.TCP_CONNECT),
    JUICITY(true, PingStrategy.HOST_RTT),
    SS_LEGACY(false, PingStrategy.TCP_CONNECT);

    companion object {
        /** URI prefiksidan protokolni aniqlash. */
        fun fromUri(uri: String): Protocol {
            val u = uri.lowercase()
            return when {
                // UDP-based
                u.startsWith("hysteria2://") || u.startsWith("hy2://") -> HYSTERIA2
                u.startsWith("tuic://") -> TUIC
                u.startsWith("juicity://") -> JUICITY

                // TCP-based yangi
                u.startsWith("socks://") || u.startsWith("socks5://") -> SOCKS
                u.startsWith("http-proxy://") || u.startsWith("https-proxy://") -> HTTP
                u.startsWith("ssh://") -> SSH
                u.startsWith("ssr://") -> SSR
                u.startsWith("shadowtls://") -> SHADOWTLS
                u.startsWith("anytls://") -> ANYTLS

                // Klassik
                u.startsWith("vmess://") -> VMESS
                u.startsWith("trojan://") -> TROJAN
                u.startsWith("ss://") -> SS_2022
                u.startsWith("vless://") -> VLESS_REALITY

                else -> VLESS_REALITY
            }
        }

        /** UI chip uchun qisqa nom. */
        fun displayName(p: Protocol): String = when (p) {
            VLESS_REALITY -> "VLESS"
            VMESS -> "VMESS"
            TROJAN -> "TROJAN"
            SS_2022 -> "SS"
            SS_LEGACY -> "SS"
            HYSTERIA2 -> "HY2"
            TUIC -> "TUIC"
            SOCKS -> "SOCKS"
            HTTP -> "HTTP"
            SSH -> "SSH"
            SSR -> "SSR"
            SHADOWTLS -> "ShadowTLS"
            ANYTLS -> "AnyTLS"
            JUICITY -> "Juicity"
        }

        /** UI chip uchun rang. */
        fun color(p: Protocol): Int = when (p) {
            VLESS_REALITY, VMESS, TROJAN -> 0xFF4A9EFF.toInt()  // Ko'k
            SS_2022, SS_LEGACY -> 0xFFFFC107.toInt()             // Sariq
            HYSTERIA2 -> 0xFF9B59B6.toInt()                       // Binafsha
            TUIC -> 0xFFE91E63.toInt()                            // Pushti
            SOCKS -> 0xFF00BCD4.toInt()                           // Cyan
            HTTP -> 0xFF4CAF50.toInt()                            // Yashil
            SSH -> 0xFF795548.toInt()                             // Jigarrang
            SSR -> 0xFFFF5722.toInt()                             // Qizil
            SHADOWTLS -> 0xFF607D8B.toInt()                       // Kulrang
            ANYTLS -> 0xFFE040FB.toInt()                          // Magenta
            JUICITY -> 0xFF00E676.toInt()                         // Yashil
        }
    }
}
