package com.nnexe.overlaybrowser

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installDialogCrashGuard()
    }

    /**
     * WebView's own pop-ups (date pickers, some <select> lists, etc.) are Activity dialogs. Our WebView
     * lives in an overlay window that has no Activity, so showing one throws BadTokenException.
     * Instead of letting that kill the app, drop just that event and keep the main loop running.
     */
    private fun installDialogCrashGuard() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: WindowManager.BadTokenException) {
                    Log.w(TAG, "Ignored a pop-up that needs an Activity", e)
                }
            }
        }
    }

    companion object {
        const val TAG = "OverlayBrowser"
    }
}
