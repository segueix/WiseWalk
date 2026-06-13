package com.wisewalk.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route renderer, Waze style: a two-layer line (dark casing + accent fill)
 * with white chevrons that flow forward along the remaining route. The part
 * already walked (behind the snapped position) is drawn as a flat gray line.
 */
class ArrowRouteOverlay : Overlay() {

    private var points: List<GeoPoint> = emptyList()

    /** Index of the segment that contains [progressPoint], -1 when unknown. */
    private var progressSegment: Int = -1
    private var progressPoint: GeoPoint? = null

    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val traveledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val lineWidthDp = 7f
    private val casingWidthDp = 11f
    private val chevronSpacingDp = 54f
    private val chevronSizeDp = 4.5f
    private val chevronStrokeDp = 2.2f

    private var flowPhase = 0f
    private var mapViewRef: WeakReference<MapView>? = null

    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            flowPhase = animation.animatedValue as Float
            mapViewRef?.get()?.postInvalidate()
        }
    }

    fun setPoints(newPoints: List<GeoPoint>) {
        points = newPoints.toList()
        progressSegment = -1
        progressPoint = null
    }

    fun getPoints(): List<GeoPoint> = points

    /**
     * Marks how far along the route the user is so the walked part can be
     * grayed out. Finds the nearest point on the polyline using an
     * equirectangular approximation (plenty accurate at walking scale).
     */
    fun setProgress(lat: Double, lng: Double) {
        if (points.size < 2) return
        val cosLat = cos(Math.toRadians(lat))
        var bestDist = Double.MAX_VALUE
        var bestSegment = -1
        var bestLat = lat
        var bestLng = lng

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = a.longitude * cosLat
            val ay = a.latitude
            val bx = b.longitude * cosLat
            val by = b.latitude
            val px = lng * cosLat
            val py = lat

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
            val nx = ax + dx * t
            val ny = ay + dy * t
            val distSq = (px - nx) * (px - nx) + (py - ny) * (py - ny)

            if (distSq < bestDist) {
                bestDist = distSq
                bestSegment = i
                bestLat = ny
                bestLng = nx / cosLat
            }
        }

        progressSegment = bestSegment
        progressPoint = GeoPoint(bestLat, bestLng)
    }

    fun startAnimation(mapView: MapView) {
        mapViewRef = WeakReference(mapView)
        if (!flowAnimator.isRunning) {
            flowAnimator.start()
        }
    }

    fun stopAnimation() {
        flowAnimator.cancel()
        mapViewRef = null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (points.size < 2) return

        val projection = mapView.projection
        val density = mapView.context.resources.displayMetrics.density

        linePaint.color = MapStyle.routeColor
        linePaint.strokeWidth = lineWidthDp * density
        casingPaint.color = MapStyle.routeColorDark
        casingPaint.strokeWidth = casingWidthDp * density
        traveledPaint.color = MapStyle.routeTraveled
        traveledPaint.strokeWidth = lineWidthDp * density
        chevronPaint.strokeWidth = chevronStrokeDp * density

        // Split the route at the snapped position into walked + remaining
        val splitSegment = progressSegment
        val splitPoint = progressPoint
        val traveledGeo: List<GeoPoint>
        val remainingGeo: List<GeoPoint>
        if (splitSegment >= 0 && splitPoint != null) {
            traveledGeo = points.subList(0, splitSegment + 1) + splitPoint
            remainingGeo = listOf(splitPoint) + points.subList(splitSegment + 1, points.size)
        } else {
            traveledGeo = emptyList()
            remainingGeo = points
        }

        if (traveledGeo.size >= 2) {
            canvas.drawPath(buildScreenPath(traveledGeo, projection), traveledPaint)
        }

        if (remainingGeo.size >= 2) {
            val screenPoints = remainingGeo.map { geoPoint ->
                val pt = projection.toPixels(geoPoint, null)
                floatArrayOf(pt.x.toFloat(), pt.y.toFloat())
            }
            val path = Path()
            path.moveTo(screenPoints[0][0], screenPoints[0][1])
            for (i in 1 until screenPoints.size) {
                path.lineTo(screenPoints[i][0], screenPoints[i][1])
            }
            canvas.drawPath(path, casingPaint)
            canvas.drawPath(path, linePaint)
            drawFlowingChevrons(canvas, screenPoints, density)
        }
    }

    private fun buildScreenPath(geoPoints: List<GeoPoint>, projection: org.osmdroid.views.Projection): Path {
        val path = Path()
        geoPoints.forEachIndexed { index, geoPoint ->
            val pt = projection.toPixels(geoPoint, null)
            if (index == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat())
            else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
        return path
    }

    private fun drawFlowingChevrons(canvas: Canvas, screenPoints: List<FloatArray>, density: Float) {
        val spacing = chevronSpacingDp * density
        val size = chevronSizeDp * density

        // The flow phase shifts every chevron forward each frame; the pattern
        // is periodic so the wrap-around at phase 1 -> 0 is seamless.
        var distToNext = spacing * (0.3f + flowPhase)

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

            while (distToNext <= segLen) {
                val t = distToNext / segLen
                drawChevron(canvas, x0 + dx * t, y0 + dy * t, angle, size)
                distToNext += spacing
            }
            distToNext -= segLen
        }
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, angle: Float, size: Float) {
        // Open ">" chevron pointing in the direction of travel
        val backAngle = Math.toRadians(135.0).toFloat()
        val tipX = x + size * cos(angle)
        val tipY = y + size * sin(angle)
        val path = Path()
        path.moveTo(tipX + 2f * size * cos(angle + backAngle), tipY + 2f * size * sin(angle + backAngle))
        path.lineTo(tipX, tipY)
        path.lineTo(tipX + 2f * size * cos(angle - backAngle), tipY + 2f * size * sin(angle - backAngle))
        canvas.drawPath(path, chevronPaint)
    }
}
