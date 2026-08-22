package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.linuxct.glyphworks.core.design.Design

/** A design the assistant produced while no editor was open. */
@Serializable
data class PendingApply(
    val format: String = PENDING_APPLY_FORMAT,
    val formatVersion: Int = PENDING_APPLY_FORMAT_VERSION,
    val designId: String = "",
    val baseModifiedAt: String = "",
    val atMs: Long = 0L,
    val design: Design = Design(),
)

const val PENDING_APPLY_FORMAT = "glyph.pendingapply"

const val PENDING_APPLY_FORMAT_VERSION = 1

const val PENDING_APPLY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

enum class PendingApplyVerdict {
    APPLY,
    CONFLICT,
    EXPIRED,
    MISSING,
}

fun pendingApplyVerdict(
    record: PendingApply,
    currentModifiedAt: String?,
    nowMs: Long,
): PendingApplyVerdict = when {
    currentModifiedAt == null || record.baseModifiedAt.isBlank() -> PendingApplyVerdict.MISSING
    nowMs - record.atMs > PENDING_APPLY_MAX_AGE_MS -> PendingApplyVerdict.EXPIRED
    currentModifiedAt != record.baseModifiedAt -> PendingApplyVerdict.CONFLICT
    else -> PendingApplyVerdict.APPLY
}

/** Never throws. Every failure degrades to "nothing waiting". */
object PendingApplyCodec {

    const val MAX_BYTES = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encode(record: PendingApply): String =
        json.encodeToString(
            PendingApply.serializer(),
            record.copy(
                format = PENDING_APPLY_FORMAT,
                formatVersion = PENDING_APPLY_FORMAT_VERSION,
            ),
        )

    fun decode(text: String): PendingApply? {
        if (text.length > MAX_BYTES) return null
        val parsed = try {
            json.decodeFromString(PendingApply.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        if (parsed.format != PENDING_APPLY_FORMAT) return null
        if (parsed.formatVersion > PENDING_APPLY_FORMAT_VERSION) return null
        return parsed
    }
}
