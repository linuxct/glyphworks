package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Cancelable
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.design.DEFAULT_FRAME_DURATION_MS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask

/**
 * Plays the design the user picked. A dynamic design runs as a chain of one-shots
 * rather than a ticker, because each frame carries its own `durationMs` and a screen
 * has a single ticker at a single interval.
 */
class CustomScreen : GlyphScreen {
    override val id = ID
    override val interactive = true

    private var ctx: ScreenContext? = null

    private var frames: List<IntArray> = emptyList()
    private var frameDurationsMs: IntArray = IntArray(0)

    private var keyMode = KeyMode.PLAY_PAUSE
    private var loop = false

    private var index = 0
    private var playing = false
    private var pendingFrame: Cancelable? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        cancelChain()
        load(ctx)
        index = 0
        // Play/pause has to start playing: on the always-on display nobody presses
        // anything, and a motionless animation would look broken.
        playing = animated() && keyMode == KeyMode.PLAY_PAUSE
        push()
        if (playing) arm()
    }

    override fun onDeactivate() {
        // ScreenManager.deactivate() only clears the ticker, so an armed one-shot would
        // still fire and paint over the next toy through the shared ScreenContext.
        // Cancelling stops it; nulling ctx catches the one already in flight.
        cancelChain()
        playing = false
        frames = emptyList()
        frameDurationsMs = IntArray(0)
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE && event != Events.SHAKE) return
        if (ctx == null || !animated()) return
        when (keyMode) {
            KeyMode.PLAY_ONCE -> {
                cancelChain()
                index = 0
                playing = true
                push()
                arm()
            }
            KeyMode.PLAY_PAUSE -> if (playing) {
                cancelChain()
                playing = false
            } else {
                if (index >= frames.size - 1) index = 0
                playing = true
                push()
                arm()
            }
        }
    }

    private fun load(ctx: ScreenContext) {
        frames = emptyList()
        frameDurationsMs = IntArray(0)
        keyMode = KeyMode.PLAY_PAUSE
        loop = false

        val design: Design = ctx.ports.design.selected() ?: return
        val codename = PokemonCodename.ofSize(ctx.size) ?: return
        val variant = design.variantFor(codename) ?: return
        if (variant.frames.isEmpty()) return

        val decoded = ArrayList<IntArray>(variant.frames.size)
        for (frame in variant.frames) {
            // DesignCodec validates on store, so a null here means the design bypassed
            // it. Refuse the whole design rather than play half of it.
            decoded += DesignFrames.decode(frame.cells, design.levels, codename.size) ?: return
        }
        keyMode = design.keyMode
        loop = design.loop
        // A static design shows only its first frame, even when the editor kept others.
        if (design.kind == DesignKind.DYNAMIC) {
            frames = decoded
            frameDurationsMs = IntArray(variant.frames.size) { variant.frames[it].durationMs }
        } else {
            frames = listOf(decoded.first())
            frameDurationsMs = intArrayOf(variant.frames.first().durationMs)
        }
    }

    private fun animated(): Boolean = frames.size > 1

    private fun push() {
        val c = ctx ?: return
        c.pushFrame(frames.getOrNull(index) ?: renderPlaceholder(c.size))
    }

    private fun arm() {
        val c = ctx ?: return
        val holdMs = frameDurationsMs.getOrElse(index) { DEFAULT_FRAME_DURATION_MS }.toLong()
        pendingFrame = c.scheduler.postDelayed(holdMs) {
            if (!playing || ctx == null) return@postDelayed
            advance()
        }
    }

    private fun advance() {
        if (index < frames.size - 1) {
            index++
            push()
            arm()
            return
        }
        when (keyMode) {
            KeyMode.PLAY_ONCE -> {
                index = 0
                playing = false
                push()
            }
            KeyMode.PLAY_PAUSE -> if (loop) {
                index = 0
                push()
                arm()
            } else {
                // Pushes nothing, so the design holds the frame its author ended on.
                playing = false
            }
        }
    }

    private fun cancelChain() {
        pendingFrame?.cancel()
        pendingFrame = null
    }

    companion object {
        const val ID = "custom"

        const val PLACEHOLDER_BORDER = 700

        // The border follows PanelMask.isEdge rather than the square buffer: the panel
        // is round, so a rectangle breaks into arcs with gaps at the diagonals.
        fun renderPlaceholder(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (PanelMask.isEdge(x, y, size)) canvas.set(x, y, PLACEHOLDER_BORDER)
                }
            }
            val textTop = size / 2 - Font3x5.HEIGHT / 2
            Font3x5.drawStringCentered(canvas, "?", textTop, MAX_BRIGHTNESS)
            return canvas.copyOut()
        }
    }
}
