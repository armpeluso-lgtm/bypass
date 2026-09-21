package com.bypasspower.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class BypassTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        ChargingMonitorService.syncState(this)
        updateTileFromSystem()
    }

    override fun onClick() {
        super.onClick()

        if (!Settings.System.canWrite(this)) {
            updateTileFromSystem()
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            openWriteSettingsPermission()
            return
        }

        if (
            PassThroughManager.wouldToggleEnable(this) &&
            !PowerConnectionState.isBatteryAboveMinimum(this)
        ) {
            updateTileFromSystem()
            Toast.makeText(this, R.string.battery_too_low, Toast.LENGTH_LONG).show()
            return
        }

        val changed = PassThroughManager.toggle(this)
        updateTileFromSystem()

        if (!changed) {
            Toast.makeText(this, R.string.change_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTileFromSystem() {
        val tile = qsTile ?: return
        val enabled = PassThroughManager.isEnabled(this)
        val stateText = getString(
            if (enabled) R.string.state_enabled_short else R.string.state_disabled_short
        )

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = stateText
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = stateText
        }
        tile.updateTile()
    }

    private fun writeSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openWriteSettingsPermission() {
        val intent = writeSettingsIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
