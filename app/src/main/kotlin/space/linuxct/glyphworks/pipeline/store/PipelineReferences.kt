package space.linuxct.glyphworks.pipeline.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.linuxct.pipeline.*
import java.time.Instant

/** Identity changes are structural. Free text, event names, and native opcodes are never rewritten. */
object PipelineReferences {
    private val referenceKeys = setOf("routine", "program", "asset", "binding", "variable", "resultVariable")

    fun remap(source: PipelineDocument, newId: () -> String = ::pipelineId): PipelineDocument {
        val ids = linkedMapOf<String, String>()
        fun identity(old: String): String = ids.getOrPut(old) { newId() }
        identity(source.id)
        fun visit(blocks: List<Block>) {
            blocks.forEach { block -> identity(block.id); visit(block.body); visit(block.otherwise) }
        }
        source.programs.forEach { program ->
            identity(program.id)
            program.parameters.forEach { identity(it.id) }
            program.variables.forEach { identity(it.id) }
            program.scripts.forEach { script -> identity(script.id); visit(script.blocks) }
        }
        source.routines.forEach { routine ->
            identity(routine.id)
            routine.parameters.forEach { identity(it.id) }
            routine.variables.forEach { identity(it.id) }
            visit(routine.blocks)
        }
        source.designs.keys.forEach { identity(it) }
        source.bindings.keys.forEach { identity(it) }
        fun mapped(old: String): String = ids[old] ?: old
        fun expression(expr: Expression, reference: Boolean = false): Expression = expr.copy(
            key = if (expr.op in setOf("variable", "parameter", "block.count")) mapped(expr.key) else expr.key,
            value = if (reference && expr.value is Value.Text) Value.Text(mapped((expr.value as Value.Text).value)) else expr.value,
            args = expr.args.map { expression(it) },
        )
        fun block(original: Block): Block = original.copy(
            id = mapped(original.id),
            arguments = original.arguments.map { (key, value) ->
                val argumentKey = if (key.startsWith("arg:")) "arg:" + mapped(key.removePrefix("arg:")) else key
                argumentKey to expression(value, key in referenceKeys || key.startsWith("binding:"))
            }.toMap(),
            body = original.body.map { block(it) },
            otherwise = original.otherwise.map { block(it) },
        )
        val now = Instant.now().toString()
        return source.copy(
            id = mapped(source.id), modifiedAt = now,
            entryPoint = mapped(source.entryPoint),
            programs = source.programs.map { program -> program.copy(
                id = mapped(program.id), template = null,
                parameters = program.parameters.map { it.copy(id = mapped(it.id)) },
                values = program.values.mapKeys { mapped(it.key) },
                variables = program.variables.map { it.copy(id = mapped(it.id)) },
                scripts = program.scripts.map { script -> script.copy(
                    id = mapped(script.id),
                    trigger = script.trigger.copy(condition = script.trigger.condition?.let { expression(it) }),
                    blocks = script.blocks.map { block(it) },
                ) },
            ) },
            routines = source.routines.map { routine -> routine.copy(
                id = mapped(routine.id),
                parameters = routine.parameters.map { it.copy(id = mapped(it.id)) },
                variables = routine.variables.map { it.copy(id = mapped(it.id)) },
                blocks = routine.blocks.map { block(it) },
            ) },
            designs = source.designs.map { (id, design) ->
                mapped(id) to JsonObject(design + ("id" to JsonPrimitive(mapped(id))))
            }.toMap(),
            bindings = source.bindings.map { (id, binding) -> mapped(id) to binding.copy(assetId = mapped(binding.assetId), routineId = binding.routineId?.let(::mapped)) }.toMap(),
            preview = source.preview.copy(thumbnailAssetId = source.preview.thumbnailAssetId?.let(::mapped)),
            editor = source.editor.copy(
                positions = source.editor.positions.mapKeys { mapped(it.key) },
                collapsed = source.editor.collapsed.mapTo(linkedSetOf(), ::mapped),
                selected = source.editor.selected?.let(::mapped),
            ),
        )
    }

