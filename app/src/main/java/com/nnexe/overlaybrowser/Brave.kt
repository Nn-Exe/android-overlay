package com.nnexe.overlaybrowser

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Android doesn't let one app draw another app inside its own window, so real Brave can't live inside
 * the overlay. Instead we hand pages to Brave. If the device has freeform windows turned on, Brave
 * opens as a floating window at the same spot as the overlay.
 */
object Brave {
    private val PACKAGES = listOf("com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly")

    /** Hidden ActivityTaskManager constant; it hasn't changed since Android 7. */
    private const val WINDOWING_MODE_FREEFORM = 5

    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** true / false when the "Enable freeform windows" developer option can be read, null when it can't. */
    fun freeformEnabled(context: Context): Boolean? {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true
        return try {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens [url] in Brave. With [floatingBounds] (screen pixels) it asks for a freeform window there;
     * if freeform is off, Android ignores the request and Brave opens full screen.
     * Returns false if Brave isn't installed.
     */
    fun open(context: Context, url: String, floatingBounds: Rect? = null): Boolean {
        val pkg = installedPackage(context)
        if (pkg == null) {
            Toast.makeText(context, R.string.no_brave, Toast.LENGTH_SHORT).show()
            openStore(context)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        if (floatingBounds != null) {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    // Blocked for normal reflection since Android 9; the framework still honors it.
                    HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
                } else {
                    ActivityOptions::class.java
                        .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(options, WINDOWING_MODE_FREEFORM)
                }
            } catch (e: Throwable) {
                Log.w(App.TAG, "Freeform launch isn't available on this device", e)
            }
            options.setLaunchBounds(floatingBounds)
        }
        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            // Some builds reject the freeform request outright; fall back to a normal launch.
            Log.w(App.TAG, "Brave launch with options failed", e)
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    fun openStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.brave.browser"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://brave.com/download/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                OverlayService.show(context, "https://brave.com/download/")
            }
        }
    }
}
