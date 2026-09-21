package com.bypasspower.app

import android.content.Context
import android.provider.Settings

object PassThroughManager {
    const val SETTING_NAME = "pass_through"
    private const val PREFERENCES_NAME = "bypass_power_preferences"
    private const val KEY_KEEP_ENABLED = "keep_enabled"

    fun isEnabled(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, SETTING_NAME, 0) == 1

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false

        val requestedValue = if (enabled) 1 else 0
        val writeAccepted = Settings.System.putInt(
            context.contentResolver,
            SETTING_NAME,
            requestedValue
        )

        return writeAccepted && isEnabled(context) == enabled
    }

    fun shouldKeepEnabled(context: Context): Boolean {
        val preferences = preferences(context)
        if (preferences.contains(KEY_KEEP_ENABLED)) {
            return preferences.getBoolean(KEY_KEEP_ENABLED, false)
        }

        // Migration for users updating from the original version: an already
        // active system value is treated as their existing manual choice.
        val initiallyEnabled = isEnabled(context)
        preferences.edit().putBoolean(KEY_KEEP_ENABLED, initiallyEnabled).apply()
        return initiallyEnabled
    }

    fun setUserEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !PowerConnectionState.isBatteryAboveMinimum(context)) return false

        val preferences = preferences(context)
        val previousPreference = preferences.getBoolean(KEY_KEEP_ENABLED, false)

        // Store the intent before writing so the observer cannot undo a manual disable.
        preferences.edit().putBoolean(KEY_KEEP_ENABLED, enabled).apply()
        val changed = setEnabled(context, enabled)

        if (!changed) {
            preferences.edit().putBoolean(KEY_KEEP_ENABLED, previousPreference).apply()
        }

        ChargingMonitorService.syncState(context)
        return changed
    }

    fun wouldToggleEnable(context: Context): Boolean =
        !shouldKeepEnabled(context) && !isEnabled(context)

    fun disableForLowBattery(context: Context): Boolean {
        preferences(context).edit().putBoolean(KEY_KEEP_ENABLED, false).apply()
        return setEnabled(context, false)
    }

    fun toggle(context: Context): Boolean {
        val newValue = if (shouldKeepEnabled(context)) false else !isEnabled(context)
        return setUserEnabled(context, newValue)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
