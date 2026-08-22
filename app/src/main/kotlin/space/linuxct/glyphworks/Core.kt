package space.linuxct.glyphworks

import android.content.Context
import com.nothing.ketchum.Common
import space.linuxct.glyphworks.audio.AudioVisualizerEngine
import space.linuxct.glyphworks.ui.installOptionalHooks
import space.linuxct.glyphworks.core.AndroidRenderScheduler
import space.linuxct.glyphworks.core.AutoBrightness
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphLink
import space.linuxct.glyphworks.core.Ports
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.PrefsMigration
import space.linuxct.glyphworks.core.ScreenManager
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.designs.AndroidDesignPort
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.key.KeyActionRouter
import space.linuxct.glyphworks.screens.CustomScreen
import space.linuxct.glyphworks.screens.ScreenRegistry
import space.linuxct.glyphworks.sensors.CompassSensor
import space.linuxct.glyphworks.sensors.InclineSensor
import space.linuxct.glyphworks.sensors.LightSensor
import space.linuxct.glyphworks.sensors.ShakeDetector
import space.linuxct.glyphworks.sensors.TiltSensor
import space.linuxct.glyphworks.toy.AndroidTimerSignal
import space.linuxct.glyphworks.util.AndroidConnectivityPort
import space.linuxct.glyphworks.util.AndroidLocationPort
import space.linuxct.glyphworks.util.AndroidPrefs
import space.linuxct.glyphworks.util.BatteryReader
import space.linuxct.glyphworks.util.JavaRandomPort
import space.linuxct.glyphworks.util.ScreenStateWatcher
import space.linuxct.glyphworks.util.SystemClockPort
import space.linuxct.glyphworks.util.TrafficSpeedPort

/**
 * The process-wide object graph, shared by the services and the UI. [init] is safe to call
 * from anywhere, more than once, and during Direct Boot.
 */
object Core {

    @Volatile
    private var built = false

    lateinit var prefs: Prefs
        private set

    lateinit var designStore: DesignStore
        private set

    lateinit var glyphLink: GlyphLink
        private set
    lateinit var scheduler: AndroidRenderScheduler
        private set
    lateinit var ports: Ports
        private set
    lateinit var screenManager: ScreenManager
        private set
    lateinit var arbiter: SessionArbiter
        private set
    lateinit var shake: ShakeDetector
        private set
    lateinit var autoBrightness: AutoBrightness
        private set
    lateinit var screenState: ScreenStateWatcher
        private set
    lateinit var audio: AudioVisualizerEngine
        private set
    lateinit var router: KeyActionRouter
        private set

    @Synchronized
    fun init(context: Context) {
        if (built) return
        val app = context.applicationContext

        installLogcatSink()
        DebugLog.i("Core", "init on ${android.os.Build.MODEL} (matrix=${Common.getDeviceMatrixLength()})")

        prefs = AndroidPrefs(app)
        if (PrefsMigration.run(prefs)) DebugLog.i("Core", "prefs migrated to v${PrefKeys.PREFS_VERSION_CURRENT}")
        armToyProbe()
        designStore = DesignStore(app)
        installOptionalHooks(app, designStore)
        glyphLink = GlyphLink(app)
        scheduler = AndroidRenderScheduler()
        shake = ShakeDetector(app)
        audio = AudioVisualizerEngine(app, prefs)

        ports = Ports(
            clock = SystemClockPort(),
            random = JavaRandomPort(),
            battery = BatteryReader(app),
            speed = TrafficSpeedPort(),
            spectrum = audio,
            azimuth = CompassSensor(app),
            shake = shake,
            tilt = TiltSensor(app),
            incline = InclineSensor(app),
            light = LightSensor(app),
            connectivity = AndroidConnectivityPort(app),
            location = AndroidLocationPort(app),
            timer = AndroidTimerSignal(app),
            design = AndroidDesignPort(prefs, designStore),
        )

        screenManager = ScreenManager(
            allScreens = ScreenRegistry.create(),
            prefs = prefs,
            ports = ports,
            scheduler = scheduler,
            size = glyphLink.size,
        ) { frame -> glyphLink.pushFrame(frame) }

        autoBrightness = AutoBrightness(prefs, ports.light, scheduler) {
            screenManager.reapplyBrightness()
        }
        screenState = ScreenStateWatcher(app) { on -> autoBrightness.setScreenOn(on) }

        arbiter = SessionArbiter(glyphLink, scheduler, screenManager, prefs) { running ->
            if (running) {
                shake.start()
                screenState.start()
                autoBrightness.start()
            } else {
                autoBrightness.stop()
                screenState.stop()
                shake.stop()
            }
        }

        router = KeyActionRouter(arbiter, screenManager, scheduler, prefs)

        shake.onShake = {
            scheduler.run { screenManager.dispatchGlyphEvent(Events.SHAKE) }
        }

        prefs.addChangeListener { key ->
            when (key) {
                PrefKeys.MASTER_TOGGLE -> arbiter.onMasterToggleChanged()
                PrefKeys.AUTO_BRIGHTNESS -> autoBrightness.onEnabledChanged()
                PrefKeys.CUSTOM_DESIGN_ID -> scheduler.run {
                    screenManager.onSelectedDesignChanged(CustomScreen.ID)
                }
            }
        }

        built = true
        arbiter.revive()
    }

    private fun installLogcatSink() {
        DebugLog.sink = { level, component, message ->
            val line = "[$component] $message"
            when (level) {
                DebugLog.Level.DEBUG -> android.util.Log.d(DebugLog.TAG, line)
                DebugLog.Level.INFO -> android.util.Log.i(DebugLog.TAG, line)
                DebugLog.Level.WARN -> android.util.Log.w(DebugLog.TAG, line)
            }
        }
    }

    /**
     * Nothing OS will not bind a freshly installed toy until this process has restarted once,
     * so the always-on-toy check may not believe a negative before then.
     */
    private fun armToyProbe() {
        if (prefs.getBoolean(PrefKeys.TOY_PROBE_ARMED, PrefKeys.TOY_PROBE_ARMED_DEF)) return
        val alreadyBound = prefs.getLong(PrefKeys.TOY_LAST_BOUND, PrefKeys.TOY_LAST_BOUND_DEF) > 0L
        val seenOnce = prefs.getBoolean(PrefKeys.TOY_PROBE_SEEN_ONCE, PrefKeys.TOY_PROBE_SEEN_ONCE_DEF)
        when {
            alreadyBound || seenOnce -> {
                prefs.putBoolean(PrefKeys.TOY_PROBE_ARMED, true)
                DebugLog.i("Core", "toy probe armed (bound=$alreadyBound seenOnce=$seenOnce)")
            }
            else -> {
                prefs.putBoolean(PrefKeys.TOY_PROBE_SEEN_ONCE, true)
                DebugLog.i("Core", "toy probe not armed yet: first process since install")
            }
        }
    }
}
