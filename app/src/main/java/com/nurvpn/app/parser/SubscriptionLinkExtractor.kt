package com.nurvpn.app.parser

object SubscriptionLinkExtractor {

    private val protocolStart = Regex(
        "(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic)://"
    )

    // Precompile — har safar yangi Regex yaratmaslik uchun
    private val whitespace = Regex("\\s+")

    fun extract(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val matches = protocolStart.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList()

        // TAXMINIY hajm — xotirani oldindan ajratish
        val result = ArrayList<String>(matches.size.coerceAtMost(600))

        for (i in matches.indices) {
            // Limit: 500 link yetarli
            if (result.size >= 500) break

            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) {
                matches[i + 1].range.first
            } else {
                raw.length
            }

            var chunk = raw.substring(start, end).trim()
            // Chunk oxiridagi ortiqcha whitespace yoki keyingi matnni kesish
            chunk = whitespace.split(chunk).firstOrNull() ?: ""
            if (chunk.isNotEmpty()) result.add(chunk)
        }
        return result
    }
}
