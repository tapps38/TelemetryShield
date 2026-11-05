package dev.telemetryshield

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile: tap to turn protection on or off without opening the app. */
class ShieldTileService : TileService() {

    companion object {
        /** Ask the system to refresh the tile (call when protection starts or stops). */
        fun refresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, ShieldTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Stats.running) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
            setTileState(false)
            return
        }
        // First use (VPN permission not granted yet) has to go through the app.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        try {
            Stats.reset()
            startForegroundService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
            setTileState(true)
        } catch (_: Exception) {
            // Some Android versions don't allow starting it from the tile; fall back to the app.
            openApp()
        }
    }

    private fun updateTile() = setTileState(Stats.running)

    private fun setTileState(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Telemetry Shield"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
