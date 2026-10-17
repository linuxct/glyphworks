package space.linuxct.glyphworks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.design.*
import space.linuxct.glyphworks.pipeline.native.NativeLibrary
import space.linuxct.glyphworks.screens.ScreenRegistry
import space.linuxct.pipeline.*
import java.time.Instant
import java.time.ZoneId
import java.util.PriorityQueue

class NativeLibraryTest {
    private class Harness(val size: Int = 13) {
        val old = TestHarness(size)
        var now = 1_000_000L
        private var order = 0L
        private data class Pending(val due: Long, val order: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private val pending = PriorityQueue<Pending>(compareBy({ it.due }, { it.order }))
        val frames = mutableListOf<IntArray>()
        val events = mutableListOf<PipelineEvent>()
        val actions = mutableListOf<Pair<String, Map<String, Value>>>()
        val persisted = mutableMapOf<String, Value>()
        val random = FakeRandom()
        init {
            val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            old.clock.hour = date.hour; old.clock.min = date.minute; old.clock.sec = date.second
            old.clock.doy = date.dayOfYear; old.clock.utcOffsetMin = date.offset.totalSeconds / 60
        }
        fun context(id: String = "one", parameters: Map<String, Value> = emptyMap(), bindings: Map<String, AssetBinding> = emptyMap(), designs: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()) = NativeContext(
            id, size, parameters, bindings, designs,
            object : PipelineClock { override fun elapsedMillis() = now; override fun wallMillis() = now },
            object : PipelineRandom { override fun nextInt(bound: Int) = random.nextInt(bound); override fun nextDouble() = random.nextFloat().toDouble() },
            object : TaskScheduler {
                override fun post(delayMs: Long, action: () -> Unit): Cancellation {
                    val task = Pending(now + delayMs, order++, action); pending += task
                    return Cancellation { task.cancelled = true }
                }
            },
            { emptyMap() }, { frames += it.copyOf() }, { events += it },
            action = { name, values -> actions += name to values },
            readState = { persisted["$id:$it"] }, writeState = { key, value -> persisted["$id:$key"] = value },
        )
        fun behavior(type: String, context: NativeContext = context()) = checkNotNull(NativeLibrary.create(type, context, old.ports, old.prefs))
        fun advance(ms: Long) {
            val target = now + ms
            var safety = 0
            while (pending.peek()?.due?.let { it <= target } == true) {
                check(++safety < 100_000)
                val task = pending.remove()
                old.clock.advance(task.due - now); now = task.due
                if (!task.cancelled) task.run()
            }
            old.clock.advance(target - now); now = target
        }
    }

    @Test fun everyToyMatchesItsOriginalInitialFrameOnBothPanels() {
        for (size in listOf(13, 25)) for (screen in ScreenRegistry.create()) {
            val harness = Harness(size)
            screen.onActivate(harness.old.context)
            val original = harness.old.lastFrame()
            val native = harness.behavior(screen.id)
            native.start(); harness.advance(0)
            assertTrue("${screen.id} at $size", harness.frames.isNotEmpty())
            assertArrayEquals("${screen.id} at $size", original, harness.frames.last())
            native.close(); screen.onDeactivate()
        }
    }

    @Test fun allTwelveBackgroundsRenderAndReleaseTheirCallbacks() {
        for (size in listOf(13, 25)) for (id in NativeLibrary.ids.filter { it.startsWith("background.") }) {
            val h = Harness(size); val native = h.behavior(id)
            native.start(); h.advance(100)
            assertEquals(size * size, h.frames.last().size)
            val count = h.frames.size
            native.close(); h.advance(1000)
            assertEquals(id, count, h.frames.size)
        }
    }

    @Test fun suspensionPreservesDiceRollRatherThanCompletingOrRestartingIt() {
        val h = Harness(); val dice = h.behavior("dice")
        dice.start(); dice.command("roll", emptyMap()); h.advance(200)
        assertEquals("rolling", dice.values()["phase"]?.text())
        val count = h.frames.size
        dice.suspend(); h.advance(10_000)
        assertEquals(count, h.frames.size)
        dice.resume(); h.advance(500)
        assertEquals("rolling", dice.values()["phase"]?.text())
        h.advance(150)
        assertEquals("result", dice.values()["phase"]?.text())
        assertEquals(1, h.events.count { it.name == "native.event" && it.values["kind"]?.text() == "result" })
    }

    @Test fun countersAreIsolatedAndPersistByInstanceWithoutChangingToyPreferences() {
        val h = Harness(); h.old.prefs.putInt(PrefKeys.COUNTER, 42)
        val one = h.behavior("counter", h.context("one")); val two = h.behavior("counter", h.context("two"))
        one.start(); two.start()
        one.command("increment", mapOf("amount" to number(3)))
        assertEquals(3.0, one.values()["value"]?.number() ?: -1.0, 0.0)
        assertEquals(0.0, two.values()["value"]?.number() ?: -1.0, 0.0)
        assertEquals(42, h.old.prefs.getInt(PrefKeys.COUNTER, 0))
        one.close()
        val restored = h.behavior("counter", h.context("one")); restored.start()
        assertEquals(3.0, restored.values()["value"]?.number() ?: -1.0, 0.0)
    }

