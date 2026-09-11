package space.linuxct.glyphworks.ui

internal data class SetupStatus(
    val accessibility: Boolean,
    val alwaysOnToy: Boolean,
    /** False on the first run after install: Nothing OS has not bound the new toy yet. */
    val toyProbeArmed: Boolean,
    val notifications: Boolean,
    val microphone: Boolean,
    val location: Boolean,
    val exactAlarms: Boolean,
    val notificationAccess: Boolean = false,
    val notificationAccessNeeded: Boolean = false,
    val weatherEnabled: Boolean = false,
    val backgroundLocation: Boolean = false,
) {
    val needsAttention: Boolean
        get() = !accessibility ||
            toyNeedsAttention ||
            !notifications ||
            !microphone ||
            !location ||
            !exactAlarms ||
            (notificationAccessNeeded && !notificationAccess) ||
            (weatherEnabled && !backgroundLocation)

    val toyNeedsAttention: Boolean get() = !alwaysOnToy && toyProbeArmed

    companion object {
        val COMPLETE = SetupStatus(
            accessibility = true,
            alwaysOnToy = true,
            toyProbeArmed = true,
            notifications = true,
            microphone = true,
            location = true,
            exactAlarms = true,
            notificationAccess = true,
            backgroundLocation = true,
        )
    }
}
