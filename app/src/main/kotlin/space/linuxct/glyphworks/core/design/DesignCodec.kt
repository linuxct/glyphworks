package space.linuxct.glyphworks.core.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The `glyph.design` reader and writer. Every input is hostile. docs/glyph-design-format.md */
object DesignCodec {

    const val MAX_BYTES = 1024 * 1024

    // UTF-8 spends a byte per character at least, so a longer string is over MAX_BYTES.
    const val MAX_CHARS = MAX_BYTES

    const val MAX_FRAMES = 240

    const val MIN_DURATION_MS = 20

    const val MAX_DURATION_MS = 60_000

    const val MAX_NAME_LENGTH = 64
    const val MAX_AUTHOR_LENGTH = 64

    const val MAX_CREATED_WITH_LENGTH = 64

    const val MAX_ID_LENGTH = 64

    // The id becomes a filename, so it must be a plain token: no separators, no dots, no NUL.
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,$MAX_ID_LENGTH}")

    private const val FIELD_FORMAT = "format"

    sealed interface Result {
        data class Ok(val design: Design) : Result

        data class Invalid(val reason: String) : Result
    }

    const val REASON_TOO_LARGE = "This file is too large to be a Glyph design."
    const val REASON_NOT_JSON = "This file is not valid JSON."
    const val REASON_NOT_A_DESIGN = "This is not a Glyph design file."
    const val REASON_NEWER_VERSION = "This design was made with a newer version of the app."
    const val REASON_OLDER_VERSION = "This design declares a format version this app cannot read."
    const val REASON_BAD_ID = "This design has an unusable id."
    const val REASON_NAME_TOO_LONG = "This design's name is too long."
    const val REASON_AUTHOR_TOO_LONG = "This design's author name is too long."
    const val REASON_CREATED_WITH_TOO_LONG = "This design's originating app name is too long."
    const val REASON_BAD_TIMESTAMP = "This design has an unreadable timestamp."
    const val REASON_EMPTY_PALETTE = "This design has no brightness levels."
    const val REASON_PALETTE_TOO_LONG = "This design has too many brightness levels."
    const val REASON_NO_VARIANTS = "This design contains no artwork for any known device."
    const val REASON_TOO_MANY_FRAMES = "This design has too many frames."
    const val REASON_BAD_DURATION = "This design has a frame duration outside 20 ms to 60 s."
    const val REASON_BAD_FRAME_SIZE = "This design has a frame that is the wrong size for its device."
    const val REASON_BAD_FRAME_CELL = "This design has a frame using a brightness level it does not define."
    const val REASON_UNREADABLE = "This design file could not be read."

    // `coerceInputValues` turns a null or an unknown enum constant into the property default.
    private val reader = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Without `encodeDefaults` a design matching the defaults loses `format` and `levels`.
    private val writer = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    // Naming `Design.serializer()` lets the plugin resolve it, so R8 needs no kotlinx rule.
    fun encode(design: Design): String = writer.encodeToString(Design.serializer(), design)

    fun decode(stream: InputStream): Result {
        val text = try {
            readBounded(stream) ?: return Result.Invalid(REASON_TOO_LARGE)
        } catch (e: Exception) {
            return Result.Invalid(REASON_UNREADABLE + " (" + (e.message ?: e.javaClass.simpleName) + ")")
        }
        return decode(text)
    }

    fun decode(text: String): Result {
        if (text.length > MAX_CHARS) return Result.Invalid(REASON_TOO_LARGE)

        // Check the magic first: every property has a default, so `{}` alone would decode as ours.
        val root: JsonObject = try {
            reader.parseToJsonElement(text) as? JsonObject
                ?: return Result.Invalid(REASON_NOT_A_DESIGN)
        } catch (e: Exception) {
            // The exception text would leak file contents, so it is dropped.
            return Result.Invalid(REASON_NOT_JSON)
        }

        val magic = (root[FIELD_FORMAT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (magic != DESIGN_FORMAT) return Result.Invalid(REASON_NOT_A_DESIGN)

        val raw: Design = try {
            reader.decodeFromJsonElement(Design.serializer(), root)
        } catch (e: Exception) {
            return Result.Invalid(REASON_NOT_JSON)
        }

        return try {
            validate(raw)
        } catch (e: Exception) {
            Result.Invalid(REASON_UNREADABLE)
        }
    }

    fun validate(raw: Design): Result {
        if (raw.format != DESIGN_FORMAT) return Result.Invalid(REASON_NOT_A_DESIGN)
        if (raw.formatVersion > DESIGN_FORMAT_VERSION) return Result.Invalid(REASON_NEWER_VERSION)
        if (raw.formatVersion < 1) return Result.Invalid(REASON_OLDER_VERSION)

        if (!SAFE_ID.matches(raw.id)) return Result.Invalid(REASON_BAD_ID)

        if (raw.name.length > MAX_NAME_LENGTH) return Result.Invalid(REASON_NAME_TOO_LONG)
        if (raw.author.length > MAX_AUTHOR_LENGTH) return Result.Invalid(REASON_AUTHOR_TOO_LONG)
        if (raw.createdWith.length > MAX_CREATED_WITH_LENGTH) {
            return Result.Invalid(REASON_CREATED_WITH_TOO_LONG)
        }

        val createdAt = normalisedInstant(raw.createdAt) ?: return Result.Invalid(REASON_BAD_TIMESTAMP)
        val modifiedAt = normalisedInstant(raw.modifiedAt) ?: return Result.Invalid(REASON_BAD_TIMESTAMP)

        if (raw.levels.isEmpty()) return Result.Invalid(REASON_EMPTY_PALETTE)
        if (raw.levels.size > DesignFrames.MAX_PALETTE) return Result.Invalid(REASON_PALETTE_TOO_LONG)
        val levels = raw.levels.map { it.coerceIn(0, DesignFrames.MAX_BRIGHTNESS) }

        val variants = LinkedHashMap<String, DesignVariant>(raw.variants.size)
        for ((key, variant) in raw.variants) {
            val codename = PokemonCodename.ofCodename(key) ?: continue
            if (variant.frames.size > MAX_FRAMES) return Result.Invalid(REASON_TOO_MANY_FRAMES)
            for (frame in variant.frames) {
                if (frame.durationMs < MIN_DURATION_MS || frame.durationMs > MAX_DURATION_MS) {
                    return Result.Invalid(REASON_BAD_DURATION)
                }
                if (frame.cells.length != codename.cellCount) {
                    return Result.Invalid(REASON_BAD_FRAME_SIZE)
                }
                val everyCellIndexesIntoPalette =
                    DesignFrames.decode(frame.cells, levels, codename.size) != null
                if (!everyCellIndexesIntoPalette) {
                    return Result.Invalid(REASON_BAD_FRAME_CELL)
                }
            }
            variants[codename.codename] = variant
        }
        if (variants.isEmpty()) return Result.Invalid(REASON_NO_VARIANTS)

        return Result.Ok(
            raw.copy(
                createdAt = createdAt,
                modifiedAt = modifiedAt,
                levels = levels,
                variants = variants,
            ),
        )
    }

    fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    private fun readBounded(stream: InputStream): String? {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream(minOf(MAX_BYTES, 64 * 1024))
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // The design list sorts timestamps as plain strings, and `Instant.parse` also takes
    // spellings that sort wrong: an offset like `+02:00`, and sub-second digits before a `Z`.
    // Re-formatting fixes both; the length check rejects years outside 0..9999.
    private fun normalisedInstant(value: String): String? {
        val canonical = try {
            Instant.parse(value).truncatedTo(ChronoUnit.SECONDS).toString()
        } catch (e: Exception) {
            return null
        }
        return canonical.takeIf { it.length == CANONICAL_TIMESTAMP_LENGTH }
    }

    /** The length of `yyyy-MM-ddTHH:mm:ssZ`. */
    private const val CANONICAL_TIMESTAMP_LENGTH = 20
}
