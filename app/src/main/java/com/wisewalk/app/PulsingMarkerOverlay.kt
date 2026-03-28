package com.wisewalk.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class PulsingMarkerOverlay(
    private var position: GeoPoint
) : Overlay() {

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val centerRadiusDp = 7f
    private val maxRippleRadiusDp = 30f
    private val rippleCount = 3
    private var animProgress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            animProgress = animation.animatedValue as Float
            mapViewRef?.get()?.postInvalidate()
        }
    }

    private var mapViewRef: java.lang.ref.WeakReference<MapView>? = null

    fun setPosition(point: GeoPoint) {
        position = point
    }

    fun startAnimation(mapView: MapView) {
        mapViewRef = java.lang.ref.WeakReference(mapView)
        if (!animator.isRunning) {
            animator.start()
        }
    }

    fun stopAnimation() {
        animator.cancel()
        mapViewRef = null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val projection = mapView.projection
        val screenPoint = projection.toPixels(position, null) ?: return
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()
        val density = mapView.context.resources.displayMetrics.density

        val centerRadius = centerRadiusDp * density
        val maxRippleRadius = maxRippleRadiusDp * density

        // Draw ripple rings
        for (i in 0 until rippleCount) {
            val offset = i.toFloat() / rippleCount
            val ripplePhase = (animProgress + offset) % 1f
            val radius = centerRadius + (maxRippleRadius - centerRadius) * ripplePhase
            val alpha = ((1f - ripplePhase) * 150).toInt().coerceIn(0, 255)
            ripplePaint.alpha = alpha
            ripplePaint.strokeWidth = (3f - ripplePhase * 2f).coerceAtLeast(1f) * density
            canvas.drawCircle(x, y, radius, ripplePaint)
        }

        // Draw center dot
        canvas.drawCircle(x, y, centerRadius, centerPaint)
    }
}
