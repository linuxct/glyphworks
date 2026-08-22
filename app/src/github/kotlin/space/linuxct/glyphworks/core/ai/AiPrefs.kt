package space.linuxct.glyphworks.core.ai

import space.linuxct.glyphworks.core.Prefs

/** Kept out of `PrefKeys` because the Play flavour ships without `core/ai`. */
object AiPrefKeys {

    // "" is a valid stored value; ChatWire.resolveModel turns it back into the default.
    const val MODEL = "aiModel"
    const val MODEL_DEF = ChatWire.MODEL

    const val MAX_ROUNDS = "aiMaxRounds"
    const val MAX_ROUNDS_DEF = GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS
    const val MAX_ROUNDS_MIN = 4
    const val MAX_ROUNDS_MAX = 40

    // Divides the bounds and the default, so the slider can land on every endpoint.
    const val MAX_ROUNDS_STEP = 4

    // Stores the wire token, not the enum name, so what is stored is what is sent.
    const val REASONING_EFFORT = "aiReasoningEffort"
    const val REASONING_EFFORT_DEF = ChatWire.DEFAULT_REASONING_EFFORT
}

/** The only bound on a loop that makes one network request per round. */
fun Prefs.aiMaxRounds(): Int =
    getInt(AiPrefKeys.MAX_ROUNDS, AiPrefKeys.MAX_ROUNDS_DEF)
        .coerceIn(AiPrefKeys.MAX_ROUNDS_MIN, AiPrefKeys.MAX_ROUNDS_MAX)

fun Prefs.aiReasoningEffort(): ReasoningEffort =
    ReasoningEffort.fromWire(getString(AiPrefKeys.REASONING_EFFORT, AiPrefKeys.REASONING_EFFORT_DEF))
