package space.linuxct.glyphworks.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphworks.core.ShakePort
import kotlin.math.sqrt

class ShakeDetector(app: Context) : ShakePort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastShakeAt = 0L
    private var lastTriggerAt = 0L
    private var started = false

    @Volatile
    var onShake: (() -> Unit)? = null

    @Synchronized
    fun start() {
        if (started) return
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI, mainHandler)
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(this)
        started = false
    }

    override fun millisSinceLastShake(): Long {
        val at = lastShakeAt
        if (at == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - at).coerceAtLeast(0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val accelX = event.values[0]
        val accelY = event.values[1]
        val accelZ = event.values[2]
        val gForce = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ) / SensorManager.GRAVITY_EARTH
        if (gForce > SHAKE_THRESHOLD_G) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerAt > DEBOUNCE_MS) {
                lastTriggerAt = now
                lastShakeAt = now
                onShake?.invoke()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SHAKE_THRESHOLD_G = 2.7f
        const val DEBOUNCE_MS = 500L
    }
}
