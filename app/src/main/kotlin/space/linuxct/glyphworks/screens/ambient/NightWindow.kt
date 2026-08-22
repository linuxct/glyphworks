package space.linuxct.glyphworks.screens.ambient

object NightWindow {
    private const val NIGHT_STARTS_HOUR = 23
    private const val NIGHT_ENDS_HOUR = 6

    fun isNight(hourOfDay: Int): Boolean =
        hourOfDay >= NIGHT_STARTS_HOUR || hourOfDay < NIGHT_ENDS_HOUR
}
