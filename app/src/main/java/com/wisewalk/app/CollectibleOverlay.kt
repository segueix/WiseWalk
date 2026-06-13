package com.wisewalk.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floating collectible items (food/toys for the Tamagotchi pet) placed beside
 * the route. Each item is a white badge with a vector icon colored by item
 * type that bobs gently; claimed items shrink away. Tapping a badge fires
 * [onItemTapped] so the web layer can decide whether the user is close enough
 * to claim it. All access happens on the UI thread.
 */
class CollectibleOverlay : Overlay() {

    class Item(val id: String, val position: GeoPoint, val type: String) {
        var scale: Float = 1f
    }

    /** Notified with the item id when the user taps a collectible badge. */
    var onItemTapped: ((String) -> Unit)? = null

    private val items = mutableListOf<Item>()

    private val typeColors = mapOf(
        "apple" to Color.parseColor("#e05a4e"),  // vermell poma
        "cookie" to Color.parseColor("#b5803c"), // marró galeta
        "ball" to Color.parseColor("#4a90d9"),   // blau pilota
        "star" to Color.parseColor("#e8b73a")    // daurat estrella
    )

    private fun colorFor(type: String): Int = typeColors[type] ?: MapStyle.accent

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val badgeRadiusDp = 22f
    private val bobAmplitudeDp = 6f

    private var bobPhase = 0f
    private var mapViewRef: WeakReference<MapView>? = null

    private val bobAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            bobPhase = animation.animatedValue as Float
            mapViewRef?.get()?.postInvalidate()
        }
    }

    fun setItems(newItems: List<Item>, mapView: MapView) {
        items.clear()
        items.addAll(newItems)
        if (items.isEmpty()) stopAnimation() else startAnimation(mapView)
    }

    fun hasItems(): Boolean = items.isNotEmpty()

    /** Shrinks the item away and removes it once the animation ends. */
    fun removeItem(id: String, mapView: MapView) {
        val item = items.find { it.id == id } ?: return
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 250L
            addUpdateListener { animation ->
                item.scale = animation.animatedValue as Float
                mapView.postInvalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    items.remove(item)
                    if (items.isEmpty()) stopAnimation()
                    mapView.postInvalidate()
                }
            })
            start()
        }
    }

    fun startAnimation(mapView: MapView) {
        mapViewRef = WeakReference(mapView)
        if (!bobAnimator.isRunning) {
            bobAnimator.start()
        }
    }

    fun stopAnimation() {
        bobAnimator.cancel()
        mapViewRef = null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (items.isEmpty()) return

        val projection = mapView.projection
        val density = mapView.context.resources.displayMetrics.density
        ringPaint.strokeWidth = 2f * density
        iconStrokePaint.strokeWidth = 1.6f * density

        items.forEachIndexed { index, item ->
            if (item.scale <= 0f) return@forEachIndexed
            val itemColor = colorFor(item.type)
            ringPaint.color = itemColor
            iconFillPaint.color = itemColor
            iconStrokePaint.color = itemColor
            val pt = projection.toPixels(item.position, null)
            val x = pt.x.toFloat()
            val bob = sin((bobPhase * 2f * Math.PI).toFloat() + index * 1.7f) * bobAmplitudeDp * density
            val radius = badgeRadiusDp * density * item.scale

            // Small ground shadow that shrinks while the badge floats up
            val shadowScale = 1f - (bob / (bobAmplitudeDp * density)) * 0.2f
            canvas.drawOval(
                pt.x - radius * 0.5f * shadowScale, pt.y.toFloat() + radius * 0.9f,
                pt.x + radius * 0.5f * shadowScale, pt.y.toFloat() + radius * 1.2f,
                shadowPaint
            )

            val y = pt.y.toFloat() - radius * 0.4f + bob
            canvas.drawCircle(x, y, radius, badgePaint)
            canvas.drawCircle(x, y, radius, ringPaint)
            drawIcon(canvas, item.type, x, y, radius * 0.55f, density)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (items.isEmpty()) return false
        val projection = mapView.projection
        val density = mapView.context.resources.displayMetrics.density
        // Generous touch target around the badge center (the badge bobs a few
        // dp, so we ignore the bob offset here)
        val touchRadius = badgeRadiusDp * density * 2.2f
        items.forEach { item ->
            if (item.scale <= 0f) return@forEach
            val pt = projection.toPixels(item.position, null)
            val cx = pt.x.toFloat()
            val cy = pt.y.toFloat() - badgeRadiusDp * density * 0.4f
            val dx = e.x - cx
            val dy = e.y - cy
            if (dx * dx + dy * dy <= touchRadius * touchRadius) {
                onItemTapped?.invoke(item.id)
                return true
            }
        }
        return false
    }

    private fun drawIcon(canvas: Canvas, type: String, cx: Float, cy: Float, size: Float, density: Float) {
        when (type) {
            "cookie" -> {
                canvas.drawCircle(cx, cy, size, iconFillPaint)
                val dotR = size * 0.18f
                canvas.drawCircle(cx - size * 0.35f, cy - size * 0.25f, dotR, dotPaint)
                canvas.drawCircle(cx + size * 0.3f, cy - size * 0.05f, dotR, dotPaint)
                canvas.drawCircle(cx - size * 0.05f, cy + size * 0.4f, dotR, dotPaint)
            }
            "ball" -> {
                canvas.drawCircle(cx, cy, size * 0.9f, iconStrokePaint)
                canvas.drawLine(cx - size * 0.9f, cy, cx + size * 0.9f, cy, iconStrokePaint)
            }
            "star" -> {
                canvas.drawPath(buildStarPath(cx, cy, size * 1.15f), iconFillPaint)
            }
            else -> { // apple
                canvas.drawCircle(cx, cy + size * 0.2f, size * 0.85f, iconFillPaint)
                canvas.drawLine(cx, cy - size * 0.55f, cx + size * 0.4f, cy - size * 1.05f, iconStrokePaint)
            }
        }
    }

    private fun buildStarPath(cx: Float, cy: Float, outerRadius: Float): Path {
        val path = Path()
        val innerRadius = outerRadius * 0.42f
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = (-Math.PI / 2 + i * Math.PI / 5).toFloat()
            val px = cx + radius * cos(angle)
            val py = cy + radius * sin(angle)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }
}
