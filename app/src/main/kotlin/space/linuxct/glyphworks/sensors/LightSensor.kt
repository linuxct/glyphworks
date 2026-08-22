package space.linuxct.glyphworks.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphworks.core.LightPort

/** TYPE_LIGHT is on-change, so the poll that registers returns null. Poll again soon after. */
class LightSensor(app: Context) : LightPort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lux: Float? = null
    @Volatile private var lastPollAt = 0L
    private var started = false

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@LightSensor) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    if (started) {
                        sensorManager?.unregisterListener(this@LightSensor)
                        started = false
                        lux = null
                    }
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    override fun lux(): Float? {
        lastPollAt = System.currentTimeMillis()
        synchronized(this) {
            if (!started) {
                val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
                started = true
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
            }
        }
        return lux
    }

    override fun onSensorChanged(event: SensorEvent) {
        lux = event.values[0].coerceAtLeast(0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val IDLE_STOP_MS = 5000L
    }
}
