package space.linuxct.glyphworks.key

class ClickCounter(private val windowMs: Long = WINDOW_MS) {

    private var count = 0
    private var lastPressAt = 0L

    fun onPress(now: Long): Int {
        if (now - lastPressAt > windowMs) count = 0
        count++
        lastPressAt = now
        return count
    }

    fun finish(): Int {
        val burstTotal = count
        count = 0
        return burstTotal
    }

    companion object {
        const val WINDOW_MS = 400L
    }
}
