package com.example.naviga

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/** Delivers GPS fixes about once per second. No Google Play Services dependency needed. */
class LocationTracker(
    private val context: Context,
    private val onFix: (Location) -> Unit
) : LocationListener {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (manager.isProviderEnabled(provider)) {
                manager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
            }
        }
        active = true
    }

    fun stop() {
        manager.removeUpdates(this)
        active = false
    }

    override fun onLocationChanged(location: Location) = onFix(location)
}