package com.nurvpn.app.core

object AWGEditorBus {
    @JvmField var configs: MutableList<AWGConfig> = ArrayList()
    @JvmField var current: AWGConfig? = null
    @JvmField var protocol: String = "awg"

    fun init(cfg: MutableList<AWGConfig>, cur: AWGConfig?, proto: String) {
        configs = cfg
        current = cur
        protocol = proto
    }
}
