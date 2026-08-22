package space.linuxct.glyphworks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.core.PrefWatch
import space.linuxct.glyphworks.core.Prefs

@Composable
internal fun <T> rememberPref(key: String, read: (Prefs) -> T): State<T> {
    val state = remember(key) { mutableStateOf(read(Core.prefs)) }
    DisposableEffect(key) {
        val watch = PrefWatch(Core.prefs, key, read) { state.value = it }
        watch.start()
        onDispose { watch.stop() }
    }
    return state
}
