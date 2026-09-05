package com.example.earthquack

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * SyncTileService
 * ===============
 * Quick Settings Tile service allowing the user to toggle earthQuack ON/OFF
 * directly from the Android quick settings / notification shade.
 */
class SyncTileService : TileService() {

    private val TAG = "SyncTileService"

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val isActive = (tile.state == Tile.STATE_ACTIVE)

        if (isActive) {
            // Stop service
            val stopIntent = Intent(this, EarthQuackService::class.java).apply {
                action = ACTION_STOP_SYNC
            }
            startService(stopIntent)
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "Stopped"
        } else {
            // Start service
            val startIntent = Intent(this, EarthQuackService::class.java).apply {
                action = ACTION_START_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = "Active"
        }
        tile.updateTile()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        // Best-effort: derive from whether service was recently running
        // (TileService can't directly query service state without binding)
        tile.label = "earthQuack"
        // Leave state as-is; onClick toggles it locally. A proper fix would
        // track status via SharedPreferences updated by the service.
        if (tile.state != Tile.STATE_ACTIVE && tile.state != Tile.STATE_INACTIVE) {
            tile.state = Tile.STATE_INACTIVE
        }
        tile.subtitle = if (tile.state == Tile.STATE_ACTIVE) "Active" else "Tap to start"
        tile.updateTile()
    }
}
