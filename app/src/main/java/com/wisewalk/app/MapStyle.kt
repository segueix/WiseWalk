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

    /**
     * Color for the route's destination marker (round dot + concentric
     * ripples). Re-rolled per route and kept visibly distinct from
     * [routeColor] so the endpoint never blends into the line.
     */
    @Volatile
    var endpointColor: Int = accent

    /** Darker accent used as the route casing/border. */
    val accentDark: Int
        get() = blend(accent, Color.BLACK, 0.32f)

    /** Darker variant of [routeColor] used as the route casing/border. */
    val routeColorDark: Int
        get() = blend(routeColor, Color.BLACK, 0.32f)

    /** Darker variant of [endpointColor] for the destination dot's outline. */
    val endpointColorDark: Int
        get() = blend(endpointColor, Color.BLACK, 0.34f)

    /** Picks fresh, saturated random colors for the route line and the
     * destination marker, keeping the two hues clearly apart. */
    fun randomizeRouteColor() {
        val routeHue = (Math.random() * 360.0).toFloat()
        routeColor = Color.HSVToColor(
            floatArrayOf(routeHue, 0.68f, if (isDark) 0.88f else 0.72f)
        )
        // Offset the endpoint hue by 90-270° so it never matches the line.
        val endpointHue = ((routeHue + 90.0 + Math.random() * 180.0) % 360.0).toFloat()
        endpointColor = Color.HSVToColor(
            floatArrayOf(endpointHue, 0.72f, if (isDark) 0.92f else 0.70f)
        )
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
