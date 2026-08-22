package space.linuxct.glyphworks.core

object DebugLog {
    const val TAG = "GlyphWorks"

    enum class Level { DEBUG, INFO, WARN }

    @Volatile
    var sink: (level: Level, component: String, message: String) -> Unit = { _, _, _ -> }

    fun d(component: String, message: String) = sink(Level.DEBUG, component, message)
    fun i(component: String, message: String) = sink(Level.INFO, component, message)
    fun w(component: String, message: String) = sink(Level.WARN, component, message)
}
