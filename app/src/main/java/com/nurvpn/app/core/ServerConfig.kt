package com.nurvpn.app.core

/** Server editor uchun barcha sozlamalar. */
class ServerConfig(@JvmField var originalLink: String) {
    var remark: String = ""
    var protocol: Protocol = Protocol.VLESS_REALITY

    var host: String = ""
    var port: Int = 443

    // Auth
    var uuid: String = ""
    var password: String = ""
    var method: String = ""
    var flow: String = ""

    // VMess
    var alterId: Int = 0
    var vmessSecurity: String = "auto"

    // Transport
    var transport: String = "tcp"
    var path: String = "/"
    var hostHeader: String = ""
    var serviceName: String = ""
    var xhttpMode: String = "auto"

    // TLS / Security
    var tlsSecurity: String = "none"   // none / tls / reality
    var sni: String = ""
    var alpn: String = ""
    var fingerprint: String = "chrome"
    var publicKey: String = ""
    var shortId: String = ""
    var allowInsecure: Boolean = false

    // Hysteria2
    var obfs: String = ""
    var obfsPassword: String = ""
    var upMbps: String = ""
    var downMbps: String = ""

    // TUIC
    var congestionControl: String = "bbr"
    var udpRelayMode: String = "native"

    // Mux
    var muxEnabled: Boolean = false
    var muxConcurrency: Int = 8
}
