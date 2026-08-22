package space.linuxct.glyphworks.key

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.ui.screenDisplayName

/** Settings.Secure stores this class's component name. Never rename or move it. */
class EssentialKeyService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val counter = ClickCounter()
    private var expiry: Runnable? = null
    @Volatile private var lastConsumedPressAt = 0L

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        DebugLog.i(C, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Core.init(this)
        logAndEnsureKeyFilterFlag()
        Core.prefs.putLong(PrefKeys.SERVICE_HEARTBEAT, System.currentTimeMillis())
        Core.router.onAction = ::announce
        Core.arbiter.revive()
        DebugLog.i(C, "onServiceConnected: heartbeat written, session revived")
    }

    private fun announce(clicks: Int, action: KeyAction, screenId: String) {
        if (!Core.prefs.getBoolean(PrefKeys.KEY_ACTION_TOASTS, PrefKeys.KEY_ACTION_TOASTS_DEF)) return
        mainHandler.post {
            val presses = resources.getQuantityString(R.plurals.key_action_presses, clicks, clicks)
            val toy = screenDisplayName(this, screenId)
            val text = when (action) {
                KeyAction.TOY_ACTION -> getString(R.string.key_action_toy, presses, toy)
                KeyAction.NEXT_TOY -> getString(R.string.key_action_next, presses, toy)
                KeyAction.HOME -> getString(R.string.key_action_home, presses, toy)
                KeyAction.MENU_OPEN -> getString(R.string.key_action_menu_open, presses)
                KeyAction.MENU_PREVIEW_NEXT -> getString(R.string.key_action_menu_next, presses, toy)
                KeyAction.MENU_COMMIT -> getString(R.string.key_action_menu_commit, presses, toy)
                KeyAction.SWALLOWED -> getString(R.string.key_action_swallowed, presses)
                KeyAction.IGNORED -> getString(R.string.key_action_ignored, presses)
            }
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** Without FLAG_REQUEST_FILTER_KEY_EVENTS onKeyEvent never fires, and some builds drop the XML flag. */
    private fun logAndEnsureKeyFilterFlag() {
        val info = serviceInfo
        if (info == null) {
            DebugLog.w(C, "serviceInfo is null — cannot verify key-filter flag!")
            return
        }
        val hasFlag = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        val hasCapability = info.capabilities and
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0
        DebugLog.i(
            C,
            "serviceInfo: flags=0x${Integer.toHexString(info.flags)} " +
                "capabilities=0x${Integer.toHexString(info.capabilities)} " +
                "keyFilterCapability=$hasCapability " +
                "FLAG_REQUEST_FILTER_KEY_EVENTS=$hasFlag",
        )
        if (!hasFlag) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            DebugLog.i(C, "re-applied FLAG_REQUEST_FILTER_KEY_EVENTS -> flags=0x${Integer.toHexString(serviceInfo?.flags ?: 0)}")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val desc = "action=${event.action} keyCode=${event.keyCode} scanCode=${event.scanCode} " +
            "repeat=${event.repeatCount} device=${event.deviceId}"
        if (event.scanCode !in KNOWN_SCAN_CODES) {
            DebugLog.d(C, "key ignored (scanCode not in ${KNOWN_SCAN_CODES.contentToString()}): $desc")
            return super.onKeyEvent(event)
        }
        if (!Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)) {
            DebugLog.i(C, "essential key PASS-THROUGH (master toggle off): $desc")
            return super.onKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            vibrate()
            Core.prefs.putLong(PrefKeys.SERVICE_HEARTBEAT, System.currentTimeMillis())
            lastConsumedPressAt = SystemClock.uptimeMillis()
            val pressNumber = counter.onPress(SystemClock.uptimeMillis())
            DebugLog.i(C, "essential key DOWN consumed: press #$pressNumber in burst ($desc)")
            restartClickWindow()
        } else {
            DebugLog.d(C, "essential key consumed (up/repeat): $desc")
        }
        // An unconsumed UP still triggers Essential Space, so consume UP and repeats too.
        return true
    }

    private fun restartClickWindow() {
        expiry?.let(mainHandler::removeCallbacks)
        val closeWindow = Runnable {
            expiry = null
            val clicks = counter.finish()
            DebugLog.i(C, "click window closed: $clicks click(s) -> routing")
            Core.router.execute(clicks)
        }
        expiry = closeWindow
        mainHandler.postDelayed(closeWindow, ClickCounter.WINDOW_MS)
    }

    // Some firmware starts Essential Space at input queueing, before the key filter runs,
    // so consuming the key cannot stop it. Dismiss the window it opens instead.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in ESSENTIAL_PACKAGES) return
        val sincePress = SystemClock.uptimeMillis() - lastConsumedPressAt
        DebugLog.i(C, "$pkg window appeared (${event.className}) ${sincePress} ms after last consumed press")
        if (!Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)) return
        if (lastConsumedPressAt == 0L || sincePress > LEAK_WINDOW_MS) {
            DebugLog.d(C, "not correlated with a consumed press; leaving it alone")
            return
        }
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (Core.prefs.getBoolean(PrefKeys.KEY_ACTION_TOASTS, PrefKeys.KEY_ACTION_TOASTS_DEF)) {
            mainHandler.post {
                Toast.makeText(this, R.string.key_action_dismissed_essential, Toast.LENGTH_SHORT).show()
            }
        }
        mainHandler.postDelayed({
            val action = if (locked) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK
            DebugLog.i(C, "dismissing $pkg leak via ${if (locked) "HOME" else "BACK"}")
            performGlobalAction(action)
        }, DISMISS_DELAY_MS)
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val clickSupported = vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) ==
                android.os.Vibrator.VIBRATION_EFFECT_SUPPORT_YES
            val effect = if (clickSupported) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(FALLBACK_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            DebugLog.w(C, "vibrate failed: ${e.message}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        DebugLog.w(C, "onUnbind (service disabled or system rebinding)")
        Core.router.onAction = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val C = "KeySvc"

        // The key is unmapped, so it arrives as KEYCODE_UNKNOWN and only the scan code
        // identifies it: 250 on the Phone (4a) Pro, 304 on earlier revisions.
        private const val SCAN_CODE_4A_PRO = 250
        private const val SCAN_CODE_LEGACY = 304
        val KNOWN_SCAN_CODES = intArrayOf(SCAN_CODE_4A_PRO, SCAN_CODE_LEGACY)

        // Keep in sync with android:packageNames in accessibility_service_config.xml.
        val ESSENTIAL_PACKAGES = setOf(
            "com.nothing.ntessentialspace",
            "com.nothing.ntessentialrecorder",
        )

        const val LEAK_WINDOW_MS = 3000L
        private const val DISMISS_DELAY_MS = 150L
        private const val FALLBACK_BUZZ_MS = 50L
    }
}
