package com.wisewalk.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Custom overlay that draws the user's position indicator.
 * When snapped=true, the indicator is drawn at the snapped position (on the route line).
 * When snapped=false (>20m deviation), it shows at the real GPS position.
 * This overlay should be added AFTER the route polyline so it renders on top.
 */
class SnappedLocationOverlay : Overlay() {

    private var position: GeoPoint? = null
    private var isSnapped: Boolean = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 25, 118, 210)
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val radiusDp = 9f
    private val shadowOffsetDp = 1.5f

    fun updatePosition(lat: Double, lng: Double, snapped: Boolean) {
        position = GeoPoint(lat, lng)
        isSnapped = snapped
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pos = position ?: return

        val projection = mapView.projection
        val screenPoint = projection.toPixels(pos, null) ?: return
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()
        val density = mapView.context.resources.displayMetrics.density
        val radius = radiusDp * density
        val shadowOffset = shadowOffsetDp * density

        // Draw accuracy circle when not snapped (off-route)
        if (!isSnapped) {
            canvas.drawCircle(x, y, radius * 3.5f, accuracyPaint)
        }

        // Shadow
        shadowPaint.strokeWidth = strokePaint.strokeWidth * density
        canvas.drawCircle(x, y + shadowOffset, radius, shadowPaint)

        // White border
        strokePaint.strokeWidth = 3f * density
        canvas.drawCircle(x, y, radius, strokePaint)

        // Translucid fill
        canvas.drawCircle(x, y, radius, fillPaint)

        // White direction arrow
        val arrowPath = Path().apply {
            moveTo(x, y - radius * 0.62f)
            lineTo(x - radius * 0.38f, y + radius * 0.42f)
            lineTo(x, y + radius * 0.18f)
            lineTo(x + radius * 0.38f, y + radius * 0.42f)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
    }
}
