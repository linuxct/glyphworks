package space.linuxct.glyphworks.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.ChatRequest
import space.linuxct.glyphworks.core.ai.ChatStreamResult
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.GlyphChatClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// XOR-0x5E encoded, decoded by `d` in OpenAIOAuth.kt.
private val OAUTH_URL = d(
    intArrayOf(
        54, 42, 42, 46, 45, 100, 113, 113, 61, 54, 63, 42, 57, 46, 42, 112, 61, 49, 51, 113, 60,
        63, 61, 53, 59, 48, 58, 115, 63, 46, 55, 113, 61, 49, 58, 59, 38, 113, 44, 59, 45, 46,
        49, 48, 45, 59, 45,
    ),
)

private val OAUTH_HEADERS = mapOf(
    d(intArrayOf(17, 46, 59, 48, 31, 23, 115, 28, 59, 42, 63)) to
        d(intArrayOf(44, 59, 45, 46, 49, 48, 45, 59, 45, 99, 59, 38, 46, 59, 44, 55, 51, 59, 48, 42, 63, 50)),
    d(intArrayOf(49, 44, 55, 57, 55, 48, 63, 42, 49, 44)) to
        d(intArrayOf(61, 49, 58, 59, 38, 1, 61, 50, 55, 1, 44, 45)),
)

/** Streams the Responses API over the Codex OAuth backend. The grammar is in ChatWire. */
class GlyphAiClient(
    private val tokens: TokenStore,
    private val url: String = OAUTH_URL,
    private val headers: Map<String, String> = OAUTH_HEADERS,
) : GlyphChatClient {

    override suspend fun respond(
        request: ChatRequest,
        onTextDelta: ((String) -> Unit)?,
    ): ChatStreamResult = withContext(Dispatchers.IO) {
        val body = ChatWire.encodeRequest(request)
        val access = accessToken()
        try {
            execute(body, access, onTextDelta)
        } catch (e: UnauthorizedException) {
            val refreshed = refreshAccessToken()
                ?: throw IOException("Signed out: please sign in again.", e)
            execute(body, refreshed, onTextDelta)
        }
    }

    private suspend fun accessToken(): String {
        val stored = tokens.accessToken
        if (!stored.isNullOrBlank() && tokens.hasFreshAccessToken()) return stored
        return refreshAccessToken()
            // A stale token is worth trying: the local clock may be wrong.
            ?: stored?.takeIf { it.isNotBlank() }
            ?: throw IOException("Not signed in.")
    }

    private suspend fun refreshAccessToken(): String? {
        val refresh = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val issued = refreshOAuthToken(refresh)
            tokens.save(issued)
            issued.accessToken
        } catch (e: Exception) {
            DebugLog.w(TAG, "token refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun execute(
        body: String,
        accessToken: String,
        onTextDelta: ((String) -> Unit)?,
    ): ChatStreamResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // The reason is in errorStream; reading inputStream here throws.
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                DebugLog.w(TAG, "HTTP $code from the assistant backend: ${text.take(ERROR_SNIPPET)}")
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException(text)
                throw IOException("The assistant service returned $code. ${text.take(ERROR_SNIPPET)}".trim())
            }

            return conn.inputStream.bufferedReader().use { reader ->
                ChatWire.assemble(ChatWire.parseSse(reader.lineSequence()), onTextDelta)
            }
        } finally {
            conn.disconnect()
        }
    }

    private class UnauthorizedException(body: String) :
        IOException("The assistant service rejected the sign-in. ${body.take(ERROR_SNIPPET)}".trim())

    companion object {
        private const val TAG = "GlyphAiClient"

        private const val READ_TIMEOUT_MS = 180_000
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val ERROR_SNIPPET = 300
    }
}
