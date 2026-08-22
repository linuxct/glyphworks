package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class OpenAIOAuthTest {

    @Test
    fun `code challenge is the unpadded base64url sha256 of the verifier`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", codeChallenge(verifier))
    }

    @Test
    fun `verifier is 43 url-safe characters`() {
        repeat(20) {
            val verifier = newVerifier()
            assertEquals(verifier, 43, verifier.length)
            assertTrue(verifier, verifier.all { it in BASE64URL })
        }
    }

    @Test
    fun `state is 32 lower-case hex characters`() {
        repeat(20) {
            val state = newState()
            assertEquals(state, 32, state.length)
            assertTrue(state, state.all { it in HEX })
        }
    }

    @Test
    fun `authorize url carries each query parameter exactly once`() {
        val flow = createOAuthFlow()
        val keys = queryPairs(flow.url).map { it.first }

        val required = listOf(
            "response_type",
            "client_id",
            "redirect_uri",
            "scope",
            "code_challenge",
            "code_challenge_method",
            "state",
        )
        required.forEach { key ->
            assertEquals("parameter $key", 1, keys.count { it == key })
        }
        assertEquals("no parameter may be repeated", keys.size, keys.toSet().size)
        assertEquals("unexpected number of query parameters", 10, keys.size)
    }

    @Test
    fun `the authorize url is the endpoint, a question mark, and the parameters in order`() {
        val flow = createOAuthFlow()
        val head = constant("AUTHORIZE_URL") + "?" + listOf(
            "response_type" to "code",
            "client_id" to constant("CLIENT_ID"),
            "redirect_uri" to OAUTH_REDIRECT_URI,
            "scope" to constant("SCOPE"),
            "code_challenge" to codeChallenge(flow.verifier),
            "code_challenge_method" to "S256",
            "state" to flow.state,
        ).joinToString("&") { (key, value) -> "${uriEncode(key)}=${uriEncode(value)}" }

        assertTrue(flow.url, flow.url.startsWith(head))
        val rest = flow.url.removePrefix(head)
        assertTrue(rest, rest.startsWith("&"))
        val tail = rest.drop(1).split("&")
        assertEquals(rest, 3, tail.size)
        tail.forEach { pair -> assertTrue(pair, ESCAPED_PAIR.matches(pair)) }
    }

    @Test
    fun `spaces in the scope are percent-encoded, never plus-encoded`() {
        val scope = rawQueryValue(createOAuthFlow().url, "scope")
        assertTrue(scope, scope.contains("%20"))
        assertFalse(scope, scope.contains("+"))
    }

    @Test
    fun `two flows share no state or verifier`() {
        val a = createOAuthFlow()
        val b = createOAuthFlow()
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.verifier, b.verifier)
    }

    @Test
    fun `a well-formed token response parses`() {
        val tokens = parseTokenResponse(
            """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
        )
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(3600, tokens.expiresIn)
    }

    @Test
    fun `a missing field names itself`() {
        assertFailsWithMessage("access_token") {
            parseTokenResponse("""{"refresh_token":"rt","expires_in":3600}""")
        }
        assertFailsWithMessage("refresh_token") {
            parseTokenResponse("""{"access_token":"at","expires_in":3600}""")
        }
        assertFailsWithMessage("expires_in") {
            parseTokenResponse("""{"access_token":"at","refresh_token":"rt"}""")
        }
    }

    @Test
    fun `malformed json fails with a readable message`() {
        listOf(
            "<html><body>502 Bad Gateway</body></html>",
            """{"access_token":"at","refresh_token":""",
            "",
            """{"access_token":{"nested":true},"refresh_token":"rt","expires_in":3600}""",
            """{"access_token":"at","refresh_token":"rt","expires_in":"soon"}""",
        ).forEach { body ->
            assertFailsWithMessage("not valid JSON", body) { parseTokenResponse(body) }
        }
    }

    private fun queryPairs(url: String): List<Pair<String, String>> =
        url.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }

    private fun rawQueryValue(url: String, key: String): String =
        url.substringAfter('?', "")
            .split('&')
            .first { it.substringBefore('=') == key }
            .substringAfter('=', "")

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun assertFailsWithMessage(needle: String, label: String = needle, block: () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (e: IllegalStateException) {
            e
        }
        val message = thrown?.message
        assertTrue(
            "expected a message mentioning \"$needle\" for <$label>, got ${thrown?.javaClass?.simpleName}: $message",
            message != null && message.contains(needle),
        )
    }

    private companion object {
        val BASE64URL = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        val HEX = ('0'..'9') + ('a'..'f')

        val ESCAPED_PAIR = Regex("""[A-Za-z0-9_\-!.~'()*%]+=[A-Za-z0-9_\-!.~'()*%]+""")

        val facade: Class<*> = Class.forName("space.linuxct.glyphworks.ai.OpenAIOAuthKt")

        fun invoke(name: String, vararg args: Any?): Any {
            val method = facade.declaredMethods.single { it.name == name }
            method.isAccessible = true
            return checkNotNull(method.invoke(null, *args)) { "$name returned null" }
        }

        fun constant(name: String): String {
            val field = facade.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as String
        }

        fun newVerifier(): String = invoke("newVerifier") as String
        fun newState(): String = invoke("newState") as String
        fun codeChallenge(verifier: String): String = invoke("codeChallenge", verifier) as String
        fun uriEncode(value: String): String = invoke("uriEncode", value) as String
    }
}
