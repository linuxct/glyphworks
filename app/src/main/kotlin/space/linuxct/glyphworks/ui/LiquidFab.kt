package space.linuxct.glyphworks.ui

import android.graphics.RuntimeShader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.collectLatest
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.ui.theme.NothingLiquidBlue
import space.linuxct.glyphworks.ui.theme.NothingLiquidRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val TWO_PI = (2.0 * PI).toFloat()

internal const val LIQUID_SLOT_MS = 3_600L

internal const val LIQUID_SLOTS = 24

internal const val LIQUID_PERIOD_MS = LIQUID_SLOT_MS * LIQUID_SLOTS

private const val LIQUID_SLOT_POSITION_EPSILON = 1e-5f

internal const val LIQUID_FRONT_GRADIENT = 1.15f

internal const val LIQUID_SWELL_AMOUNT = 0.44f

internal const val LIQUID_SHORT_WEIGHT = 0.50f

internal const val LIQUID_LONG_WEIGHT = 1f - LIQUID_SHORT_WEIGHT

internal const val LIQUID_LONG_FX = 1.15f
internal const val LIQUID_LONG_FY = 0.95f

internal const val LIQUID_SHORT_FX = 2.90f
internal const val LIQUID_SHORT_FY = 3.35f

internal const val LIQUID_WARP = 0.30f

internal const val LIQUID_WARP_X_SAMPLE_DX = 0.7f
internal const val LIQUID_WARP_X_SAMPLE_DY = -1.3f
internal const val LIQUID_WARP_Y_SAMPLE_DX = -1.1f
internal const val LIQUID_WARP_Y_SAMPLE_DY = 0.6f
internal const val LIQUID_WARP_Y_PHASE_OFFSET = 2.1f

internal const val LIQUID_EDGE = 0.55f

internal const val LIQUID_TIDE_AMPLITUDE = 2.75f

internal const val LIQUID_LONG_CYCLES = 5

internal const val LIQUID_SHORT_CYCLES = 8

internal const val LIQUID_SWELL_WINDOW_RADII = 4f

internal const val LIQUID_CLAMP_BOUND =
    LIQUID_FRONT_GRADIENT + LIQUID_SWELL_AMOUNT + LIQUID_EDGE

internal const val LIQUID_FALLBACK_CLAMP = LIQUID_FRONT_GRADIENT + LIQUID_EDGE

internal fun liquidPhase(timeMs: Long): Float =
    timeMs.mod(LIQUID_PERIOD_MS).toFloat() / LIQUID_PERIOD_MS * TWO_PI

private fun liquidSlotPosition(phase: Float): Float =
    (phase / TWO_PI * LIQUID_SLOTS).coerceIn(0f, LIQUID_SLOTS - LIQUID_SLOT_POSITION_EPSILON)

internal fun liquidSlot(phase: Float): Int = liquidSlotPosition(phase).toInt()

internal fun liquidSlotProgress(phase: Float): Float =
    liquidSlotPosition(phase).let { it - it.toInt() }

private fun liquidSlotBringsRed(slot: Int): Boolean = slot % 2 == 0

private fun liquidSlotSign(slot: Int): Float = if (liquidSlotBringsRed(slot)) 1f else -1f

internal fun liquidTide(phase: Float): Float {
    val slot = liquidSlot(phase)
    return LIQUID_TIDE_AMPLITUDE * liquidSlotSign(slot) * (2f * liquidSlotProgress(phase) - 1f)
}

internal const val LIQUID_MIN_TURN = (40.0 * PI / 180.0).toFloat()

internal const val LIQUID_MIN_REPEAT = (25.0 * PI / 180.0).toFloat()

internal const val LIQUID_HEADING_WINDOW = 4

private const val LIQUID_HEADING_TRIES = 64

private const val LOWBIAS32_SHIFT_A = 16
private const val LOWBIAS32_SHIFT_B = 15
private const val LOWBIAS32_MULTIPLIER_A = 0x7feb352d
private val LOWBIAS32_MULTIPLIER_B = 0x846ca68b.toInt()

