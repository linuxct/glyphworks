package space.linuxct.glyphworks.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread

/** The OpenAI sign-in, in credential-protected storage. */
class TokenStore(context: Context) {

    private val sp: SharedPreferences

    init {
        val app = context.applicationContext
        check(!app.isDeviceProtectedStorage) {
            "TokenStore must be built from a credential-protected Context; " +
                "an OAuth token must not be readable before the first unlock"
        }
        sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val refreshToken: String? get() = sp.getString(KEY_REFRESH_TOKEN, null)

    val accessToken: String? get() = sp.getString(KEY_ACCESS_TOKEN, null)

    val accessTokenExpiresAtMs: Long get() = sp.getLong(KEY_EXPIRES_AT, 0L)

    val isSignedIn: Boolean get() = !refreshToken.isNullOrBlank()

    fun hasFreshAccessToken(nowMs: Long = System.currentTimeMillis()): Boolean =
        !accessToken.isNullOrBlank() && accessTokenExpiresAtMs - EXPIRY_MARGIN_MS > nowMs

    // commit, not apply: the sign-in dialog reports success once this returns.
    @Suppress("ApplySharedPref")
    @WorkerThread
    fun save(tokens: OAuthTokens, nowMs: Long = System.currentTimeMillis()) {
        sp.edit()
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putLong(KEY_EXPIRES_AT, nowMs + tokens.expiresIn * 1000L)
            .commit()
    }

    // commit, not apply: a sign-out that has not reached disk leaves a credential behind.
    @Suppress("ApplySharedPref")
    @WorkerThread
    fun clear() {
        sp.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "openai_auth"

        const val KEY_REFRESH_TOKEN = "OPENAI_REFRESH_TOKEN"
        private const val KEY_ACCESS_TOKEN = "OPENAI_ACCESS_TOKEN"
        private const val KEY_EXPIRES_AT = "OPENAI_ACCESS_TOKEN_EXPIRES_AT"

        private const val EXPIRY_MARGIN_MS = 60_000L
    }
}
