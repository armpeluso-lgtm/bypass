package com.bypasspower.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object PowerConnectionState {
    const val MINIMUM_BYPASS_PERCENT = 20

    @Suppress("DEPRECATION")
    fun isPluggedIn(context: Context): Boolean {
        val batteryStatus = currentBatteryStatus(context) ?: return false

        return batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun batteryPercent(context: Context): Int? {
        val batteryStatus = currentBatteryStatus(context) ?: return null
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        return (level * 100 / scale).coerceIn(0, 100)
    }

    fun isBatteryAboveMinimum(context: Context): Boolean =
        batteryPercent(context)?.let { it > MINIMUM_BYPASS_PERCENT } ?: true

    @Suppress("DEPRECATION")
    private fun currentBatteryStatus(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}
