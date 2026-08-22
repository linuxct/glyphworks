package space.linuxct.glyphworks.ui

import android.os.Build
import android.view.Display
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import space.linuxct.glyphworks.core.DebugLog

/** Call once from `onCreate`. The observer unregisters itself on ON_DESTROY. */
fun ComponentActivity.requestPeakRefreshRateWhileVisible() {
    lifecycle.addObserver(PeakRefreshRateObserver(this))
}

private const val C = "RefreshRate"

private const val NO_PREFERRED_MODE = 0

// Drivers report rates like 119.99 and 89.53, so rates need slack to compare.
private const val REFRESH_RATE_EPSILON_HZ = 1f

// The panel also offers 144 Hz, but the worst measured frames (7.21 ms) miss its
// 6.94 ms budget. 120 Hz gives 8.33 ms and is the display's own default mode.
private const val CEILING_HZ = 120f

private class PeakRefreshRateObserver(
    private val activity: ComponentActivity,
) : LifecycleEventObserver {

    private var targetMode: Display.Mode? = null
    private var resolvedAgainstWidth = 0
    private var resolvedAgainstHeight = 0

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> onStart()
            Lifecycle.Event.ON_STOP -> onStop()
            Lifecycle.Event.ON_DESTROY -> source.lifecycle.removeObserver(this)
            else -> Unit
        }
    }

    private fun onStart() {
        val current = activity.display?.mode
        val resolutionChanged = current == null ||
            current.physicalWidth != resolvedAgainstWidth ||
            current.physicalHeight != resolvedAgainstHeight
        if (resolutionChanged) {
            targetMode = resolveTargetMode()
            resolvedAgainstWidth = current?.physicalWidth ?: 0
            resolvedAgainstHeight = current?.physicalHeight ?: 0
        }
        applyFrameRateCategory(peak = true)
        val mode = targetMode ?: return
        applyPreferredMode(mode.modeId)
    }

    private fun onStop() {
        applyFrameRateCategory(peak = false)
        if (targetMode == null) return
        applyPreferredMode(NO_PREFERRED_MODE)
    }

    private fun resolveTargetMode(): Display.Mode? {
        val display = activity.display
        if (display == null) {
            DebugLog.w(C, "no display attached; leaving refresh rate to the system")
            return null
        }
        val current = display.mode
        // A modeId pins width and height too, so only the current resolution can win.
        val best = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .filter { it.refreshRate <= CEILING_HZ + REFRESH_RATE_EPSILON_HZ }
            .maxByOrNull { it.refreshRate }
        if (best == null) {
            DebugLog.i(
                C,
                "no mode at or below ${CEILING_HZ}Hz at " +
                    "${current.physicalWidth}x${current.physicalHeight}; " +
                    "supported ${display.supportedModes.joinToString { describe(it) }}",
            )
            return null
        }
        DebugLog.i(
            C,
            "pinning ${best.refreshRate}Hz (modeId=${best.modeId}) " +
                "at ${current.physicalWidth}x${current.physicalHeight}; " +
                "display was on ${current.refreshRate}Hz (modeId=${current.modeId}); " +
                "supported ${display.supportedModes.joinToString { describe(it) }}",
        )
        return best
    }

    // `window.attributes` hands back the live LayoutParams; assigning it back applies it.
    private fun applyPreferredMode(modeId: Int) {
        activity.window.attributes = activity.window.attributes.also {
            it.preferredDisplayModeId = modeId
        }
    }

    // Android 15 votes on frame rate on top of the pinned mode: a layer it judges "not
    // demanding" is presented at a divisor of 120 Hz. These API 35 calls object.
    private fun applyFrameRateCategory(peak: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val window = activity.window
        window.decorView.requestedFrameRate = if (peak) {
            View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        } else {
            View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
        window.setFrameRatePowerSavingsBalanced(!peak)
        if (peak && !window.frameRateBoostOnTouchEnabled) {
            window.setFrameRateBoostOnTouchEnabled(true)
        }
    }

    private fun describe(mode: Display.Mode): String =
        "${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate}(id=${mode.modeId})"
}