    @Test fun weatherAndTimersNeverAcquireHardwareInsideNativeBehavior() {
        val h = Harness(); val weather = h.behavior("weather"); val timer = h.behavior("timer")
        weather.start(); timer.start(); timer.command("start", emptyMap()); h.advance(0)
        assertTrue(h.old.weather.demands.isEmpty())
        assertNull(h.old.timer.scheduledAt)
        assertTrue(h.actions.any { it.first == "native.timer.schedule" })
        timer.command("pause", emptyMap())
        assertTrue(h.actions.any { it.first == "native.timer.cancel" })
        weather.close(); timer.close()
    }

    @Test fun dinoJumpArtworkMovesWithItsFootAnchorWithoutChangingPhysics() {
        for (size in listOf(13, 25)) {
            val h = Harness(size)
            val panel = checkNotNull(PokemonCodename.ofSize(size))
            val cells = CharArray(size * size) { '0' }.apply { this[size / 2 * size + size / 2] = '1' }
            val design = Design(id = "jump_art", name = "Jump", createdAt = "2026-10-06T00:00:00Z", modifiedAt = "2026-10-06T00:00:00Z", levels = listOf(0, 900), variants = mapOf(panel.codename to DesignVariant(listOf(DesignFrame(cells = String(cells))))))
            val binding = AssetBinding("jump_art", variants = mapOf(panel.codename to SpriteGeometry(x = size / 2, y = size / 2, width = 1, height = 1)))
            val customized = h.behavior("dino", h.context(bindings = mapOf("jump" to binding), designs = mapOf("jump_art" to Json.parseToJsonElement(DesignCodec.encode(design)).jsonObject)))
            val baseline = h.behavior("dino", h.context("baseline"))
            customized.start(); baseline.start(); customized.command("start", emptyMap()); baseline.command("start", emptyMap()); h.advance(0)
            customized.command("jump", emptyMap()); baseline.command("jump", emptyMap()); h.advance(50)
            assertEquals(baseline.values()["height"], customized.values()["height"])
            assertEquals(baseline.values()["velocity"], customized.values()["velocity"])
            assertTrue(h.frames.any { it.count { pixel -> pixel == 900 } == 1 })
            assertEquals("jump", customized.values()["phase"]?.text())
        }
    }

    @Test fun anExplicitCommandCanUpdateACoveredCounterWithoutSubmittingAFrame() {
        val h = Harness(); val counter = h.behavior("counter")
        counter.start(); h.advance(0)
        counter.suspend(); val frames = h.frames.size
        counter.command("increment", mapOf("amount" to number(7)))
        assertEquals(7.0, counter.values()["value"]!!.number(), 0.0)
        assertEquals(frames, h.frames.size)
        counter.resume()
        assertFalse(h.frames.last().contentEquals(h.frames.first()))
        counter.close()
    }

    @Test fun notificationTimingOverrideControlsBothFrameAndReportedPhase() {
        val h = Harness(); h.old.notifications.value = 3
        val notifications = h.behavior("notifications", h.context(parameters = mapOf("style" to text("bell"), "holdDuration" to number(500), "slideDuration" to number(200))))
        notifications.start(); h.advance(0)
        assertEquals("icon", notifications.values()["phase"]!!.text())
        h.advance(600)
        assertEquals("slide_to_count", notifications.values()["phase"]!!.text())
        h.advance(100)
        assertEquals("count", notifications.values()["phase"]!!.text())
        assertArrayEquals(space.linuxct.glyphworks.screens.NotificationsRenderer.renderFrame(13, 3, "bell", 4000), h.frames.last())
        notifications.close()
    }


    @Test fun nativeAmbientUsesTheEditableGraphAndPausesItsVisibleCycleWhenCovered() {
        val h = Harness()
        val ambient = h.behavior("ambient", h.context(parameters = mapOf("backgrounds" to Value.Items(listOf(text("text_clock"), text("analog_clock"))), "autoCycle" to boolean(true), "interval" to duration(1000))))
        ambient.start(); h.advance(500)
        assertEquals(0.0, ambient.values()["index"]!!.number(), 0.0)
        ambient.suspend(); h.advance(5000)
        assertEquals(0.0, ambient.values()["index"]!!.number(), 0.0)
        ambient.resume(); h.advance(500)
        assertEquals(1.0, ambient.values()["index"]!!.number(), 0.0)
        ambient.command("action", emptyMap())
        assertEquals(0.0, ambient.values()["index"]!!.number(), 0.0)
        ambient.close()
    }

}
