package space.linuxct.glyphworks.core.design

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS as PANEL_MAX_BRIGHTNESS

/**
 * Reads a third-party Glyph design format and returns it as a [Design].
 *
 * Recognised by shape alone, never by a name in the file: an array of frames, each holding one
 * brightness per panel LED plus a duration. The brightness array is packed, so it carries only
 * the LEDs the panel has, row-major, skipping the corners the disc does not light.
 */
internal object ThirdPartyDesign {

    private const val SOURCE_MAX_BRIGHTNESS = 255

    private const val FIELD_FRAMES = "frames"
    private const val FIELD_PIXELS = "p"
    private const val FIELD_DURATION = "d"
    private const val FIELD_META = "meta"
    private const val FIELD_AUTHOR = "author"

    const val CREATED_WITH = "Third-party import"

    fun convert(root: JsonObject, id: String, name: String, createdAt: String): Design? {
        val frames = (root[FIELD_FRAMES] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
        if (frames.size > DesignCodec.MAX_FRAMES) return null

        val pixelRows = frames.map { pixelsOf(it) ?: return null }
        val codename = codenameFor(pixelRows) ?: return null
        val levels = levelsFor(pixelRows)

        val designFrames = frames.mapIndexed { index, frame ->
            DesignFrame(
                durationMs = durationOf(frame),
                cells = DesignFrames.encode(grid(pixelRows[index], codename), levels, codename.size)
                    ?: return null,
            )
        }

        val dynamic = designFrames.size > 1
        return Design(
            id = id,
            name = name,
            author = authorOf(root),
            createdAt = createdAt,
            modifiedAt = createdAt,
            createdWith = CREATED_WITH,
            kind = if (dynamic) DesignKind.DYNAMIC else DesignKind.STATIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = dynamic,
            levels = levels,
            variants = mapOf(codename.codename to DesignVariant(designFrames)),
        )
    }

    private fun pixelsOf(frame: kotlinx.serialization.json.JsonElement): List<Int>? {
        val values = ((frame as? JsonObject)?.get(FIELD_PIXELS) as? JsonArray) ?: return null
        return values.map { element ->
            val value = (element as? JsonPrimitive)?.intOrNull ?: return null
            if (value !in 0..SOURCE_MAX_BRIGHTNESS) return null
            value
        }
    }

    private fun durationOf(frame: kotlinx.serialization.json.JsonElement): Int {
        val raw = ((frame as? JsonObject)?.get(FIELD_DURATION) as? JsonPrimitive)?.intOrNull
        return (raw ?: DEFAULT_FRAME_DURATION_MS)
            .coerceIn(DesignCodec.MIN_DURATION_MS, DesignCodec.MAX_DURATION_MS)
    }

    private fun authorOf(root: JsonObject): String {
        val meta = root[FIELD_META] as? JsonObject ?: return ""
        val author = (meta[FIELD_AUTHOR] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return author.take(DesignCodec.MAX_AUTHOR_LENGTH)
    }

    /** Every frame must be one panel's worth of LEDs, and all frames the same panel. */
    private fun codenameFor(pixelRows: List<List<Int>>): PokemonCodename? {
        val sizes = pixelRows.map { it.size }.distinct()
        val ledCount = sizes.singleOrNull() ?: return null
        return PokemonCodename.entries.firstOrNull { PanelMask.count(it.size) == ledCount }
    }

    /**
     * The source gives every LED its own brightness, so the palette is built from the values the
     * file actually uses and the artwork keeps its shading. Beyond [DesignFrames.MAX_PALETTE]
     * distinct values it becomes an even ramp and [DesignFrames.encode] snaps to the nearest.
     */
    private fun levelsFor(pixelRows: List<List<Int>>): List<Int> {
        val used = sortedSetOf(0)
        pixelRows.forEach { row -> row.forEach { used.add(scale(it)) } }
        if (used.size <= DesignFrames.MAX_PALETTE) return used.toList()
        val steps = DesignFrames.MAX_PALETTE - 1
        return (0..steps).map { it * PANEL_MAX_BRIGHTNESS / steps }
    }

    private fun scale(value: Int): Int =
        value * PANEL_MAX_BRIGHTNESS / SOURCE_MAX_BRIGHTNESS

    /** Unpacks LED-order brightness into the full square grid the `cells` string needs. */
    private fun grid(pixels: List<Int>, codename: PokemonCodename): IntArray {
        val size = codename.size
        val out = IntArray(size * size)
        var next = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!PanelMask.contains(x, y, size)) continue
                out[y * size + x] = scale(pixels[next])
                next++
            }
        }
        return out
    }
}
