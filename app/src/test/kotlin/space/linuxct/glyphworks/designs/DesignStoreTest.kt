package space.linuxct.glyphworks.designs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignStoreTest {
    private val old = """{"design":"the one already on disk"}"""
    private val new = """{"design":"the one being saved"}"""

    @Test
    fun `a rename that replaces outright is the only step that runs`() {
        val f = fixture(withTarget = true)
        var renames = 0

        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { _, _ -> renames++; false })

        assertTrue(ok)
        assertEquals("POSIX replaces in one move; nothing else may be attempted", 1, renames)
        assertEquals(new, f.target.readText())
        assertFalse("no backup is made when none is needed", f.backup.exists())
    }

    @Test
    fun `a rename that cannot replace goes round by the backup`() {
        val f = fixture(withTarget = true)
        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { _, to -> to.exists() })

        assertTrue(ok)
        assertEquals(new, f.target.readText())
        assertFalse("a backup that has been superseded is dropped", f.backup.exists())
    }

    @Test
    fun `a failure with nothing on disk to protect reports it and stops`() {
        val f = fixture(withTarget = false)
        var renames = 0

        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { _, _ -> renames++; true })

        assertFalse(ok)
        assertEquals(1, renames)
        assertFalse(f.target.exists())
        assertFalse(f.backup.exists())
    }

    @Test
    fun `a backup is restored when the replacement will not land`() {
        val f = fixture(withTarget = true)
        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { from, _ -> from == f.tmp })

        assertFalse(ok)
        assertEquals("the previous design is back under its own name", old, f.target.readText())
        assertFalse("and nothing is left lying beside it", f.backup.exists())
        assertRecoverable(f)
    }

    @Test
    fun `a registered hook is told the id of every design deleted`() {
        val f = fixture(withTarget = true)
        val hooks = DesignDeletionHooks()
        val seen = mutableListOf<String>()
        hooks.add { seen.add(it) }

        assertTrue(deleteDesignFile(f.target, "abc", hooks))

        assertFalse(f.target.exists())
        assertEquals(listOf("abc"), seen)
    }

    @Test
    fun `a hook that throws costs the caller nothing`() {
        val f = fixture(withTarget = true)
        val hooks = DesignDeletionHooks()
        val seen = mutableListOf<String>()
        hooks.add { throw IllegalStateException("locked") }
        hooks.add { seen.add(it) }

        assertTrue(deleteDesignFile(f.target, "abc", hooks))

        assertFalse(f.target.exists())
        assertEquals("one listener failing must not silence the next", listOf("abc"), seen)
    }

    @Test
    fun `every name the store writes maps back to its design id`() {
        assertEquals("abc123", storedDesignId("abc123.json"))
        assertEquals("abc123", storedDesignId("abc123.json.bak"))
        assertEquals("abc123", storedDesignId("abc123.json.tmp"))
    }

    @Test
    fun `nothing else in the directory is read as a design id`() {
        val notOurs = listOf(
            "abc123",
            "abc123.txt",
            ".json",
            "abc 123.json",
            "../secrets.json",
            "sub/abc.json",
            "abc.json.bak.bak",
            "",
        )

        notOurs.forEach { assertNull(it, storedDesignId(it)) }
    }

    private fun assertRecoverable(f: Fixture) {
        val readable = when {
            f.target.exists() -> f.target.readText()
            f.backup.exists() -> f.backup.readText()
            else -> null
        }
        assertTrue(
            "no complete design left on disk — this is the data loss the function exists to prevent",
            readable == old || readable == new,
        )
    }

    private class Fixture(val tmp: File, val target: File, val backup: File)

    private fun fixture(withTarget: Boolean): Fixture {
        val dir = File.createTempFile("glyphworks-store", null).let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }
        val target = File(dir, "abc.json")
        val fixture = Fixture(
            tmp = File(dir, "abc.json.tmp").apply { writeText(new); deleteOnExit() },
            target = target.apply { deleteOnExit() },
            backup = File(dir, "abc.json.bak").apply { deleteOnExit() },
        )
        if (withTarget) target.writeText(old)
        return fixture
    }

    private fun rename(fail: (from: File, to: File) -> Boolean): (File, File) -> Boolean =
        { from, to -> if (fail(from, to)) false else from.renameTo(to) }
}
