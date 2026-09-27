package space.linuxct.glyphworks.core.preview

import space.linuxct.glyphworks.core.*
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.weather.WeatherCondition
import space.linuxct.glyphworks.core.weather.WeatherSnapshot
import space.linuxct.glyphworks.core.weather.WeatherStatus
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.screens.BottleScreen
import space.linuxct.glyphworks.screens.CoinScreen
import space.linuxct.glyphworks.screens.DiceScreen
import space.linuxct.glyphworks.screens.DinoGame
import space.linuxct.glyphworks.screens.DinoScreen
import space.linuxct.glyphworks.screens.EyesScreen
import space.linuxct.glyphworks.screens.ScreenRegistry
import java.time.Instant
import java.time.ZoneOffset
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * A thread-confined, in-app demonstration. The caller drives time with [advance]; this class
 * starts no threads and has no Android, network, sensor, alarm or Glyph output dependencies.
 * Real renderers read the user's presentation settings through a private write overlay.
 * Selecting a toy starts a fresh deterministic demonstration, including when reselecting it.
 */
class ToyPreview(
    val size: Int,
    private val prefs: Prefs,
    design: Design? = null,
) : AutoCloseable {
    init { require(size == 13 || size == 25) { "Unsupported preview panel: $size" } }

    // Do not retain mutable collection aliases supplied by a design editor.
    private val design = design?.copy(
        levels = design.levels.toList(),
        variants = design.variants.mapValues { (_, variant) -> variant.copy(frames = variant.frames.toList()) },
    )
    private var pixels = IntArray(size * size)
    val frame: IntArray get() = pixels.copyOf()
    var selectedId: String? = null
        private set
    private var active: GlyphScreen? = null
    private var scheduler: PreviewScheduler? = null
    private var closed = false

    fun select(id: String) {
        check(!closed) { "Preview is closed" }
        val registered = ScreenRegistry.create().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown toy: $id")
        deactivate()
        selectedId = id
        val clock = PreviewClock(id)
        val queue = PreviewScheduler(clock, if (id == "moon") 1_000L else Long.MAX_VALUE)
        scheduler = queue
        val localPrefs = PreviewPrefs(prefs)
        if (id == "ambient") {
            // Use demo key presses at a readable pace, without changing the user's cycle setting.
            localPrefs.putBoolean(PrefKeys.AMBIENT_AUTO_CYCLE, false)
        }
        if (id == "counter") localPrefs.putInt(PrefKeys.COUNTER, 7)
        if (id == "timer") {
            val duration = localPrefs.getInt(PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF).coerceAtLeast(5)
            localPrefs.putLong(PrefKeys.TIMER_START, clock.nowMillis() - duration * 1_000L / 3)
            localPrefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 0)
            localPrefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0)
        }
        val ports = demoPorts(clock, id)
        val screen = when (id) {
            "dino" -> PreviewDino()
            "bottle" -> PreviewBottle()
            else -> registered
        }
        active = screen
        screen.onActivate(ScreenContext(size, localPrefs, ports, queue) { incoming ->
            pixels = mask(incoming, size)
        })
        when (id) {
            "ambient" -> queue.repeat(AMBIENT_CYCLE_MS, AMBIENT_CYCLE_MS) { screen.onEvent(Events.CHANGE) }
            "dice", "coin" -> queue.repeat(500, 3_000) { screen.onEvent(Events.CHANGE) }
            "bottle" -> queue.repeat(500, BottleScreen.SPIN_MS + 3_500) { screen.onEvent(Events.CHANGE) }
            "counter" -> queue.repeat(1_000, 1_000) { screen.onEvent(Events.CHANGE) }
            "breathing" -> screen.onEvent(Events.CHANGE)
            "custom" -> if (design?.keyMode == KeyMode.PLAY_ONCE) screen.onEvent(Events.CHANGE)
        }
    }

    /** Advance virtual time. Calling in small or large chunks produces the same frames. */
    fun advance(deltaMs: Long) {
        require(deltaMs >= 0) { "Preview time cannot run backwards" }
        if (!closed) scheduler?.advance(deltaMs)
    }

    /** Simulate an Essential Key/Glyph Touch press inside this preview only. */
    fun interact() {
        if (!closed) active?.onEvent(Events.CHANGE)
    }

    override fun close() {
        if (closed) return
        closed = true
        deactivate()
        selectedId = null
    }

    private fun deactivate() {
        active?.onDeactivate()
        active = null
        scheduler?.clear()
        scheduler = null
        pixels = IntArray(size * size)
    }

    private fun demoPorts(clock: PreviewClock, id: String): Ports {
        val random = Random(42L + id.hashCode())
        return Ports(
            clock = clock,
            random = object : RandomPort {
                // Each Bottle demonstration starts upright and lands exactly right.
                override fun nextInt(bound: Int) = if (id == "bottle") 90 % bound else random.nextInt(bound)
                override fun nextFloat() = random.nextFloat()
            },
            battery = object : BatteryPort {
                override fun levelPercent() = 68
                override fun isCharging() = id == "battery"
                override fun chargeWatts() = 33f
            },
            speed = object : SpeedPort {
                override fun totalRxBytes() = 10_000_000L + clock.elapsed * 2_500L +
                    ((1 - cos(clock.elapsed / 1_000.0)) * 600_000).toLong()
            },
            spectrum = object : SpectrumPort {
                override fun bands(n: Int): FloatArray? = if (id != "visualizer") null else
                    FloatArray(n) { x -> (0.48 + 0.35 * sin(x * 0.7 + clock.elapsed / 280.0)).toFloat() }
            },
            azimuth = object : AzimuthPort {
                override fun azimuthDegrees() = (180 + 140 * sin(clock.elapsed / 2_000.0)).toFloat()
            },
            shake = object : ShakePort { override fun millisSinceLastShake() = 0L },
            tilt = object : TiltPort {
                override fun tiltX() = (2 * sin(clock.elapsed / 700.0)).toFloat()
                override fun tiltY() = (2 * cos(clock.elapsed / 900.0)).toFloat()
            },
            incline = object : InclinePort {
                override fun pitchDegrees() = (12 * sin(clock.elapsed / 1_500.0)).toFloat()
                override fun rollDegrees() = (15 * cos(clock.elapsed / 1_700.0)).toFloat()
            },
            light = object : LightPort { override fun lux() = 100f },
            connectivity = object : ConnectivityPort { override fun state() = ConnectionState.WIFI },
            location = object : LocationPort { override fun latLon(): Pair<Double, Double>? = null },
            timer = object : TimerSignalPort {
                override fun scheduleAlarm(atEpochMillis: Long) = Unit
                override fun cancelAlarm() = Unit
                override fun chime() = Unit
            },
            design = object : DesignPort { override fun selected() = design },
            notifications = NotificationPort { NOTIFICATION_COUNTS[(clock.elapsed / 3_000 % NOTIFICATION_COUNTS.size).toInt()] },
            weather = object : WeatherPort {
                override fun snapshot() = WeatherSnapshot(WeatherStatus.READY, 24.0, WeatherCondition.PARTLY_CLOUDY)
                override fun setActive(active: Boolean) = Unit
            },
        )
    }

    companion object {
        const val AMBIENT_CYCLE_MS = 4_000L
        private val NOTIFICATION_COUNTS = intArrayOf(7, 3, 10, 0)

        /** Stable card poses use the real artwork without sampling an arbitrary interaction frame. */
        fun thumbnail(id: String, size: Int, prefs: Prefs, design: Design? = null): IntArray {
            require(size == 13 || size == 25) { "Unsupported preview panel: $size" }
            val frame = when (id) {
                "bottle" -> BottleScreen.renderIdle(size)
                "dice" -> DiceScreen.renderFace(size, face = 6, sides = 6)
                "coin" -> CoinScreen.renderResult(size, heads = true,
                    design = prefs.getInt(PrefKeys.COIN_DESIGN, PrefKeys.COIN_DESIGN_DEF))
                "eyes" -> EyesScreen.renderFrame(size, pupilX = -1f, pupilY = -1f, blinkPhase = -1)
                else -> ToyPreview(size, prefs, design).use { preview ->
                    preview.select(id)
                    preview.advance(700)
                    preview.frame
                }
            }
            return mask(frame, size)
        }

        private fun mask(frame: IntArray, size: Int) = IntArray(size * size) { index ->
            if (PanelMask.contains(index % size, index / size, size))
                frame.getOrElse(index) { 0 }.coerceIn(0, MAX_BRIGHTNESS) else 0
        }
    }
}

