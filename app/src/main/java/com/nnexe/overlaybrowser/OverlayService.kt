package com.nnexe.overlaybrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast

/** Foreground service that owns the floating window, so it survives while you're in a game or on the home screen. */
class OverlayService : Service(), FloatingWindow.Listener {

    private var window: FloatingWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        goForeground()
        refreshTile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            // Cheap path: called repeatedly while a slider on the settings screen moves.
            window?.refreshAppearance()
            return START_STICKY
        }
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.no_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        when (intent?.action) {
            ACTION_STOP_SELF -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val w = window
                when {
                    w == null -> createWindow(url)
                    w.isMinimized -> w.restore()
                    else -> w.minimize()
                }
            }
            else -> {
                val w = window
                if (w == null) {
                    createWindow(url)
                } else {
                    if (url != null) w.load(url)
                    w.restore()
                }
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.onScreenChanged()
    }

    override fun onDestroy() {
        window?.destroy()
        window = null
        isRunning = false
        refreshTile()
        super.onDestroy()
    }

    private fun createWindow(url: String?) {
        val themed = ContextThemeWrapper(this, R.style.OverlayTheme)
        window = FloatingWindow(themed, getSystemService(WindowManager::class.java), Prefs(this), url, this)
    }

    // FloatingWindow.Listener

    override fun onCloseRequested() {
        stopSelf()
    }

    override fun onMinimizedChanged(minimized: Boolean) {
        updateNotification()
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Notification

    private fun buildNotification(): Notification {
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SELF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val minimized = window?.isMinimized == true
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_public)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (minimized) R.string.notif_minimized else R.string.notif_showing))
            .setContentIntent(toggle)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), getString(R.string.close), close)
                    .build(),
            )
            .build()
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, OverlayTileService::class.java))
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1
        private const val ACTION_SHOW = "com.nnexe.overlaybrowser.SHOW"
        private const val ACTION_TOGGLE = "com.nnexe.overlaybrowser.TOGGLE"
        private const val ACTION_STOP_SELF = "com.nnexe.overlaybrowser.STOP"
        private const val ACTION_REFRESH = "com.nnexe.overlaybrowser.REFRESH"
        private const val EXTRA_URL = "url"

        @Volatile
        var isRunning = false
            private set

        /** Shows the floating window (creating it if needed), optionally opening [url]. */
        fun show(context: Context, url: String? = null) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            if (url != null) intent.putExtra(EXTRA_URL, url)
            context.startForegroundService(intent)
        }

        /** Minimizes the window if it's showing, shows it if it's minimized, starts it if it isn't running. */
        fun toggle(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_TOGGLE))
        }

        /** Re-reads settings (like opacity) into the floating window, if it's open. */
        fun refresh(context: Context) {
            if (isRunning) context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
