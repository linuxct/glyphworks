package space.linuxct.glyphworks.weather

import android.content.Context
import android.os.UserManager
import android.util.AtomicFile
import space.linuxct.glyphworks.core.weather.WeatherCacheCodec
import space.linuxct.glyphworks.core.weather.WeatherCacheEntry
import java.io.File
import java.io.IOException

/** Accessed only on the weather worker after unlock; noBackupFilesDir excludes all backups. */
internal class AndroidWeatherCache(private val app: Context) {
    private fun file(): AtomicFile? {
        if (app.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return null
        // Application context is credential-protected even when a Direct Boot receiver
        // initializes Core. Refuse a device-protected context rather than weakening storage.
        val credentialContext = app.applicationContext
        if (credentialContext.isDeviceProtectedStorage) return null
        return AtomicFile(File(credentialContext.noBackupFilesDir, "weather/current.json"))
    }

    fun load(nowMillis: Long): WeatherCacheEntry? = try {
        val file = file()
        if (file == null || !file.baseFile.exists()) null else {
            val bytes = file.openRead().use { it.readNBytes(WeatherCacheCodec.MAX_BYTES + 1) }
            val entry = if (bytes.size <= WeatherCacheCodec.MAX_BYTES)
                WeatherCacheCodec.decode(bytes.toString(Charsets.UTF_8), nowMillis) else null
            if (entry == null) file.delete()
            entry
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    fun save(entry: WeatherCacheEntry) {
        val file = try { file() } catch (_: IllegalStateException) { null } ?: return
        if (file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } != true) return
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(WeatherCacheCodec.encode(entry).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: IOException) {
            file.failWrite(stream)
        }
    }

    fun clear() {
        try { file()?.delete() } catch (_: IllegalStateException) { /* locked again / shutting down */ }
    }
}
