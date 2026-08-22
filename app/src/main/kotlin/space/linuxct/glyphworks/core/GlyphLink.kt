package space.linuxct.glyphworks.core

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.UserManager
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager

/**
 * The one process-wide GlyphMatrixManager. Components lease it with [acquire] and give it back
 * with [Lease.release]. Every SDK call is a blocking binder round-trip, so they all run on the
 * private "glyph-io" thread, and that thread owns every mutable field below.
 */
class GlyphLink(private val app: Context) {

    private val ioThread = HandlerThread("glyph-io").apply { start() }

    private val glyph = Handler(ioThread.looper)

    private var manager: GlyphMatrixManager? = null
    private var refCount = 0
    private var pendingFrame: IntArray? = null
    private var lastFrame: IntArray? = null
    private var teardown: Runnable? = null

    private val userManager = app.getSystemService(UserManager::class.java)

    private fun userUnlocked(): Boolean = userManager?.isUserUnlocked ?: true

    private var awaitingUnlock = false

    /** Set by [space.linuxct.glyphworks.Core] to revive the session on unlock. */
    @Volatile
    var onUserUnlocked: (() -> Unit)? = null

    // ACTION_USER_UNLOCKED is not deliverable to manifest receivers.
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            DebugLog.i(C, "user unlocked; starting the Glyph session")
            runCatching { app.unregisterReceiver(this) }
            onUserUnlocked?.invoke()
            glyph.post {
                awaitingUnlock = false
                if (refCount > 0 && manager == null) connect()
            }
        }
    }

    private fun awaitUnlock() {
        if (awaitingUnlock) return
        awaitingUnlock = true
        runCatching {
            app.registerReceiver(
                unlockReceiver,
                android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            awaitingUnlock = false
            DebugLog.w(C, "could not listen for unlock: $it")
        }
    }

    private val recovery = Runnable {
        if (refCount > 0 && !ready) {
            disconnect()
            connect()
        }
    }

    private fun scheduleRecovery() {
        glyph.removeCallbacks(recovery)
        glyph.postDelayed(recovery, RECONNECT_DELAY_MS)
    }

    @Volatile
    var ready = false
        private set

    private var firstFrameLogged = false

    val matrixLength: Int = Common.getDeviceMatrixLength()
    val isSupported: Boolean =
        matrixLength == PHONE_3_MATRIX_LENGTH || matrixLength == PHONE_4A_PRO_MATRIX_LENGTH

    val size: Int = if (isSupported) matrixLength else PREVIEW_SIZE

    inner class Lease internal constructor(private val tag: String) {
        private var released = false

        fun release() {
            glyph.post {
                if (released) return@post
                released = true
                doRelease(tag)
            }
        }
    }

    fun acquire(tag: String): Lease {
        glyph.post { doAcquire(tag) }
        return Lease(tag)
    }

    fun pushFrame(frame: IntArray) {
        glyph.post {
            val currentManager = manager
            if (ready && currentManager != null) {
                try {
                    currentManager.setMatrixFrame(frame)
                    lastFrame = frame
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        DebugLog.i(C, "first frame delivered to the matrix")
                    }
                } catch (e: Exception) {
                    DebugLog.w(C, "setMatrixFrame failed: $e — scheduling recovery")
                    ready = false
                    pendingFrame = frame
                    scheduleRecovery()
                }
            } else {
                pendingFrame = frame
            }
        }
    }

    private fun doAcquire(tag: String) {
        refCount++
        DebugLog.d(C, "acquire($tag) -> refCount=$refCount")
        teardown?.let { glyph.removeCallbacks(it) }
        teardown = null
        if (manager == null) connect()
    }

    private fun doRelease(tag: String) {
        refCount = (refCount - 1).coerceAtLeast(0)
        DebugLog.d(C, "release($tag) -> refCount=$refCount")
        if (refCount > 0) return
        val pendingTeardown = Runnable {
            if (refCount == 0) disconnect()
            teardown = null
        }
        teardown = pendingTeardown
        glyph.postDelayed(pendingTeardown, TEARDOWN_GRACE_MS)
    }

    private fun connect() {
        if (!isSupported) {
            DebugLog.w(C, "no Glyph Matrix on this device (${android.os.Build.MODEL}); rendering disabled")
            return
        }
        // com.nothing.thirdparty is not directBootAware: its GlyphService reads a
        // credential-encrypted database in onCreate, so binding it before unlock
        // crash-loops it until the platform reboots the device.
        if (!userUnlocked()) {
            DebugLog.i(C, "user locked; deferring Glyph bind until unlock")
            awaitUnlock()
            return
        }
        DebugLog.i(C, "connecting to the Glyph service")
        try {
            val newManager = GlyphMatrixManager.getInstance(app.applicationContext)
            manager = newManager
            newManager.init(callback)
        } catch (e: Exception) {
            DebugLog.w(C, "GlyphMatrixManager init failed: $e")
            manager = null
        }
    }

    private fun disconnect() {
        val currentManager = manager ?: return
        ready = false
        manager = null
        pendingFrame = null
        try {
            currentManager.turnOff()
        } catch (e: Exception) {
            DebugLog.w(C, "turnOff failed: $e")
        }
        try {
            currentManager.unInit()
        } catch (e: Exception) {
            DebugLog.w(C, "unInit failed: $e")
        }
        DebugLog.i(C, "disconnected")
    }

    /** The SDK calls this back on the main thread, so both methods hop to [glyph] first. */
    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            glyph.post {
                val currentManager = manager ?: return@post
                val device =
                    if (matrixLength == PHONE_3_MATRIX_LENGTH) Glyph.DEVICE_23112 else Glyph.DEVICE_25111p
                val ok = try {
                    currentManager.register(device)
                } catch (e: Exception) {
                    DebugLog.w(C, "register threw: $e")
                    false
                }
                DebugLog.i(C, "glyph service connected, register($device) = $ok")
                ready = ok
                firstFrameLogged = false
                if (ok) {
                    val frameToRestore = pendingFrame ?: lastFrame
                    pendingFrame = null
                    frameToRestore?.let { pushFrame(it) }
                } else {
                    scheduleRecovery()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            glyph.post {
                DebugLog.w(C, "glyph service disconnected")
                ready = false
                val sessionStillWanted = refCount > 0 && manager != null
                if (sessionStillWanted) scheduleRecovery()
            }
        }
    }

    private companion object {
        const val C = "GlyphLink"
        const val PHONE_3_MATRIX_LENGTH = 25
        const val PHONE_4A_PRO_MATRIX_LENGTH = 13
        const val PREVIEW_SIZE = PHONE_4A_PRO_MATRIX_LENGTH
        const val TEARDOWN_GRACE_MS = 3000L
        const val RECONNECT_DELAY_MS = 2000L
    }
}
