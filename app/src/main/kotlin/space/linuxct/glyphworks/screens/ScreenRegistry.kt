package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.screens.ambient.AmbientScreen

/**
 * Every screen, in the default cycle order. The Toys tab reads a second roster,
 * DISPLAY_NAMES in ui/MainActivity.kt, keyed by the same ids: add or remove a toy in
 * both lists, or in neither.
 */
object ScreenRegistry {
    fun create(): List<GlyphScreen> = listOf(
        AmbientScreen(),
        ClockScreen(),
        EyesScreen(),
        SpeedScreen(),
        BatteryScreen(),
        SolarScreen(),
        MoonScreen(),
        DiceScreen(),
        CoinScreen(),
        DinoScreen(),
        BottleScreen(),
        // Rock Paper Scissors is off for now. To bring it back, restore this line
        // and the matching one in DISPLAY_NAMES.
        // RpsScreen(),
        CounterScreen(),
        BreathingScreen(),
        TimerScreen(),
        CompassScreen(),
        LevelScreen(),
        VisualizerScreen(),
        CustomScreen(),
    )
}
