package space.linuxct.glyphworks.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * This checks the keep rule exists. It cannot prove `AndroidPrefs.spListener` survives R8,
 * and neither can `mapping.txt`. Only the shipped DEX can, where the count must be 1:
 * `unzip -p app-release.apk classes.dex | strings | grep -c spListener`
 */
class AndroidPrefsKeepRuleTest {

    private fun proguardRules(): File {
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("../app/proguard-rules.pro"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("proguard-rules.pro not found from ${File(".").absolutePath}")
    }

    @Test
    fun theListenerFieldIsKeptFromR8() {
        val rules = proguardRules().readText()
        assertTrue(
            "The -keep rule for AndroidPrefs.spListener is gone. Without it R8 deletes " +
                "the field, SharedPreferences' WeakHashMap drops the listener at the next " +
                "GC, and every preference change stops being delivered — permanently, and " +
                "only in release builds. See AndroidPrefs.spListener.",
            rules.contains("spListener"),
        )
        assertTrue(
            "The keep rule no longer names AndroidPrefs, so it cannot be matching the field.",
            rules.contains("space.linuxct.glyphworks.util.AndroidPrefs"),
        )
    }

    @Test
    fun logCallsAreNotStripped() {
        val directives = proguardRules().readLines()
            .map { it.trim() }
            .filter { it.startsWith("-") }
        assertTrue(
            "-assumenosideeffects would strip DebugLog, which release builds keep on " +
                "purpose so field issues can be traced with `adb logcat -s GlyphWorks`.",
            directives.none { it.startsWith("-assumenosideeffects") },
        )
    }
}
