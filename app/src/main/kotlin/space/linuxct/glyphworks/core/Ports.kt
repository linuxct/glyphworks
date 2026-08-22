package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.core.design.Design

interface ClockPort {
    fun nowMillis(): Long
    fun hourOfDay(): Int
    fun minute(): Int
    fun second(): Int
    fun utcOffsetMinutes(): Int
    fun dayOfYear(): Int
}

interface RandomPort {
    fun nextInt(bound: Int): Int
    fun nextFloat(): Float
}

interface BatteryPort {
    fun levelPercent(): Int
    /** True only for BATTERY_STATUS_CHARGING, not merely plugged in. */
    fun isCharging(): Boolean
    fun chargeWatts(): Float?
}

interface SpeedPort {
    fun totalRxBytes(): Long
}

interface SpectrumPort {
    fun bands(n: Int): FloatArray?
}

interface AzimuthPort {
    fun azimuthDegrees(): Float?
}

interface ShakePort {
    fun millisSinceLastShake(): Long
}

interface TiltPort {
    fun tiltX(): Float
    fun tiltY(): Float
}

/**
 * Both angles run -90..90 and read 0 when the device lies flat, face up or face down. The
 * sign points at the edge gravity runs toward: [rollDegrees] is positive when the RIGHT edge
 * of the matrix is low, [pitchDegrees] when the TOP edge is.
 */
interface InclinePort {
    fun pitchDegrees(): Float?
    fun rollDegrees(): Float?
}

interface LightPort {
    fun lux(): Float?
}

enum class ConnectionState { WIFI, CELLULAR, AIRPLANE, NONE }

interface ConnectivityPort {
    fun state(): ConnectionState
}

interface LocationPort {
    fun latLon(): Pair<Double, Double>?
}

interface TimerSignalPort {
    fun scheduleAlarm(atEpochMillis: Long)
    fun cancelAlarm()
    fun chime()
}

interface DesignPort {
    /** Does file I/O. Call it from `onActivate`, never from a ticker or from `glyph-io`. */
    fun selected(): Design?
}

class Ports(
    val clock: ClockPort,
    val random: RandomPort,
    val battery: BatteryPort,
    val speed: SpeedPort,
    val spectrum: SpectrumPort,
    val azimuth: AzimuthPort,
    val shake: ShakePort,
    val tilt: TiltPort,
    val incline: InclinePort,
    val light: LightPort,
    val connectivity: ConnectivityPort,
    val location: LocationPort,
    val timer: TimerSignalPort,
    val design: DesignPort,
)
