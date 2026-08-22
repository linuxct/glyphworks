package space.linuxct.glyphworks.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** XOR-0x5E decoder. Not a secret: it keeps these URLs out of a `strings` sweep. */
internal fun d(v: IntArray) = v.map { (it xor 0x5E).toChar() }.joinToString("")

private val CLIENT_ID     = d(intArrayOf(63, 46, 46, 1, 27, 19, 49, 63, 51, 27, 27, 4, 105, 109, 56, 110, 29, 53, 6, 63, 6, 46, 105, 54, 44, 63, 48, 48))
private val AUTHORIZE_URL = d(intArrayOf(54, 42, 42, 46, 45, 100, 113, 113, 63, 43, 42, 54, 112, 49, 46, 59, 48, 63, 55, 112, 61, 49, 51, 113, 49, 63, 43, 42, 54, 113, 63, 43, 42, 54, 49, 44, 55, 36, 59))
private val TOKEN_URL     = d(intArrayOf(54, 42, 42, 46, 45, 100, 113, 113, 63, 43, 42, 54, 112, 49, 46, 59, 48, 63, 55, 112, 61, 49, 51, 113, 49, 63, 43, 42, 54, 113, 42, 49, 53, 59, 48))
private val SCOPE         = d(intArrayOf(49, 46, 59, 48, 55, 58, 126, 46, 44, 49, 56, 55, 50, 59, 126, 59, 51, 63, 55, 50, 126, 49, 56, 56, 50, 55, 48, 59, 1, 63, 61, 61, 59, 45, 45))
val OAUTH_REDIRECT_URI    = d(intArrayOf(54, 42, 42, 46, 100, 113, 113, 50, 49, 61, 63, 50, 54, 49, 45, 42, 100, 111, 106, 107, 107, 113, 63, 43, 42, 54, 113, 61, 63, 50, 50, 60, 63, 61, 53))

data class OAuthFlow(
    val url: String,
    val state: String,
    val verifier: String
)

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int
)

fun createOAuthFlow(): OAuthFlow {
    val verifier  = newVerifier()
    val challenge = codeChallenge(verifier)
    val state     = newState()

    val url = authorizeUrl(
        "response_type"              to "code",
        "client_id"                  to CLIENT_ID,
        "redirect_uri"               to OAUTH_REDIRECT_URI,
        "scope"                      to SCOPE,
        "code_challenge"             to challenge,
        "code_challenge_method"      to "S256",
        "state"                      to state,
        d(intArrayOf(55, 58, 1, 42, 49, 53, 59, 48, 1, 63, 58, 58, 1, 49, 44, 57, 63, 48, 55, 36, 63, 42, 55, 49, 48, 45)) to "true",
        d(intArrayOf(61, 49, 58, 59, 38, 1, 61, 50, 55, 1, 45, 55, 51, 46, 50, 55, 56, 55, 59, 58, 1, 56, 50, 49, 41)) to "true",
        d(intArrayOf(49, 44, 55, 57, 55, 48, 63, 42, 49, 44)) to d(intArrayOf(61, 49, 58, 59, 38, 1, 61, 50, 55, 1, 44, 45)),
    )

    return OAuthFlow(url = url, state = state, verifier = verifier)
}

private fun authorizeUrl(vararg params: Pair<String, String>): String =
    params.joinToString(
        separator = "&",
        prefix = AUTHORIZE_URL + if (AUTHORIZE_URL.contains('?')) "&" else "?",
    ) { (key, value) -> "${uriEncode(key)}=${uriEncode(value)}" }

/**
 * `android.net.Uri.encode(s, null)`, character for character. Not `URLEncoder`: that is
 * form encoding, and it writes a space as `+` where the authorize URL needs `%20`. One
 * character of drift is a sign-in that fails with nothing to see.
 */
private fun uriEncode(value: String): String {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        if (isUriAllowed(value[i])) {
            out.append(value[i])
            i++
            continue
        }
        var end = i
        while (end < value.length && !isUriAllowed(value[end])) end++
        for (byte in value.substring(i, end).toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt()
            out.append('%').append(HEX_DIGITS[(b shr 4) and 0xF]).append(HEX_DIGITS[b and 0xF])
        }
        i = end
    }
    return out.toString()
}

private fun isUriAllowed(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in URI_UNRESERVED

// RFC 2396 unreserved, in AOSP's own order.
private const val URI_UNRESERVED = "_-!.~'()*"

private const val HEX_DIGITS = "0123456789ABCDEF"

suspend fun exchangeAuthorizationCode(code: String, verifier: String): OAuthTokens =
    requestToken(
        "grant_type"    to "authorization_code",
        "client_id"     to CLIENT_ID,
        "code"          to code,
        "code_verifier" to verifier,
        "redirect_uri"  to OAUTH_REDIRECT_URI
    )

suspend fun refreshOAuthToken(refreshToken: String): OAuthTokens =
    requestToken(
        "grant_type"    to "refresh_token",
        "client_id"     to CLIENT_ID,
        "refresh_token" to refreshToken
    )

private const val TOKEN_TIMEOUT_MS = 20_000

@Serializable
internal data class TokenResponseJson(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
)

private val tokenJson = Json { ignoreUnknownKeys = true }

internal fun parseTokenResponse(body: String): OAuthTokens {
    val parsed = try {
        tokenJson.decodeFromString(TokenResponseJson.serializer(), body)
    } catch (e: Exception) {
        error("Token response was not valid JSON: ${e.message ?: e.javaClass.simpleName}")
    }
    val access = parsed.accessToken?.takeIf { it.isNotBlank() }
        ?: error("Token response did not contain access_token")
    val refresh = parsed.refreshToken?.takeIf { it.isNotBlank() }
        ?: error("Token response did not contain refresh_token")
    val expires = parsed.expiresIn
        ?: error("Token response did not contain expires_in")
    return OAuthTokens(accessToken = access, refreshToken = refresh, expiresIn = expires)
}

private suspend fun requestToken(vararg params: Pair<String, String>): OAuthTokens =
    withContext(Dispatchers.IO) {
        val body = params.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TOKEN_TIMEOUT_MS
            conn.readTimeout = TOKEN_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // The reason is in errorStream; reading inputStream on a 4xx throws.
            val stream = if (code == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code != HttpURLConnection.HTTP_OK) error("Token request failed $code: $text")
            if (text.isBlank()) error("Empty token response")
            parseTokenResponse(text)
        } finally {
            conn.disconnect()
        }
    }

private fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun newVerifier(): String {
    val bytes = ByteArray(VERIFIER_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun codeChallenge(verifier: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
}

private fun newState(): String {
    val bytes = ByteArray(STATE_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private const val VERIFIER_BYTES = 32
private const val STATE_BYTES = 16