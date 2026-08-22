package space.linuxct.glyphworks.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.SpectrumPort
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The one audiofx.Visualizer(0) in the process. [bands] returns null when it cannot run. */
class AudioVisualizerEngine(
    private val app: Context,
    private val prefs: Prefs,
) : SpectrumPort {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var captureBuf = ByteArray(0)
    private var smoothed = FloatArray(0)
    private var bandEdges = IntArray(0)

    @Volatile private var lastPollAt = 0L
    @Volatile private var lastFailAt = 0L

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@AudioVisualizerEngine) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    releaseLocked()
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    @Synchronized
    override fun bands(n: Int): FloatArray? {
        lastPollAt = System.currentTimeMillis()
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val engine = visualizer ?: create() ?: return null
        if (captureBuf.size != engine.captureSize) captureBuf = ByteArray(engine.captureSize)
        val status = try {
            engine.getFft(captureBuf)
        } catch (e: Exception) {
            Log.w(TAG, "getFft failed", e)
            releaseLocked()
            lastFailAt = System.currentTimeMillis()
            return null
        }
        if (status != Visualizer.SUCCESS) return null
        return toBands(captureBuf, n)
    }

    private fun create(): Visualizer? {
        val now = System.currentTimeMillis()
        if (now - lastFailAt < RETRY_COOLDOWN_MS) return null
        return try {
            Visualizer(0).apply {
                captureSize = CAPTURE_SIZE
                enabled = true
            }.also {
                visualizer = it
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
                Log.d(TAG, "Visualizer started")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer unavailable: ${e.message}")
            lastFailAt = now
            null
        }
    }

    private fun releaseLocked() {
        visualizer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (_: Exception) {
            }
            Log.d(TAG, "Visualizer released")
        }
        visualizer = null
    }

    // The FFT buffer holds DC, then Nyquist, then (re, im) pairs.
    private fun toBands(fft: ByteArray, n: Int): FloatArray {
        val tuning = prefs.getInt(PrefKeys.VISUALIZER_TUNING, PrefKeys.VISUALIZER_TUNING_DEF)
            .coerceIn(CALMEST_TUNING, LIVELIEST_TUNING)
        val gain = GAIN_BASE + tuning * GAIN_PER_STEP
        val attack = ATTACK_BASE + tuning * ATTACK_PER_STEP
        val decay = DECAY_BASE + (LIVELIEST_TUNING - tuning) * DECAY_PER_STEP

        val pairs = (fft.size - 2) / 2
        val twoThirdsOfNyquist = pairs * 2 / 3
        val maxBin = twoThirdsOfNyquist.coerceAtLeast(n + 1)
        if (bandEdges.size != n + 1) bandEdges = buildLogEdges(n, maxBin)
        if (smoothed.size != n) smoothed = FloatArray(n)

        val out = FloatArray(n)
        var rawMax = 0f
        for (band in 0 until n) {
            var sum = 0f
            var count = 0
            for (bin in bandEdges[band] until bandEdges[band + 1]) {
                if (bin >= pairs) break
                val re = fft[2 + bin * 2].toFloat()
                val im = fft[3 + bin * 2].toFloat()
                sum += hypot(re, im) / BYTE_FULL_SCALE
                count++
            }
            val tilt = 1f + HF_TILT * band / (n - 1).coerceAtLeast(1)
            val energy = if (count > 0) (sum / count) * gain * tilt else 0f
            val raw = min(1f, kotlin.math.sqrt(energy.coerceAtLeast(0f)))
            if (raw > rawMax) rawMax = raw
            val previous = smoothed[band]
            smoothed[band] = if (raw > previous) {
                previous + (raw - previous) * attack
            } else {
                max(raw, previous * decay)
            }
            out[band] = smoothed[band]
        }
        if (rawMax < TRUE_SILENCE) {
            smoothed.fill(0f)
            out.fill(0f)
        }
        return out
    }

    private fun buildLogEdges(n: Int, maxBin: Int): IntArray {
        val edges = IntArray(n + 1)
        edges[0] = 1
        val lnMax = kotlin.math.ln(maxBin.toDouble())
        for (i in 1..n) {
            val ideal = kotlin.math.exp(lnMax * i / n).toInt()
            edges[i] = maxOf(edges[i - 1] + 1, ideal)
        }
        edges[n] = edges[n].coerceAtMost(maxBin).coerceAtLeast(edges[n - 1] + 1)
        return edges
    }

    private companion object {
        const val TAG = "AudioVisualizer"
        const val CAPTURE_SIZE = 256
        const val IDLE_STOP_MS = 5000L
        const val RETRY_COOLDOWN_MS = 30_000L
        const val TRUE_SILENCE = 0.02f
        const val BYTE_FULL_SCALE = 128f
        const val HF_TILT = 1.6f

        const val CALMEST_TUNING = 1
        const val LIVELIEST_TUNING = 6
        const val GAIN_BASE = 0.75f
        const val GAIN_PER_STEP = 0.125f
        const val ATTACK_BASE = 0.25f
        const val ATTACK_PER_STEP = 0.05f
        const val DECAY_BASE = 0.84f
        const val DECAY_PER_STEP = 0.015f
    }
}
