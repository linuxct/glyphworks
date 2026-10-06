package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.pipeline.AnimationFit
import space.linuxct.pipeline.AssetBinding
import space.linuxct.pipeline.NativeContext
import space.linuxct.pipeline.SpriteGeometry
import kotlin.math.floor

/** Decodes once when the instance is created. No design-store or network work during rendering. */
internal class NativeArtwork(private val context: NativeContext, private val state: () -> Map<String, space.linuxct.pipeline.Value> = { emptyMap() }) {
    private data class Animation(val frames: List<IntArray>, val durations: List<Long>) {
        val duration = durations.sum().coerceAtLeast(1)
    }
    private data class Decoded(val designs: Map<String, Design>, val animations: Map<String, Animation>)
    private val prepared = prepare(context.designs, context.size)
    val designs get() = prepared.designs
    private val decoded get() = prepared.animations
    companion object {
        // Immutable pixels are shared by simultaneous instances. Identity keys avoid repeatedly
        // hashing large JSON cell strings; eviction releases only the cache's ownership.
        private data class Entry(val source: Map<String, kotlinx.serialization.json.JsonObject>, val size: Int, val data: Decoded)
        private val cache = ArrayList<Entry>()
        @Synchronized private fun prepare(source: Map<String, kotlinx.serialization.json.JsonObject>, size: Int, assets: space.linuxct.pipeline.PipelineAssets? = null): Decoded {
            cache.firstOrNull { it.source === source && it.size == size }?.let { return it.data }
            val designs = source.mapNotNull { (id, json) -> (DesignCodec.decode(json.toString()) as? DesignCodec.Result.Ok)?.design?.let { id to it } }.toMap()
            val decoded = designs.mapNotNull { (id, design) ->
                val variant = design.variantForSize(size) ?: return@mapNotNull null
                val frames = if (design.kind == DesignKind.STATIC) variant.frames.take(1) else variant.frames
                val pixels = assets?.frames(id, size)?.map { it.pixels }?.take(frames.size)
                    ?: frames.map { DesignFrames.decode(it.cells, design.levels, size) ?: return@mapNotNull null }
                if (pixels.size != frames.size || pixels.isEmpty()) null else id to Animation(pixels, frames.map { it.durationMs.toLong() })
            }.toMap()
            val result = Decoded(designs, decoded)
            cache += Entry(source, size, result)
            while (cache.size > 8 || (cache.size > 1 && cache.sumOf { entry -> entry.data.animations.values.sumOf { animation -> animation.frames.sumOf { it.size.toLong() } } } > 4_000_000L)) cache.removeAt(0)
            return result
        }
        fun prewarm(source: Map<String, kotlinx.serialization.json.JsonObject>, size: Int, assets: space.linuxct.pipeline.PipelineAssets? = null) { prepare(source, size, assets) }
    }
    fun binding(slot: String) = context.bindings[slot]
    fun geometry(slot: String): SpriteGeometry? {
        val variants = binding(slot)?.variants ?: return null
        return variants[context.size.toString()] ?: PokemonCodename.ofSize(context.size)?.let { variants[it.codename] }
    }
    fun frame(slot: String, elapsedMs: Long, phaseDurationMs: Long? = null): IntArray? {
        val binding = binding(slot) ?: return null
        val elapsed = elapsedMs.coerceAtLeast(0)
        binding.routineId?.let { routine ->
            val values = state() + mapOf(
                "slot" to space.linuxct.pipeline.text(slot),
                "elapsedMs" to space.linuxct.pipeline.duration(elapsed),
                "phaseDurationMs" to (phaseDurationMs?.let { space.linuxct.pipeline.duration(it) } ?: space.linuxct.pipeline.Value.Unavailable()),
                "progress" to space.linuxct.pipeline.number(if (phaseDurationMs != null && phaseDurationMs > 0) (elapsed.toDouble() / phaseDurationMs).coerceIn(0.0, 1.0) else 0.0),
            )
            return context.renderDrawing(routine, values)
        }
        val animation = decoded[binding.assetId] ?: return null
        var position = when (binding.playback) {
            AnimationFit.LOOP -> elapsed % animation.duration
            AnimationFit.STRETCH_TO_PHASE -> if (phaseDurationMs != null && phaseDurationMs > 0) {
                (elapsed.toDouble() / phaseDurationMs * animation.duration).toLong().coerceAtMost(animation.duration - 1)
            } else elapsed.coerceAtMost(animation.duration - 1)
            AnimationFit.NATURAL, AnimationFit.HOLD_LAST -> elapsed.coerceAtMost(animation.duration - 1)
        }
        animation.durations.forEachIndexed { index, duration ->
            if (position < duration) return animation.frames[index]
            position -= duration
        }
        return animation.frames.last()
    }
    fun compose(slot: String, destination: IntArray, elapsedMs: Long, anchorX: Double? = null, anchorY: Double? = null, phaseDurationMs: Long? = null): Boolean {
        val source = frame(slot, elapsedMs, phaseDurationMs) ?: return false
        val binding = binding(slot) ?: return false
        val geometry = geometry(slot) ?: SpriteGeometry(width = context.size, height = context.size)
        val x = geometry.x.coerceIn(0, context.size - 1)
        val y = geometry.y.coerceIn(0, context.size - 1)
        val width = (if (geometry.width > 0) geometry.width else context.size - x).coerceIn(1, context.size - x)
        val height = (if (geometry.height > 0) geometry.height else context.size - y).coerceIn(1, context.size - y)
        val outX = if (anchorX == null) x else floor(anchorX - geometry.anchorX).toInt()
        val outY = if (anchorY == null) y else floor(anchorY - geometry.anchorY).toInt()
        for (dy in 0 until height) for (dx in 0 until width) {
            val tx = outX + dx
            val ty = outY + dy
            if (tx !in 0 until context.size || ty !in 0 until context.size) continue
            val pixel = source[(y + dy) * context.size + x + dx]
            if (pixel > 0 || !binding.transparentZero) destination[ty * context.size + tx] = pixel
        }
        return true
    }
}