private const val HASH_UNIT_BITS = 24

private val LIQUID_SEED_TURN = 0x9E3779B1.toInt()
private val LIQUID_SEED_SALT = 0x85EBCA6B.toInt()
private const val LIQUID_SEED_BASE = 0x2545F491
private const val LIQUID_SEED_SLOT = 0x27D4EB2D
private const val LIQUID_SEED_LONG = 0x165667B1
private val LIQUID_SEED_SHORT = 0x9E3779B9.toInt()
private val LIQUID_SEED_ORIGIN_X = 0xC2B2AE35.toInt()
private const val LIQUID_SEED_ORIGIN_Y = 0x27D4EB2F

internal fun liquidHash(seed: Int): Int {
    var h = seed
    h = h xor (h ushr LOWBIAS32_SHIFT_A)
    h *= LOWBIAS32_MULTIPLIER_A
    h = h xor (h ushr LOWBIAS32_SHIFT_B)
    h *= LOWBIAS32_MULTIPLIER_B
    h = h xor (h ushr LOWBIAS32_SHIFT_A)
    return h
}

internal fun liquidUnit(seed: Int): Float =
    (liquidHash(seed) ushr (Int.SIZE_BITS - HASH_UNIT_BITS)).toFloat() /
        (1 shl HASH_UNIT_BITS).toFloat()

private fun liquidSignedUnit(seed: Int, extent: Float): Float =
    liquidUnit(seed) * 2f * extent - extent

internal fun liquidSeparation(a: Float, b: Float): Float {
    val d = (a - b).mod(TWO_PI)
    return min(d, TWO_PI - d)
}

internal val LIQUID_HEADINGS: FloatArray = buildLiquidHeadings()

private fun buildLiquidHeadings(): FloatArray {
    val out = FloatArray(LIQUID_SLOTS) { Float.NaN }
    for (k in 0 until LIQUID_SLOTS) {
        var best = 0f
        var bestWorstMargin = -Float.MAX_VALUE
        for (salt in 0 until LIQUID_HEADING_TRIES) {
            val candidate = liquidCandidateHeading(k, salt)
            val worstMargin = liquidWorstMargin(candidate, k, out)
            if (worstMargin > bestWorstMargin) {
                bestWorstMargin = worstMargin
                best = candidate
            }
            if (worstMargin >= 0f) break
        }
        out[k] = best
    }
    return out
}

private fun liquidCandidateHeading(slot: Int, salt: Int): Float =
    liquidUnit(slot * LIQUID_SEED_TURN + salt * LIQUID_SEED_SALT + LIQUID_SEED_BASE) * TWO_PI

private fun liquidWorstMargin(candidate: Float, slot: Int, chosen: FloatArray): Float {
    var worst = Float.MAX_VALUE
    for (j in 0 until LIQUID_SLOTS) {
        if (chosen[j].isNaN()) continue
        val gap = min((j - slot).mod(LIQUID_SLOTS), (slot - j).mod(LIQUID_SLOTS))
        if (gap == 0 || gap > LIQUID_HEADING_WINDOW) continue
        val apart = liquidSeparation(candidate, chosen[j])
        val margin =
            if (gap == 1) liquidNeitherRepeatNorReversalMargin(apart) else liquidRepeatMargin(apart)
        if (margin < worst) worst = margin
    }
    return worst
}

private fun liquidNeitherRepeatNorReversalMargin(apart: Float): Float =
    min(apart - LIQUID_MIN_TURN, (PI.toFloat() - LIQUID_MIN_TURN) - apart)

private fun liquidRepeatMargin(apart: Float): Float = apart - LIQUID_MIN_REPEAT

internal fun liquidArrivalAngle(k: Int): Float = LIQUID_HEADINGS[k.mod(LIQUID_SLOTS)]

