package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt

/** Music visualizer, drawn from the shared FFT engine. */
class VisualizerScreen : GlyphScreen {
    override val id = "visualizer"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(TICK_MS) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event == Events.AOD) {
            ctx?.prefs?.putBoolean(PrefKeys.VISUALIZER_AOD_HINT, true)
        }
    }

    private fun tick() {
        val c = ctx ?: return
        val bands = c.ports.spectrum.bands(c.size)
        val theme = c.prefs.getInt(PrefKeys.VISUALIZER_THEME, PrefKeys.VISUALIZER_THEME_DEF)
        c.pushFrame(renderFrame(c.size, bands, theme))
    }

    companion object {
        const val TICK_MS = 50L

        const val SILENCE_THRESHOLD = 0.1f

        const val THEME_BARS = 0
        const val THEME_MIRRORED = 1
        const val THEME_RINGS = 2

        /** A null spectrum means no mic. */
        fun renderFrame(size: Int, bands: FloatArray?, theme: Int): IntArray {
            if (bands == null) return renderPermissionPattern(size)
            if ((bands.maxOrNull() ?: 0f) <= SILENCE_THRESHOLD) return renderIdlePattern(size)
            val canvas = MatrixCanvas(size)
            when (theme) {
                THEME_MIRRORED -> renderMirrored(canvas, bands)
                THEME_RINGS -> renderPalette(canvas, bands)
                else -> renderBars(canvas, bands)
            }
            return canvas.copyOut()
        }

        /** The 1-cell noise floor every column shows while audio plays. */
        private const val FLOOR_BRIGHTNESS = 1300
        private const val BAR_TIP = MAX_BRIGHTNESS
        private const val BAR_BODY = 1400
        private const val MIRROR_AXIS = 2200

        private fun renderBars(canvas: MatrixCanvas, bands: FloatArray) {
            val size = canvas.size
            for (x in 0 until size) {
                val h = (bands[x % bands.size] * size).roundToInt().coerceIn(1, size)
                if (h == 1) {
                    canvas.light(x, size - 1, FLOOR_BRIGHTNESS)
                    continue
                }
                for (i in 0 until h) {
                    val y = size - 1 - i
                    canvas.light(x, y, if (i == h - 1) BAR_TIP else BAR_BODY)
                }
            }
        }

        private fun renderMirrored(canvas: MatrixCanvas, bands: FloatArray) {
            val size = canvas.size
            val mid = size / 2
            for (x in 0 until size) {
                val h = (bands[x % bands.size] * (size / 2f)).roundToInt().coerceIn(1, mid)
                if (h == 1) {
                    canvas.light(x, mid, FLOOR_BRIGHTNESS)
                    continue
                }
                for (i in 0 until h) {
                    val v = if (i == h - 1) BAR_TIP else BAR_BODY
                    canvas.light(x, mid - i, v)
                    canvas.light(x, mid + i, v)
                }
                canvas.light(x, mid, MIRROR_AXIS)
            }
        }

        // The disc holds the frame's peak so panel brightness stays put, and only the
        // ring, which carries the treble energy, changes with the music.
        private fun renderPalette(canvas: MatrixCanvas, bands: FloatArray) {
            val size = canvas.size
            val center = (size - 1) / 2f
            val low = bands.take(bands.size / BASS_BAND_DIVISOR).average().toFloat()
            val high = bands.drop(bands.size / TREBLE_BAND_DIVISOR).average().toFloat()
            val discRadius = 1f + low * (size / 2f - 1f)
            canvas.discSoft(center, center, discRadius, MAX_BRIGHTNESS)
            canvas.ring(
                center, center,
                discRadius + RING_GAP, discRadius + RING_GAP + RING_THICKNESS,
                (MAX_BRIGHTNESS * high).roundToInt().coerceAtLeast(RING_MIN),
            )
        }

        private const val BASS_BAND_DIVISOR = 4
        private const val TREBLE_BAND_DIVISOR = 2
        private const val RING_GAP = 0.5f
        private const val RING_THICKNESS = 0.7f
        private const val RING_MIN = 600

        /** Audio is on but silent. */
        fun renderIdlePattern(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val mid = size / 2
            for (x in 1 until size - 1) canvas.light(x, size - 2, IDLE_BASELINE)
            canvas.set(mid - 3, size - 3, IDLE_DOT)
            canvas.set(mid, size - 4, IDLE_DOT)
            canvas.set(mid + 3, size - 3, IDLE_DOT)
            return canvas.copyOut()
        }

        /** No mic access: a crossed-out mic. */
        fun renderPermissionPattern(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val cx = size / 2
            val topY = size / 4
            canvas.fillRect(cx - 1, topY, 3, 4, MIC_BODY)
            canvas.line(cx - 2, topY + 4, cx + 2, topY + 4, MIC_STAND)
            canvas.line(cx, topY + 5, cx, topY + 6, MIC_STAND)
            canvas.line(cx - 2, topY + 6, cx + 2, topY + 6, MIC_STAND)
            canvas.line(size - 3, 2, 2, size - 3, MIC_SLASH)
            return canvas.copyOut()
        }

        private const val IDLE_DOT = MAX_BRIGHTNESS
        private const val IDLE_BASELINE = 2389

        private const val MIC_SLASH = MAX_BRIGHTNESS
        private const val MIC_BODY = 2457
        private const val MIC_STAND = 1228
    }
}
