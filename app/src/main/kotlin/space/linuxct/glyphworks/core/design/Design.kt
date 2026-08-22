package space.linuxct.glyphworks.core.design

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// The `glyph.design` file format. Full layout and an example: docs/glyph-design-format.md.

const val DESIGN_FORMAT = "glyph.design"

const val DESIGN_FORMAT_VERSION = 1

val DEFAULT_LEVELS: List<Int> = listOf(0, 2048, 4095)

enum class PokemonCodename(val codename: String, val size: Int) {
    /** Nothing Phone (4a) Pro. */
    BELLSPROUT("bellsprout", 13),

    /** Nothing Phone (3). */
    ARBOK("arbok", 25);

    val cellCount: Int get() = size * size

    companion object {
        fun ofCodename(codename: String): PokemonCodename? =
            entries.firstOrNull { it.codename == codename }

        fun ofSize(size: Int): PokemonCodename? = entries.firstOrNull { it.size == size }
    }
}

@Serializable
enum class DesignKind {
    @SerialName("static")
    STATIC,

    @SerialName("dynamic")
    DYNAMIC,
}

@Serializable
enum class KeyMode {
    @SerialName("playOnce")
    PLAY_ONCE,

    @SerialName("playPause")
    PLAY_PAUSE,
}

@Serializable
data class DesignFrame(
    val durationMs: Int = DEFAULT_FRAME_DURATION_MS,
    val cells: String = "",
)

const val DEFAULT_FRAME_DURATION_MS = 120

@Serializable
data class DesignVariant(
    val frames: List<DesignFrame> = emptyList(),
)

/**
 * Every field has a default, so a truncated file still decodes and the validator can reject
 * it with a reason worth showing. [variants] is keyed by the codename as a String, not the
 * enum, because kotlinx throws on an unknown enum key and an unknown codename must be ignored.
 */
@Serializable
data class Design(
    val format: String = DESIGN_FORMAT,
    val formatVersion: Int = DESIGN_FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val author: String = "",
    /** ISO-8601 UTC like `2026-07-30T12:00:00Z`, never epoch millis. */
    val createdAt: String = "",
    val modifiedAt: String = "",
    val createdWith: String = "",
    val kind: DesignKind = DesignKind.STATIC,
    val keyMode: KeyMode = KeyMode.PLAY_PAUSE,
    val loop: Boolean = false,
    val levels: List<Int> = DEFAULT_LEVELS,
    val variants: Map<String, DesignVariant> = emptyMap(),
) {
    fun variantFor(codename: PokemonCodename): DesignVariant? = variants[codename.codename]

    fun variantForSize(size: Int): DesignVariant? =
        PokemonCodename.ofSize(size)?.let { variantFor(it) }
}

/** The id becomes a filename, so it never comes from text a user typed. */
fun newDesignId(): String = UUID.randomUUID().toString().replace("-", "")

fun nowIsoUtc(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
