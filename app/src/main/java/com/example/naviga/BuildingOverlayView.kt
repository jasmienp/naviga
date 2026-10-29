package com.example.naviga

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

class BuildingOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mapWidth = 1f
    var mapHeight = 1f
    var buildings: List<CampusLocation> = emptyList()      // every building (for tap detection)

    var imageRect = RectF()                                // where the map image currently is on screen
        set(v) { field = v; invalidate() }

    var lit: List<CampusLocation> = emptyList()            // buildings drawn bright
        set(v) { field = v; animateDim(if (v.isEmpty()) 0f else 1f) }

    var selected: CampusLocation? = null                   // gets the white outline
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private var dim = 0f
    private var animator: ValueAnimator? = null

    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outlinePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    private val dst = RectF()
    private val tmp = RectF()
    private val dirs = arrayOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)

    private fun animateDim(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(dim, target).apply {
            duration = 180
            addUpdateListener { dim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (dim <= 0f || imageRect.isEmpty) return
        dimPaint.alpha = (160 * dim).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        lit.forEach { drawBuilding(canvas, it, it.id == selected?.id) }
    }

    private fun drawBuilding(canvas: Canvas, b: CampusLocation, outlined: Boolean) {
        val s = imageRect.width() / mapWidth                // map pixel -> screen pixel
        val l = imageRect.left + b.left * s
        val t = imageRect.top + b.top * s
        dst.set(l, t, l + b.sprite.width * s, t + b.sprite.height * s)
        if (outlined) {
            val o = 3 * d
            for ((dx, dy) in dirs) {
                tmp.set(dst); tmp.offset(dx * o, dy * o)
                canvas.drawBitmap(b.sprite, null, tmp, outlinePaint)
            }
        }
        canvas.drawBitmap(b.sprite, null, dst, spritePaint)
    }

    /** nx, ny = tap position as fractions (0..1) of the map image. */
    fun buildingAt(nx: Float, ny: Float): CampusLocation? {
        val mx = nx * mapWidth
        val my = ny * mapHeight

        // 1) the tap landed on a visible pixel of a building
        for (b in buildings.asReversed()) {
            val px = (mx - b.left).toInt()
            val py = (my - b.top).toInt()
            if (px in 0 until b.sprite.width && py in 0 until b.sprite.height &&
                Color.alpha(b.sprite.getPixel(px, py)) > 40) return b
        }
        // 2) near miss: nearest building within ~20dp (helps thin buildings like Kostka)
        val tol = 20 * d / (imageRect.width() / mapWidth)
        return buildings.minByOrNull { distToBox(it, mx, my) }
            ?.takeIf { distToBox(it, mx, my) <= tol }
    }

    private fun distToBox(b: CampusLocation, mx: Float, my: Float): Float {
        val dx = maxOf(b.left - mx, 0f, mx - (b.left + b.sprite.width))
        val dy = maxOf(b.top - my, 0f, my - (b.top + b.sprite.height))
        return hypot(dx, dy)
    }
}