    /**
     * Produce a self-contained subset. Literal references are required by the validator;
     * no project points to a mutable object in another file.
     */
    fun closure(source: PipelineDocument, entryPoint: String = source.entryPoint): PipelineDocument {
        val programs = linkedSetOf(entryPoint)
        val routines = source.bindings.values.mapNotNullTo(linkedSetOf()) { it.routineId }
        val assets = source.preview.thumbnailAssetId?.let { linkedSetOf(it) } ?: linkedSetOf()
        val scannedPrograms = mutableSetOf<String>()
        val scannedRoutines = mutableSetOf<String>()
        val blockOwners = buildMap<String, Pair<Boolean, String>> {
            fun index(blocks: List<Block>, program: Boolean, owner: String) { blocks.forEach { put(it.id, program to owner); index(it.body, program, owner); index(it.otherwise, program, owner) } }
            source.programs.forEach { p -> p.scripts.forEach { index(it.blocks, true, p.id) } }
            source.routines.forEach { index(it.blocks, false, it.id) }
        }
        fun expression(e: Expression) {
            if (e.op == "block.count") blockOwners[e.key]?.let { (program, owner) -> if (program) programs += owner else routines += owner }
            e.args.forEach(::expression)
        }
        fun scan(blocks: List<Block>) {
            for (block in blocks) {
                block.arguments.values.forEach(::expression)
                for ((key, value) in block.arguments) {
                    val id = (value.value as? Value.Text)?.value ?: continue
                    when (key) {
                        "program" -> programs += id
                        "routine" -> routines += id
                        "asset" -> assets += id
                        "binding" -> source.bindings[id]?.assetId?.let { assets += it }
                        else -> if (key.startsWith("binding:")) source.bindings[id]?.assetId?.let { assets += it }
                    }
                }
                scan(block.body); scan(block.otherwise)
            }
        }
        do {
            val pendingPrograms = programs - scannedPrograms
            val pendingRoutines = routines - scannedRoutines
            pendingPrograms.forEach { id ->
                scannedPrograms += id
                source.programs.firstOrNull { it.id == id }?.scripts?.forEach { it.trigger.condition?.let(::expression); scan(it.blocks) }
            }
            pendingRoutines.forEach { id ->
                scannedRoutines += id
                source.routines.firstOrNull { it.id == id }?.let { scan(it.blocks) }
            }
        } while ((programs - scannedPrograms).isNotEmpty() || (routines - scannedRoutines).isNotEmpty())
        // Native behavior bindings are document-level named slots and implicit dependencies.
        assets += source.bindings.values.map { it.assetId }.filter(String::isNotEmpty)
        return source.copy(
            entryPoint = entryPoint,
            programs = source.programs.filter { it.id in programs },
            routines = source.routines.filter { it.id in routines },
            designs = source.designs.filterKeys { it in assets },
        )
    }

    fun usages(document: PipelineDocument, referencedId: String): List<String> {
        val usages = mutableListOf<String>()
        if (document.preview.thumbnailAssetId == referencedId) usages += document.id
        fun expressionReferences(e: Expression): Boolean = e.op == "block.count" && e.key == referencedId || e.args.any(::expressionReferences)
        fun inspect(blocks: List<Block>) {
            for (block in blocks) {
                if (block.arguments.values.any(::expressionReferences)) usages += block.id
                if (block.arguments.any { (key, value) -> (key in referenceKeys || key.startsWith("binding:")) && (value.value as? Value.Text)?.value == referencedId }) usages += block.id
                inspect(block.body); inspect(block.otherwise)
            }
        }
        document.programs.forEach { program -> program.scripts.forEach { if (it.trigger.condition?.let(::expressionReferences) == true) usages += it.id; inspect(it.blocks) } }
        document.routines.forEach { inspect(it.blocks) }
        document.bindings.forEach { (id, binding) -> if (binding.routineId == referencedId || binding.assetId == referencedId) usages += id }
        return usages
    }
}
