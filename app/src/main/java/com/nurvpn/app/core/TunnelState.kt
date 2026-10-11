package com.nurvpn.app.core

import android.util.Log

object TunnelState {
    @Volatile var isConnected: Boolean = false
        set(v) {
            Log.d("NurVPN-PING", "TunnelState.isConnected → $v")
            field = v
        }
}
