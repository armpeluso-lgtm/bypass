package com.bypasspower.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

class ChargingMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val restoreRunnable = Runnable { restoreIfNeeded() }
    private val connectionRestoreRunnables = mutableListOf<Runnable>()
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!PowerConnectionState.isBatteryAboveMinimum(context)) {
                PassThroughManager.disableForLowBattery(context)
                updateForegroundNotification()
            } else {
                scheduleRestore(delayMillis = 0L)
            }
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            scheduleRestore()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            settingsObserver
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        registerBatteryReceiverIfNeeded()

        if (!PowerConnectionState.isBatteryAboveMinimum(this)) {
            PassThroughManager.disableForLowBattery(this)
        }

        if (!shouldRun()) {
            stopSelf()
            return START_NOT_STICKY
        }

        scheduleRestore(delayMillis = 0L)
        if (intent?.action == ACTION_REASSERT_AFTER_POWER_CONNECTED) {
            scheduleConnectionReassertions()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(restoreRunnable)
        clearConnectionReassertions()
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        contentResolver.unregisterContentObserver(settingsObserver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shouldRun(): Boolean =
        (
            PassThroughManager.shouldKeepEnabled(this) ||
                !PowerConnectionState.isBatteryAboveMinimum(this)
            ) &&
            Settings.System.canWrite(this)

    private fun scheduleRestore(delayMillis: Long = RESTORE_DEBOUNCE_MILLIS) {
        handler.removeCallbacks(restoreRunnable)
        handler.postDelayed(restoreRunnable, delayMillis)
    }

    private fun scheduleConnectionReassertions() {
        clearConnectionReassertions()
        CONNECTION_REASSERT_DELAYS_MILLIS.forEach { delayMillis ->
            val runnable = Runnable { restoreIfNeeded() }
            connectionRestoreRunnables += runnable
            handler.postDelayed(runnable, delayMillis)
        }
    }

    private fun clearConnectionReassertions() {
        connectionRestoreRunnables.forEach(handler::removeCallbacks)
        connectionRestoreRunnables.clear()
    }

    private fun restoreIfNeeded() {
        if (!PowerConnectionState.isBatteryAboveMinimum(this)) {
            if (PassThroughManager.isEnabled(this) || PassThroughManager.shouldKeepEnabled(this)) {
                PassThroughManager.disableForLowBattery(this)
            }
            updateForegroundNotification()
            return
        }

        if (!shouldRun()) {
            stopSelf()
            return
        }

        if (!PassThroughManager.isEnabled(this)) {
            PassThroughManager.setEnabled(this, true)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBatteryReceiverIfNeeded() {
        if (batteryReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val batteryLow = !PowerConnectionState.isBatteryAboveMinimum(this)
        return builder
            .setSmallIcon(R.drawable.ic_bypass)
            .setContentTitle(
                getString(
                    if (batteryLow) R.string.monitor_notification_title_low
                    else R.string.monitor_notification_title
                )
            )
            .setContentText(
                getString(
                    if (batteryLow) R.string.monitor_notification_text_low
                    else R.string.monitor_notification_text
                )
            )
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.monitor_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "bypass_while_charging"
        private const val NOTIFICATION_ID = 1001
        private const val RESTORE_DEBOUNCE_MILLIS = 500L
        private const val ACTION_REASSERT_AFTER_POWER_CONNECTED =
            "com.bypasspower.app.action.REASSERT_AFTER_POWER_CONNECTED"
        private val CONNECTION_REASSERT_DELAYS_MILLIS =
            longArrayOf(250L, 750L, 1_500L, 3_000L, 5_000L, 8_000L, 12_000L)

        fun onPowerConnected(context: Context) {
            val batteryAboveMinimum = PowerConnectionState.isBatteryAboveMinimum(context)
            val shouldKeepEnabled = PassThroughManager.shouldKeepEnabled(context)
            val canWrite = Settings.System.canWrite(context)

            if (!batteryAboveMinimum) {
                PassThroughManager.disableForLowBattery(context)
                syncState(context)
                return
            }

            if (shouldKeepEnabled && canWrite) {
                // Assert immediately in the broadcast callback, before the
                // foreground service finishes starting.
                PassThroughManager.setEnabled(context, true)
                start(context, ACTION_REASSERT_AFTER_POWER_CONNECTED)
            } else {
                syncState(context)
            }
        }

        fun syncState(context: Context) {
            val batteryAboveMinimum = PowerConnectionState.isBatteryAboveMinimum(context)
            if (!batteryAboveMinimum) {
                PassThroughManager.disableForLowBattery(context)
            }

            val shouldStart =
                (PassThroughManager.shouldKeepEnabled(context) || !batteryAboveMinimum) &&
                    Settings.System.canWrite(context)

            if (shouldStart) start(context) else stop(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargingMonitorService::class.java))
        }

        private fun start(context: Context, action: String? = null) {
            val intent = Intent(context, ChargingMonitorService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
