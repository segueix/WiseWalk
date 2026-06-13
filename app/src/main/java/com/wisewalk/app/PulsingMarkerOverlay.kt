package com.wisewalk.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * With [markerStyle] = "egg" it renders a golden mystery egg centered on the
 * destination point instead of the pin (used until the user adopts the
 * Tamagotchi pet).
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

    /** Thin defining edge around the solid destination dot. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val groundShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    /** Vivid shell and spot colors so the egg stands out against the map. */
    private val eggShellColor = Color.parseColor("#ffb300")
    private val eggSpotColor = Color.parseColor("#ff5252")

    // Big, solid destination dot so the end of the route is unmistakable.
    // Roughly double the route line width (the path line is ~7-11dp wide in
    // ArrowRouteOverlay), with concentric rings rippling outwards.
    private val dotRadiusDp = 18f
    private val outlineWidthDp = 2.5f
    private val maxRippleRadiusDp = 66f
    private val rippleCount = 3
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
        val color = MapStyle.endpointColor

        val dotRadius = dotRadiusDp * density
        val maxRippleRadius = maxRippleRadiusDp * density

        // Concentric ripple rings rippling outwards from the dot edge,
        // in the endpoint color
        ripplePaint.color = color
        for (i in 0 until rippleCount) {
            val offset = i.toFloat() / rippleCount
            val ripplePhase = (animProgress + offset) % 1f
            val radius = dotRadius + (maxRippleRadius - dotRadius) * ripplePhase
            ripplePaint.alpha = ((1f - ripplePhase) * 130).toInt().coerceIn(0, 255)
            ripplePaint.strokeWidth = (3f - ripplePhase * 1.8f).coerceAtLeast(1f) * density
            canvas.drawCircle(x, y, radius, ripplePaint)
        }

        // Ground shadow grows as the dot lands
        val shadowRadius = dotRadius * 0.55f * dropProgress.coerceIn(0f, 1f)
        if (shadowRadius > 0f) {
            canvas.drawOval(
                RectF(x - shadowRadius, y - shadowRadius * 0.38f, x + shadowRadius, y + shadowRadius * 0.38f),
                groundShadowPaint
            )
        }

        // Drop-in offset (marker falls from above and bounces on landing)
        val dropOffset = (1f - dropProgress) * dropHeightDp * density

        if (markerStyle == "egg") {
            drawEgg(canvas, x, y, density, dropOffset)
            return
        }

        // Big solid destination dot filled with the (random) endpoint color,
        // plus a thin darker outline so it stays crisp over any map color.
        val centerY = y - dropOffset
        pinPaint.color = color
        canvas.drawCircle(x, centerY, dotRadius, pinPaint)
        outlinePaint.color = MapStyle.endpointColorDark
        outlinePaint.strokeWidth = outlineWidthDp * density
        canvas.drawCircle(x, centerY, dotRadius - outlineWidthDp * density / 2f, outlinePaint)
    }

    /** Mystery egg: golden oval with coral spots floating a bit above the
     * arrival point (the point itself stays marked by the ripples/shadow). The
     * leg is still claimed by reaching the real route end, not the egg. */
    private fun drawEgg(canvas: Canvas, x: Float, y: Float, density: Float, dropOffset: Float) {
        val eggWidth = 46f * density
        val eggHeight = 58f * density
        // Lift the egg clear of the arrival point with a small gap below it.
        val lift = eggHeight / 2f + 12f * density
        val centerY = y - dropOffset - lift
        val top = centerY - eggHeight / 2f
        val bottom = centerY + eggHeight / 2f
        val rect = RectF(x - eggWidth / 2f, top, x + eggWidth / 2f, bottom)

        pinPaint.color = eggShellColor
        canvas.drawOval(rect, pinPaint)
        ringPaint.strokeWidth = 4f * density
        canvas.drawOval(rect, ringPaint)

        pinPaint.color = eggSpotColor
        canvas.drawCircle(x - eggWidth * 0.18f, top + eggHeight * 0.38f, 5.8f * density, pinPaint)
        canvas.drawCircle(x + eggWidth * 0.16f, top + eggHeight * 0.56f, 4.6f * density, pinPaint)
        canvas.drawCircle(x - eggWidth * 0.05f, top + eggHeight * 0.76f, 3.6f * density, pinPaint)
    }
}
