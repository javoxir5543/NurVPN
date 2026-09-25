package com.nurvpn.app.core

class AWGConfig(@JvmField var rawConf: String?) {
    var name: String? = null
    var endpoint: String? = null
    var address: String? = null
    var ping: Int = -1
    var favorite: Boolean = false
}

