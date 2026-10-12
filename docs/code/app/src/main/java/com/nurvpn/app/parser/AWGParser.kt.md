# `app/src/main/java/com/nurvpn/app/parser/AWGParser.kt`

```kotlin
package com.nurvpn.app.parser

import android.util.Log

object AWGParser {
    class Result {
        var ok: Boolean = false
        var error: String = ""
        var endpoint: String = ""
        var address: String = ""
        var version: String = "2.0"
    }

    /**
     * Kirish matni:
     *  - MASQUE .conf (PrivateKey+PublicKey+Server, [Interface] yo'q) -> o'zgarishsiz
     *  - klassik WireGuard/AmneziaWG .conf ([Interface]/[Peer])       -> o'zgarishsiz
     *  - Clash/Mihomo YAML (proxies: ... type: wireguard)             -> har bir proxy uchun .conf
     */
    fun expand(raw: String): List<String> {
        val t = raw.trim()
        if (isMasqueConf(t)) return listOf(t)
        if (t.contains("[Interface]", ignoreCase = true)) return listOf(t)
        val converted = try {
            ClashWgYaml.convert(t)
        } catch (e: Throwable) {
            Log.w("NurVPN-AWG", "YAML convert xato: ${e.message}")
            emptyList()
        }
        return if (converted.isNotEmpty()) converted else listOf(t)
    }

    fun isMasqueConf(text: String): Boolean {
        if (text.contains("[Interface]") || text.contains("[Peer]")) return false
        if (!text.contains("PrivateKey")) return false
        if (!text.contains("PublicKey")) return false
        if (!text.contains("Server") && !text.contains("Endpoint")) return false
        if (text.contains("Jc =") || text.contains("Jmin =")) return false
        return true
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
            r.error = "[Interface] va [Peer] kerak (WireGuard .conf yoki Clash YAML)"
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

object ClashWgYaml {
    private const val TAG = "NurVPN-AWG"

    private val AWG_KEYS = listOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5"
    )

    fun convert(text: String): List<String> {
        val lines = text.replace("\t", "  ").lines()
            .map { stripComment(it).trimEnd() }
            .filter { it.isNotBlank() }
        if (lines.none { it.contains("wireguard", ignoreCase = true) }) return emptyList()

        class Block(val key: String, val anchor: String?) {
            val body = ArrayList<String>()
        }
        val head = Regex("^([A-Za-z0-9_.\\-]+):\\s*(?:&(\\S+))?\\s*(.*)$")
        val blocks = ArrayList<Block>()
        for (ln in lines) {
            val m = if (ln.startsWith(" ") || ln.startsWith("-")) null else head.find(ln)
            if (m != null) {
                blocks.add(Block(m.groupValues[1], m.groupValues[2].ifEmpty { null }))
            } else {
                blocks.lastOrNull()?.body?.add(ln)
            }
        }
        val anchors = HashMap<String, Map<String, String>>()
        for (b in blocks) {
            val a = b.anchor ?: continue
            anchors[a] = parseMap(b.body)
        }

        val pb = blocks.firstOrNull { it.key == "proxies" } ?: return emptyList()
        val dashIndent = pb.body
            .filter { it.trimStart().startsWith("-") }
            .minOfOrNull { indentOf(it) } ?: return emptyList()
        val items = ArrayList<MutableList<String>>()
        for (ln in pb.body) {
            if (indentOf(ln) == dashIndent && ln.trimStart().startsWith("-")) {
                val rest = ln.trimStart().removePrefix("-").trimStart()
                items.add(mutableListOf(" ".repeat(dashIndent + 2) + rest))
            } else if (items.isNotEmpty()) {
                items.last().add(ln)
            }
        }

        val out = ArrayList<String>()
        for (item in items) {
            var m: Map<String, String> =
                if (item.size == 1 && item[0].trim().startsWith("{")) parseFlow(item[0], "")
                else parseMap(item)
            val ref = m["<<"]?.trim()?.removePrefix("*")
            val base = if (ref != null) anchors[ref] else null
            if (base != null) {
                val merged = LinkedHashMap<String, String>(base)
                merged.putAll(m)
                m = merged
            }
            val conf = toConf(m)
            if (conf != null) out.add(conf)
        }
        return out
    }

    private fun toConf(m: Map<String, String>): String? {
        fun g(vararg keys: String): String? {
            for (k in keys) {
                val v = m[k]
                if (!v.isNullOrEmpty()) return v
            }
            return null
        }
        val type = g("type")?.lowercase()
        if (type != null && type != "wireguard") return null
        val priv = g("private-key", "private_key", "privatekey") ?: return null
        val pub = g("public-key", "public_key", "publickey") ?: return null
        val server = g("server") ?: return null
        val port = g("port") ?: "51820"

        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(priv).append('\n')

        val addrs = ArrayList<String>()
        g("ip")?.let { addrs.add(if (it.contains('/')) it else "$it/32") }
        g("ipv6")?.let { addrs.add(if (it.contains('/')) it else "$it/128") }
        if (addrs.isEmpty()) {
            g("address")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.let { addrs.addAll(it) }
        }
        if (addrs.isEmpty()) addrs.add("172.16.0.2/32")
        sb.append("Address = ").append(addrs.joinToString(", ")).append('\n')

        g("dns")?.let {
            sb.append("DNS = ")
                .append(it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
                    .joinToString(", "))
                .append('\n')
        }
        sb.append("MTU = ").append(g("mtu") ?: "1280").append('\n')

        var ignored = 0
        for ((k, v) in m) {
            if (k.startsWith("amnezia-wg-option.") || k.startsWith("amnezia_wg_option.")) {
                val name = k.substringAfter('.').lowercase()
                if (name in AWG_KEYS && v.isNotEmpty()) {
                    sb.append(name.replaceFirstChar { c -> c.uppercase() })
                        .append(" = ").append(v).append('\n')
                } else if (v.isNotEmpty()) ignored++
            }
        }
        if (ignored > 0) Log.i(TAG, "YAML: $ignored ta nostandart amnezia parametr e'tiborsiz qoldirildi")

        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(pub).append('\n')
        g("pre-shared-key", "preshared-key", "psk")?.let {
            sb.append("PresharedKey = ").append(it).append('\n')
        }
        val host = if (server.contains(':') && !server.startsWith("[")) "[$server]" else server
        sb.append("Endpoint = ").append(host).append(':').append(port).append('\n')
        val allowed = (g("allowed-ips", "allowed_ips") ?: "0.0.0.0/0,::/0")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        sb.append("AllowedIPs = ").append(allowed.joinToString(", ")).append('\n')
        g("reserved")?.let { sb.append("Reserved = ").append(it).append('\n') }
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    private fun indentOf(s: String): Int = s.length - s.trimStart(' ').length

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t[t.length - 1] == t[0]) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    private fun stripComment(s: String): String {
        var q: Char? = null
        for (i in s.indices) {
            val ch = s[i]
            if (q != null) {
                if (ch == q) q = null
            } else if (ch == '"' || ch == '\'') {
                q = ch
            } else if (ch == '#' && (i == 0 || s[i - 1] == ' ')) {
                return s.substring(0, i)
            }
        }
        return s
    }

    private fun keyColon(t: String): Int {
        for (i in t.indices) {
            if (t[i] == ':' && (i + 1 == t.length || t[i + 1] == ' ')) return i
        }
        return -1
    }

    private fun splitTop(s: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var q: Char? = null
        for (ch in s) {
            if (q != null) {
                cur.append(ch)
                if (ch == q) q = null
                continue
            }
            if (ch == '"' || ch == '\'') {
                q = ch
                cur.append(ch)
            } else if (ch == '[' || ch == '{') {
                depth++
                cur.append(ch)
            } else if (ch == ']' || ch == '}') {
                depth--
                cur.append(ch)
            } else if (ch == ',' && depth == 0) {
                parts.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString())
        return parts
    }

    private fun flowValue(v: String): String {
        val t = v.trim()
        if (t.startsWith("[") && t.endsWith("]")) {
            return splitTop(t.substring(1, t.length - 1)).joinToString(",") { unquote(it) }
        }
        return unquote(t)
    }

    private fun parseFlow(s: String, prefix: String): Map<String, String> {
        var t = s.trim()
        if (t.startsWith("{") && t.endsWith("}")) t = t.substring(1, t.length - 1)
        val out = LinkedHashMap<String, String>()
        for (p in splitTop(t)) {
            val piece = p.trim()
            val c = keyColon(piece)
            if (c < 0) continue
            val k = unquote(piece.substring(0, c))
            val v = piece.substring(c + 1).trim()
            if (v.startsWith("{")) out.putAll(parseFlow(v, "$prefix$k."))
            else out[prefix + k] = flowValue(v)
        }
        return out
    }

    private fun parseMap(lines: List<String>): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val stack = ArrayList<Pair<Int, String>>()
        var listKey: String? = null
        for (ln in lines) {
            val ind = indentOf(ln)
            val t = ln.trim()
            if (t.startsWith("- ") || t == "-") {
                val lk = listKey ?: continue
                val v = unquote(t.removePrefix("-").trim())
                val old = out[lk]
                out[lk] = if (old.isNullOrEmpty()) v else "$old,$v"
                continue
            }
            val c = keyColon(t)
            if (c < 0) continue
            while (stack.isNotEmpty() && stack[stack.size - 1].first >= ind) {
                stack.removeAt(stack.size - 1)
            }
            val key = unquote(t.substring(0, c))
            val value = t.substring(c + 1).trim()
            val path = (stack.map { it.second } + key).joinToString(".")
            if (value.isEmpty()) {
                stack.add(Pair(ind, key))
                listKey = path
                if (!out.containsKey(path)) out[path] = ""
            } else {
                out[path] = flowValue(value)
                listKey = null
            }
        }
        return out
    }
}

```
