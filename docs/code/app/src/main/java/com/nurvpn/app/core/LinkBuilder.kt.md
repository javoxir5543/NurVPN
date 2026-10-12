# `app/src/main/java/com/nurvpn/app/core/LinkBuilder.kt`

```kotlin
package com.nurvpn.app.core

import android.net.Uri
import java.net.URLEncoder

/** Link <-> ServerConfig. */
object LinkBuilder {

    fun parse(link: String): ServerConfig {
        val cfg = ServerConfig(link)
        val si = com.nurvpn.app.parser.ServerLinkParser.parse(link) ?: return cfg
        cfg.remark = si.remark ?: ""
        cfg.protocol = si.protocol
        cfg.host = si.host ?: ""
        cfg.port = si.port

        val q = extractQuery(link)
        val ui = extractUserInfo(link)

        when (cfg.protocol) {
            Protocol.VLESS_REALITY -> {
                cfg.uuid = ui
                cfg.flow = q["flow"] ?: ""
                cfg.transport = q["type"] ?: "tcp"
                cfg.path = q["path"] ?: "/"
                cfg.hostHeader = q["host"] ?: ""
                cfg.serviceName = q["serviceName"] ?: ""
                cfg.xhttpMode = q["mode"] ?: q["xhttpMode"] ?: "auto"
                cfg.tlsSecurity = q["security"] ?: "none"
                cfg.sni = q["sni"] ?: ""
                cfg.publicKey = q["pbk"] ?: ""
                cfg.shortId = q["sid"] ?: ""
                cfg.fingerprint = q["fp"] ?: "chrome"
                cfg.alpn = q["alpn"] ?: ""
                cfg.allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1"
            }
            Protocol.VMESS -> {
                cfg.uuid = ui
                cfg.alterId = q["aid"]?.toIntOrNull() ?: 0
                cfg.transport = q["type"] ?: "tcp"
                cfg.path = q["path"] ?: "/"
                cfg.hostHeader = q["host"] ?: ""
                cfg.serviceName = q["serviceName"] ?: ""
                cfg.tlsSecurity = q["security"] ?: "none"
                cfg.sni = q["sni"] ?: ""
                cfg.alpn = q["alpn"] ?: ""
                cfg.vmessSecurity = q["encryption"] ?: "auto"
            }
            Protocol.TROJAN -> {
                cfg.password = ui
                cfg.transport = q["type"] ?: "tcp"
                cfg.path = q["path"] ?: "/"
                cfg.hostHeader = q["host"] ?: ""
                cfg.serviceName = q["serviceName"] ?: ""
                cfg.tlsSecurity = "tls"
                cfg.sni = q["sni"] ?: ""
                cfg.alpn = q["alpn"] ?: ""
                cfg.fingerprint = q["fp"] ?: "chrome"
                cfg.allowInsecure = q["allowInsecure"] == "1"
            }
            Protocol.SS_2022, Protocol.SS_LEGACY -> {
                if (ui.contains(":")) {
                    cfg.method = ui.substringBefore(":")
                    cfg.password = ui.substringAfter(":")
                }
            }
            Protocol.HYSTERIA2 -> {
                cfg.password = ui
                cfg.transport = "quic"
                cfg.tlsSecurity = "tls"
                cfg.sni = q["sni"] ?: ""
                cfg.obfs = q["obfs"] ?: ""
                cfg.obfsPassword = q["obfs-password"] ?: ""
                cfg.allowInsecure = q["insecure"] == "1"
                cfg.upMbps = q["upmbps"] ?: ""
                cfg.downMbps = q["downmbps"] ?: ""
            }
            Protocol.TUIC -> {
                cfg.uuid = ui.substringBefore(":")
                cfg.password = ui.substringAfter(":", "")
                cfg.transport = "quic"
                cfg.tlsSecurity = "tls"
                cfg.sni = q["sni"] ?: ""
                cfg.alpn = q["alpn"] ?: ""
                cfg.congestionControl = q["congestion_control"] ?: "bbr"
                cfg.udpRelayMode = q["udp_relay_mode"] ?: "native"
                cfg.allowInsecure = q["insecure"] == "1"
            }
            else -> {}
        }
        cfg.muxEnabled = q["mux"] == "1"
        cfg.muxConcurrency = q["muxConcurrency"]?.toIntOrNull() ?: 8
        return cfg
    }

    fun build(cfg: ServerConfig): String = when (cfg.protocol) {
        Protocol.VLESS_REALITY -> buildVless(cfg)
        Protocol.VMESS -> buildVmess(cfg)
        Protocol.TROJAN -> buildTrojan(cfg)
        Protocol.SS_2022, Protocol.SS_LEGACY -> buildSs(cfg)
        Protocol.HYSTERIA2 -> buildHy2(cfg)
        Protocol.TUIC -> buildTuic(cfg)
        else -> cfg.originalLink
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (t: Throwable) { s }

    private fun extractUserInfo(link: String): String {
        val noFrag = link.substringBefore("#")
        val noQuery = noFrag.substringBefore("?")
        val at = noQuery.indexOf('@')
        if (at < 0) return ""
        val sep = noQuery.indexOf("://")
        if (sep < 0 || sep >= at) return ""
        return try { Uri.decode(noQuery.substring(sep + 3, at)) }
                catch (t: Throwable) { noQuery.substring(sep + 3, at) }
    }

    private fun extractQuery(link: String): Map<String, String> {
        val out = HashMap<String, String>()
        val q = link.substringBefore("#").substringAfter("?", "")
        if (q.isEmpty()) return out
        for (pair in q.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                out[kv[0]] = try { Uri.decode(kv[1]) } catch (t: Throwable) { kv[1] }
            }
        }
        return out
    }

    private fun buildVless(c: ServerConfig): String {
        val params = mutableListOf<String>()
        params.add("encryption=none")
        params.add("type=${c.transport}")
        if (c.flow.isNotEmpty()) params.add("flow=${enc(c.flow)}")
        when (c.transport) {
            "ws", "httpupgrade" -> {
                if (c.path.isNotEmpty()) params.add("path=${enc(c.path)}")
                if (c.hostHeader.isNotEmpty()) params.add("host=${enc(c.hostHeader)}")
            }
            "xhttp" -> {
                if (c.path.isNotEmpty()) params.add("path=${enc(c.path)}")
                if (c.hostHeader.isNotEmpty()) params.add("host=${enc(c.hostHeader)}")
                params.add("mode=${c.xhttpMode}")
                params.add("xhttpMode=${c.xhttpMode}")
            }
            "grpc" -> {
                if (c.serviceName.isNotEmpty()) params.add("serviceName=${enc(c.serviceName)}")
            }
        }
        params.add("security=${c.tlsSecurity}")
        if (c.tlsSecurity == "reality" || c.tlsSecurity == "tls") {
            if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
            if (c.alpn.isNotEmpty()) params.add("alpn=${enc(c.alpn)}")
            if (c.fingerprint.isNotEmpty()) params.add("fp=${c.fingerprint}")
        }
        if (c.tlsSecurity == "reality") {
            if (c.publicKey.isNotEmpty()) params.add("pbk=${c.publicKey}")
            if (c.shortId.isNotEmpty()) params.add("sid=${c.shortId}")
        }
        if (c.allowInsecure) params.add("allowInsecure=1")
        if (c.muxEnabled) {
            params.add("mux=1")
            params.add("muxConcurrency=${c.muxConcurrency}")
        }
        val sb = StringBuilder("vless://")
        sb.append(enc(c.uuid)).append("@").append(c.host).append(":").append(c.port)
        sb.append("?").append(params.joinToString("&"))
        if (c.remark.isNotEmpty()) sb.append("#").append(enc(c.remark))
        return sb.toString()
    }

    private fun buildVmess(c: ServerConfig): String {
        val params = mutableListOf<String>()
        params.add("encryption=${c.vmessSecurity}")
        params.add("type=${c.transport}")
        when (c.transport) {
            "ws", "httpupgrade" -> {
                if (c.path.isNotEmpty()) params.add("path=${enc(c.path)}")
                if (c.hostHeader.isNotEmpty()) params.add("host=${enc(c.hostHeader)}")
            }
            "grpc" -> {
                if (c.serviceName.isNotEmpty()) params.add("serviceName=${enc(c.serviceName)}")
            }
        }
        params.add("security=${c.tlsSecurity}")
        if (c.tlsSecurity == "tls") {
            if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
            if (c.alpn.isNotEmpty()) params.add("alpn=${enc(c.alpn)}")
        }
        if (c.alterId > 0) params.add("aid=${c.alterId}")
        if (c.allowInsecure) params.add("allowInsecure=1")
        val sb = StringBuilder("vmess://")
        sb.append(enc(c.uuid)).append("@").append(c.host).append(":").append(c.port)
        sb.append("?").append(params.joinToString("&"))
        if (c.remark.isNotEmpty()) sb.append("#").append(enc(c.remark))
        return sb.toString()
    }

    private fun buildTrojan(c: ServerConfig): String {
        val params = mutableListOf<String>()
        params.add("type=${c.transport}")
        when (c.transport) {
            "ws" -> {
                if (c.path.isNotEmpty()) params.add("path=${enc(c.path)}")
                if (c.hostHeader.isNotEmpty()) params.add("host=${enc(c.hostHeader)}")
            }
            "grpc" -> {
                if (c.serviceName.isNotEmpty()) params.add("serviceName=${enc(c.serviceName)}")
            }
        }
        params.add("security=tls")
        if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
        if (c.alpn.isNotEmpty()) params.add("alpn=${enc(c.alpn)}")
        if (c.fingerprint.isNotEmpty()) params.add("fp=${c.fingerprint}")
        if (c.allowInsecure) params.add("allowInsecure=1")
        val sb = StringBuilder("trojan://")
        sb.append(enc(c.password)).append("@").append(c.host).append(":").append(c.port)
        sb.append("?").append(params.joinToString("&"))
        if (c.remark.isNotEmpty()) sb.append("#").append(enc(c.remark))
        return sb.toString()
    }

    private fun buildSs(c: ServerConfig): String {
        val userInfo = "${c.method}:${c.password}"
        val b64 = android.util.Base64.encodeToString(
            userInfo.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        val sb = StringBuilder("ss://")
        sb.append(b64).append("@").append(c.host).append(":").append(c.port)
        if (c.remark.isNotEmpty()) sb.append("#").append(enc(c.remark))
        return sb.toString()
    }

    private fun buildHy2(c: ServerConfig): String {
        val params = mutableListOf<String>()
        if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
        if (c.obfs.isNotEmpty()) params.add("obfs=${c.obfs}")
        if (c.obfsPassword.isNotEmpty()) params.add("obfs-password=${enc(c.obfsPassword)}")
        if (c.allowInsecure) params.add("insecure=1")
        if (c.upMbps.isNotEmpty()) params.add("upmbps=${c.upMbps}")
        if (c.downMbps.isNotEmpty()) params.add("downmbps=${c.downMbps}")
        val sb = StringBuilder("hysteria2://")
        sb.append(enc(c.password)).append("@").append(c.host).append(":").append(c.port)
        if (params.isNotEmpty()) sb.append("?").append(params.joinToString("&"))
        if (c.remark.isNotEmpty()) sb.append("#").append(enc(c.remark))
        return sb.toString()
    }

    private fun buildTuic(c: ServerConfig): String {
        val params = mutableListOf<String>()
        if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
        if (c.alpn.isNotEmpty()) params.add("alpn=${enc(c.alpn)}")
        params.add("congestion_control=${c.congestionControl}")
        params.add("udp_relay_mode=${c.udpRelayMode}")
        if (c.allowInsecure) params.add("insecure=1")
        val sb = StringBuilder("tuic://")
        sb.append(enc(c.uuid)).append(":").append(enc(c.password))
        sb.append("@").append(c.host).append(":").append(c.port)
        sb.append("?").append(params.joinToString("&"))
        if (c.remark.isNotEmpty()) sb.append("#").append(enc(c.remark))
        return sb.toString()
    }
}

```
