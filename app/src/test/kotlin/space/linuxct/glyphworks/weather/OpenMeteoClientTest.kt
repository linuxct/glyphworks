package space.linuxct.glyphworks.weather

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.core.weather.WeatherLocation
import space.linuxct.glyphworks.core.weather.WeatherPolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

class OpenMeteoClientTest {
    private val now = 1_783_000_000_000L
    private val fix = WeatherLocation.create(40.41689, -3.70381, now)!!
    private val body = """{"current_units":{"time":"unixtime","temperature_2m":"°C"},"current":{"time":${now / 1000},"temperature_2m":22.5,"weather_code":0,"is_day":1}}"""

    private class Connection(
        private val status: Int = 200,
        private val bytes: ByteArray = byteArrayOf(),
        private val length: Long = bytes.size.toLong(),
        private val retry: String? = null,
        private val timeout: Boolean = false,
    ) : HttpsURLConnection(URL("https://api.open-meteo.com/v1/forecast")) {
        var disconnects = 0
        var reads = 0
        override fun connect() = Unit
        override fun disconnect() { disconnects++ }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_256_GCM_SHA384"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?): String? = if (name == "Retry-After") retry else null
        override fun getInputStream(): InputStream {
            reads++
            if (timeout) throw SocketTimeoutException("test timeout")
            return ByteArrayInputStream(bytes)
        }
    }

    @Test fun `transport requests bounded JSON with timeouts and always disconnects`() {
        val connection = Connection(bytes = body.toByteArray())
        val result = OpenMeteoClient({ now }) { connection }.newCall(fix).execute()
        assertTrue(result is WeatherFetchResult.Success)
        assertEquals(1, connection.disconnects)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
    }

    @Test fun `rate limit applies provider Retry After and defaults to fifteen minutes`() {
        val limited = Connection(status = 429, retry = "1800")
        val result = OpenMeteoClient({ now }) { limited }.newCall(fix).execute() as WeatherFetchResult.Failure
        assertEquals(30 * 60_000L, result.retryAfterMillis)
        assertEquals(0, limited.reads)
        val noHeader = OpenMeteoClient({ now }) { Connection(status = 429) }.newCall(fix).execute() as WeatherFetchResult.Failure
        assertEquals(WeatherPolicy.REFRESH_MS, noHeader.retryAfterMillis)
    }

    @Test fun `retry after supports HTTP date malformed and bounded enormous values`() {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 120_000).atZone(ZoneOffset.UTC))
        assertEquals(120_000L, OpenMeteoClient.retryAfterMillis(date, now))
        assertEquals(0L, OpenMeteoClient.retryAfterMillis("not a date", now))
        assertEquals(0L, OpenMeteoClient.retryAfterMillis("-20", now))
        assertEquals(WeatherPolicy.MAX_RETRY_AFTER_MS, OpenMeteoClient.retryAfterMillis("${Long.MAX_VALUE}", now))
    }

    @Test fun `declared oversized response is rejected before reading body`() {
        val connection = Connection(length = WeatherPolicy.MAX_RESPONSE_BYTES + 1L)
        assertTrue(OpenMeteoClient({ now }) { connection }.newCall(fix).execute() is WeatherFetchResult.Failure)
        assertEquals(0, connection.reads)
        assertEquals(1, connection.disconnects)
    }

    @Test fun `unknown length oversized stream is bounded and closed`() {
        val connection = Connection(bytes = ByteArray(WeatherPolicy.MAX_RESPONSE_BYTES + 1) { 32 }, length = -1)
        assertTrue(OpenMeteoClient({ now }) { connection }.newCall(fix).execute() is WeatherFetchResult.Failure)
        assertEquals(1, connection.reads)
        assertEquals(1, connection.disconnects)
    }

    @Test fun `timeouts malformed JSON and redirects return unavailable without escaping transport`() {
        listOf(Connection(timeout = true), Connection(bytes = "{".toByteArray()), Connection(status = 302))
            .forEach { connection ->
                assertTrue(OpenMeteoClient({ now }) { connection }.newCall(fix).execute() is WeatherFetchResult.Failure)
                assertEquals(1, connection.disconnects)
            }
    }

    @Test fun `canceled call does not open a connection or leak a successful result`() {
        var opened = false
        val call = OpenMeteoClient({ now }) { opened = true; Connection(bytes = body.toByteArray()) }.newCall(fix)
        call.cancel()
        assertTrue(call.execute() is WeatherFetchResult.Failure)
        assertFalse(opened)
    }
}
