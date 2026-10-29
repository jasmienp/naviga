package com.example.naviga

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One view that owns the map image, the route, the destination pin and the user marker, so they
 * all share a single pan/zoom transform and can never drift out of sync.
 *
 * All positions passed in/out are in MAP UNITS (pixels of map.jpg, see MapConfig.REF_*).
 */
class CampusMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMapTapped(mapX: Float, mapY: Float)
        fun onUserMovedCamera()
    }

    companion object {
        private const val MARKER_HEIGHT_DP = 56f
        /** 0 = marker's top sits on the location, 1 = marker's bottom (feet) sits on it. */
        private const val MARKER_ANCHOR_Y = 0.8f
        private const val POS_SMOOTH = 4f        // higher = marker catches up faster
        private const val FOLLOW_SMOOTH = 3.5f   // camera easing while following
        private const val LOOKAHEAD_S = 0.6f     // dead-reckoning between 1 Hz GPS fixes
        private const val BLEND_SECONDS = 0.28f  // stationary <-> walking crossfade time
    }

    var listener: Listener? = null
    var metersPerUnit = MapConfig.FALLBACK_METERS_PER_UNIT

    private val dp = resources.displayMetrics.density
    private val refW = MapConfig.REF_WIDTH
    private val refH = MapConfig.REF_HEIGHT

    // ---- camera: screen = map * scale + off ----
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var minScale = 0.1f
    private var maxScale = 4f
    private var initialized = false
    private var camAnimator: ValueAnimator? = null
    var isFollowing = false
        private set
    private val followScale get() = (minScale * 3.2f).coerceAtMost(maxScale)

    // ---- map ----
    private val mapBitmap: Bitmap = loadMap()
    private val mapDst = RectF(0f, 0f, refW, refH)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ---- user marker ----
    private val stationaryDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.stationary)?.mutate()
    private val walkingDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.walking)?.mutate()
    private var hasUser = false
    private var userX = 0f
    private var userY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var velX = 0f
    private var velY = 0f
    private var lastFixAt = 0L
    private var walkingTarget = false
    private var walkBlend = 0f
    private var lastFrame = 0L

    // ---- route / destination ----
    private var route: List<RoutePoint> = emptyList()
    private var routeSeg = -1
    private var routeProj: PointF? = null
    private var destination: PointF? = null
    private var maneuver: PointF? = null

    // ---- paints ----
    private val blue = ContextCompat.getColor(context, R.color.main_blue)
    private val destColor = Color.parseColor("#E5484D")
    private val casingPaint = strokePaint(Color.WHITE, 10f)
    private val routePaint = strokePaint(blue, 6f)
    private val walkedPaint = strokePaint(Color.parseColor("#9AA5C4"), 6f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
    }

    private fun strokePaint(c: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = widthDp * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun loadMap(): Bitmap {
        // inScaled = false => ignore density folders, so it works from drawable/ or drawable-nodpi/.
        val bounds = BitmapFactory.Options().apply {
            inScaled = false
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(resources, R.drawable.map, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sample
        }
        return BitmapFactory.decodeResource(resources, R.drawable.map, opts)
    }

    // ================= public API =================

    fun setRoute(points: List<RoutePoint>) {
        route = points
        routeSeg = -1
        routeProj = null
        invalidate()
    }

    fun setRouteProgress(segIndex: Int, projected: PointF?) {
        routeSeg = segIndex
        routeProj = projected
        invalidate()
    }

    fun setDestination(x: Float, y: Float) {
        destination = PointF(x, y)
        invalidate()
    }

    fun setManeuverPoint(p: PointF?) {
        maneuver = p
        invalidate()
    }

    /** Sets where the user is. The marker glides there; [teleport] jumps instantly. */
    fun setUserPosition(x: Float, y: Float, teleport: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!hasUser || teleport) {
            hasUser = true
            userX = x
            userY = y
            velX = 0f
            velY = 0f
        } else {
            val dt = (now - lastFixAt) / 1000f
            if (dt in 0.03f..3f) {
                var vx = (x - targetX) / dt
                var vy = (y - targetY) / dt
                val maxSpeed = 3f / metersPerUnit          // never extrapolate faster than 3 m/s
                val speed = hypot(vx, vy)
                if (speed > maxSpeed) {
                    vx *= maxSpeed / speed
                    vy *= maxSpeed / speed
                }
                velX += (vx - velX) * 0.5f
                velY += (vy - velY) * 0.5f
            }
        }
        targetX = x
        targetY = y
        lastFixAt = now
        invalidate()
    }

    /** Switches the marker between stationary.png and walking.png (crossfades smoothly). */
    fun setWalking(walking: Boolean) {
        walkingTarget = walking
        invalidate()
    }

    /** Camera locks onto the user (eased, no jumps) until the person drags or zooms the map. */
    fun setFollowing(follow: Boolean) {
        isFollowing = follow
        if (follow) camAnimator?.cancel()
        invalidate()
    }

    /** Zooms the camera so the whole route is visible. */
    fun fitRoute(paddingDp: Float = 56f) {
        if (route.size < 2 || width == 0) return
        isFollowing = false
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in route) {
            l = min(l, p.x); r = max(r, p.x)
            t = min(t, p.y); b = max(b, p.y)
        }
        val pad = paddingDp * dp
        val s = min(
            (width - 2 * pad) / max(r - l, 1f),
            (height - 2 * pad) / max(b - t, 1f)
        ).coerceIn(minScale, maxScale)
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        animateCamera(s, width / 2f - cx * s, height / 2f - cy * s)
    }

    // ================= camera =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fit = min(w / refW, h / refH)
        minScale = fit
        maxScale = fit * 8f
        if (!initialized) {
            scale = fit
            offX = (w - refW * scale) / 2f
            offY = (h - refH * scale) / 2f
            initialized = true
        } else {
            scale = scale.coerceIn(minScale, maxScale)
        }
        clampCamera()
    }

    private fun clampCamera() {
        if (width == 0 || height == 0) return
        val mw = refW * scale
        val mh = refH * scale
        val slack = 0.35f
        offX = if (mw <= width) (width - mw) / 2f
        else offX.coerceIn(width - mw - width * slack, width * slack)
        offY = if (mh <= height) (height - mh) / 2f
        else offY.coerceIn(height - mh - height * slack, height * slack)
    }

    private fun animateCamera(endScale: Float, endX: Float, endY: Float) {
        camAnimator?.cancel()
        val s0 = scale
        val x0 = offX
        val y0 = offY
        camAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                scale = s0 + (endScale - s0) * f
                offX = x0 + (endX - x0) * f
                offY = y0 + (endY - y0) * f
                clampCamera()
                invalidate()
            }
            start()
        }
    }

    private fun zoomAround(factor: Float, fx: Float, fy: Float) {
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val f = newScale / scale
        offX = fx - (fx - offX) * f
        offY = fy - (fy - offY) * f
        scale = newScale
        clampCamera()
        invalidate()
    }

    private fun onUserGesture() {
        camAnimator?.cancel()
        if (isFollowing) {
            isFollowing = false
            listener?.onUserMovedCamera()
        }
    }

    // ================= gestures =================

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onUserGesture()
                zoomAround(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                onUserGesture()
                offX -= distanceX
                offY -= distanceY
                clampCamera()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onUserGesture()
                val zoomIn = scale < minScale * 2f
                val newScale = if (zoomIn) minScale * 3.5f else minScale
                if (zoomIn) {
                    val mx = (e.x - offX) / scale
                    val my = (e.y - offY) / scale
                    animateCamera(newScale, e.x - mx * newScale, e.y - my * newScale)
                } else {
                    animateCamera(newScale, (width - refW * newScale) / 2f, (height - refH * newScale) / 2f)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onMapTapped((e.x - offX) / scale, (e.y - offY) / scale)
                return true
            }
        })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ================= per-frame animation =================

    private fun updateFrame(dt: Float, now: Long) {
        // stationary <-> walking blend
        val goal = if (walkingTarget) 1f else 0f
        val step = dt / BLEND_SECONDS
        walkBlend = if (walkBlend < goal) min(goal, walkBlend + step) else max(goal, walkBlend - step)

        // glide toward the latest fix, leading slightly while walking
        if (hasUser) {
            if (now - lastFixAt > 1500) {
                val decay = exp(-4f * dt)
                velX *= decay
                velY *= decay
            }
            val lead = if (walkingTarget) LOOKAHEAD_S else 0f
            val k = 1f - exp(-POS_SMOOTH * dt)
            userX += (targetX + velX * lead - userX) * k
            userY += (targetY + velY * lead - userY) * k
        }

        // eased camera follow
        if (isFollowing && hasUser) {
            val k = 1f - exp(-FOLLOW_SMOOTH * dt)
            scale += (followScale - scale) * k
            offX += ((width * 0.5f - userX * scale) - offX) * k
            offY += ((height * 0.58f - userY * scale) - offY) * k
            clampCamera()
        }
    }

    private fun sx(x: Float) = x * scale + offX
    private fun sy(y: Float) = y * scale + offY
    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame).coerceIn(1L, 50L) / 1000f
        lastFrame = now
        updateFrame(dt, now)

        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(mapBitmap, null, mapDst, bitmapPaint)
        canvas.restore()

        drawRoute(canvas)
        drawDestination(canvas, now)
        drawUser(canvas, now)

        if (hasUser || destination != null) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawRoute(c: Canvas) {
        if (route.size < 2) return
        val ahead = Path()
        val walked = Path()
        val proj = routeProj
        if (routeSeg >= 0 && proj != null) {
            walked.moveTo(sx(route[0].x), sy(route[0].y))
            for (i in 1..routeSeg) walked.lineTo(sx(route[i].x), sy(route[i].y))
            walked.lineTo(sx(proj.x), sy(proj.y))
            ahead.moveTo(sx(proj.x), sy(proj.y))
            for (i in routeSeg + 1 until route.size) ahead.lineTo(sx(route[i].x), sy(route[i].y))
            c.drawPath(walked, walkedPaint)
        } else {
            ahead.moveTo(sx(route[0].x), sy(route[0].y))
            for (i in 1 until route.size) ahead.lineTo(sx(route[i].x), sy(route[i].y))
        }
        c.drawPath(ahead, casingPaint)
        c.drawPath(ahead, routePaint)

        maneuver?.let { m ->
            val x = sx(m.x)
            val y = sy(m.y)
            fillPaint.color = Color.WHITE
            c.drawCircle(x, y, 7 * dp, fillPaint)
            ringPaint.color = blue
            c.drawCircle(x, y, 7 * dp, ringPaint)
        }
    }

    private fun drawDestination(c: Canvas, now: Long) {
        val d = destination ?: return
        val x = sx(d.x)
        val y = sy(d.y)
        val phase = (now % 2000L) / 2000f
        ringPaint.color = destColor
        ringPaint.alpha = ((1f - phase) * 110).toInt()
        c.drawCircle(x, y, (10 + 18 * phase) * dp, ringPaint)
        fillPaint.color = Color.WHITE
        c.drawCircle(x, y, 10 * dp, fillPaint)
        fillPaint.color = destColor
        c.drawCircle(x, y, 7 * dp, fillPaint)
    }

    private fun drawUser(c: Canvas, now: Long) {
        if (!hasUser) return
        val x = sx(userX)
        val y = sy(userY)

        // soft halo + expanding pulse ring
        fillPaint.color = blue
        fillPaint.alpha = 38
        c.drawCircle(x, y, 18 * dp, fillPaint)
        val phase = (now % 1800L) / 1800f
        ringPaint.color = blue
        ringPaint.alpha = ((1f - phase) * 90).toInt()
        c.drawCircle(x, y, (14 + 26 * phase) * dp, ringPaint)

        // little hop while walking
        val blend = smooth(walkBlend)
        val bob = if (blend > 0f) -abs(sin(now / 95.0).toFloat()) * 2f * dp * blend else 0f

        drawMarker(c, stationaryDrawable, x, y + bob, 1f - blend)
        drawMarker(c, walkingDrawable, x, y + bob, blend)
    }

    private fun drawMarker(c: Canvas, d: Drawable?, x: Float, y: Float, alpha: Float) {
        if (d == null || alpha <= 0.01f) return
        val h = MARKER_HEIGHT_DP * dp
        val ratio = if (d.intrinsicHeight > 0) d.intrinsicWidth.toFloat() / d.intrinsicHeight else 1f
        val w = h * ratio
        val left = x - w / 2f
        val top = y - h * MARKER_ANCHOR_Y
        d.setBounds(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
        d.alpha = (alpha * 255).toInt()
        d.draw(c)
    }

    override fun onDetachedFromWindow() {
        camAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}