package com.nnexe.overlaybrowser

/**
 * One-tap window looks. Applying a preset overwrites the corner, border, shadow and glass settings.
 * The window opacity is only touched by presets that need it (glass), and the theme, blur and
 * compact-bar choices are never changed.
 */
enum class StylePreset(
    private val rounded: Boolean,
    private val radiusDp: Int,
    private val borderDp: Int,
    private val shadow: Int,
    private val glass: Boolean,
    private val opacity: Float?,
) {
    MINIMAL(rounded = true, radiusDp = 4, borderDp = 1, shadow = 0, glass = false, opacity = null),
    ROUNDED(rounded = true, radiusDp = 16, borderDp = 1, shadow = 25, glass = false, opacity = null),
    ULTRA_ROUNDED(rounded = true, radiusDp = 32, borderDp = 1, shadow = 40, glass = false, opacity = null),
    LIQUID_GLASS(rounded = true, radiusDp = 28, borderDp = 1, shadow = 55, glass = true, opacity = 0.94f),
    ;

    fun applyTo(prefs: Prefs) {
        prefs.roundedCorners = rounded
        prefs.cornerRadiusDp = radiusDp
        prefs.borderDp = borderDp
        prefs.shadow = shadow
        prefs.glass = glass
        opacity?.let { prefs.opacity = it }
    }
}
