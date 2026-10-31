package com.nnexe.overlaybrowser

import android.content.Context
import android.net.Uri

/** Everything the floating window remembers between launches. Sizes and positions are in pixels. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    var x: Int
        get() = sp.getInt("x", -1)
        set(v) = sp.edit().putInt("x", v).apply()
    var y: Int
        get() = sp.getInt("y", -1)
        set(v) = sp.edit().putInt("y", v).apply()
    var width: Int
        get() = sp.getInt("w", -1)
        set(v) = sp.edit().putInt("w", v).apply()
    var height: Int
        get() = sp.getInt("h", -1)
        set(v) = sp.edit().putInt("h", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit().putInt("bx", v).apply()
    var bubbleY: Int
        get() = sp.getInt("by", -1)
        set(v) = sp.edit().putInt("by", v).apply()

    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()
    var muted: Boolean
        get() = sp.getBoolean("muted", false)
        set(v) = sp.edit().putBoolean("muted", v).apply()
    var blockAds: Boolean
        get() = sp.getBoolean("block_ads", true)
        set(v) = sp.edit().putBoolean("block_ads", v).apply()
    var opacity: Float
        get() = sp.getFloat("opacity", 1f).coerceIn(MIN_OPACITY, 1f)
        set(v) = sp.edit().putFloat("opacity", v).apply()
    var desktopMode: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) = sp.edit().putBoolean("desktop", v).apply()
    var videoFillsScreen: Boolean
        get() = sp.getBoolean("video_full", false)
        set(v) = sp.edit().putBoolean("video_full", v).apply()

    var autoFitVideo: Boolean
        get() = sp.getBoolean("auto_fit_video", true)
        set(v) = sp.edit().putBoolean("auto_fit_video", v).apply()

    /** Where the window sits in video mode, and how much screen the video gets (content area, px²). */
    var videoX: Int
        get() = sp.getInt("vx", -1)
        set(v) = sp.edit().putInt("vx", v).apply()
    var videoY: Int
        get() = sp.getInt("vy", -1)
        set(v) = sp.edit().putInt("vy", v).apply()
    var videoArea: Int
        get() = sp.getInt("varea", -1)
        set(v) = sp.edit().putInt("varea", v).apply()

    var homeUrl: String
        get() = sp.getString("home", null) ?: DEFAULT_HOME
        set(v) = sp.edit().putString("home", v).apply()
    var lastUrl: String?
        get() = sp.getString("last", null)
        set(v) = sp.edit().putString("last", v).apply()

    companion object {
        const val DEFAULT_HOME = "https://search.brave.com/"

        /** Below this the window is too faint to find again. */
        const val MIN_OPACITY = 0.1f

        /** Turns whatever was typed into the address bar into a URL, using Brave Search for plain text. */
        fun toUrl(input: String): String? {
            val t = input.trim()
            if (t.isEmpty()) return null
            val lower = t.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:") -> t
                lower.startsWith("localhost") -> "http://$t"
                !t.contains(' ') && t.contains('.') && !t.endsWith('.') -> "https://$t"
                else -> "https://search.brave.com/search?q=" + Uri.encode(t)
            }
        }
    }
}
