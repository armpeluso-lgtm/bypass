package com.bypasspower.app

import android.app.Activity
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var stateText: TextView
    private lateinit var permissionText: TextView
    private lateinit var maintenanceText: TextView
    private lateinit var batteryLevelText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var batteryCurrentText: TextView
    private lateinit var batteryPowerText: TextView
    private lateinit var batteryRollingText: TextView
    private lateinit var bypassDiagnosisText: TextView
    private lateinit var batteryVoltageText: TextView
    private lateinit var batterySessionText: TextView
    private lateinit var toggleButton: Button
    private lateinit var permissionButton: Button

    private val telemetryHandler = Handler(Looper.getMainLooper())
    private var telemetryStartedAt = 0L
    private var lastTelemetryAt = 0L
    private var lastPowerWatts: Double? = null
    private var sessionEnergyWh = 0.0
    private var lastKnownBypassState: Boolean? = null
    private val rollingSamples = ArrayDeque<RollingSample>()

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            updateBatteryTelemetry()
            telemetryHandler.postDelayed(this, TELEMETRY_INTERVAL_MILLIS)
        }
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateText = findViewById(R.id.stateText)
        permissionText = findViewById(R.id.permissionText)
        maintenanceText = findViewById(R.id.maintenanceText)
        batteryLevelText = findViewById(R.id.batteryLevelText)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        batteryCurrentText = findViewById(R.id.batteryCurrentText)
        batteryPowerText = findViewById(R.id.batteryPowerText)
        batteryRollingText = findViewById(R.id.batteryRollingText)
        bypassDiagnosisText = findViewById(R.id.bypassDiagnosisText)
        batteryVoltageText = findViewById(R.id.batteryVoltageText)
        batterySessionText = findViewById(R.id.batterySessionText)
        toggleButton = findViewById(R.id.toggleButton)
        permissionButton = findViewById(R.id.permissionButton)

        toggleButton.setOnClickListener {
            if (!Settings.System.canWrite(this)) {
                openWriteSettingsPermission()
                return@setOnClickListener
            }

            if (
                PassThroughManager.wouldToggleEnable(this) &&
                !PowerConnectionState.isBatteryAboveMinimum(this)
            ) {
                Toast.makeText(this, R.string.battery_too_low, Toast.LENGTH_LONG).show()
                refreshUi()
                return@setOnClickListener
            }

            val changed = PassThroughManager.toggle(this)
            refreshUi()
            if (!changed) {
                Toast.makeText(this, R.string.change_failed, Toast.LENGTH_LONG).show()
            }
        }

        permissionButton.setOnClickListener { openWriteSettingsPermission() }
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(PassThroughManager.SETTING_NAME),
            false,
            settingsObserver
        )
        refreshUi()
        startBatteryTelemetry()
    }

    override fun onResume() {
        super.onResume()
        ChargingMonitorService.syncState(this)
        refreshUi()
    }

    override fun onStop() {
        telemetryHandler.removeCallbacks(telemetryRunnable)
        contentResolver.unregisterContentObserver(settingsObserver)
        super.onStop()
    }

    private fun refreshUi() {
        val enabled = PassThroughManager.isEnabled(this)
        val canWrite = Settings.System.canWrite(this)
        val keepEnabled = PassThroughManager.shouldKeepEnabled(this)

        if (
            telemetryStartedAt > 0L &&
            lastKnownBypassState != null &&
            lastKnownBypassState != enabled
        ) {
            startBatteryTelemetry()
        }
        lastKnownBypassState = enabled

        stateText.setText(if (enabled) R.string.state_enabled else R.string.state_normal)
        toggleButton.setText(
            if (enabled || keepEnabled) R.string.action_disable else R.string.action_enable
        )
        permissionText.setText(if (canWrite) R.string.permission_granted else R.string.permission_denied)
        maintenanceText.setText(
            when {
                !PowerConnectionState.isBatteryAboveMinimum(this) ->
                    R.string.maintenance_stopped_low_battery
                !keepEnabled -> R.string.maintenance_disabled
                else -> R.string.maintenance_active
            }
        )
        permissionButton.visibility = if (canWrite) View.GONE else View.VISIBLE
    }

    private fun openWriteSettingsPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun startBatteryTelemetry() {
        telemetryHandler.removeCallbacks(telemetryRunnable)
        telemetryStartedAt = SystemClock.elapsedRealtime()
        lastTelemetryAt = 0L
        lastPowerWatts = null
        sessionEnergyWh = 0.0
        rollingSamples.clear()
        telemetryHandler.post(telemetryRunnable)
    }

    private fun updateBatteryTelemetry() {
        val snapshot = BatteryTelemetry.read(this)
        val now = SystemClock.elapsedRealtime()

        val currentPower = snapshot.netPowerWatts
        if (lastTelemetryAt > 0L && currentPower != null && lastPowerWatts != null) {
            val elapsedHours = (now - lastTelemetryAt) / 3_600_000.0
            sessionEnergyWh += (lastPowerWatts!! + currentPower) * 0.5 * elapsedHours
        }
        lastTelemetryAt = now
        lastPowerWatts = currentPower

        batteryLevelText.text = snapshot.levelPercent?.let {
            getString(R.string.battery_level_format, it)
        } ?: getString(R.string.battery_level_unavailable)

        batteryStatusText.text = getString(
            R.string.battery_status_format,
            batteryStatusLabel(snapshot.status),
            powerSourceLabel(snapshot.plugged)
        )

        batteryCurrentText.text = if (snapshot.currentNowMilliamps != null) {
            val currentAverage = snapshot.currentAverageMilliamps?.let(::formatSignedMilliamps)
                ?: getString(R.string.value_unavailable)
            getString(
                R.string.battery_current_format,
                formatSignedMilliamps(snapshot.currentNowMilliamps),
                currentAverage,
                currentDirectionLabel(snapshot.currentNowMilliamps)
            )
        } else {
            getString(R.string.battery_current_unavailable)
        }

        batteryPowerText.text = currentPower?.let {
            getString(R.string.battery_power_format, formatSignedWatts(it))
        } ?: getString(R.string.battery_power_unavailable)

        updateRollingAverage(snapshot, now)

        batteryVoltageText.text = if (
            snapshot.voltageVolts != null && snapshot.temperatureCelsius != null
        ) {
            getString(
                R.string.battery_voltage_temperature_format,
                formatDecimal(snapshot.voltageVolts, 3),
                formatDecimal(snapshot.temperatureCelsius, 1)
            )
        } else {
            getString(R.string.battery_voltage_temperature_unavailable)
        }

        val sessionHours = (now - telemetryStartedAt) / 3_600_000.0
        val averagePower = if (sessionHours > 0.0) sessionEnergyWh / sessionHours else 0.0
        batterySessionText.text = getString(
            R.string.battery_session_format,
            formatSignedEnergy(sessionEnergyWh),
            formatSignedWatts(averagePower)
        )
    }

    private fun batteryStatusLabel(status: Int): String = getString(
        when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> R.string.battery_status_charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.battery_status_discharging
            BatteryManager.BATTERY_STATUS_FULL -> R.string.battery_status_full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.battery_status_not_charging
            else -> R.string.battery_status_unknown
        }
    )

    private fun powerSourceLabel(plugged: Int): String = getString(
        when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> R.string.power_source_ac
            BatteryManager.BATTERY_PLUGGED_USB -> R.string.power_source_usb
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.power_source_wireless
            else -> R.string.power_source_battery
        }
    )

    private fun currentDirectionLabel(milliamps: Double): String = getString(
        when {
            milliamps > CURRENT_NEUTRAL_THRESHOLD_MA -> R.string.current_into_battery
            milliamps < -CURRENT_NEUTRAL_THRESHOLD_MA -> R.string.current_from_battery
            else -> R.string.current_near_zero
        }
    )

    private fun formatSignedMilliamps(value: Double): String =
        String.format(Locale.getDefault(), "%+.0f", value)

    private fun formatSignedWatts(value: Double): String =
        String.format(Locale.getDefault(), "%+.3f", value)

    private fun formatSignedEnergy(value: Double): String =
        String.format(Locale.getDefault(), "%+.4f", value)

    private fun formatDecimal(value: Double, decimals: Int): String =
        String.format(Locale.getDefault(), "%.${decimals}f", value)

    private fun updateRollingAverage(snapshot: BatterySnapshot, now: Long) {
        val current = snapshot.currentNowMilliamps
        val power = snapshot.netPowerWatts
        if (current != null) {
            rollingSamples.addLast(RollingSample(now, current, power))
        }

        val oldestAllowed = now - ROLLING_WINDOW_MILLIS
        while (rollingSamples.peekFirst()?.timestampMillis?.let { it < oldestAllowed } == true) {
            rollingSamples.removeFirst()
        }

        if (rollingSamples.isEmpty()) {
            batteryRollingText.setText(R.string.battery_rolling_unavailable)
            bypassDiagnosisText.setText(R.string.bypass_diagnosis_unavailable)
            return
        }

        var currentSum = 0.0
        var powerSum = 0.0
        var powerSamples = 0
        rollingSamples.forEach { sample ->
            currentSum += sample.currentMilliamps
            sample.powerWatts?.let {
                powerSum += it
                powerSamples++
            }
        }
        val averageCurrent = currentSum / rollingSamples.size
        val averagePower = if (powerSamples > 0) powerSum / powerSamples else null
        batteryRollingText.text = getString(
            R.string.battery_rolling_format,
            ROLLING_WINDOW_MILLIS / 1_000L,
            formatSignedMilliamps(averageCurrent),
            averagePower?.let(::formatSignedWatts) ?: getString(R.string.value_unavailable)
        )

        val firstSampleAt = rollingSamples.peekFirst()?.timestampMillis ?: now
        val sampleDuration = now - firstSampleAt
        bypassDiagnosisText.setText(
            when {
                !PowerConnectionState.isBatteryAboveMinimum(this) ->
                    R.string.bypass_diagnosis_low_battery
                !PassThroughManager.isEnabled(this) -> R.string.bypass_diagnosis_inactive
                snapshot.plugged == 0 -> R.string.bypass_diagnosis_connect_charger
                sampleDuration < DIAGNOSIS_MINIMUM_MILLIS -> R.string.bypass_diagnosis_analyzing
                kotlin.math.abs(averageCurrent) <= BYPASS_NEAR_ZERO_THRESHOLD_MA ->
                    R.string.bypass_diagnosis_probable
                averageCurrent < -BYPASS_NEAR_ZERO_THRESHOLD_MA ->
                    R.string.bypass_diagnosis_discharging
                else -> R.string.bypass_diagnosis_not_confirmed
            }
        )
    }

    private data class RollingSample(
        val timestampMillis: Long,
        val currentMilliamps: Double,
        val powerWatts: Double?
    )

    companion object {
        private const val TELEMETRY_INTERVAL_MILLIS = 1_000L
        private const val CURRENT_NEUTRAL_THRESHOLD_MA = 25.0
        private const val ROLLING_WINDOW_MILLIS = 30_000L
        private const val DIAGNOSIS_MINIMUM_MILLIS = 10_000L
        private const val BYPASS_NEAR_ZERO_THRESHOLD_MA = 250.0
    }
}
