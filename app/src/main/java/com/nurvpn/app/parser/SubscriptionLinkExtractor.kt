package com.nurvpn.app.parser

/**
 * Subscription (obuna) matnidan barcha proxy linklarini ajratib oladi.
 * 14 protokolni qo'llab-quvvatlaydi.
 */
object SubscriptionLinkExtractor {

    /**
     * 14 protokol prefikslari:
     * VLESS, VMess, Trojan, SS, SSR, Hysteria2, TUIC, Juicity,
     * SOCKS, HTTP-proxy, SSH, ShadowTLS, AnyTLS
     */
    private val protocolStart = Regex(
        "(?i)(?:vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic|juicity|" +
        "socks|socks5|http-proxy|https-proxy|ssh|shadowtls|anytls)://"
    )

    private val whitespace = Regex("\\s+")

    /**
     * Matndan linklarni ajratib oladi.
     * @param raw  Subscription javobi (base64 yoki plain)
     * @return Topilgan linklar ro'yxati (max 500 ta)
     */
    fun extract(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val matches = protocolStart.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList()

        val result = ArrayList<String>(matches.size.coerceAtMost(600))

        for (i in matches.indices) {
            if (result.size >= 500) break

            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) {
                matches[i + 1].range.first
            } else {
                raw.length
            }

            var chunk = raw.substring(start, end).trim()
            chunk = whitespace.split(chunk).firstOrNull() ?: ""
            if (chunk.isNotEmpty()) result.add(chunk)
        }
        return result
    }
}
