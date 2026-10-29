package com.example.naviga

/** Central tuning values for the map, GPS and navigation. */
object MapConfig {

    /** Pixel size of map.jpg that every coordinate in CampusGraph refers to. */
    const val REF_WIDTH = 1526f
    const val REF_HEIGHT = 2024f

    /**
     * Real-world meters per map pixel. Only used until CONTROL_POINTS is filled in
     * (after that it is computed automatically). This is an ESTIMATE; measure a known
     * distance on the campus to refine it.
     */
    const val FALLBACK_METERS_PER_UNIT = 0.17f

    const val WALK_SPEED_MPS = 1.3f      // used for ETA
    const val SNAP_RADIUS_M = 15f        // GPS fixes this close to a path are snapped onto it
    const val JITTER_M = 6f              // GPS movement smaller than this is ignored while standing still
    const val MAX_ACCURACY_M = 25f       // GPS fixes worse than this are discarded
    const val OFF_ROUTE_M = 20f          // farther than this from the route => reroute
    const val ARRIVE_M = 10f             // closer than this to the destination => arrived
    const val SIM_SPEED_MPS = 1.6f       // demo-mode walking speed

    /**
     * GPS calibration. Add 3 or more points that you know BOTH the map pixel and the real
     * latitude/longitude of (stand at a gate and read GPS, or long-press the spot in Google Maps).
     * Spread them out (opposite corners of the campus) for the best accuracy.
     *
     * While this list has fewer than 3 entries the app runs in DEMO MODE (tap the map to move).
     *
     * Pixel positions of the gates on map.jpg:
     *   Gate 1 (1185, 1745)   Gate 3 (205, 1350)   Gate 4 (255, 232)   Gate 6 (1228, 325)
     */
    val CONTROL_POINTS: List<ControlPoint> = listOf(
        // ControlPoint(mapX = 1185f, mapY = 1745f, lat = 0.0, lon = 0.0), // Gate 1
        // ControlPoint(mapX = 205f,  mapY = 1350f, lat = 0.0, lon = 0.0), // Gate 3
        // ControlPoint(mapX = 255f,  mapY = 232f,  lat = 0.0, lon = 0.0), // Gate 4
        // ControlPoint(mapX = 1228f, mapY = 325f,  lat = 0.0, lon = 0.0), // Gate 6
    )
}