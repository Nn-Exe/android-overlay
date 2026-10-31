package com.nnexe.overlaybrowser

import android.app.Application
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
     * Catch that specific exception on the main thread and keep the main loop running without
     * preemptively nesting Looper.loop() during normal execution.
     */
    private fun installDialogCrashGuard() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread === Looper.getMainLooper().thread && isBadToken(throwable)) {
                Log.w(TAG, "Ignored a pop-up that needs an Activity", throwable)
                while (true) {
                    try {
                        Looper.loop()
                    } catch (e: Throwable) {
                        if (isBadToken(e)) {
                            Log.w(TAG, "Ignored a pop-up that needs an Activity", e)
                        } else {
                            defaultHandler?.uncaughtException(thread, e)
                            return@setDefaultUncaughtExceptionHandler
                        }
                    }
                }
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun isBadToken(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            if (cur is WindowManager.BadTokenException) return true
            cur = cur.cause
        }
        return false
    }

    companion object {
        const val TAG = "OverlayBrowser"
    }
}
