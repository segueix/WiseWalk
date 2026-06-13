package com.wisewalk.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference

/**
 * Navigation puck: an accent-colored dot with a white ring, a breathing pulse
 * and a Pokémon GO-style direction cone that rotates with the GPS bearing.
 * When snapped=true, the indicator is drawn at the snapped position (on the route line).
 * When snapped=false (>20m deviation), it shows at the real GPS position plus
 * an accuracy halo.
 * This overlay should be added AFTER the route polyline so it renders on top.
 */
class SnappedLocationOverlay : Overlay() {

    private var position: GeoPoint? = null
    private var isSnapped: Boolean = false
    private var bearingDeg: Float = 0f
    private var hasBearing: Boolean = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotRadiusDp = 12f
    private val ringWidthDp = 3f
    private val coneLengthDp = 46f
    private val coneSweepDeg = 56f
    private val shadowOffsetDp = 1.5f

    private var pulseProgress = 0f
    private var mapViewRef: WeakReference<MapView>? = null

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            pulseProgress = animation.animatedValue as Float
            mapViewRef?.get()?.postInvalidate()
        }
    }

    fun updatePosition(lat: Double, lng: Double, snapped: Boolean) {
        position = GeoPoint(lat, lng)
        isSnapped = snapped
    }

    fun updateBearing(bearing: Float) {
        bearingDeg = bearing
        hasBearing = true
    }

    fun startAnimation(mapView: MapView) {
        mapViewRef = WeakReference(mapView)
        if (!pulseAnimator.isRunning) {
            pulseAnimator.start()
        }
    }

    fun stopAnimation() {
        pulseAnimator.cancel()
        mapViewRef = null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pos = position ?: return

        val projection = mapView.projection
        val screenPoint = projection.toPixels(pos, null) ?: return
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()
        val density = mapView.context.resources.displayMetrics.density
        val radius = dotRadiusDp * density
        val accent = MapStyle.routeColor

        // Accuracy halo when off-route
        if (!isSnapped) {
            accuracyPaint.color = MapStyle.withAlpha(accent, 26)
            canvas.drawCircle(x, y, radius * 3.5f, accuracyPaint)
        }

        // Direction cone (flashlight beam) following the GPS bearing.
        // Bearing is relative to true north, which matches the map frame even
        // when the map itself is rotated, so no orientation correction needed.
        if (hasBearing) {
            val coneLength = coneLengthDp * density
            conePaint.shader = RadialGradient(
                x, y, coneLength,
                MapStyle.withAlpha(accent, 110), MapStyle.withAlpha(accent, 0),
                Shader.TileMode.CLAMP
            )
            canvas.drawArc(
                x - coneLength, y - coneLength, x + coneLength, y + coneLength,
                bearingDeg - 90f - coneSweepDeg / 2f, coneSweepDeg, true, conePaint
            )
            conePaint.shader = null
        }

        // Breathing pulse expanding from the dot
        pulsePaint.color = MapStyle.withAlpha(accent, ((1f - pulseProgress) * 70).toInt())
        canvas.drawCircle(x, y, radius * (1f + 0.85f * pulseProgress), pulsePaint)

        // Soft drop shadow, white ring, accent dot
        canvas.drawCircle(x, y + shadowOffsetDp * density, radius + 1.5f * density, shadowPaint)
        canvas.drawCircle(x, y, radius, ringPaint)
        dotPaint.color = accent
        canvas.drawCircle(x, y, radius - ringWidthDp * density, dotPaint)
    }
}
