package com.nurvpn.app.util

object AWGEditor {

    class Data {
        var host: String = ""
        var port: Int = 0
        var jc: Int = 4
        var jmin: Int = 40
        var jmax: Int = 70
        var mtu: Int = 1280
        var keepalive: Int = 25
        var dns: String = ""
        // FIX: H1-H4 qo'shildi — AmneziaWG magic header'lari
        var h1: Int = 1
        var h2: Int = 2
        var h3: Int = 3
        var h4: Int = 4
    }

    fun parse(raw: String): Data {
        val d = Data()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("[")) continue
            val parts = t.split("=", limit = 2)
            if (parts.size < 2) continue
            val k = parts[0].trim()
            val v = parts[1].trim()
            when (k.lowercase()) {
                "endpoint" -> {
                    d.host = v.substringBeforeLast(":")
                    d.port = v.substringAfterLast(":").toIntOrNull() ?: 0
                }
                "jc" -> d.jc = v.toIntOrNull() ?: 4
                "jmin" -> d.jmin = v.toIntOrNull() ?: 40
                "jmax" -> d.jmax = v.toIntOrNull() ?: 70
                "mtu" -> d.mtu = v.toIntOrNull() ?: 1280
                "persistentkeepalive" -> d.keepalive = v.toIntOrNull() ?: 25
                "dns" -> d.dns = v
                // FIX: H1-H4 parsing
                "h1" -> d.h1 = v.toIntOrNull() ?: 1
                "h2" -> d.h2 = v.toIntOrNull() ?: 2
                "h3" -> d.h3 = v.toIntOrNull() ?: 3
                "h4" -> d.h4 = v.toIntOrNull() ?: 4
            }
        }
        return d
    }

    fun setValue(raw: String, key: String, value: String): String {
        val sb = StringBuilder()
        var replaced = false
        for (line in raw.lines()) {
            val t = line.trim()
            val eqIdx = t.indexOf('=')
            if (eqIdx > 0) {
                val lineKey = t.substring(0, eqIdx).trim()
                if (lineKey.equals(key, ignoreCase = true)) {
                    sb.append("$key = $value\n")
                    replaced = true
                    continue
                }
            }
            sb.append(line).append("\n")
        }
        if (!replaced) sb.append("$key = $value\n")
        return sb.toString().trimEnd()
    }

    fun setEndpoint(raw: String, host: String, port: Int): String =
        setValue(raw, "Endpoint", "$host:$port")

    fun applyAll(raw: String, d: Data): String {
        var out = raw
        out = setEndpoint(out, d.host, d.port)
        out = setValue(out, "Jc", d.jc.toString())
        out = setValue(out, "Jmin", d.jmin.toString())
        out = setValue(out, "Jmax", d.jmax.toString())
        out = setValue(out, "MTU", d.mtu.toString())
        out = setValue(out, "PersistentKeepalive", d.keepalive.toString())
        // FIX: H1-H4 ham tahrirlanadi
        out = setValue(out, "H1", d.h1.toString())
        out = setValue(out, "H2", d.h2.toString())
        out = setValue(out, "H3", d.h3.toString())
        out = setValue(out, "H4", d.h4.toString())
        if (d.dns.isNotEmpty()) out = setValue(out, "DNS", d.dns)
        return out
    }

    fun applyPort443(raw: String): String {
        val d = parse(raw)
        return setEndpoint(raw, d.host, 443)
    }

    fun applyBeelinePreset(raw: String): String {
        var out = setValue(raw, "Jc", "120")
        out = setValue(out, "Jmin", "50")
        out = setValue(out, "Jmax", "100")
        out = setValue(out, "MTU", "1180")
        // FIX: H1-H4 Beeline uchun
        out = setValue(out, "H1", "4")
        out = setValue(out, "H2", "5")
        out = setValue(out, "H3", "6")
        out = setValue(out, "H4", "7")
        return applyPort443(out)
    }

    fun applyMtsPreset(raw: String): String {
        var out = setValue(raw, "Jc", "8")
        out = setValue(out, "Jmin", "20")
        out = setValue(out, "Jmax", "50")
        out = setValue(out, "MTU", "1280")
        // FIX: H1-H4 MTS uchun
        out = setValue(out, "H1", "1")
        out = setValue(out, "H2", "2")
        out = setValue(out, "H3", "3")
        out = setValue(out, "H4", "4")
        return applyPort443(out)
    }
}
