package space.linuxct.pipeline

/**
 * A native visual slot can invoke authored drawing blocks without sharing its execution scope.
 * Prepared VMs and decoded pixels are cached. Each call resets the private scene and variables;
 * only its captured frame leaves this host. It never forwards subscriptions, state or actions.
 */
class DrawingRoutineRenderer(
    private val document: PipelineDocument,
    private val program: Program,
    private val host: PipelineHost,
    private val variables: () -> Map<String, Value> = { program.variables.associate { it.id to it.initial } },
    private val parameters: Map<String, Value> = program.values,
    preparedAssets: PipelineAssets? = null,
    private val limits: RuntimeLimits = RuntimeLimits(),
) : AutoCloseable {
    private val assets by lazy { preparedAssets ?: PipelineAssets(document) }
    private val prepared = mutableMapOf<String, Prepared?>()
    private val validSource by lazy { PipelineCodec.validateDraft(document).also { it.forEach(host::diagnostic) }.isEmpty() }
    private var closed = false

    fun render(routineId: String, state: Map<String, Value>): IntArray? {
        if (closed || !validSource) return null
        val renderer = if (routineId in prepared) prepared[routineId] else prepare(routineId).also { prepared[routineId] = it }
        return renderer?.render(state)
    }

    private fun prepare(id: String): Prepared? {
        val issues = validate(document, id)
        if (issues.isNotEmpty()) { issues.forEach(host::diagnostic); return null }
        val routine = document.routines.firstOrNull { it.id == id } ?: return null
        val included = dependencies(document, id)
        val authoredBlocks = buildMap<String, Block> {
            fun index(blocks: List<Block>) { blocks.forEach { put(it.id, it); index(it.body); index(it.otherwise) } }
            document.programs.forEach { p -> p.scripts.forEach { index(it.blocks) } }
            document.routines.forEach { index(it.blocks) }
        }
        // Counts are immutable document structure. Resolve them before pruning unrelated scripts.
        fun expression(e: Expression): Expression = if (e.op == "block.count") Expression.num(authoredBlocks[e.key]?.body?.count { it.enabled } ?: 0)
            else e.copy(args = e.args.map(::expression))
        fun normalize(block: Block): Block = block.copy(
            arguments = block.arguments.mapValues { expression(it.value) } + if (block.op == "scene.create") mapOf("slot" to Expression.str("drawing"), "priority" to Expression.num(0)) else emptyMap(),
            body = block.body.map(::normalize), otherwise = block.otherwise.map(::normalize),
        )
        val resetVariables = program.variables.mapIndexed { index, variable ->
            Block(op = "variable.set", arguments = mapOf("variable" to Expression.str(variable.id), "value" to Expression.event("v$index")))
        }
        val call = Block(op = "routine.call", arguments = mapOf("routine" to Expression.str(id)) +
            routine.parameters.mapIndexed { index, parameter -> "arg:${parameter.id}" to Expression.event("p$index") }.toMap())
        val draw = Script(trigger = Trigger("signal.draw"), blocks = listOf(
            Block(op = "display.release", arguments = mapOf("slot" to Expression.str("drawing"))),
            Block(op = "scene.create", arguments = mapOf("slot" to Expression.str("drawing"))),
            Block(op = "scene.clip", arguments = mapOf("enabled" to Expression.bool(false))),
        ) + resetVariables + call + Block(op = "scene.present"))
        val source = document.copy(
            entryPoint = program.id,
            programs = listOf(program.copy(scripts = listOf(draw), values = parameters,
                variables = program.variables.map { it.copy(persistent = false) })),
            routines = document.routines.filter { it.id in included }.map { r -> r.copy(blocks = r.blocks.map(::normalize), variables = r.variables.map { it.copy(persistent = false) }) },
            bindings = document.bindings.filterValues { it.routineId == null }, requires = emptyList(),
        )
        return Prepared(source, routine).takeIf { it.runtime.isRunning }
    }

    private inner class Prepared(source: PipelineDocument, private val routine: Routine) {
        private var frame = IntArray(host.size * host.size)
        private var failed = false
        val runtime = PipelineRuntime(source, object : PipelineHost {
            override val size = host.size
            override val clock = host.clock
            override val random = host.random
            override fun inputs() = host.inputs()
            override fun createNative(type: String, context: NativeContext): NativeBehavior? = null
            override fun output(frame: IntArray) { this@Prepared.frame = frame.copyOf() }
            override fun diagnostic(diagnostic: Diagnostic) { failed = true; host.diagnostic(diagnostic) }
        }, limits.copy(maxFibers = 1, maxStarvedTurns = 1, maxTrace = 16), assets)

        init { runtime.start() }

        fun render(state: Map<String, Value>): IntArray? {
            if (failed || !runtime.isRunning) return null
            val values = variables()
            val supplied = state.toMutableMap()
            supplied["state"] = Value.Record(state)
            program.variables.forEachIndexed { index, variable -> supplied["v$index"] = values[variable.id]?.takeIf { it.matches(variable.type) } ?: variable.initial }
            routine.parameters.forEachIndexed { index, parameter ->
                supplied["p$index"] = (if (parameter.id == "state" || parameter.name == "state") Value.Record(state)
                    else state[parameter.id] ?: state[parameter.name])?.takeIf { value -> value.matches(parameter.type) && (parameter.choices.isEmpty() || value in parameter.choices) && (value !is Value.Number || ((parameter.minimum == null || value.value >= parameter.minimum) && (parameter.maximum == null || value.value <= parameter.maximum))) } ?: parameter.default
            }
            runtime.dispatch(PipelineEvent("signal.draw", supplied, host.clock.elapsedMillis()))
            val progress = runtime.debugSnapshot()
            if (!runtime.isRunning || progress.activeBlocks.isNotEmpty() || progress.waitingBlocks.isNotEmpty()) {
                failed = true
                host.diagnostic(Diagnostic("Drawing routine exceeded its immediate rendering budget", routine.id, true))
                runtime.close()
                return null
            }
            return frame.copyOf()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        prepared.values.forEach { it?.runtime?.close() }
        prepared.clear()
    }

    companion object {
        private val allowed = setOf("flow.sequence", "flow.if", "flow.repeat", "flow.select", "flow.break", "flow.return", "routine.call",
            "variable.set", "variable.change", "list.add", "list.set", "list.remove") +
            BlockCatalog.all.filter { it.op.startsWith("scene.") || it.op.startsWith("sprite.") }.map { it.op }

        /** Extra contract for routines used as native artwork, in addition to normal codec checks. */
        fun validate(document: PipelineDocument, routineId: String): List<Diagnostic> {
            val diagnostics = mutableListOf<Diagnostic>()
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            var blockCount = 0
            fun routine(id: String, depth: Int) {
                if (id in visiting || depth > 32) { diagnostics += Diagnostic("Drawing routine call cycle or excessive depth", id, true); return }
                if (!visited.add(id)) return
                val source = document.routines.find { it.id == id }
                if (source == null) { diagnostics += Diagnostic("Missing drawing routine", id, true); return }
                visiting += id
                if (source.variables.any { it.persistent }) diagnostics += Diagnostic("Drawing routines cannot persist state", id, true)
                fun inspect(blocks: List<Block>, nesting: Int = 0) {
                    if (nesting > 32) { diagnostics += Diagnostic("Drawing blocks exceed nesting limit", id, true); return }
                    blocks.forEach { block ->
                        if (++blockCount > 4096) { if (blockCount == 4097) diagnostics += Diagnostic("Drawing routine exceeds block limit", id, true); return }
                        if (block.enabled) {
                            if (block.op !in allowed) diagnostics += Diagnostic("Drawing routines cannot use ${BlockCatalog[block.op]?.title ?: block.op}; draw immediately without waiting or side effects", block.id, true)
                            if (block.op == "routine.call") routine(block.arguments["routine"]?.value?.text().orEmpty(), depth + 1)
                            block.arguments["binding"]?.value?.text()?.let { binding ->
                                if (document.bindings[binding]?.routineId != null) diagnostics += Diagnostic("A drawing routine cannot recursively bind another drawing source to a sprite", block.id, true)
                            }
                            inspect(block.body, nesting + 1); inspect(block.otherwise, nesting + 1)
                        }
                    }
                }
                inspect(source.blocks)
                visiting -= id
            }
            routine(routineId, 0)
            return diagnostics.take(100)
        }

        private fun dependencies(document: PipelineDocument, root: String): Set<String> {
            val found = mutableSetOf<String>()
            fun routine(id: String) {
                if (!found.add(id)) return
                fun visit(blocks: List<Block>) { blocks.forEach { block ->
                    if (block.op == "routine.call") routine(block.arguments["routine"]?.value?.text().orEmpty())
                    visit(block.body); visit(block.otherwise)
                } }
                document.routines.find { it.id == id }?.let { visit(it.blocks) }
            }
            routine(root)
            return found
        }
    }
}
