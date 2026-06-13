package com.wisewalk.app

import android.graphics.Color

/**
 * Shared map theme state, kept in sync with the web UI theme via the
 * WiseWalkBridge. Overlays read these values on every draw, so a theme or
 * accent change restyles the whole map without rebuilding overlays.
 */
object MapStyle {

    var isDark: Boolean = false
        private set

    var accent: Int = Color.parseColor("#3d7a6b")
        private set

    /**
     * Color used for the route line and the location puck. Re-rolled to a
     * random vivid hue every time a new route is drawn (see
     * [randomizeRouteColor]); defaults to [accent] before any route exists.
     */
    @Volatile
    var routeColor: Int = accent

    /** Darker accent used as the route casing/border. */
    val accentDark: Int
        get() = blend(accent, Color.BLACK, 0.32f)

    /** Darker variant of [routeColor] used as the route casing/border. */
    val routeColorDark: Int
        get() = blend(routeColor, Color.BLACK, 0.32f)

    /** Picks a fresh, saturated random color for the route line + location puck. */
    fun randomizeRouteColor() {
        val hsv = floatArrayOf(
            (Math.random() * 360.0).toFloat(),
            0.68f,
            if (isDark) 0.88f else 0.72f
        )
        routeColor = Color.HSVToColor(hsv)
    }

    /** Already-walked part of the route. */
    val routeTraveled: Int
        get() = if (isDark) Color.parseColor("#5a6262") else Color.parseColor("#a8b0b0")

    /** Surface color for the floating map buttons. */
    val controlBackground: Int
        get() = if (isDark) Color.parseColor("#262b2b") else Color.WHITE

    /** Icon/text color for the floating map buttons. */
    val controlForeground: Int
        get() = if (isDark) Color.parseColor("#e8e6e1") else Color.parseColor("#3c4043")

    /** Attribution text color over the map tiles. */
    val copyrightText: Int
        get() = if (isDark) Color.parseColor("#9ca8a8") else Color.parseColor("#5f6368")

    fun update(dark: Boolean, accentHex: String) {
        isDark = dark
        try {
            accent = Color.parseColor(accentHex)
        } catch (_: IllegalArgumentException) {
            // Keep the previous accent if the web UI sends something unexpected
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.rgb(
            (Color.red(from) * inv + Color.red(to) * ratio).toInt(),
            (Color.green(from) * inv + Color.green(to) * ratio).toInt(),
            (Color.blue(from) * inv + Color.blue(to) * ratio).toInt()
        )
    }
}
