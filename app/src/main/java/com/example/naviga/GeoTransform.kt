package com.example.naviga

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** A spot whose pixel position on map.jpg and real GPS position are both known. */
data class ControlPoint(val mapX: Float, val mapY: Float, val lat: Double, val lon: Double)

/**
 * Converts GPS (lat/lon) into map.jpg pixel coordinates using a least-squares affine fit
 * over the control points. Accurate enough for a single campus (a few hundred meters).
 */
class GeoTransform(points: List<ControlPoint>) {

    var isCalibrated = false
        private set

    /** Real-world meters represented by one map pixel. */
    var metersPerUnit = MapConfig.FALLBACK_METERS_PER_UNIT
        private set

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var metersPerDegLon = 111_320.0
    private val ax = DoubleArray(3)   // mapX = ax0*east + ax1*north + ax2
    private val ay = DoubleArray(3)   // mapY = ay0*east + ay1*north + ay2

    init {
        if (points.size >= 3) {
            lat0 = points.map { it.lat }.average()
            lon0 = points.map { it.lon }.average()
            metersPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

            val rows = points.map { doubleArrayOf(east(it.lon), north(it.lat), 1.0) }
            val sx = solveLeastSquares(rows, points.map { it.mapX.toDouble() })
            val sy = solveLeastSquares(rows, points.map { it.mapY.toDouble() })

            if (sx != null && sy != null) {
                sx.copyInto(ax)
                sy.copyInto(ay)
                val det = abs(ax[0] * ay[1] - ax[1] * ay[0])
                if (det > 1e-9) {
                    metersPerUnit = (1.0 / sqrt(det)).toFloat()
                    isCalibrated = true
                }
            }
        }
    }

    private fun east(lon: Double) = (lon - lon0) * metersPerDegLon
    private fun north(lat: Double) = (lat - lat0) * 110_540.0

    /** Returns the map position for a GPS fix, or null if not calibrated. */
    fun toMap(lat: Double, lon: Double): PointF? {
        if (!isCalibrated) return null
        val e = east(lon)
        val n = north(lat)
        return PointF(
            (ax[0] * e + ax[1] * n + ax[2]).toFloat(),
            (ay[0] * e + ay[1] * n + ay[2]).toFloat()
        )
    }

    /** Solves the 3x3 normal equations (A^T A) x = A^T b with Gaussian elimination. */
    private fun solveLeastSquares(rows: List<DoubleArray>, rhs: List<Double>): DoubleArray? {
        val m = Array(3) { DoubleArray(4) }
        for (k in rows.indices) {
            for (i in 0..2) {
                for (j in 0..2) m[i][j] += rows[k][i] * rows[k][j]
                m[i][3] += rows[k][i] * rhs[k]
            }
        }
        for (c in 0..2) {
            var p = c
            for (r in c + 1..2) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-9) return null   // points are collinear / identical
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            for (r in 0..2) {
                if (r == c) continue
                val f = m[r][c] / m[c][c]
                for (j in c..3) m[r][j] -= f * m[c][j]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }
}