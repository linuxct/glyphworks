package space.linuxct.glyphworks.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphworks.core.InclineMath
import space.linuxct.glyphworks.core.InclinePort

// Never TYPE_LINEAR_ACCELERATION here: gravity is subtracted out of it, so a resting
// phone reads about 0 at any angle.
class InclineSensor(app: Context) : InclinePort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val gravity = FloatArray(3)
    private var haveGravity = false

    @Volatile private var pitch: Float? = null
    @Volatile private var roll: Float? = null
    @Volatile private var lastPollAt = 0L
    private var started = false

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@InclineSensor) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    if (started) {
                        sensorManager?.unregisterListener(this@InclineSensor)
                        started = false
                        haveGravity = false
                        pitch = null
                        roll = null
                    }
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    private fun poll() {
        lastPollAt = System.currentTimeMillis()
        synchronized(this) {
            if (!started) {
                val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                    ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    ?: return
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                started = true
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
            }
        }
    }

    override fun pitchDegrees(): Float? {
        poll()
        return pitch
    }

    override fun rollDegrees(): Float? {
        poll()
        return roll
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        if (haveGravity) {
            for (i in gravity.indices) gravity[i] += LOW_PASS_ALPHA * (event.values[i] - gravity[i])
        } else {
            event.values.copyInto(gravity, endIndex = 3)
            haveGravity = true
        }
        val gravityX = gravity[0]
        val gravityY = gravity[1]
        val gravityZ = gravity[2]
        roll = InclineMath.rollDegrees(gravityX, gravityZ)
        pitch = InclineMath.pitchDegrees(gravityY, gravityZ)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val LOW_PASS_ALPHA = 0.2f
        const val IDLE_STOP_MS = 5000L
    }
}
