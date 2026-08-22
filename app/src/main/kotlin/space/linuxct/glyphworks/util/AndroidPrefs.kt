package space.linuxct.glyphworks.util

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.annotation.Keep
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings live in device-protected storage, so the direct-boot-aware services read them
 * before the first unlock. Nothing stored here is sensitive.
 */
class AndroidPrefs(context: Context) : Prefs {

    private val sp: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * SharedPreferences holds its listeners weakly, so this field is the only strong
     * reference. R8 once deleted it as written-but-never-read and every preference
     * notification in the process stopped. [Keep] plus a `-keep` in `proguard-rules.pro`
     * hold it, and `AndroidPrefsKeepRuleTest` fails if that rule goes. To check a release
     * build: `unzip -p app-release.apk classes.dex | strings | grep -c spListener` must say 1.
     */
    @Keep
    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) listeners.forEach { it(key) }
    }.also { sp.registerOnSharedPreferenceChangeListener(it) }

    init {
        if (!sp.contains(PrefKeys.USE_12H)) {
            sp.edit().putBoolean(PrefKeys.USE_12H, !DateFormat.is24HourFormat(context)).apply()
        }
        if (!sp.contains(PrefKeys.PREFS_VERSION)) {
            sp.edit().putInt(PrefKeys.PREFS_VERSION, PrefKeys.PREFS_VERSION_DEF).apply()
        }
    }

    override fun getBoolean(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    override fun getInt(key: String, def: Int): Int = sp.getInt(key, def)
    override fun getLong(key: String, def: Long): Long = sp.getLong(key, def)
    override fun getFloat(key: String, def: Float): Float = sp.getFloat(key, def)
    override fun getString(key: String, def: String): String = sp.getString(key, def) ?: def
    override fun contains(key: String): Boolean = sp.contains(key)

    override fun remove(key: String) = sp.edit().remove(key).apply()

    override fun putBoolean(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    override fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    override fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    override fun putFloat(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    override fun putString(key: String, v: String) {
        val valueIsUnchanged = sp.contains(key) && sp.getString(key, null) == v
        if (valueIsUnchanged) {
            DebugLog.d("Prefs", "putString $key = '$v' is unchanged — no listener will fire")
        }
        sp.edit().putString(key, v).apply()
    }

    override fun addChangeListener(listener: (String) -> Unit) {
        listeners += listener
    }

    override fun removeChangeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}