/** A deterministic calendar; slow real-world changes are sped up only for their own demos. */
private class PreviewClock(id: String) : ClockPort {
    var elapsed = 0L
    private val wallRate = when (id) { "clock" -> 60L; "solar" -> 3_600L; "moon" -> 86_400L; else -> 1L }
    override fun elapsedMillis() = elapsed
    override fun nowMillis() = BASE_EPOCH_MS + elapsed * wallRate
    private fun date() = Instant.ofEpochMilli(nowMillis()).atOffset(ZoneOffset.UTC)
    override fun hourOfDay() = date().hour
    override fun minute() = date().minute
    override fun second() = date().second
    override fun utcOffsetMinutes() = 0
    override fun dayOfYear() = date().dayOfYear

    companion object { private const val BASE_EPOCH_MS = 1_790_517_000_000L } // 2026-09-27 13:50 UTC
}

/** Virtual callbacks preserve ordering, cancellation and immediate first ticks of the real scheduler. */
private class PreviewScheduler(private val clock: PreviewClock, private val maxTickerMs: Long) : RenderScheduler {
    private class Task(val due: Long, val order: Long, val action: () -> Unit) : Cancelable {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }
    private val queue = PriorityQueue(compareBy<Task> { it.due }.thenBy { it.order })
    private var sequence = 0L
    private var ticker: Any? = null

