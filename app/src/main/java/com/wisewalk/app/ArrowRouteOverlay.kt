package com.wisewalk.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Custom overlay that draws the route as a colored line with direction arrows.
 * Arrows are drawn at regular intervals along the polyline to indicate direction.
 */
class ArrowRouteOverlay : Overlay() {

    private var points: List<GeoPoint> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        strokeJoin = Paint.Join.ROUND
    }

    /** Spacing between arrow heads in dp */
    private val arrowSpacingDp = 50f
    /** Arrow head size in dp */
    private val arrowSizeDp = 8f

    fun setPoints(newPoints: List<GeoPoint>) {
        points = newPoints.toList()
    }

    fun getPoints(): List<GeoPoint> = points

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (points.size < 2) return

        val projection = mapView.projection
        val density = mapView.context.resources.displayMetrics.density

        // Convert GeoPoints to screen pixels
        val screenPoints = points.map { geoPoint ->
            val pt = projection.toPixels(geoPoint, null)
            floatArrayOf(pt.x.toFloat(), pt.y.toFloat())
        }

        // Draw the route line
        val linePath = Path()
        linePath.moveTo(screenPoints[0][0], screenPoints[0][1])
        for (i in 1 until screenPoints.size) {
            linePath.lineTo(screenPoints[i][0], screenPoints[i][1])
        }
        canvas.drawPath(linePath, linePaint)

        // Draw direction arrows at regular intervals
        val arrowSpacingPx = arrowSpacingDp * density
        val arrowSize = arrowSizeDp * density

        var accumulatedDist = arrowSpacingPx * 0.5f // Start offset

        for (i in 0 until screenPoints.size - 1) {
            val x0 = screenPoints[i][0]
            val y0 = screenPoints[i][1]
            val x1 = screenPoints[i + 1][0]
            val y1 = screenPoints[i + 1][1]

            val dx = x1 - x0
            val dy = y1 - y0
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 1f) continue

            val angle = atan2(dy, dx)

            var distInSeg = if (accumulatedDist <= 0f) arrowSpacingPx * 0.3f else 0f

            while (accumulatedDist <= segLen) {
                distInSeg = accumulatedDist
                val t = distInSeg / segLen
                val ax = x0 + dx * t
                val ay = y0 + dy * t

                drawArrowHead(canvas, ax, ay, angle, arrowSize, arrowPaint)
                accumulatedDist += arrowSpacingPx
            }
            accumulatedDist -= segLen
        }
    }

    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, angle: Float, size: Float, paint: Paint) {
        val path = Path()
        // Chevron arrow pointing in the direction of travel
        val halfAngle = Math.toRadians(150.0).toFloat()

        val tipX = x + size * kotlin.math.cos(angle)
        val tipY = y + size * kotlin.math.sin(angle)

        val leftX = x + size * kotlin.math.cos(angle + halfAngle)
        val leftY = y + size * kotlin.math.sin(angle + halfAngle)

        val rightX = x + size * kotlin.math.cos(angle - halfAngle)
        val rightY = y + size * kotlin.math.sin(angle - halfAngle)

        path.moveTo(tipX, tipY)
        path.lineTo(leftX, leftY)
        path.lineTo(x, y) // inner notch for chevron shape
        path.lineTo(rightX, rightY)
        path.close()

        canvas.drawPath(path, paint)
    }
}