internal data class LiquidFrame(
    val dirX: Float,
    val dirY: Float,
    val tide: Float,
    val longPhase: Float,
    val shortPhase: Float,
    val originX: Float,
    val originY: Float,
)

internal fun liquidFrame(phase: Float): LiquidFrame {
    val slot = liquidSlot(phase)
    val sign = liquidSlotSign(slot)
    val heading = liquidArrivalAngle(slot)
    val seed = slot * LIQUID_SEED_SLOT
    return LiquidFrame(
        dirX = sign * cos(heading),
        dirY = sign * sin(heading),
        tide = liquidTide(phase),
        longPhase = (LIQUID_LONG_CYCLES * phase + liquidUnit(seed + LIQUID_SEED_LONG) * TWO_PI)
            .mod(TWO_PI),
        shortPhase = (LIQUID_SHORT_CYCLES * phase + liquidUnit(seed + LIQUID_SEED_SHORT) * TWO_PI)
            .mod(TWO_PI),
        originX = liquidSignedUnit(seed + LIQUID_SEED_ORIGIN_X, LIQUID_SWELL_WINDOW_RADII),
        originY = liquidSignedUnit(seed + LIQUID_SEED_ORIGIN_Y, LIQUID_SWELL_WINDOW_RADII),
    )
}

private fun liquidWave(qx: Float, qy: Float, fx: Float, fy: Float, phase: Float): Float =
    0.5f * (sin(qx * fx + phase) + sin(qy * fy - phase))

internal fun liquidField(px: Float, py: Float, frame: LiquidFrame): Float {
    val sx = px + frame.originX
    val sy = py + frame.originY
    val warpX = LIQUID_WARP * liquidWave(
        sx + LIQUID_WARP_X_SAMPLE_DX,
        sy + LIQUID_WARP_X_SAMPLE_DY,
        LIQUID_LONG_FX,
        LIQUID_LONG_FY,
        frame.longPhase,
    )
    val warpY = LIQUID_WARP * liquidWave(
        sx + LIQUID_WARP_Y_SAMPLE_DX,
        sy + LIQUID_WARP_Y_SAMPLE_DY,
        LIQUID_LONG_FX,
        LIQUID_LONG_FY,
        frame.longPhase + LIQUID_WARP_Y_PHASE_OFFSET,
    )
    val qx = sx + warpX
    val qy = sy + warpY
    val swell =
        LIQUID_LONG_WEIGHT * liquidWave(qx, qy, LIQUID_LONG_FX, LIQUID_LONG_FY, frame.longPhase) +
            LIQUID_SHORT_WEIGHT *
            liquidWave(qx, qy, LIQUID_SHORT_FX, LIQUID_SHORT_FY, frame.shortPhase)
    return LIQUID_FRONT_GRADIENT * (px * frame.dirX + py * frame.dirY) +
        LIQUID_SWELL_AMOUNT * swell +
        frame.tide
}

