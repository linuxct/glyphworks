package space.linuxct.glyphworks.ai

import android.content.Context
import android.content.SharedPreferences

enum class AiGate {
    CONSENT,
    SIGN_IN,
    CHAT,
}

fun aiGate(consented: Boolean, signedIn: Boolean): AiGate = when {
    !consented -> AiGate.CONSENT
    !signedIn -> AiGate.SIGN_IN
    else -> AiGate.CHAT
}

interface AiConsentStorage {
    val accepted: Boolean

    fun accept()
}

class AiConsentStore(context: Context) : AiConsentStorage {

    private val app: Context

    init {
        val application = context.applicationContext
        check(!application.isDeviceProtectedStorage) {
            "AiConsentStore must be built from a credential-protected Context, " +
                "like the token and chat stores it sits beside"
        }
        app = application
    }

    // Direct Boot: Core.init can run while the device is locked, and the
    // credential-protected directory does not exist yet. Open the file on first use.
    private val sp: SharedPreferences by lazy {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override val accepted: Boolean get() = sp.getBoolean(KEY_ACCEPTED, false)

    // commit, not apply: the first request leaves the device on the same tap.
    @Suppress("ApplySharedPref")
    override fun accept() {
        sp.edit().putBoolean(KEY_ACCEPTED, true).commit()
    }

    private companion object {
        const val PREFS_NAME = "ai_consent"
        const val KEY_ACCEPTED = "AI_DISCLOSURE_ACCEPTED"
    }
}
