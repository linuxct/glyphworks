package space.linuxct.glyphworks.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCounterTest {
    private val counter = NotificationCounter()

    @Test
    fun `not connected is distinct from a connected empty notification shade`() {
        assertNull(counter.count())
        counter.connected(emptyList())
        assertEquals(0, counter.count())
    }

    @Test
    fun `initial snapshot counts individual notifications`() {
        counter.connected(listOf(item("a"), item("b"), item("c")))
        assertEquals(3, counter.count())
    }

    @Test
    fun `duplicate posts and duplicate snapshot entries replace the same key`() {
        counter.connected(listOf(item("a"), item("a")))
        counter.posted(item("a"))
        counter.posted(item("a"))
        assertEquals(1, counter.count())

        counter.posted(item("a").copy(isOngoing = true))
        assertEquals(0, counter.count())
        counter.posted(item("a"))
        assertEquals(1, counter.count())
    }

    @Test
    fun `removing notifications is idempotent and an unrelated key does not change count`() {
        counter.connected(listOf(item("a"), item("b")))
        counter.removed("missing")
        assertEquals(2, counter.count())
        counter.removed("a")
        counter.removed("a")
        assertEquals(1, counter.count())
        counter.removed("b")
        assertEquals(0, counter.count())
    }

    @Test
    fun `non-dismissible ongoing foreground service and media entries are excluded`() {
        counter.connected(listOf(
            item("ordinary"),
            item("fixed").copy(isClearable = false),
            item("ongoing").copy(isOngoing = true),
            item("service").copy(isForegroundService = true),
            item("media").copy(isMedia = true),
        ))
        assertEquals(1, counter.count())
    }

    @Test
    fun `group children count individually without also counting the summary`() {
        counter.connected(listOf(
            summary("summary", "group"), item("a", "group"), item("b", "group"),
            item("ungrouped"),
        ))
        assertEquals(3, counter.count())
    }

    @Test
    fun `summary represents a group once only when there are no children`() {
        counter.connected(listOf(summary("summary", "group")))
        assertEquals(1, counter.count())
        counter.posted(item("a", "group"))
        assertEquals(1, counter.count())
        counter.posted(item("b", "group"))
        assertEquals(2, counter.count())
        counter.removed("a")
        counter.removed("b")
        assertEquals(1, counter.count())
        counter.removed("summary")
        assertEquals(0, counter.count())
    }

    @Test
    fun `multiple summaries of the same group count once`() {
        counter.connected(listOf(summary("a", "group"), summary("b", "group")))
        assertEquals(1, counter.count())
        counter.removed("a")
        assertEquals(1, counter.count())
    }

    @Test
    fun `unrelated groups do not suppress or deduplicate one another`() {
        counter.connected(listOf(
            summary("first-summary", "user-a-package-a-group"),
            item("first-child", "user-a-package-a-group"),
            summary("second-summary", "user-a-package-b-group"),
            summary("third-summary", "user-b-package-a-group"),
        ))
        assertEquals(3, counter.count())
    }

    @Test
    fun `excluded children do not leave their group summary counted`() {
        counter.connected(listOf(
            summary("summary", "media-group"),
            item("media", "media-group").copy(isMedia = true),
        ))
        assertEquals(0, counter.count())
        counter.posted(item("ordinary", "media-group"))
        assertEquals(1, counter.count())
    }

    @Test
    fun `non-dismissible summary does not hide its countable children`() {
        counter.connected(listOf(
            summary("summary", "group").copy(isClearable = false),
            item("a", "group"), item("b", "group"),
        ))
        assertEquals(2, counter.count())
    }

    @Test
    fun `an update moving a child out of a group restores the orphan summary`() {
        counter.connected(listOf(summary("summary", "group"), item("a", "group")))
        assertEquals(1, counter.count())
        counter.posted(item("a"))
        assertEquals(2, counter.count())
    }

    @Test
    fun `disconnect clears state and ignores late posts and removals`() {
        counter.connected(listOf(item("a"), item("b")))
        counter.disconnected()
        assertNull(counter.count())
        counter.posted(item("late"))
        counter.removed("a")
        assertNull(counter.count())
        counter.connected(emptyList())
        assertEquals(0, counter.count())
    }

    @Test
    fun `reconnect rebuilds state after additions and removals while unavailable`() {
        counter.connected(listOf(item("old-a"), item("old-b")))
        counter.disconnected()
        counter.connected(listOf(item("new")))
        assertEquals(1, counter.count())
        counter.removed("old-a")
        assertEquals(1, counter.count())
        counter.removed("new")
        assertEquals(0, counter.count())
    }

    @Test
    fun `reseed replaces previous state even without a disconnect callback`() {
        counter.connected(listOf(item("old-a"), item("old-b")))
        counter.connected(listOf(item("new")))
        assertEquals(1, counter.count())
    }

    @Test
    fun `posts before initial snapshot cannot turn unavailable into zero or partial data`() {
        counter.posted(item("early"))
        counter.removed("missing")
        assertNull(counter.count())
        counter.connected(emptyList())
        assertEquals(0, counter.count())
    }

    private fun item(key: String, group: String? = null) =
        NotificationMetadata(key = key, groupKey = group)

    private fun summary(key: String, group: String) =
        item(key, group).copy(isGroupSummary = true)
}
