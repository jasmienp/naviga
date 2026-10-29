package com.example.naviga

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Decides whether the user is walking or standing still.
 *
 * Signals: the hardware step detector (needs the ACTIVITY_RECOGNITION permission), with the
 * accelerometer as a fallback, plus GPS speed. Hysteresis stops the state from flickering:
 * walking starts after two quick steps and ends ~1.7 s after the last one.
 */
class MovementDetector(
    private val context: Context,
    private val onChanged: (walking: Boolean) -> Unit
) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    var isWalking = false
        private set

    private var running = false
    private var lastStep = 0L
    private var prevStep = 0L
    private var lastWalkSignal = 0L
    private var gpsSpeed = 0f
    private var gpsSpeedAt = 0L
    private var gravity = 9.81f
    private var lastAccelStep = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            evaluate()
            handler.postDelayed(this, 200)
        }
    }

    fun start() {
        if (running) return
        val permitted = Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        val stepSensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val usingStepSensor = permitted && stepSensor != null &&
                sensors.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)

        if (!usingStepSensor) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        running = true
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        sensors.unregisterListener(this)
        isWalking = false
    }

    /** Feed GPS speed (m/s) so walking is still detected if the step sensor is unavailable. */
    fun onGpsSpeed(metersPerSecond: Float) {
        gpsSpeed = metersPerSecond
        gpsSpeedAt = SystemClock.elapsedRealtime()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> registerStep(now)
            Sensor.TYPE_ACCELEROMETER -> {
                val v = event.values
                val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                gravity = 0.9f * gravity + 0.1f * magnitude
                if (magnitude - gravity > 1.6f && now - lastAccelStep > 300) {
                    lastAccelStep = now
                    registerStep(now)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerStep(now: Long) {
        prevStep = lastStep
        lastStep = now
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val stepping = prevStep > 0 && now - lastStep < 1200 && lastStep - prevStep < 1400
        val gpsMoving = now - gpsSpeedAt < 3000 && gpsSpeed > 0.8f

        if (stepping || gpsMoving) {
            lastWalkSignal = now
            if (!isWalking) {
                isWalking = true
                onChanged(true)
            }
        } else if (isWalking && now - lastWalkSignal > 500) {
            isWalking = false
            onChanged(false)
        }
    }
}