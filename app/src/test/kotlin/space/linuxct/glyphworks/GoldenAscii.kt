package space.linuxct.glyphworks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import java.io.File

private const val OFF_CHAR = ' '
private const val DIM_CHAR = '.'
private const val MID_CHAR = '+'
private const val FULL_CHAR = '#'
private const val DIM_MAX_BRIGHTNESS = 1365
private const val MID_MAX_BRIGHTNESS = 2730

object GoldenAscii {

    private val goldenDir: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        if (dir.name != "app" && File(dir, "app").isDirectory) dir = File(dir, "app")
        File(dir, "src/test/resources/goldens")
    }

    private val updateMode: Boolean
        get() = System.getProperty("updateGoldens") == "true"

    fun render(frame: IntArray, size: Int): String {
        val sb = StringBuilder()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = frame[y * size + x]
                sb.append(
                    when {
                        v <= 0 -> OFF_CHAR
                        v <= DIM_MAX_BRIGHTNESS -> DIM_CHAR
                        v <= MID_MAX_BRIGHTNESS -> MID_CHAR
                        else -> FULL_CHAR
                    }
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun assertFrameValid(frame: IntArray, size: Int) {
        assertEquals("frame length must be size^2", size * size, frame.size)
        frame.forEachIndexed { i, v ->
            assertTrue("cell $i out of range: $v", v in 0..MAX_BRIGHTNESS)
        }
    }

    fun check(name: String, frame: IntArray, size: Int) {
        assertFrameValid(frame, size)
        val actual = render(frame, size)
        val file = File(goldenDir, "$name.txt")
        if (updateMode) {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.isFile) {
            fail(
                "Missing golden '$name'. Run ./gradlew :app:testGithubDebugUnitTest -DupdateGoldens=true " +
                    "to generate, then review the ASCII output.\nActual frame:\n$actual"
            )
        }
        val expected = file.readText()
        if (expected != actual) {
            fail("Golden mismatch '$name'.\nExpected:\n$expected\nActual:\n$actual")
        }
    }
}
