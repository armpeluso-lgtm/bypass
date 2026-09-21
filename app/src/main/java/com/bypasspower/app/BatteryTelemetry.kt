package com.bypasspower.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatterySnapshot(
    val levelPercent: Int?,
    val status: Int,
    val plugged: Int,
    val currentNowMilliamps: Double?,
    val currentAverageMilliamps: Double?,
    val voltageVolts: Double?,
    val temperatureCelsius: Double?,
    val netPowerWatts: Double?
)

object BatteryTelemetry {
    @Suppress("DEPRECATION")
    fun read(context: Context): BatterySnapshot {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val batteryManager = context.getSystemService(BatteryManager::class.java)

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            null
        }

        val currentNowMicroamps = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeUnless { it == Int.MIN_VALUE }
        val currentAverageMicroamps = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            .takeUnless { it == Int.MIN_VALUE }
        val voltageMillivolts = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }

        val currentNowMilliamps = currentNowMicroamps?.div(1_000.0)
        val voltageVolts = voltageMillivolts?.div(1_000.0)
        val netPowerWatts = if (currentNowMicroamps != null && voltageMillivolts != null) {
            currentNowMicroamps.toDouble() * voltageMillivolts.toDouble() / 1_000_000_000.0
        } else {
            null
        }

        return BatterySnapshot(
            levelPercent = levelPercent,
            status = batteryIntent?.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN,
            plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
            currentNowMilliamps = currentNowMilliamps,
            currentAverageMilliamps = currentAverageMicroamps?.div(1_000.0),
            voltageVolts = voltageVolts,
            temperatureCelsius = batteryIntent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE }
                ?.div(10.0),
            netPowerWatts = netPowerWatts
        )
    }
}
