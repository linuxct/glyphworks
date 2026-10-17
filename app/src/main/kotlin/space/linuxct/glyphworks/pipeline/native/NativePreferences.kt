package space.linuxct.glyphworks.pipeline.native

import space.linuxct.glyphworks.core.Prefs
import space.linuxct.pipeline.Value

/** Read-through settings, local mutable state. Copies and simultaneous instances never write each other. */
class NativePreferences(
    private val defaults: Prefs,
    initial: Map<String, Value> = emptyMap(),
    private val readOverride: (String) -> Value? = { null },
    private val onWrite: (String, Value?) -> Unit = { _, _ -> },
) : Prefs {
    private val values = initial.toMutableMap()
    private val removed = mutableSetOf<String>()
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private fun current(key: String) = readOverride(key) ?: values[key]
    fun snapshot(): Map<String, Value> = values.toMap()
    fun set(key: String, value: Value) { values[key] = value; removed.remove(key); changed(key, value) }
    private fun changed(key: String, value: Value?) { onWrite(key, value); listeners.toList().forEach { it(key) } }
    override fun getBoolean(key: String, def: Boolean) = (current(key) as? Value.Bool)?.value ?: if (key in removed) def else defaults.getBoolean(key, def)
    override fun getInt(key: String, def: Int) = (current(key) as? Value.Number)?.value?.toInt() ?: if (key in removed) def else defaults.getInt(key, def)
    override fun getLong(key: String, def: Long) = (current(key) as? Value.Number)?.value?.toLong() ?: if (key in removed) def else defaults.getLong(key, def)
    override fun getFloat(key: String, def: Float) = (current(key) as? Value.Number)?.value?.toFloat() ?: if (key in removed) def else defaults.getFloat(key, def)
    override fun getString(key: String, def: String) = (current(key) as? Value.Text)?.value ?: if (key in removed) def else defaults.getString(key, def)
    override fun contains(key: String) = key in values || (key !in removed && defaults.contains(key))
    override fun remove(key: String) { values.remove(key); removed.add(key); changed(key, null) }
    override fun putBoolean(key: String, v: Boolean) = set(key, Value.Bool(v))
    override fun putInt(key: String, v: Int) = set(key, Value.Number(v.toDouble()))
    override fun putLong(key: String, v: Long) = set(key, Value.Number(v.toDouble()))
    override fun putFloat(key: String, v: Float) = set(key, Value.Number(v.toDouble()))
    override fun putString(key: String, v: String) = set(key, Value.Text(v))
    override fun addChangeListener(listener: (String) -> Unit) { listeners += listener }
    override fun removeChangeListener(listener: (String) -> Unit) { listeners -= listener }
}
