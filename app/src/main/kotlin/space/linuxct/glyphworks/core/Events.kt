package space.linuxct.glyphworks.core

/** Event names for [GlyphScreen.onEvent]. The first four match the SDK's GlyphToy constants. */
object Events {
    const val CHANGE = "change"
    const val AOD = "aod"
    const val ACTION_DOWN = "action_down"
    const val ACTION_UP = "action_up"
    const val SHAKE = "compat_shake"
}
