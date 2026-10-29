package com.example.naviga

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class TurnType(val arrowRotation: Float, val label: String) {
    STRAIGHT(0f, "Continue straight"),
    SLIGHT_LEFT(-45f, "Bear left"),
    LEFT(-90f, "Turn left"),
    SHARP_LEFT(-135f, "Turn sharp left"),
    SLIGHT_RIGHT(45f, "Bear right"),
    RIGHT(90f, "Turn right"),
    SHARP_RIGHT(135f, "Turn sharp right"),
    UTURN(180f, "Make a U-turn"),
    ARRIVE(0f, "Arrive")
}

data class Maneuver(val type: TurnType, val pointIndex: Int, val atMeters: Float, val landmark: String?)

data class NavUpdate(
    val type: TurnType,
    val primary: String,            // e.g. "Turn left at Xavier Hall"
    val secondary: String,          // e.g. "In 30 m" / "Now"
    val distanceToManeuverM: Float,
    val remainingM: Float,
    val etaMinutes: Int,
    val arrived: Boolean,
    val offRoute: Boolean
)

/** Turns a list of route points into turn-by-turn guidance and tracks the user's progress. */
class NavigationEngine(private val metersPerUnit: Float) {

    companion object {
        fun lengthMeters(route: List<RoutePoint>, metersPerUnit: Float): Float {
            var total = 0f
            for (i in 1 until route.size) {
                total += hypot(route[i].x - route[i - 1].x, route[i].y - route[i - 1].y)
            }
            return total * metersPerUnit
        }
    }

    var route: List<RoutePoint> = emptyList()
        private set
    var segIndex = 0
        private set
    var projected = PointF()
        private set
    var nextManeuverPoint: PointF? = null
        private set

    private var destName = ""
    private var cum = FloatArray(0)            // cumulative meters at each route point
    private var maneuvers: List<Maneuver> = emptyList()
    private var progress = 0f
    private var offRouteCount = 0

    private val totalMeters get() = if (cum.isEmpty()) 0f else cum.last()

    fun start(route: List<RoutePoint>, destinationName: String): NavUpdate {
        this.route = route
        destName = destinationName
        segIndex = 0
        progress = 0f
        offRouteCount = 0
        cum = FloatArray(route.size)
        for (i in 1 until route.size) {
            cum[i] = cum[i - 1] +
                    hypot(route[i].x - route[i - 1].x, route[i].y - route[i - 1].y) * metersPerUnit
        }
        maneuvers = buildManeuvers()
        projected = PointF(route.first().x, route.first().y)
        return buildUpdate(false)
    }

    /** Feed the user's (snapped) map position; returns the instruction to display. */
    fun update(p: PointF): NavUpdate {
        if (route.size < 2) return buildUpdate(false)

        var bestLateral = Float.MAX_VALUE
        var bestSeg = segIndex
        var bestAlong = progress
        var bestPoint = projected

        // Only look at nearby segments so a route that doubles back can't "jump" ahead.
        val from = max(0, segIndex - 1)
        val to = min(route.size - 2, segIndex + 5)
        for (i in from..to) {
            val a = route[i]
            val b = route[i + 1]
            val abx = b.x - a.x
            val aby = b.y - a.y
            val len2 = abx * abx + aby * aby
            val t = if (len2 == 0f) 0f else
                (((p.x - a.x) * abx + (p.y - a.y) * aby) / len2).coerceIn(0f, 1f)
            val qx = a.x + abx * t
            val qy = a.y + aby * t
            val lateral = hypot(p.x - qx, p.y - qy) * metersPerUnit
            if (lateral < bestLateral) {
                bestLateral = lateral
                bestSeg = i
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
                bestPoint = PointF(qx, qy)
            }
        }
        segIndex = bestSeg
        progress = bestAlong
        projected = bestPoint

        offRouteCount = if (bestLateral > MapConfig.OFF_ROUTE_M) offRouteCount + 1 else 0
        return buildUpdate(offRouteCount >= 3)
    }

    private fun buildUpdate(offRoute: Boolean): NavUpdate {
        val remaining = max(0f, totalMeters - progress)
        val arrived = remaining < MapConfig.ARRIVE_M

        val next = if (arrived) maneuvers.last() else
            (maneuvers.firstOrNull { it.type != TurnType.ARRIVE && it.atMeters > progress + 1f }
                ?: maneuvers.last())
        nextManeuverPoint = route[next.pointIndex].let { PointF(it.x, it.y) }

        val distance = max(0f, next.atMeters - progress)
        val primary = when {
            arrived -> "You have arrived at $destName"
            next.type == TurnType.ARRIVE -> "Continue to $destName"
            else -> next.type.label + (next.landmark?.let { " at $it" } ?: "")
        }
        val secondary = when {
            arrived -> ""
            distance < 8f -> "Now"
            else -> "In ${formatDistance(distance)}"
        }
        val eta = if (arrived) 0 else max(1, ceil(remaining / MapConfig.WALK_SPEED_MPS / 60f).toInt())

        return NavUpdate(next.type, primary, secondary, distance, remaining, eta, arrived, offRoute)
    }

    private fun formatDistance(m: Float): String = when {
        m < 100f -> "${(m / 5f).roundToInt() * 5} m"
        m < 1000f -> "${(m / 10f).roundToInt() * 10} m"
        else -> String.format("%.1f km", m / 1000f)
    }

    private fun buildManeuvers(): List<Maneuver> {
        val list = mutableListOf<Maneuver>()
        for (i in 1 until route.size - 1) {
            val ax = route[i].x - route[i - 1].x
            val ay = route[i].y - route[i - 1].y
            val bx = route[i + 1].x - route[i].x
            val by = route[i + 1].y - route[i].y
            if (hypot(ax, ay) < 0.5f || hypot(bx, by) < 0.5f) continue

            // Screen coordinates (y points down): positive cross product = turning right.
            val angle = Math.toDegrees(
                atan2((ax * by - ay * bx).toDouble(), (ax * bx + ay * by).toDouble())
            ).toFloat()
            val type = classify(angle) ?: continue
            if (list.isNotEmpty() && cum[i] - list.last().atMeters < 6f) continue
            list += Maneuver(type, i, cum[i], route[i].name)
        }
        list += Maneuver(TurnType.ARRIVE, route.lastIndex, cum.last(), route.last().name)
        return list
    }

    private fun classify(angle: Float): TurnType? {
        val m = abs(angle)
        val right = angle > 0
        return when {
            m < 30f -> null                                    // basically straight on
            m < 60f -> if (right) TurnType.SLIGHT_RIGHT else TurnType.SLIGHT_LEFT
            m < 120f -> if (right) TurnType.RIGHT else TurnType.LEFT
            m < 165f -> if (right) TurnType.SHARP_RIGHT else TurnType.SHARP_LEFT
            else -> TurnType.UTURN
        }
    }
}