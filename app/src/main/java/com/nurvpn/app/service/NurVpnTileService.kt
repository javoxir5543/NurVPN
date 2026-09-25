package com.nurvpn.app.service

import com.nurvpn.app.R
import com.nurvpn.app.ui.MainActivity
import com.nurvpn.app.service.NurVpnService
import com.nurvpn.app.core.TunnelState

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings panelidagi NurVPN tugmasi.
 * qWDTT/INCY kabi — VPN ON/OFF toggle.
 */
@RequiresApi(Build.VERSION_CODES.N)
class NurVpnTileService : TileService() {

    /** ActivityManager orqali NurVpnService ishlayaptimi — aniq tekshirish. */
    private fun isVpnServiceRunning(): Boolean {
        try {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (svc in am.getRunningServices(80)) {
                if (svc.service.className == NurVpnService::class.java.name) {
                    return true
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-Tile",
                "isVpnServiceRunning xato: ${t.message}")
        }
        return false
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        // ActivityManager orqali haqiqiy holat (TunnelState'ga ishonchsiz)
        val isRunning = isVpnServiceRunning()
        android.util.Log.i("NurVPN-Tile",
            "onClick: isVpnServiceRunning=$isRunning, TunnelState=${TunnelState.isConnected}")

        if (isRunning) {
            // VPN'ni to'xtatish
            android.util.Log.i("NurVPN-Tile", "onClick: STOP yuborilmoqda")
            val i = Intent(this, NurVpnService::class.java)
            i.putExtra(MainActivity.EXTRA_STOP, true)
            startService(i)
        } else {
            // Oxirgi config bilan to'g'ridan-to'g'ri ulanish
            val prefs = getSharedPreferences("main", MODE_PRIVATE)
            val lastLink = prefs.getString("current_link", null)
            val lastAwg = prefs.getString("current_awg", null)
            val proto = prefs.getString("protocol", MainActivity.PROTO_XRAY)
                ?: MainActivity.PROTO_XRAY

            val hasConfig = if (proto == MainActivity.PROTO_AWG)
                !lastAwg.isNullOrEmpty()
            else !lastLink.isNullOrEmpty()

            if (hasConfig) {
                // Config bor — to'g'ridan-to'g'ri ulanish (ilova ochilmaydi)
                android.util.Log.i("NurVPN-Tile",
                    "Tile: to'g'ridan-to'g'ri ulanish, proto=$proto")
                val i = Intent(this, NurVpnService::class.java).apply {
                    if (proto == MainActivity.PROTO_AWG) {
                        putExtra(MainActivity.EXTRA_AWG, lastAwg)
                        putExtra("protocol", MainActivity.PROTO_AWG)
                    } else {
                        putExtra(MainActivity.EXTRA_LINK, lastLink)
                        putExtra("protocol", MainActivity.PROTO_XRAY)
                    }
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            } else {
                // Config yo'q — MainActivity'ni ochish (server tanlash kerak)
                android.util.Log.i("NurVPN-Tile",
                    "Tile: config yo'q — MainActivity ochilyapti")
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(this, 0, i,
                            android.app.PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(i)
                }
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val connected = isVpnServiceRunning()
        android.util.Log.i("NurVPN-Tile",
            "updateTileState: running=$connected, tile=${if (connected) "ACTIVE" else "INACTIVE"}")
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Icon: shield (fallback — system lock)
        tile.icon = Icon.createWithResource(this,
            if (connected) R.drawable.ic_vpn_lock
            else android.R.drawable.ic_lock_lock)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (connected) R.string.tile_subtitle_on
                else R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }
}
