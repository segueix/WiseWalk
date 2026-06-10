package com.wisewalk.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.animation.BounceInterpolator
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference

/**
 * Destination marker: an accent-colored map pin with a white core, soft
 * ground shadow, expanding ripple rings and a Pokémon GO-style drop-in
 * bounce when it first appears.
 * With [markerStyle] = "egg" it renders a mystery egg instead of the pin
 * (used until the user adopts the Tamagotchi pet).
 */
class PulsingMarkerOverlay(
    private var position: GeoPoint
) : Overlay() {

    /** "flag" (default pin) or "egg". */
    @Volatile
    var markerStyle: String = "flag"

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val groundShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val headRadiusDp = 11f
    private val stemLengthDp = 13f
    private val ringWidthDp = 2.5f
    private val coreRadiusDp = 3.5f
    private val maxRippleRadiusDp = 56f
    private val rippleCount = 2
    private val dropHeightDp = 42f

    private var animProgress = 0f
    private var dropProgress = 1f

    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            animProgress = animation.animatedValue as Float
            mapViewRef?.get()?.postInvalidate()
        }
    }

    private val dropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700L
        interpolator = BounceInterpolator()
        addUpdateListener { animation ->
            dropProgress = animation.animatedValue as Float
            mapViewRef?.get()?.postInvalidate()
        }
    }

    private var hasDropped = false
    private var mapViewRef: WeakReference<MapView>? = null

    fun setPosition(point: GeoPoint) {
        position = point
    }

    fun startAnimation(mapView: MapView) {
        mapViewRef = WeakReference(mapView)
        if (!rippleAnimator.isRunning) {
            rippleAnimator.start()
        }
        if (!hasDropped) {
            hasDropped = true
            dropProgress = 0f
            dropAnimator.start()
        }
    }

    fun stopAnimation() {
        rippleAnimator.cancel()
        dropAnimator.cancel()
        dropProgress = 1f
        mapViewRef = null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val projection = mapView.projection
        val screenPoint = projection.toPixels(position, null) ?: return
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()
        val density = mapView.context.resources.displayMetrics.density
        val accent = MapStyle.accent

        val headRadius = headRadiusDp * density
        val stemLength = stemLengthDp * density
        val maxRippleRadius = maxRippleRadiusDp * density

        // Ripple rings expanding from the ground point
        ripplePaint.color = accent
        for (i in 0 until rippleCount) {
            val offset = i.toFloat() / rippleCount
            val ripplePhase = (animProgress + offset) % 1f
            val radius = headRadius * 0.6f + (maxRippleRadius - headRadius * 0.6f) * ripplePhase
            ripplePaint.alpha = ((1f - ripplePhase) * 110).toInt().coerceIn(0, 255)
            ripplePaint.strokeWidth = (2.5f - ripplePhase * 1.5f).coerceAtLeast(1f) * density
            canvas.drawCircle(x, y, radius, ripplePaint)
        }

        // Ground shadow grows as the pin lands
        val shadowRadius = headRadius * 0.55f * dropProgress.coerceIn(0f, 1f)
        if (shadowRadius > 0f) {
            canvas.drawOval(
                RectF(x - shadowRadius, y - shadowRadius * 0.38f, x + shadowRadius, y + shadowRadius * 0.38f),
                groundShadowPaint
            )
        }

        // Drop-in offset (marker falls from above and bounces on landing)
        val dropOffset = (1f - dropProgress) * dropHeightDp * density

        if (markerStyle == "egg") {
            drawEgg(canvas, x, y, density, accent, dropOffset)
            return
        }

        val headCenterY = y - stemLength - headRadius - dropOffset

        // Stem connecting the head to the ground point
        pinPaint.color = accent
        val stem = Path().apply {
            moveTo(x, y - dropOffset)
            lineTo(x - headRadius * 0.55f, headCenterY + headRadius * 0.6f)
            lineTo(x + headRadius * 0.55f, headCenterY + headRadius * 0.6f)
            close()
        }
        canvas.drawPath(stem, pinPaint)

        // Pin head: accent circle with white ring and white core
        canvas.drawCircle(x, headCenterY, headRadius, pinPaint)
        ringPaint.strokeWidth = ringWidthDp * density
        canvas.drawCircle(x, headCenterY, headRadius, ringPaint)
        canvas.drawCircle(x, headCenterY, coreRadiusDp * density, corePaint)
    }

    /** Mystery egg: white oval with accent spots resting on the ground point. */
    private fun drawEgg(canvas: Canvas, x: Float, y: Float, density: Float, accent: Int, dropOffset: Float) {
        val eggWidth = 57f * density
        val eggHeight = 72f * density
        val bottom = y - dropOffset
        val top = bottom - eggHeight
        val rect = RectF(x - eggWidth / 2f, top, x + eggWidth / 2f, bottom)

        canvas.drawOval(rect, corePaint)
        ringPaint.color = accent
        ringPaint.strokeWidth = 4.5f * density
        canvas.drawOval(rect, ringPaint)
        ringPaint.color = Color.WHITE

        pinPaint.color = accent
        canvas.drawCircle(x - eggWidth * 0.18f, top + eggHeight * 0.38f, 7.2f * density, pinPaint)
        canvas.drawCircle(x + eggWidth * 0.16f, top + eggHeight * 0.56f, 5.7f * density, pinPaint)
        canvas.drawCircle(x - eggWidth * 0.05f, top + eggHeight * 0.76f, 4.5f * density, pinPaint)
    }
}
