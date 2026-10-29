package com.example.naviga

import android.graphics.PointF
import java.util.PriorityQueue
import kotlin.math.hypot

data class MapNode(val id: String, val x: Float, val y: Float, val name: String? = null)
data class Destination(
    val name: String,
    val nodeId: String,
    val category: String,
    val aliases: String = ""
)
data class RoutePoint(val x: Float, val y: Float, val name: String? = null)
data class SnapResult(val point: PointF, val distance: Float, val edgeA: String, val edgeB: String)

/**
 * The walkable network of the Salvador Campus.
 *
 * All coordinates are PIXELS ON map.jpg (see MapConfig.REF_WIDTH / REF_HEIGHT).
 * They were estimated from the picture, so check them on the real map: in demo mode, every
 * tap on the map prints "tap map=(x, y)" to Logcat (filter: NavigaMap). Adjust any node that
 * is not sitting on a real walkway, or add nodes/edges for paths that are missing.
 */
object CampusGraph {

    private val nodeList = listOf(
        // Gates
        MapNode("g1", 1185f, 1745f, "Gate 1"),
        MapNode("g2", 655f, 1735f, "Gate 2"),
        MapNode("g3", 205f, 1350f, "Gate 3"),
        MapNode("g4", 255f, 232f, "Gate 4"),
        MapNode("g4x", 560f, 262f, "Gate 4 Extension"),
        MapNode("g5", 1115f, 300f, "Gate 5"),
        MapNode("g6", 1228f, 325f, "Gate 6"),

        // North-west
        MapNode("mp", 190f, 330f),
        MapNode("tl1", 300f, 445f),
        MapNode("jr", 440f, 447f, "Jesuit Residence"),
        MapNode("jmrh", 548f, 350f, "JMRH"),

        // Backfield
        MapNode("bw", 575f, 450f),
        MapNode("bnw", 580f, 300f),
        MapNode("bne", 1100f, 335f),
        MapNode("bc", 850f, 580f, "Backfield"),
        MapNode("bswt", 600f, 830f),
        MapNode("bsw", 590f, 900f),
        MapNode("bs", 840f, 830f, "Xavier Hall"),
        MapNode("bst", 990f, 835f),
        MapNode("bse", 1100f, 800f),
        MapNode("m1j", 1190f, 862f),
        MapNode("mpcc1", 1320f, 868f, "MPCC-1"),
        MapNode("x1", 950f, 910f),

        // East road + Sauras / SACSI / Kostka corridor
        MapNode("rtop", 985f, 1005f),
        MapNode("rmpcc2", 990f, 1150f, "MPCC-2"),
        MapNode("rjoin", 985f, 1348f),
        MapNode("re", 1210f, 1348f),
        MapNode("rs1", 1210f, 1520f, "FWS Building"),
        MapNode("rs2", 1210f, 1700f),
        MapNode("k1", 1130f, 1003f, "Sauras Hall"),
        MapNode("k2", 1295f, 1003f, "SACSI"),
        MapNode("k3", 1345f, 1003f, "Kostka Bldg."),

        // Central corridor (between LRC and Canisius)
        MapNode("ctop", 625f, 985f),
        MapNode("cmid", 625f, 1165f),
        MapNode("cbot", 625f, 1348f),

        // South walkway (y ~ 1348)
        MapNode("wlrc", 435f, 1348f, "Learning Resource Center"),
        MapNode("wcg", 800f, 1348f, "Canisius & Gonzaga Bldg."),
        MapNode("wbc", 912f, 1348f, "Bellarmine & Campion Bldg."),

        // Gate 2 walkway + bottom strip
        MapNode("g2j", 665f, 1348f),
        MapNode("g2a", 662f, 1520f),
        MapNode("g2b", 662f, 1650f),
        MapNode("q1", 800f, 1728f),
        MapNode("quad", 915f, 1725f, "BC Quadrangle"),
        MapNode("q2", 1125f, 1735f),

        // Building entrances off the south walkway
        MapNode("ch", 435f, 1470f, "University Church"),
        MapNode("bcf", 912f, 1420f, "Bellarmine & Campion Bldg.")
    )

    private val edgeList = listOf(
        "g4" to "mp", "mp" to "tl1", "tl1" to "jr", "jr" to "bw",
        "bw" to "jmrh", "jmrh" to "bnw", "bnw" to "g4x",
        "bnw" to "bne", "bw" to "bc",
        "bw" to "bswt", "bswt" to "bsw", "bsw" to "ctop", "bswt" to "bs",
        "bs" to "bc", "bs" to "bst", "bc" to "bne", "bc" to "bse", "bne" to "bse",
        "bne" to "g5", "g5" to "g6", "bse" to "bst", "bse" to "m1j", "m1j" to "mpcc1",
        "bst" to "x1", "x1" to "rtop", "rtop" to "rmpcc2", "rmpcc2" to "rjoin",
        "rtop" to "k1", "k1" to "k2", "k2" to "k3",
        "ctop" to "cmid", "cmid" to "cbot",
        "g3" to "wlrc", "wlrc" to "cbot", "cbot" to "g2j", "g2j" to "wcg",
        "wcg" to "wbc", "wbc" to "rjoin",
        "rjoin" to "re", "re" to "rs1", "rs1" to "rs2", "rs2" to "g1",
        "wlrc" to "ch", "wbc" to "bcf",
        "g2j" to "g2a", "g2a" to "g2b", "g2b" to "g2",
        "g2" to "q1", "q1" to "quad", "quad" to "q2", "q2" to "g1"
    )

    val nodes: Map<String, MapNode> = nodeList.associateBy { it.id }

