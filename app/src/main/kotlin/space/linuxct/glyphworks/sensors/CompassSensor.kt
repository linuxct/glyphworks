package space.linuxct.glyphworks.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphworks.core.AzimuthPort

class CompassSensor(private val app: Context) : AzimuthPort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveGeo = false

    @Volatile private var azimuth: Float? = null
    @Volatile private var declination = 0f
    @Volatile private var lastPollAt = 0L
    private var started = false

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@CompassSensor) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    if (started) {
                        sensorManager?.unregisterListener(this@CompassSensor)
                        started = false
                        azimuth = null
                        haveGravity = false
                        haveGeo = false
                    }
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    override fun azimuthDegrees(): Float? {
        lastPollAt = System.currentTimeMillis()
        synchronized(this) {
            if (!started) {
                val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val mag = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                if (accel == null || mag == null) return null
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                started = true
                loadDeclination()
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
            }
        }
        return azimuth?.let { (it + declination + FULL_TURN_DEGREES) % FULL_TURN_DEGREES }
    }

    private fun loadDeclination() {
        val hasCoarseLocation =
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarseLocation) return
        try {
            val locationManager = app.getSystemService(LocationManager::class.java) ?: return
            val location = locationManager.allProviders.firstNotNullOfOrNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            } ?: return
            declination = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            ).declination
        } catch (_: Exception) {
            // keep the last known declination
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                haveGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomagnetic)
                haveGeo = true
            }
        }
        if (haveGravity && haveGeo) {
            val rotation = FloatArray(ROTATION_MATRIX_SIZE)
            val inclination = FloatArray(ROTATION_MATRIX_SIZE)
            if (SensorManager.getRotationMatrix(rotation, inclination, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotation, orientation)
                val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                azimuth = (degrees + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
            }
        }
    }

    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] += LOW_PASS_ALPHA * (input[i] - output[i])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val LOW_PASS_ALPHA = 0.15f
        const val IDLE_STOP_MS = 5000L
        const val FULL_TURN_DEGREES = 360f
        const val ROTATION_MATRIX_SIZE = 9
    }
}
