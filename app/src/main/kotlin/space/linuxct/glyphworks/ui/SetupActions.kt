package space.linuxct.glyphworks.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.key.EssentialKeyService

internal fun isNothingGlyphDevice(context: Context): Boolean =
    Core.glyphLink.isSupported &&
        context.packageManager.hasSystemFeature("com.nothing.feature")

internal fun isTestedGlyphDevice(): Boolean =
    Core.glyphLink.matrixLength == PHONE_4A_PRO_MATRIX_LENGTH

// Panel size names the model: 13 rows is the Phone (4a) Pro, 25 is the Phone (3).
private const val PHONE_4A_PRO_MATRIX_LENGTH = 13

internal fun isEssentialKeyServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, EssentialKeyService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any {
        it.equals(component.flattenToString(), ignoreCase = true) ||
            it.equals(component.flattenToShortString(), ignoreCase = true)
    }
}

internal fun openGlyphToySettings(context: Context): Boolean {
    val pickersMostDirectFirst = listOf(
        "com.nothing.thirdparty.matrix.toys.manager.AodToySelectActivity",
        "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity",
    )
    return pickersMostDirectFirst.any { cls ->
        try {
            context.startActivity(
                Intent().setComponent(ComponentName("com.nothing.thirdparty", cls)),
            )
            DebugLog.i("Ui", "opened $cls")
            true
        } catch (e: Exception) {
            DebugLog.d("Ui", "$cls not launchable: ${e.message}")
            false
        }
    }
}
