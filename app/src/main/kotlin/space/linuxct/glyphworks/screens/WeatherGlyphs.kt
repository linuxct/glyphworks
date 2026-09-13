package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.weather.WeatherCondition
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/**
 * Dot layouts from Nothing Weather 3.4.1 / Nothing Widgets 3.5.3.
 * The widget vectors use an 88×88 viewport with dots spaced eight units apart:
 * one source dot becomes one Glyph pixel on 13×13, or a 2×2 block on 25×25.
 */
internal object WeatherGlyphs {
    /** The same filled, two-lobed cloud, compressed to leave room for a status mark. */
    fun drawStatusCloud(c: MatrixCanvas) {
        val scale = if (c.size >= 25) 2 else 1
        val left = if (scale == 2) 3 else 2
        val top = if (scale == 2) 3 else 2
        STATUS_CLOUD.forEachIndexed { y, row ->
            row.forEachIndexed { x, cell ->
                if (cell == '#') c.fillRect(left + x * scale, top + y * scale, scale, scale, MAX_BRIGHTNESS)
            }
        }
    }

    fun draw(c: MatrixCanvas, condition: WeatherCondition, isDay: Boolean, shift: Int) {
        val rows = when (condition) {
            WeatherCondition.CLEAR -> if (isDay) SUNNY else CLEAR_NIGHT
            WeatherCondition.PARTLY_CLOUDY -> if (isDay) PARTLY_CLOUDY else CLOUDY_NIGHT
            WeatherCondition.CLOUDY -> CLOUDY
            WeatherCondition.FOG -> FOG
            // Nothing's static set has a single rainy glyph for both precipitation cases.
            WeatherCondition.DRIZZLE, WeatherCondition.RAIN -> RAINY
            WeatherCondition.SNOW -> SNOW
            WeatherCondition.THUNDERSTORM -> THUNDERSTORMS
        }
        val scale = if (c.size >= 25) 2 else 1
        val left = (c.size - 11 * scale) / 2 + shift
        // Fog's source dots sit half a lattice row above the other icons.
        // 25×25 can preserve that offset; 13×13 rounds to the nearest whole row.
        val top = (c.size - 11 * scale) / 2 - if (condition == WeatherCondition.FOG && scale == 2) 1 else 0
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, cell ->
                if (cell == '#') c.fillRect(left + x * scale, top + y * scale, scale, scale, MAX_BRIGHTNESS)
            }
        }
    }

    // ic_widget_sunny.xml
    private val SUNNY = listOf(
        "...........",
        ".....#.....",
        "..#.....#..",
        "....###....",
        "...#####...",
        ".#.#####.#.",
        "...#####...",
        "....###....",
        "..#.....#..",
        ".....#.....",
        "...........",
    )

    // ic_widget_clear_night.xml
    private val CLEAR_NIGHT = listOf(
        "...........",
        "...##...#..",
        "..##...#.#.",
        "..##....#..",
        ".###.......",
        ".####......",
        ".#####...#.",
        "..########.",
        "..#######..",
        "....###....",
        "...........",
    )

    // ic_widget_partly_cloudy.xml
    private val PARTLY_CLOUDY = listOf(
        "...........",
        "....#......",
        ".#.....#...",
        "...###.....",
        "..#####....",
        "#.......##.",
        "...##.#####",
        "..#########",
        "..#########",
        "...#######.",
        "...........",
    )

    // ic_widget_cloudy_night.xml
    private val CLOUDY_NIGHT = listOf(
        "...........",
        "..###......",
        ".###....##.",
        "###..#.####",
        "###.#######",
        "###..#####.",
        "####.......",
        ".######....",
        "..####.....",
        "...........",
        "...........",
    )

    // ic_widget_cloudy.xml
    private val CLOUDY = listOf(
        "...........",
        "...........",
        "......###..",
        "..##.#####.",
        ".##########",
        "###########",
        "###########",
        ".#########.",
        "..#######..",
        "...........",
        "...........",
    )

    // ic_widget_fog.xml
    private val FOG = listOf(
        "...........",
        "...........",
        "..#.#.#.#..",
        ".#.#.#.#.#.",
        "...........",
        "..#.#.#.#..",
        ".#.#.#.#.#.",
        "...........",
        "..#.#.#.#..",
        ".#.#.#.#.#.",
        "...........",
    )

    // ic_widget_rainy.xml
    private val RAINY = listOf(
        "...........",
        "......###..",
        "..##.#####.",
        ".##########",
        "###########",
        "###########",
        ".#########.",
        "...........",
        "..#..#..#..",
        ".#..#..#...",
        "...........",
    )

    // ic_widget_snow.xml
    private val SNOW = listOf(
        "...........",
        ".....#.....",
        "...#.#.#...",
        "..##.#.##..",
        "....#.#....",
        ".###.#.###.",
        "....#.#....",
        "..##.#.##..",
        "...#.#.#...",
        ".....#.....",
        "...........",
    )

    // ic_widget_thunderstorms.xml
    private val THUNDERSTORMS = listOf(
        "...........",
        "......#....",
        ".....##....",
        "....###....",
        "...####....",
        "..#######..",
        "....####...",
        "....###....",
        "....##.....",
        "....#......",
        "...........",
    )

    private val STATUS_CLOUD = listOf(
        "....###..",
        ".##.####.",
        "#########",
        "#########",
        ".#######.",
    )
}
