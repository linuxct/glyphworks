package space.linuxct.glyphworks.core

class PrefWatch<T>(
    private val prefs: Prefs,
    private val key: String,
    private val read: (Prefs) -> T,
    private val onValue: (T) -> Unit,
) {

    private var listener: ((String) -> Unit)? = null

    val watching: Boolean get() = listener != null

    fun start() {
        if (listener != null) return
        val keyListener: (String) -> Unit = { changed ->
            if (changed == key) {
                val v = read(prefs)
                DebugLog.d(C, "$key changed -> $v")
                onValue(v)
            }
        }
        listener = keyListener
        // Register before the first read. A write that lands in between is then seen twice,
        // never missed.
        prefs.addChangeListener(keyListener)
        val seed = read(prefs)
        DebugLog.d(C, "watching $key, now $seed")
        onValue(seed)
    }

    fun stop() {
        val registered = listener ?: return
        DebugLog.d(C, "released $key")
        listener = null
        prefs.removeChangeListener(registered)
    }

    private companion object {
        const val C = "PrefWatch"
    }
}
