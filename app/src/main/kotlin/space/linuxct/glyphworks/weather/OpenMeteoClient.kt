package space.linuxct.glyphworks.weather

import space.linuxct.glyphworks.core.weather.OpenMeteoResponse
import space.linuxct.glyphworks.core.weather.WeatherLocation
import space.linuxct.glyphworks.core.weather.WeatherObservation
import space.linuxct.glyphworks.core.weather.WeatherPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

sealed interface WeatherFetchResult {
    data class Success(val observation: WeatherObservation) : WeatherFetchResult
    data class Failure(val retryAfterMillis: Long = 0) : WeatherFetchResult
}

/** Blocking transport, only used by the dedicated network executor. Never logs coordinates. */
class OpenMeteoClient(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    fun newCall(location: WeatherLocation) = Call(location)

    inner class Call internal constructor(private val location: WeatherLocation) {
        @Volatile private var cancelled = false
        @Volatile private var connection: HttpsURLConnection? = null

        /** Interrupting a Future alone need not unblock HttpURLConnection's socket read. */
        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }

        fun execute(): WeatherFetchResult {
            if (cancelled) return WeatherFetchResult.Failure()
            var opened: HttpsURLConnection? = null
            return try {
                val conn = open(URL(OpenMeteoResponse.requestUrl(location)))
                opened = conn
                connection = conn
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GlyphWorks/3.3.1 (+https://github.com/linuxct/glyphworks)")
                if (cancelled) return WeatherFetchResult.Failure()
                val status = conn.responseCode
                if (status != 200) {
                    val retry = if (status == 429 || status == 503)
                        retryAfterMillis(conn.getHeaderField("Retry-After"), nowMillis()) else 0
                    return WeatherFetchResult.Failure(if (status == 429)
                        retry.coerceAtLeast(WeatherPolicy.REFRESH_MS) else retry)
                }
                if (conn.contentLengthLong > WeatherPolicy.MAX_RESPONSE_BYTES) return WeatherFetchResult.Failure()
                val bytes = conn.inputStream.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        if (cancelled || Thread.currentThread().isInterrupted) return WeatherFetchResult.Failure()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > WeatherPolicy.MAX_RESPONSE_BYTES) return WeatherFetchResult.Failure()
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                if (cancelled) return WeatherFetchResult.Failure()
                OpenMeteoResponse.parse(bytes.toString(Charsets.UTF_8), nowMillis())
                    ?.let { WeatherFetchResult.Success(it) } ?: WeatherFetchResult.Failure()
            } catch (_: IOException) {
                WeatherFetchResult.Failure()
            } catch (_: IllegalArgumentException) {
                WeatherFetchResult.Failure()
            } catch (_: SecurityException) {
                WeatherFetchResult.Failure()
            } finally {
                connection = null
                opened?.disconnect()
            }
        }
    }

    companion object {
        internal fun retryAfterMillis(value: String?, nowMillis: Long): Long {
            val header = value?.trim() ?: return 0
            val seconds = header.toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0, WeatherPolicy.MAX_RETRY_AFTER_MS / 1000) * 1000
            return try {
                (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
                    .coerceIn(0, WeatherPolicy.MAX_RETRY_AFTER_MS)
            } catch (_: RuntimeException) {
                0
            }
        }
    }
}
