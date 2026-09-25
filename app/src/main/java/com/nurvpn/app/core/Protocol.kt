package com.nurvpn.app.core

enum class PingStrategy {
    TCP_CONNECT,
    HOST_RTT
}

enum class Protocol(
    val isUdp: Boolean,
    val pingStrategy: PingStrategy
) {
    VLESS_REALITY(false, PingStrategy.TCP_CONNECT),
    VMESS(false, PingStrategy.TCP_CONNECT),
    TROJAN(false, PingStrategy.TCP_CONNECT),
    SS_2022(false, PingStrategy.TCP_CONNECT),
    HYSTERIA2(true, PingStrategy.HOST_RTT),
    TUIC(true, PingStrategy.HOST_RTT);

    companion object {
        fun fromUri(uri: String): Protocol = when {
            uri.startsWith("hysteria2://", true) ||
            uri.startsWith("hy2://", true) -> HYSTERIA2
            uri.startsWith("tuic://", true) -> TUIC
            uri.startsWith("vmess://", true) -> VMESS
            uri.startsWith("trojan://", true) -> TROJAN
            uri.startsWith("ss://", true) -> SS_2022
            else -> VLESS_REALITY
        }
    }
}
