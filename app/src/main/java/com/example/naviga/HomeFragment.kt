package com.example.naviga

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFragment : Fragment(R.layout.fragment_home), CampusMapView.Listener {

    private lateinit var mapView: CampusMapView
    private lateinit var banner: View
    private lateinit var bannerArrow: ImageView
    private lateinit var bannerPrimary: TextView
    private lateinit var bannerSecondary: TextView
    private lateinit var destTitle: TextView
    private lateinit var destSubtitle: TextView
    private lateinit var goButton: MaterialButton
    private lateinit var recenterButton: View

    private val geo = GeoTransform(MapConfig.CONTROL_POINTS)
    private val engine = NavigationEngine(geo.metersPerUnit)
    private val simulationMode get() = !geo.isCalibrated

    private lateinit var movement: MovementDetector
    private lateinit var tracker: LocationTracker
    private val routeWalker = RouteWalker()

    private var destination: Destination = CampusGraph.destinations.first()
    private var userPoint: PointF? = null
    private var navigating = false
    private var walking = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSensors() }

    // ------------------------------------------------------------------ lifecycle

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.mapView)
        banner = view.findViewById(R.id.instructionBanner)
        bannerArrow = view.findViewById(R.id.bannerArrow)
        bannerPrimary = view.findViewById(R.id.bannerPrimary)
        bannerSecondary = view.findViewById(R.id.bannerSecondary)
        destTitle = view.findViewById(R.id.destinationTitle)
        destSubtitle = view.findViewById(R.id.destinationSubtitle)
        goButton = view.findViewById(R.id.goButton)
        recenterButton = view.findViewById(R.id.recenterButton)

        mapView.listener = this
        mapView.metersPerUnit = geo.metersPerUnit

        movement = MovementDetector(requireContext()) { setWalking(it) }
        tracker = LocationTracker(requireContext()) { onLocation(it) }

        goButton.setOnClickListener { if (navigating) stopNavigation() else startNavigation() }
        view.findViewById<View>(R.id.destinationInfo).setOnClickListener {
            showDestinationPicker(CampusGraph.destinations)
        }
        recenterButton.setOnClickListener {
            mapView.setFollowing(true)
            recenterButton.visibility = View.GONE
        }
        setupSearch(view)

        showDestination(destination)
        if (simulationMode) {
            toast("Demo mode: tap the map to place yourself, then press Go")
        }
    }

    override fun onStart() {
        super.onStart()
        if (!simulationMode) ensurePermissionsThenStart()
    }

    override fun onStop() {
        tracker.stop()
        movement.stop()
        routeWalker.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        routeWalker.stop()
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ permissions & sensors

    private fun ensurePermissionsThenStart() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 29) wanted += Manifest.permission.ACTIVITY_RECOGNITION
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startSensors() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startSensors() {
        if (!isAdded || simulationMode) return
        tracker.start()
        movement.start()
    }

    // ------------------------------------------------------------------ location pipeline

    private fun onLocation(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MapConfig.MAX_ACCURACY_M) return
        movement.onGpsSpeed(if (loc.hasSpeed()) loc.speed else 0f)

        val p = geo.toMap(loc.latitude, loc.longitude) ?: return
        val margin = 200f   // ignore fixes that are clearly far outside the campus map
        if (p.x < -margin || p.y < -margin ||
            p.x > MapConfig.REF_WIDTH + margin || p.y > MapConfig.REF_HEIGHT + margin
        ) return
        onPosition(p)
    }

    /** Single entry point for every position (real GPS or demo): snap, filter, move, navigate. */
    private fun onPosition(raw: PointF, teleport: Boolean = false) {
        val mpu = geo.metersPerUnit
        val snapped = CampusGraph.snap(raw)
        val pt = if (snapped.distance * mpu <= MapConfig.SNAP_RADIUS_M) snapped.point else raw

        // Standing still: ignore GPS wobble so the idle marker doesn't drift around.
        val cur = userPoint
        if (!teleport && cur != null && !walking &&
            hypot(cur.x - pt.x, cur.y - pt.y) * mpu < MapConfig.JITTER_M
        ) return

        userPoint = pt
        mapView.setUserPosition(pt.x, pt.y, teleport)

        if (navigating) {
            val update = engine.update(pt)
            mapView.setRouteProgress(engine.segIndex, engine.projected)
            mapView.setManeuverPoint(engine.nextManeuverPoint)
            if (update.offRoute) reroute(pt) else applyUpdate(update)
            if (update.arrived) finishNavigation()
        } else {
            updateDestinationInfo(fit = false)
        }
    }

    private fun setWalking(w: Boolean) {
        walking = w
        mapView.setWalking(w)
    }

    // ------------------------------------------------------------------ navigation

    private fun startNavigation() {
        val p = userPoint
        if (p == null) {
            toast(if (simulationMode) "Tap the map to place yourself first" else "Still finding your location…")
            return
        }
        val route = CampusGraph.routeFrom(p, destination.nodeId)
        if (route == null) {
            toast("No walking route found")
            return
        }
        val first = engine.start(route, destination.name)
        navigating = true
        goButton.text = "Stop"
        banner.visibility = View.VISIBLE
        applyUpdate(first)

        mapView.setRoute(route)
        mapView.setRouteProgress(0, PointF(route[0].x, route[0].y))
        mapView.setManeuverPoint(engine.nextManeuverPoint)
        mapView.setFollowing(true)
        recenterButton.visibility = View.GONE

        if (simulationMode) routeWalker.start(route)
    }

    private fun stopNavigation() {
        navigating = false
        routeWalker.stop()
        if (simulationMode) setWalking(false)
        goButton.text = "Go"
        banner.visibility = View.GONE
        mapView.setFollowing(false)
        recenterButton.visibility = View.GONE
        mapView.setRouteProgress(-1, null)
        mapView.setManeuverPoint(null)
        updateDestinationInfo(fit = true)
    }

    private fun finishNavigation() {
        navigating = false
        routeWalker.stop()
        if (simulationMode) setWalking(false)
        goButton.text = "Go"
        mapView.setFollowing(false)
        recenterButton.visibility = View.GONE
        mapView.setRoute(emptyList())
        mapView.setRouteProgress(-1, null)
        mapView.setManeuverPoint(null)
        destSubtitle.text = "You have arrived"
        view?.postDelayed({ if (!navigating) banner.visibility = View.GONE }, 5000)
    }

    private fun reroute(from: PointF) {
        val route = CampusGraph.routeFrom(from, destination.nodeId) ?: return
        val update = engine.start(route, destination.name)
        mapView.setRoute(route)
        mapView.setRouteProgress(0, PointF(route[0].x, route[0].y))
        mapView.setManeuverPoint(engine.nextManeuverPoint)
        applyUpdate(update)
        bannerSecondary.text = "Route updated"
    }

    private fun applyUpdate(u: NavUpdate) {
        bannerArrow.rotation = u.type.arrowRotation
        bannerPrimary.text = u.primary
        bannerSecondary.text = u.secondary
        destSubtitle.text = "${u.etaMinutes} min · ${u.remainingM.roundToInt()} m left"
    }

    // ------------------------------------------------------------------ destination UI

    private fun showDestination(d: Destination) {
        destination = d
        destTitle.text = d.name
        val node = CampusGraph.nodes.getValue(d.nodeId)
        mapView.setDestination(node.x, node.y)
        updateDestinationInfo(fit = true)
    }

    private fun chooseDestination(d: Destination) {
        if (navigating) stopNavigation()
        showDestination(d)
    }

    private fun updateDestinationInfo(fit: Boolean) {
        val p = userPoint
        if (p == null) {
            destSubtitle.text = if (simulationMode) "Tap the map to set your position" else "Finding your location…"
            return
        }
        val route = CampusGraph.routeFrom(p, destination.nodeId)
        if (route == null) {
            destSubtitle.text = "No route found"
            return
        }
        val meters = NavigationEngine.lengthMeters(route, geo.metersPerUnit)
        if (meters < MapConfig.ARRIVE_M) {
            destSubtitle.text = "You're here"
            mapView.setRoute(emptyList())
            return
        }
        val minutes = max(1, ceil(meters / (MapConfig.WALK_SPEED_MPS * 60f)).toInt())
        destSubtitle.text = "$minutes min walk · ${meters.roundToInt()} m"
        mapView.setRoute(route)
        if (fit) mapView.fitRoute()
    }

    private fun setupSearch(root: View) {
        val searchView = root.findViewById<SearchView>(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val q = query?.trim().orEmpty()
                searchView.clearFocus()
                val matches = CampusGraph.search(q)
                when {
                    matches.isEmpty() -> toast("No place found for \"$q\"")
                    matches.size == 1 -> chooseDestination(matches[0])
                    else -> showDestinationPicker(matches)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?) = false
        })
    }

    private fun showDestinationPicker(list: List<Destination>) {
        AlertDialog.Builder(requireContext())
            .setTitle("Where to?")
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> chooseDestination(list[i]) }
            .show()
    }

    // ------------------------------------------------------------------ CampusMapView.Listener

    override fun onMapTapped(mapX: Float, mapY: Float) {
        // Handy for editing CampusGraph: shows the pixel position of any tap in Logcat.
        Log.d("NavigaMap", "tap map=(${mapX.roundToInt()}, ${mapY.roundToInt()})")
        if (simulationMode && !navigating) {
            routeWalker.stop()
            setWalking(false)
            onPosition(PointF(mapX, mapY), teleport = true)
        }
    }

    override fun onUserMovedCamera() {
        if (navigating) recenterButton.visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ demo-mode walker

    /** Walks the marker along the route at a natural pace so the whole experience can be tried indoors. */
    private inner class RouteWalker {
        private val handler = Handler(Looper.getMainLooper())
        private val tickMs = 50L
        private var points: List<RoutePoint> = emptyList()
        private var next = 1
        private var x = 0f
        private var y = 0f
        private var running = false

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                var budget = MapConfig.SIM_SPEED_MPS / geo.metersPerUnit * tickMs / 1000f
                while (budget > 0f && next < points.size) {
                    val t = points[next]
                    val d = hypot(t.x - x, t.y - y)
                    if (d <= budget) {
                        x = t.x; y = t.y
                        budget -= d
                        next++
                    } else {
                        x += (t.x - x) / d * budget
                        y += (t.y - y) / d * budget
                        budget = 0f
                    }
                }
                setWalking(true)
                onPosition(PointF(x, y))
                if (!running) return                       // navigation finished during onPosition
                if (next >= points.size) {
                    stop()
                    setWalking(false)
                } else {
                    handler.postDelayed(this, tickMs)
                }
            }
        }

        fun start(route: List<RoutePoint>) {
            stop()
            points = route
            x = route[0].x
            y = route[0].y
            next = 1
            running = true
            handler.post(tick)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
        }
    }
}