    override fun setTicker(intervalMs: Long, tick: () -> Unit) {
        val token = Any()
        ticker = token
        val interval = intervalMs.coerceAtLeast(1).coerceAtMost(maxTickerMs)
        fun arm() {
            postDelayed(interval) {
                if (ticker === token) {
                    tick()
                    if (ticker === token) arm()
                }
            }
        }
        tick()
        if (ticker === token) arm()
    }
    override fun clearTicker() { ticker = null }
    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable =
        Task(clock.elapsed + delayMs.coerceAtLeast(1), sequence++, action).also { queue += it }
    override fun run(action: () -> Unit) = action()

    fun repeat(delayMs: Long, periodMs: Long, action: () -> Unit) {
        postDelayed(delayMs) { action(); repeat(periodMs, periodMs, action) }
    }

    fun advance(deltaMs: Long) {
        require(deltaMs <= Long.MAX_VALUE - clock.elapsed) { "Preview time overflow" }
        val target = clock.elapsed + deltaMs
        while (true) {
            val next = queue.peek() ?: break
            if (next.due > target) break
            val task = queue.remove()
            clock.elapsed = task.due
            if (!task.cancelled) task.action()
        }
        clock.elapsed = target
    }

    fun clear() { ticker = null; queue.clear() }
}

/** Writes and removals belong to the demo, never to the caller's settings or listeners. */
private class PreviewPrefs(private val source: Prefs) : Prefs {
    private val values = HashMap<String, Any?>()
    private val listeners = mutableListOf<(String) -> Unit>()
    override fun contains(key: String) = if (values.containsKey(key)) values[key] != null else source.contains(key)
    override fun getBoolean(key: String, def: Boolean) = if (values.containsKey(key)) values[key] as? Boolean ?: def else source.getBoolean(key, def)
    override fun getInt(key: String, def: Int) = if (values.containsKey(key)) values[key] as? Int ?: def else source.getInt(key, def)
    override fun getLong(key: String, def: Long) = if (values.containsKey(key)) values[key] as? Long ?: def else source.getLong(key, def)
    override fun getFloat(key: String, def: Float) = if (values.containsKey(key)) values[key] as? Float ?: def else source.getFloat(key, def)
    override fun getString(key: String, def: String) = if (values.containsKey(key)) values[key] as? String ?: def else source.getString(key, def)
    private fun put(key: String, value: Any?) { values[key] = value; listeners.toList().forEach { it(key) } }
    override fun remove(key: String) = put(key, null)
    override fun putBoolean(key: String, v: Boolean) = put(key, v)
    override fun putInt(key: String, v: Int) = put(key, v)
    override fun putLong(key: String, v: Long) = put(key, v)
    override fun putFloat(key: String, v: Float) = put(key, v)
    override fun putString(key: String, v: String) = put(key, v)
    override fun addChangeListener(listener: (String) -> Unit) { listeners += listener }
    override fun removeChangeListener(listener: (String) -> Unit) { listeners -= listener }
}

/** Restart from the upright bottle so every spin ends at the same demonstrated direction. */
private class PreviewBottle : GlyphScreen {
    override val id = "bottle"
    override val interactive = true
    private val screen = BottleScreen()
    private var context: ScreenContext? = null
    override fun onActivate(ctx: ScreenContext) {
        context = ctx
        screen.onActivate(ctx)
    }
    override fun onEvent(event: String) {
        val ctx = context ?: return
        if (event != Events.CHANGE) return
        screen.onDeactivate()
        ctx.scheduler.clearTicker()
        screen.onActivate(ctx)
        screen.onEvent(event)
    }
    override fun onDeactivate() {
        screen.onDeactivate()
        context = null
    }
}

/** Autopilot uses the real game physics and artwork, with a shorter approach to the first cactus. */
private class PreviewDino : GlyphScreen {
    override val id = "dino"
    override val interactive = true
    private var game: DinoGame? = null
    override fun onActivate(ctx: ScreenContext) {
        fun start(): DinoGame = DinoGame(ctx.size, ctx.ports.random).also { run ->
            while (run.obstacleCells().first().x > ctx.size + 4 * DinoScreen.unit(ctx.size)) run.step()
        }
        game = start()
        ctx.scheduler.setTicker(DinoScreen.TICK_MS) {
            var run = game ?: return@setTicker
            if (run.state == DinoGame.State.OVER) { run = start(); game = run }
            val right = DinoScreen.charX(ctx.size) + DinoScreen.charW(ctx.size) - 1
            val next = run.obstacleCells().firstOrNull { it.x + it.w > DinoScreen.charX(ctx.size) }
            if (next != null && next.x - right <= 4 * DinoScreen.unit(ctx.size)) run.jump()
            run.step()
            ctx.pushFrame(DinoScreen.renderRun(ctx.size, run.jumpCells(), run.legPhase(), run.groundPhase(), run.obstacleCells()))
        }
    }
    override fun onEvent(event: String) { if (event == Events.CHANGE) game?.jump() }
    override fun onDeactivate() { game = null }
}
