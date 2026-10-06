package space.linuxct.glyphworks.pipeline.runtime

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.serialization.json.Json
import space.linuxct.glyphworks.core.*
import space.linuxct.glyphworks.pipeline.native.NativeLibrary
import space.linuxct.glyphworks.pipeline.native.NativeCatalog
import space.linuxct.glyphworks.pipeline.store.PipelineStore
import space.linuxct.glyphworks.pipeline.store.PipelineReferences
import space.linuxct.glyphworks.pipeline.templates.BuiltinPipelines
import space.linuxct.glyphworks.pipeline.templates.ControllerTemplate
import space.linuxct.pipeline.*
import java.util.concurrent.Executors

/** App boundary for program selection, runtime scopes, source demands and single display ownership. */
class PipelineController(
    private val app: Context,
    private val prefs: Prefs,
    private val ports: Ports,
    private val scheduler: RenderScheduler,
    private val size: Int,
    val store: PipelineStore,
) {
    val controllerMode get() = prefs.getBoolean(PipelinePrefs.CONTROLLER_ENABLED, false)
    val controllerId get() = prefs.getString(PipelinePrefs.CONTROLLER_ID, "")
    val ambientId get() = prefs.getString(PipelinePrefs.AMBIENT_ID, "")
    val stopped get() = prefs.getBoolean(PipelinePrefs.STOPPED, false)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pipeline-library").apply { isDaemon = true } }
    private val inputs = PipelineInputs(app, ports)
    private val stateJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val builtins = BuiltinPipelines.all().associateBy { it.entry()!!.template!!.removePrefix("glyphworks.") }
    @Volatile private var applied = emptyMap<String, PipelineDocument>()
    private var manager: ScreenManager? = null
    private var context: ScreenContext? = null
    private var activeId: String? = null
    private var selected: Running? = null
    private val rules = linkedMapOf<String, Running>()
    private var activeTriggerAssignments = emptyMap<String, String>()
    private var tick: Cancelable? = null
    private var sequence = 0L
    private var activationGeneration = 0L
    private var ruleGeneration = 0L
    private data class Prepared(val source: Map<String, kotlinx.serialization.json.JsonObject>, val assets: PipelineAssets, val cells: Long)
    private val assetCache = linkedMapOf<String, Prepared>()
    private val emptyAssets by lazy { PipelineAssets(PipelineDocument(name = "Empty assets")) }
    @Synchronized private fun assetsFor(document: PipelineDocument): PipelineAssets? =
        if (document.designs.isEmpty()) emptyAssets else assetCache[document.id]?.takeIf { it.source === document.designs }?.assets
    private fun prepare(document: PipelineDocument): PipelineAssets {
        assetsFor(document)?.let { return it }
        val assets = PipelineAssets(document)
        NativeLibrary.prepare(document, size, assets)
        val cells = document.designs.keys.sumOf { id -> listOf(13, 25).sumOf { panel -> assets.frames(id, panel).sumOf { it.pixels.size.toLong() } } }
        synchronized(this) {
            assetCache.remove(document.id)
            assetCache[document.id] = Prepared(document.designs, assets, cells)
            while (assetCache.size > 8 || (assetCache.size > 1 && assetCache.values.sumOf { it.cells } > 4_000_000L)) assetCache.remove(assetCache.keys.first())
        }
        return assets
    }
    private var nextChimeAt = 0L
    private var nextHapticAt = 0L
    private val hostSignal = object : TimerSignalPort {
        override fun scheduleAlarm(atEpochMillis: Long) = ports.timer.scheduleAlarm(atEpochMillis)
        override fun cancelAlarm() = ports.timer.cancelAlarm()
        override fun chime() = boundedChime()
    }
    private val alarms = PipelineTimerAlarms(app, prefs, hostSignal)
    private var clockRunning = false
    private var previewSuspended = false
    private var sdkReady = false
    private var sessionAnnounced = false
    private var hasRunSession = false
    private var previousError: String? = null
    private data class Running(val document: PipelineDocument, val runtime: PipelineRuntime, var frame: IntArray, val order: Long, var capabilities: Set<String>)

    init {
        val interrupted = prefs.getString(PipelinePrefs.STARTUP, "")
        if (interrupted.isNotBlank()) {
            prefs.putBoolean(PipelinePrefs.STOPPED, true)
            fail("A pipeline was interrupted while starting. Review '$interrupted' before resuming.")
            prefs.putStringDurable(PipelinePrefs.STARTUP, "")
        }
        migrate()
        loadDocuments()
        store.addListener { reload(null) }
    }
    fun attachManager(value: ScreenManager) { manager = value; if (controllerMode) value.setControllerScreen(advancedScreen()) }
    fun screens(): List<GlyphScreen> = builtins.map { (id, document) -> screen(id, document) } + applied.values.filter { it.entry()?.kind == ProgramKind.TOY }.map { screen("pipeline_${it.id}", it) }
    private fun screen(id: String, document: PipelineDocument) = PipelineScreen(id, id.startsWith("pipeline_") || id in setOf("ambient", "dice", "coin", "dino", "bottle", "counter", "breathing", "timer", "custom"), document.entry()?.immediateAction == true, this)
    private fun advancedScreen() = PipelineScreen(ADVANCED_ID, true, applied[controllerId]?.entry()?.immediateAction == true, this)
    fun projects() = applied.values.toList()
    fun document(id: String): PipelineDocument? = when { id == "ambient" -> applied[ambientId] ?: builtins[id]; id == ADVANCED_ID -> applied[controllerId]; id.startsWith("pipeline_") -> applied[id.removePrefix("pipeline_")]; else -> applied[id] ?: builtins[id] }
    fun onProjectApplied(id: String) = reload(id)
    fun assignAmbient(id: String) {
        require((store.loadApplied(id)?.document?.entry()?.kind) == ProgramKind.AMBIENT)
        prefs.putString(PipelinePrefs.AMBIENT_ID, id); reload(id)
    }
    fun setController(id: String) {
        require((store.loadApplied(id)?.document?.entry()?.kind) == ProgramKind.CONTROLLER)
        prefs.putString(PipelinePrefs.CONTROLLER_ID, id); reload(id)
    }
    /** The App settings UI is the only production caller of this explicit activation operation. */
    fun setControllerEnabled(enabled: Boolean) {
        if (enabled && store.loadApplied(controllerId)?.document?.entry()?.kind != ProgramKind.CONTROLLER) return
        clearDiagnostic()
        prefs.putBoolean(PipelinePrefs.CONTROLLER_ENABLED, enabled)
        prefs.putBoolean(PipelinePrefs.STOPPED, false)
        scheduler.run { manager?.setControllerScreen(if (enabled) advancedScreen() else null); restartRules() }
    }
    fun setPocketProtection(enabled: Boolean) { prefs.putBoolean(PipelinePrefs.POCKET_PROTECTION, enabled); scheduler.run { updateDemand() } }
    fun setTrigger(toyId: String, projectId: String?) {
        val mapping = triggers().toMutableMap()
        if (projectId == null) mapping.remove(toyId) else {
            require(store.loadApplied(projectId) != null)
            if (toyId !in mapping && mapping.size >= MAX_STANDARD_RULES) { fail("At most $MAX_STANDARD_RULES Standard trigger projects can run together."); return }
            mapping[toyId] = projectId
        }
        prefs.putString(PipelinePrefs.TRIGGERS, mapping.entries.joinToString(",") { "${it.key}=${it.value}" })
        reload(null)
    }
    fun deleteProject(id: String): Boolean {
        if (!store.delete(id)) return false
        alarms.cancelDocument(id)
        if (ambientId == id) prefs.putString(PipelinePrefs.AMBIENT_ID, "")
        if (controllerId == id) prefs.putString(PipelinePrefs.CONTROLLER_ID, "")
        val remaining = triggers().filterValues { it != id }
        prefs.putString(PipelinePrefs.TRIGGERS, remaining.entries.joinToString(",") { "${it.key}=${it.value}" })
        reload(null)
        return true
    }
    fun triggerFor(toyId: String) = triggers()[toyId]
    private fun triggers() = prefs.getString(PipelinePrefs.TRIGGERS, "").split(',').mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] } }.toMap()
    fun stop() { prefs.putBoolean(PipelinePrefs.STOPPED, true); scheduler.run { alarms.cancelAll(); closeRuntimes(); context?.pushFrame(IntArray(size * size)) } }
    fun resume() { clearDiagnostic(); prefs.putBoolean(PipelinePrefs.STOPPED, false); scheduler.run { manager?.refreshCurrentScreen() } }
    fun suspendPreview() { previewSuspended = true; selected?.runtime?.externallyCovered(true); rules.values.forEach { it.runtime.externallyCovered(true) } }
    fun resumePreview() {
        previewSuspended = false
        // Apply/stop may have changed the chosen project while the preview owned the display.
        if (!stopped && (selected == null || activeId?.let(::document) != selected?.document)) manager?.refreshCurrentScreen()
        else compose()
    }
    fun sdkAvailability(available: Boolean) { scheduler.run { sdkReady = available; dispatch(PipelineEvent(if (available) "sdk.available" else "sdk.unavailable", atMillis = ports.clock.elapsedMillis())) } }
    fun onSessionChanged(running: Boolean) { scheduler.run { if (!running) { sessionAnnounced = false; closeRuntimes(); context = null; activeId = null } else if (context != null) startClock() } }

    internal fun activate(id: String, ctx: ScreenContext) {
        val generation = ++activationGeneration
        selected?.runtime?.close(); selected = null
        activeId = id; context = ctx; previewSuspended = false
        if (stopped) { ctx.pushFrame(IntArray(size * size)); return }
        val document = document(id)
        if (document == null) { fail("The selected controller is unavailable. Choose a project in App settings."); return }
        val assets = assetsFor(document)
        if (assets == null) {
            executor.execute {
                val prepared = prepare(document)
                scheduler.run { if (generation == activationGeneration && context === ctx && !stopped) installSelected(document, prepared) }
            }
            startClock(); compose()
            return
        }
        installSelected(document, assets)
    }
    private fun installSelected(document: PipelineDocument, assets: PipelineAssets) {
        selected = create(document, "selected", assets)
        selected?.let(::startSafely)
        if (controllerMode || activeTriggerAssignments != triggers() || rules.isEmpty()) restartRules()
        updateDemand(); startClock()
        if (!sessionAnnounced) {
            dispatch(PipelineEvent(if (hasRunSession) "session.resume" else "session.start", atMillis = ports.clock.elapsedMillis()))
            sessionAnnounced = true; hasRunSession = true
        }
        selected?.runtime?.dispatch(PipelineEvent("program.selected", mapOf("id" to text(activeId.orEmpty())), ports.clock.elapsedMillis()))
        compose()
    }
    internal fun deactivate(id: String) {
        if (activeId != id) return
        activationGeneration++
        selected?.runtime?.dispatch(PipelineEvent("program.deselected", mapOf("id" to text(id)), ports.clock.elapsedMillis()))
        selected?.runtime?.close(); selected = null
        tick?.cancel(); tick = null; clockRunning = false
        context = null; activeId = null; updateDemand()
    }
    fun glyphEvent(event: String): Boolean {
        val name = when (event) { Events.CHANGE -> "key.action"; Events.SHAKE -> "shake"; Events.ACTION_DOWN -> "key.down"; Events.ACTION_UP -> "key.up"; Events.AOD -> "session.aod"; else -> event }
        return dispatch(PipelineEvent(name, atMillis = ports.clock.elapsedMillis(), consumable = name.startsWith("key.") || name == "shake"))
    }
    fun routeGesture(clicks: Int): Boolean {
        if (controllerMode) { keyGesture(clicks); return true }
        if (stopped || context == null || clicks == 1) return false
        val name = when (clicks) { 1 -> "key.action"; 2 -> "key.double"; 3 -> "key.triple"; else -> return false }
        val event = PipelineEvent(name, atMillis = ports.clock.elapsedMillis(), consumable = true)
        for (rule in rules.values.sortedByDescending { it.order }) if (rule.runtime.dispatch(event)) { compose(); return true }
        val consumed = selected?.runtime?.dispatch(event) == true
        compose(); return consumed
    }
    fun resolvedSingle(): Boolean = dispatch(PipelineEvent("key.single", atMillis = ports.clock.elapsedMillis(), consumable = true))
    fun rawKey(down: Boolean) = dispatch(PipelineEvent(if (down) "key.down" else "key.up", atMillis = ports.clock.elapsedMillis(), consumable = true))
    fun heldKey(durationMs: Long) = dispatch(PipelineEvent("key.hold", mapOf("duration" to duration(durationMs)), ports.clock.elapsedMillis(), consumable = true))
    fun keyGesture(clicks: Int): Boolean {
        val name = when (clicks) { 1 -> "key.action"; 2 -> "key.double"; 3 -> "key.triple"; else -> return false }
        return dispatch(PipelineEvent(name, atMillis = ports.clock.elapsedMillis(), consumable = true))
    }
    fun event(event: PipelineEvent) { scheduler.run { dispatch(event) } }
    private fun dispatch(event: PipelineEvent): Boolean {
        if (stopped || context == null) return false
        var consumed = false
        if (!controllerMode) for (rule in rules.values.sortedByDescending { it.order }) if (rule.runtime.dispatch(event)) { consumed = true; break }
        if (!consumed) consumed = selected?.runtime?.dispatch(event) == true
        compose(); return consumed
    }
    private fun create(document: PipelineDocument, name: String, assets: PipelineAssets): Running {
        val stateDocument = if (name == "selected") document.id else "${document.id}@$name"
        var holder: Running? = null
        val host = object : PipelineHost {
            override val size = this@PipelineController.size
            override val clock = object : PipelineClock { override fun elapsedMillis() = ports.clock.elapsedMillis(); override fun wallMillis() = ports.clock.nowMillis() }
            override val random = object : PipelineRandom { override fun nextInt(bound: Int) = ports.random.nextInt(bound); override fun nextDouble() = ports.random.nextFloat().toDouble() }
            override fun supports(requirement: Requirement): Boolean = requirement.version == 1 && (
                requirement.capability.removePrefix("native.") in NativeLibrary.ids ||
                    requirement.capability in InputCatalog.all.mapNotNull { it.capability }.toSet() + setOf("timer")
                )
            override fun inputs() = inputs.snapshot + mapOf("sdk.available" to boolean(sdkReady), "session.running" to boolean(context != null))
            override fun nativeCapabilities(type: String, parameters: Map<String, Value>) = NativeCatalog.capabilities(type, parameters, prefs)
            override fun demand(capabilities: Set<String>) {
                holder?.capabilities = capabilities
                updateDemand()
            }
            override fun createNative(type: String, context: NativeContext) = NativeLibrary.create(type, context, ports, prefs)
            override fun output(frame: IntArray) { holder?.frame = frame.copyOf() }
            override fun action(name: String, values: Map<String, Value>) {
                when {
                    name.startsWith("native.timer.") -> alarms.action(stateDocument, name, values)
                    name == "calendar.schedule" || name == "calendar.cancel" -> alarms.persistent(stateDocument, values["owner"]?.text().orEmpty(), values["name"]?.text().orEmpty(), if (name == "calendar.cancel") 0 else values["deadline"]?.number()?.toLong() ?: 0)
                    name == "chime" -> boundedChime()
                    name == "haptic" -> boundedHaptic(values["kind"]?.text())
                }
            }
            override fun readState(programId: String, variableId: String): Value? = readStateValue(stateDocument, programId, variableId)
            override fun writeState(programId: String, variableId: String, value: Value) {
                prefs.putString(stateKey(stateDocument, programId, variableId), stateJson.encodeToString(Value.serializer(), value))
                if (variableId.startsWith("timer:") || ":timer:" in variableId) alarms.persistent(stateDocument, programId, variableId, value.number().toLong())
            }
            override fun diagnostic(diagnostic: Diagnostic) {
                if (diagnostic.fatal) {
                    fail(diagnostic.message)
                    prefs.putBoolean(PipelinePrefs.STOPPED, true)
                    scheduler.postDelayed(0) { stop() }
                }
            }
        }
        return Running(document, PipelineRuntime(document, host, preparedAssets = assets), IntArray(size * size), ++sequence, emptySet()).also { holder = it }
    }
    private fun startSafely(running: Running) {
        val guarded = builtins.values.none { it === running.document }
        if (guarded && !prefs.putStringDurable(PipelinePrefs.STARTUP, running.document.id)) {
            fail("The pipeline startup state could not be saved. Free storage before trying again.")
            stop(); return
        }
        try {
            running.runtime.start()
            if (running.runtime.isRunning) running.runtime.advance()
            if (guarded) prefs.putStringDurable(PipelinePrefs.STARTUP, "")
        } catch (failure: RuntimeException) {
            fail("Pipeline startup failed: ${failure.message ?: failure.javaClass.simpleName}")
            stop()
            if (guarded) prefs.putStringDurable(PipelinePrefs.STARTUP, "")
        }
    }
    private fun startClock() {
        if (clockRunning || context == null || stopped) return
        clockRunning = true
        fun step() {
            if (!clockRunning || context == null || stopped) return
            val changes = inputs.poll()
            changes.forEach(::dispatch)
            selected?.runtime?.advance(); rules.values.toList().forEach { it.runtime.advance() }
            compose()
            tick = scheduler.postDelayed(25, ::step)
        }
        step()
    }
    private fun compose() {
        val ctx = context ?: return
        if (stopped || previewSuspended) return
        val rule = if (controllerMode) null else rules.values.filter { it.runtime.hasDisplay() }.maxWithOrNull(compareBy<Running> { it.runtime.presentationPriority() ?: 0 }.thenBy { it.order })
        selected?.runtime?.externallyCovered(rule != null)
        rules.values.forEach { it.runtime.externallyCovered(it !== rule) }
        val pocket = controllerMode && prefs.getBoolean(PipelinePrefs.POCKET_PROTECTION, true) && inputs.snapshot["sensor.proximity"]?.boolean() == true
        val frame = if (pocket) IntArray(size * size) else rule?.frame ?: selected?.frame ?: IntArray(size * size)
        ctx.pushFrame(frame)
    }
    private fun restartRules() {
        val generation = ++ruleGeneration
        rules.values.forEach { it.runtime.close() }; rules.clear()
        activeTriggerAssignments = triggers()
        if (!controllerMode && context != null && !stopped) for ((toy, id) in triggers().entries.take(MAX_STANDARD_RULES)) applied[id]?.let { document ->
            fun start(assets: PipelineAssets) {
                if (generation != ruleGeneration || controllerMode || context == null || stopped) return
                val running = create(document, "trigger_$toy", assets)
                rules[toy] = running
                startSafely(running)
                updateDemand(); compose()
            }
            val assets = assetsFor(document)
            if (assets != null) start(assets) else executor.execute { val prepared = prepare(document); scheduler.run { start(prepared) } }
        }
        if (triggers().size > MAX_STANDARD_RULES) fail("Only the first $MAX_STANDARD_RULES Standard trigger projects are active.")
        updateDemand()
    }
    private fun updateDemand() {
        val demand = buildSet {
            selected?.capabilities?.let(::addAll); rules.values.forEach { addAll(it.capabilities) }
            if (selected != null && controllerMode && prefs.getBoolean(PipelinePrefs.POCKET_PROTECTION, true)) add("proximity")
        }
        inputs.demand(demand)
    }
    private fun closeRuntimes() {
        activationGeneration++; ruleGeneration++
        selected?.runtime?.close(); selected = null; rules.values.forEach { it.runtime.close() }; rules.clear()
        clockRunning = false; tick?.cancel(); tick = null; inputs.close()
    }
    @Synchronized private fun boundedChime() {
        val now = ports.clock.elapsedMillis()
        if (now < nextChimeAt) return
        nextChimeAt = now + 1000
        runCatching { ports.timer.chime() }
    }
    private fun boundedHaptic(kind: String?) {
        val now = ports.clock.elapsedMillis()
        if (now < nextHapticAt) return
        nextHapticAt = now + 100
        val effect = when (kind) { "confirm" -> VibrationEffect.EFFECT_CLICK; "reject" -> VibrationEffect.EFFECT_DOUBLE_CLICK; else -> VibrationEffect.EFFECT_TICK }
        runCatching { app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(effect)) }
    }
    private fun clearDiagnostic() { previousError = null; prefs.putString(PipelinePrefs.DIAGNOSTIC, "") }
    private fun fail(message: String) {
        if (message != previousError) { previousError = message; prefs.putString(PipelinePrefs.DIAGNOSTIC, message) }
    }
    private fun reload(changedId: String?) { executor.execute {
        val before = applied
        loadDocuments()
        val changed = (before.keys + applied.keys).filterTo(mutableSetOf()) { before[it] != applied[it] }
        scheduler.run {
            manager?.replaceCatalog(screens())
            val expected = activeId?.let(::document)
            if (context != null && !stopped && (expected != selected?.document || selected?.document?.id in changed)) {
                if (controllerMode) manager?.setControllerScreen(advancedScreen()) else manager?.refreshCurrentScreen()
            }
            if (triggers() != activeTriggerAssignments || activeTriggerAssignments.values.any { it in changed }) restartRules()
            prefs.putLong(PipelinePrefs.LIBRARY_REVISION, prefs.getLong(PipelinePrefs.LIBRARY_REVISION, 0) + 1)
        }
    } }
    private fun loadDocuments() {
        val loaded = store.list().mapNotNull { summary -> store.loadApplied(summary.id)?.document?.let { summary.id to it } }.toMap()
        applied = loaded.mapValues { (id, document) -> applied[id]?.takeIf { it == document } ?: document }
        (listOf(ambientId, controllerId) + triggers().values.take(MAX_STANDARD_RULES)).distinct().mapNotNull(applied::get).forEach(::prepare)
        applied.values.filter { it.entry()?.kind == ProgramKind.TOY }.forEach { document ->
            val key = PrefKeys.screenEnabled("pipeline_${document.id}")
            if (!prefs.contains(key)) prefs.putBoolean(key, false)
        }
    }
    private fun migrate() {
        if (prefs.getInt(PipelinePrefs.MIGRATION_VERSION, 0) >= 1) return
        // A fixed migration ID makes retries idempotent after a crash between file and preference writes.
        val ambient = BuiltinPipelines.ambient(prefs).let { it.copy(id = "migrated_ambient", name = "My Ambient", programs = it.programs.map { p -> p.copy(template = null) }) }
        if (store.loadApplied(ambient.id) == null && store.apply(ambient) !is PipelineStore.SaveResult.Saved) return
        prefs.putString(PipelinePrefs.AMBIENT_ID, ambient.id)
        val controller = ControllerTemplate.create().copy(id = "my_controls", name = "My controls and menus")
        if (store.loadApplied(controller.id) == null) store.apply(controller)
        if (store.loadApplied(controller.id) != null) prefs.putString(PipelinePrefs.CONTROLLER_ID, controller.id)
        for ((key, value) in mapOf(PrefKeys.COUNTER to number(prefs.getInt(PrefKeys.COUNTER, 0)), PrefKeys.TIMER_START to number(prefs.getLong(PrefKeys.TIMER_START, 0)), PrefKeys.TIMER_PAUSED_ELAPSED to number(prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, 0)), PrefKeys.TIMER_CHIMED_FOR to number(prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, 0)))) {
            val toy = if (key == PrefKeys.COUNTER) "counter" else "timer"
            prefs.putString(stateKey("builtin_$toy", "builtin_$toy", "native:main:$key"), stateJson.encodeToString(Value.serializer(), value))
        }
        val legacyStart = prefs.getLong(PrefKeys.TIMER_START, 0)
        if (legacyStart > 0 && prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, 0) == 0L) {
            alarms.action("builtin_timer", "native.timer.schedule", mapOf("owner" to text("builtin_timer"), "instance" to text("root:main"), "deadline" to number(legacyStart + prefs.getInt(PrefKeys.TIMER_DURATION, 60) * 1000L)))
        }
        ports.timer.cancelAlarm()
        prefs.putInt(PipelinePrefs.MIGRATION_VERSION, 1)
    }
    private fun readStateValue(documentId: String, programId: String, state: String): Value? = prefs.getString(stateKey(documentId, programId, state), "").takeIf(String::isNotBlank)?.let { runCatching { stateJson.decodeFromString(Value.serializer(), it) }.getOrNull() }
    fun onAlarm(key: String, deadline: Long) { alarms.fire(key, deadline); scheduler.run { selected?.runtime?.advance(); rules.values.forEach { it.runtime.advance() }; compose() } }
    companion object {
        const val MAX_STANDARD_RULES = 32
        const val ADVANCED_ID = "pipeline_controller"
        fun stateKey(document: String, program: String, state: String) = "pipelineState:$document:$program:$state"
        fun capabilities(document: PipelineDocument, prefs: Prefs? = null): Set<String> = PipelineRuntime.capabilities(document) + buildSet {
            val knownDynamic = document.programs.flatMap { program -> program.parameters.flatMap { parameter ->
                ((program.values[parameter.id] ?: parameter.default) as? Value.Items)?.values.orEmpty().mapNotNull { item ->
                    val name = (item as? Value.Text)?.value ?: return@mapNotNull null
                    when { name in NativeLibrary.ids -> name; "background.$name" in NativeLibrary.ids -> "background.$name"; else -> null }
                }
            } }.toSet()
            fun visit(blocks: List<Block>) { blocks.forEach { block ->
                if (block.op == "display.toy") {
                    val literal = (block.arguments["toy"]?.value as? Value.Text)?.value
                    if (literal != null) addAll(NativeCatalog.capabilities(literal, (block.arguments["parameters"]?.value as? Value.Record)?.fields.orEmpty(), prefs))
                    else (knownDynamic.ifEmpty { NativeLibrary.ids }).mapNotNull(NativeCatalog::get).forEach { addAll(it.capabilities) }
                }
                visit(block.body); visit(block.otherwise)
            } }
            document.programs.forEach { it.scripts.forEach { script -> visit(script.blocks) } }; document.routines.forEach { visit(it.blocks) }
        }
    }
}
