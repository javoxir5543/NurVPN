package com.nurvpn.app.parser

object AWGParser {
    class Result {
        var ok: Boolean = false
        var error: String = ""
        var endpoint: String = ""
        var address: String = ""
        var version: String = "2.0"
    }

    fun parse(conf: String): Result {
        val r = Result()
        if (conf.isBlank()) { r.error = "Bo'sh config"; return r }
        var hasInterface = false
        var hasPeer = false
        for (line in conf.lines()) {
            val t = line.trim()
            when {
                t.equals("[Interface]", true) -> hasInterface = true
                t.equals("[Peer]", true) -> hasPeer = true
                t.startsWith("Endpoint", true) ->
                    r.endpoint = t.substringAfter("=").trim()
                t.startsWith("Address", true) ->
                    r.address = t.substringAfter("=").trim()
                t.startsWith("Jc", true) || t.startsWith("Jmin", true) ||
                t.startsWith("Jmax", true) || t.startsWith("S1", true) ||
                t.startsWith("I1", true) || t.startsWith("I2", true) ->
                    r.version = "3.1"
            }
        }
        if (!hasInterface || !hasPeer) {
            r.error = "[Interface] va [Peer] kerak"
            return r
        }
        if (r.endpoint.isEmpty()) {
            r.error = "Endpoint topilmadi"
            return r
        }
        r.ok = true
        return r
    }
}

// ═══════════ NET / SECURITY ═══════════