internal fun liquidMixAt(px: Float, py: Float, frame: LiquidFrame): Float {
    val t = ((liquidField(px, py, frame) + LIQUID_EDGE) / (2f * LIQUID_EDGE)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

// Every interpolated constant has to render as a legal AGSL literal. A Float that comes
// out in scientific notation, like 1.0E-5, fails to compile and drops the FAB to
// liquidFallbackBrush forever. LiquidFabTest reads this string to catch that.
internal val LIQUID_AGSL = """
uniform float2 uSize;
uniform float2 uDir;
uniform float2 uPhase;
uniform float2 uOrigin;
uniform float uTide;
layout(color) uniform half4 uLow;
layout(color) uniform half4 uHigh;

float wave(float2 q, float2 f, float ph) {
    return 0.5 * (sin(q.x * f.x + ph) + sin(q.y * f.y - ph));
}

half4 main(float2 fragCoord) {
    float radius = 0.5 * min(uSize.x, uSize.y);
    float2 p = (fragCoord - 0.5 * uSize) / max(radius, 1.0);

    float2 s = p + uOrigin;
    float2 fLong = float2(${LIQUID_LONG_FX}, ${LIQUID_LONG_FY});
    float2 fShort = float2(${LIQUID_SHORT_FX}, ${LIQUID_SHORT_FY});

    float2 q = s + ${LIQUID_WARP} * float2(
        wave(s + float2(${LIQUID_WARP_X_SAMPLE_DX}, ${LIQUID_WARP_X_SAMPLE_DY}), fLong, uPhase.x),
        wave(s + float2(${LIQUID_WARP_Y_SAMPLE_DX}, ${LIQUID_WARP_Y_SAMPLE_DY}), fLong,
             uPhase.x + ${LIQUID_WARP_Y_PHASE_OFFSET}));

    float swell = ${LIQUID_LONG_WEIGHT} * wave(q, fLong, uPhase.x)
                + ${LIQUID_SHORT_WEIGHT} * wave(q, fShort, uPhase.y);

    float f = ${LIQUID_FRONT_GRADIENT} * dot(p, uDir) + ${LIQUID_SWELL_AMOUNT} * swell + uTide;
    return mix(uLow, uHigh, half(smoothstep(-${LIQUID_EDGE}, ${LIQUID_EDGE}, f)));
}
"""

@Composable
internal fun LiquidFabFill(modifier: Modifier = Modifier) {
    val shader = rememberLiquidShader()
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }
    val phase = rememberLiquidPhaseWhileResumed()

    Spacer(
        modifier.drawBehind {
            val frame = liquidFrame(phase.floatValue)
            if (shader != null && brush != null) {
                shader.setLiquidUniforms(frame, size.width, size.height)
                drawCircle(brush)
            } else {
                drawCircle(liquidFallbackBrush(frame, size.width, size.height))
            }
        },
    )
}

@Composable
private fun rememberLiquidShader(): RuntimeShader? = remember {
    runCatching { RuntimeShader(LIQUID_AGSL) }
        .onSuccess {
            it.setColorUniform("uLow", NothingLiquidBlue.toArgb())
            it.setColorUniform("uHigh", NothingLiquidRed.toArgb())
        }
        .onFailure { DebugLog.w("LiquidFab", "AGSL unavailable, falling back: ${it.message}") }
        .getOrNull()
}

@Composable
private fun rememberLiquidPhaseWhileResumed(): FloatState {
    val phase = remember { mutableFloatStateOf(liquidPhase(0L)) }
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { resumed }.collectLatest { running ->
            if (!running) return@collectLatest
            while (true) {
                withFrameMillis { phase.floatValue = liquidPhase(it) }
            }
        }
    }
    return phase
}

private fun RuntimeShader.setLiquidUniforms(frame: LiquidFrame, width: Float, height: Float) {
    setFloatUniform("uSize", width, height)
    setFloatUniform("uDir", frame.dirX, frame.dirY)
    setFloatUniform("uPhase", frame.longPhase, frame.shortPhase)
    setFloatUniform("uOrigin", frame.originX, frame.originY)
    setFloatUniform("uTide", frame.tide)
}

private fun liquidFallbackBrush(frame: LiquidFrame, width: Float, height: Float): Brush {
    val cx = width / 2f
    val cy = height / 2f
    val radius = (min(width, height) / 2f).coerceAtLeast(1f)
    val blueEnd = (-LIQUID_EDGE - frame.tide) / LIQUID_FRONT_GRADIENT * radius
    val redEnd = (LIQUID_EDGE - frame.tide) / LIQUID_FRONT_GRADIENT * radius
    return Brush.linearGradient(
        colors = listOf(NothingLiquidBlue, NothingLiquidRed),
        start = Offset(cx + frame.dirX * blueEnd, cy + frame.dirY * blueEnd),
        end = Offset(cx + frame.dirX * redEnd, cy + frame.dirY * redEnd),
    )
}
