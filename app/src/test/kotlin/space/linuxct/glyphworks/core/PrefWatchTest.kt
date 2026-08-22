package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefWatchTest {
    private class DedupingPrefs : Prefs {
        private val map = mutableMapOf<String, Any>()
        private val listeners = mutableListOf<(String) -> Unit>()

        val listenerCount: Int get() = listeners.size

        override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
        override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = map[key] as? Float ?: def
        override fun getString(key: String, def: String) = map[key] as? String ?: def
        override fun contains(key: String) = map.containsKey(key)

        override fun remove(key: String) {
            if (map.remove(key) != null) notify(key)
        }

        private fun put(key: String, v: Any) {
            if (map[key] == v) return // SharedPreferencesImpl.commitToMemory skips equal values
            map[key] = v
            notify(key)
        }

        private fun notify(key: String) = listeners.toList().forEach { it(key) }

        override fun putBoolean(key: String, v: Boolean) = put(key, v)
        override fun putInt(key: String, v: Int) = put(key, v)
        override fun putLong(key: String, v: Long) = put(key, v)
        override fun putFloat(key: String, v: Float) = put(key, v)
        override fun putString(key: String, v: String) = put(key, v)

        override fun addChangeListener(listener: (String) -> Unit) {
            listeners += listener
        }

        override fun removeChangeListener(listener: (String) -> Unit) {
            listeners -= listener
        }
    }

    private val key = PrefKeys.CURRENT_SCREEN
    private val def = PrefKeys.CURRENT_SCREEN_DEF

    private fun watchOf(prefs: Prefs, seen: MutableList<String>) =
        PrefWatch(prefs, key, { it.getString(key, def) }) { seen += it }

    @Test
    fun `starting delivers the value as it stands`() {
        val prefs = DedupingPrefs()
        prefs.putString(key, "clock")
        val seen = mutableListOf<String>()
        watchOf(prefs, seen).start()
        assertEquals(listOf("clock"), seen)
    }

    @Test
    fun `changes arrive while watching`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        watchOf(prefs, seen).start()
        prefs.putString(key, "clock")
        prefs.putString(key, "eyes")
        assertEquals(listOf(def, "clock", "eyes"), seen)
    }

    @Test
    fun `other keys are ignored`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        watchOf(prefs, seen).start()
        prefs.putString(PrefKeys.SCREEN_ORDER, "dice,clock")
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        assertEquals(listOf(def), seen)
    }

    @Test
    fun `stopping ends delivery`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        val watch = watchOf(prefs, seen)
        watch.start()
        watch.stop()
        prefs.putString(key, "clock")
        assertEquals(listOf(def), seen)
    }

    @Test
    fun `resubscribing catches up on what it missed`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        val watch = watchOf(prefs, seen)

        watch.start()
        assertEquals(listOf(def), seen)

        watch.stop()
        prefs.putString(key, "clock")
        assertEquals("nothing may be delivered while stopped", listOf(def), seen)

        watch.start()
        assertEquals(listOf(def, "clock"), seen)
    }
}