    val destinations = listOf(
        Destination("Main Library (Learning Resource Center)", "wlrc", "Study", "library lrc learning resource center"),
        Destination("Canisius & Gonzaga Bldg.", "wcg", "Academic", "canisius gonzaga"),
        Destination("Bellarmine & Campion Bldg.", "bcf", "Academic", "bellarmine campion bc"),
        Destination("BC Quadrangle", "quad", "Academic", "quad quadrangle"),
        Destination("University Church of Sacred Heart", "ch", "Other", "church chapel sacred heart"),
        Destination("Xavier Hall", "bs", "Academic", "xavier"),
        Destination("Sauras Hall", "k1", "Academic", "sauras"),
        Destination("SACSI", "k2", "Academic", "sacsi"),
        Destination("Kostka Bldg.", "k3", "Academic", "kostka"),
        Destination("MPCC-1", "mpcc1", "Academic", "mpcc gym court"),
        Destination("MPCC-2", "rmpcc2", "Academic", "mpcc gym court"),
        Destination("FWS Building", "rs1", "Academic", "fws"),
        Destination("JMRH", "jmrh", "Academic", "jmrh"),
        Destination("Jesuit Residence", "jr", "Other", "jesuit residence"),
        Destination("Backfield", "bc", "Other", "field backfield"),
        Destination("Gate 1", "g1", "Gate", "gate entrance exit"),
        Destination("Gate 2", "g2", "Gate", "gate entrance exit"),
        Destination("Gate 3", "g3", "Gate", "gate entrance exit"),
        Destination("Gate 4", "g4", "Gate", "gate entrance exit"),
        Destination("Gate 5", "g5", "Gate", "gate entrance exit"),
        Destination("Gate 6", "g6", "Gate", "gate entrance exit")
    )

    init {
        // Fail fast if a typo slipped into the data above.
        edgeList.forEach { (a, b) ->
            require(nodes.containsKey(a) && nodes.containsKey(b)) { "Unknown node in edge $a -> $b" }
        }
        destinations.forEach {
            require(nodes.containsKey(it.nodeId)) { "Unknown node for destination ${it.name}" }
        }
    }

    private val adjacency: Map<String, List<Pair<String, Float>>> by lazy {
        val map = HashMap<String, MutableList<Pair<String, Float>>>()
        for ((a, b) in edgeList) {
            val na = nodes.getValue(a)
            val nb = nodes.getValue(b)
            val w = hypot(na.x - nb.x, na.y - nb.y)
            map.getOrPut(a) { mutableListOf() }.add(b to w)
            map.getOrPut(b) { mutableListOf() }.add(a to w)
        }
        map
    }

    fun search(query: String): List<Destination> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return destinations
        return destinations.filter { d ->
            d.name.lowercase().contains(q) || d.aliases.lowercase().contains(q) || d.category.lowercase() == q
        }
    }

    /** Projects [p] onto the closest walkway segment. */
    fun snap(p: PointF): SnapResult {
        var best: SnapResult? = null
        for ((a, b) in edgeList) {
            val na = nodes.getValue(a)
            val nb = nodes.getValue(b)
            val abx = nb.x - na.x
            val aby = nb.y - na.y
            val len2 = abx * abx + aby * aby
            val t = if (len2 == 0f) 0f else
                (((p.x - na.x) * abx + (p.y - na.y) * aby) / len2).coerceIn(0f, 1f)
            val qx = na.x + abx * t
            val qy = na.y + aby * t
            val d = hypot(p.x - qx, p.y - qy)
            if (best == null || d < best.distance) best = SnapResult(PointF(qx, qy), d, a, b)
        }
        return best!!
    }

    /** Shortest walking route from any map position to a destination node (always >= 2 points). */
    fun routeFrom(start: PointF, destNodeId: String): List<RoutePoint>? {
        if (!nodes.containsKey(destNodeId)) return null
        val (dist, prev) = dijkstra(destNodeId)
        val s = snap(start)
        val na = nodes.getValue(s.edgeA)
        val nb = nodes.getValue(s.edgeB)
        val viaA = hypot(s.point.x - na.x, s.point.y - na.y) + (dist[na.id] ?: Float.MAX_VALUE)
        val viaB = hypot(s.point.x - nb.x, s.point.y - nb.y) + (dist[nb.id] ?: Float.MAX_VALUE)

        val out = mutableListOf(RoutePoint(s.point.x, s.point.y))
        var cur: String? = if (viaA <= viaB) na.id else nb.id
        while (cur != null) {
            val n = nodes.getValue(cur)
            val last = out.last()
            if (hypot(n.x - last.x, n.y - last.y) > 0.5f) out += RoutePoint(n.x, n.y, n.name)
            else if (n.name != null) out[out.lastIndex] = RoutePoint(last.x, last.y, n.name)
            cur = prev[cur]
        }
        if (out.size < 2) out += out.first()
        return out
    }

    /** Dijkstra rooted at [source]; prev[v] is the next hop from v toward the source. */
    private fun dijkstra(source: String): Pair<Map<String, Float>, Map<String, String?>> {
        val dist = HashMap<String, Float>()
        val prev = HashMap<String, String?>()
        val queue = PriorityQueue<Pair<String, Float>>(compareBy<Pair<String, Float>> { it.second })
        dist[source] = 0f
        prev[source] = null
        queue.add(source to 0f)
        while (queue.isNotEmpty()) {
            val (u, d) = queue.poll()!!
            if (d > (dist[u] ?: Float.MAX_VALUE)) continue
            for ((v, w) in adjacency[u].orEmpty()) {
                val nd = d + w
                if (nd < (dist[v] ?: Float.MAX_VALUE)) {
                    dist[v] = nd
                    prev[v] = u
                    queue.add(v to nd)
                }
            }
        }
        return dist to prev
    }
}