package space.linuxct.glyphworks.ui.pipeline

import space.linuxct.pipeline.*

enum class BlockBranch { BODY, OTHERWISE }

/** A position in a structured statement list. Owners are script or routine IDs. */
data class BlockLocation(
    val ownerId: String,
    val parentId: String? = null,
    val branch: BlockBranch = BlockBranch.BODY,
    val index: Int = 0,
)

/** Immutable editor commands. A failed command leaves the input document intact. */
object EditorDocument {
    /** Imported dependencies are copied into this file with fresh identity; nothing runs on import. */
    fun merge(document: PipelineDocument, source: PipelineDocument): PipelineDocument {
        val imported = space.linuxct.glyphworks.pipeline.store.PipelineReferences.remap(source)
        return document.copy(programs = document.programs + imported.programs, routines = document.routines + imported.routines,
            designs = document.designs + imported.designs, bindings = document.bindings + imported.bindings,
            requires = (document.requires + imported.requires).distinct(),
            editor = document.editor.copy(positions = document.editor.positions + imported.editor.positions))
    }

    fun blocks(document: PipelineDocument, location: BlockLocation): List<Block>? {
        val roots = roots(document, location.ownerId) ?: return null
        if (location.parentId == null) return roots
        val parent = find(roots, location.parentId) ?: return null
        return if (location.branch == BlockBranch.BODY) parent.body else parent.otherwise
    }

    fun block(document: PipelineDocument, id: String): Block? =
        document.programs.asSequence().flatMap { it.scripts.asSequence() }.mapNotNull { find(it.blocks, id) }.firstOrNull()
            ?: document.routines.firstNotNullOfOrNull { find(it.blocks, id) }

    fun location(document: PipelineDocument, id: String): BlockLocation? {
        document.programs.forEach { program -> program.scripts.forEach { script ->
            locate(script.blocks, id, BlockLocation(script.id))?.let { return it }
        } }
        document.routines.forEach { routine -> locate(routine.blocks, id, BlockLocation(routine.id))?.let { return it } }
        return null
    }

    fun insert(document: PipelineDocument, location: BlockLocation, block: Block): PipelineDocument {
        val siblings = blocks(document, location) ?: return document
        if (allIds(document).intersect(ids(block)).isNotEmpty()) return document
        return replaceList(document, location, siblings.toMutableList().apply {
            add(location.index.coerceIn(0, size), block)
        })
    }

    fun remove(document: PipelineDocument, id: String): PipelineDocument {
        val position = location(document, id) ?: return document
        val siblings = blocks(document, position) ?: return document
        val removed = block(document, id)?.let(::ids).orEmpty()
        return replaceList(document, position, siblings.filterNot { it.id == id }).let {
            it.copy(editor = it.editor.copy(
                collapsed = it.editor.collapsed - removed,
                selected = it.editor.selected?.takeUnless(removed::contains),
            ))
        }
    }

    fun update(document: PipelineDocument, block: Block): PipelineDocument {
        val position = location(document, block.id) ?: return document
        val siblings = blocks(document, position) ?: return document
        return replaceList(document, position, siblings.map { if (it.id == block.id) block else it })
    }

    fun canMove(document: PipelineDocument, id: String, destination: BlockLocation): Boolean {
        val source = block(document, id) ?: return false
        return blocks(document, destination) != null && destination.parentId !in ids(source)
    }

    fun move(document: PipelineDocument, id: String, destination: BlockLocation): PipelineDocument {
        if (!canMove(document, id, destination)) return document
        val source = location(document, id) ?: return document
        val value = block(document, id) ?: return document
        val sameList = source.ownerId == destination.ownerId && source.parentId == destination.parentId && source.branch == destination.branch
        val corrected = destination.copy(index = destination.index - if (sameList && source.index < destination.index) 1 else 0)
        val result = insert(remove(document, id), corrected, value)
        // Moving a folded block preserves its presentation metadata.
        return result.copy(editor = result.editor.copy(collapsed = document.editor.collapsed, selected = id))
    }

    fun duplicate(document: PipelineDocument, id: String): PipelineDocument {
        val position = location(document, id) ?: return document
        val source = block(document, id) ?: return document
        return insert(document, position.copy(index = position.index + 1), fresh(source))
    }

    fun fresh(block: Block): Block {
        val identities = ids(block).associateWith { pipelineId() }
        fun expression(value: Expression): Expression = value.copy(
            key = if (ExpressionCatalog[value.op]?.keyKind == "block") identities[value.key] ?: value.key else value.key,
            args = value.args.map(::expression),
        )
        fun copy(source: Block): Block = source.copy(id = identities.getValue(source.id), arguments = source.arguments.mapValues { expression(it.value) }, body = source.body.map(::copy), otherwise = source.otherwise.map(::copy))
        return copy(block)
    }

    /** Explicitly move the selected statement and every following sibling, never implicitly on drag. */
    fun moveStack(document: PipelineDocument, id: String, destination: BlockLocation): PipelineDocument {
        val source = location(document, id) ?: return document
        val moving = blocks(document, source)?.drop(source.index) ?: return document
        if (moving.any { destination.parentId in ids(it) } || blocks(document, destination) == null) return document
        val sameList = source.ownerId == destination.ownerId && source.parentId == destination.parentId && source.branch == destination.branch
        if (sameList && destination.index >= source.index) return document
        var result = moving.fold(document) { current, b -> remove(current, b.id) }
        moving.forEachIndexed { offset, block -> result = insert(result, destination.copy(index = destination.index + offset), block) }
        return result.copy(editor = result.editor.copy(collapsed = document.editor.collapsed, selected = id))
    }

    fun extractRoutine(document: PipelineDocument, id: String, name: String): PipelineDocument {
        val position = location(document, id) ?: return document
        val source = block(document, id) ?: return document
        val routine = Routine(name = name.ifBlank { "My routine" }, blocks = listOf(source))
        val result = remove(document, id).copy(routines = document.routines + routine)
        return insert(result, position, Block(op = "routine.call", arguments = mapOf("routine" to Expression.str(routine.id))))
    }

    fun script(document: PipelineDocument, script: Script): PipelineDocument = document.copy(programs = document.programs.map { program ->
        program.copy(scripts = program.scripts.map { if (it.id == script.id) script else it })
    })

    fun addScript(document: PipelineDocument, programId: String, script: Script, position: Position): PipelineDocument = document.copy(
        programs = document.programs.map { if (it.id == programId) it.copy(scripts = it.scripts + script) else it },
        editor = document.editor.copy(positions = document.editor.positions + (script.id to position)),
    )

    fun removeScript(document: PipelineDocument, id: String): PipelineDocument = document.copy(
        programs = document.programs.map { it.copy(scripts = it.scripts.filterNot { script -> script.id == id }) },
        editor = document.editor.copy(positions = document.editor.positions - id),
    )

    fun routine(document: PipelineDocument, routine: Routine): PipelineDocument = document.copy(routines = document.routines.map { if (it.id == routine.id) routine else it })

    private fun roots(document: PipelineDocument, owner: String): List<Block>? =
        document.programs.asSequence().flatMap { it.scripts.asSequence() }.firstOrNull { it.id == owner }?.blocks
            ?: document.routines.firstOrNull { it.id == owner }?.blocks

    private fun replaceList(document: PipelineDocument, location: BlockLocation, value: List<Block>): PipelineDocument {
        // Use a separate recursion so replacing an owner's root does not also replace every child.
        fun ownerTransform(roots: List<Block>): List<Block> = if (location.parentId == null) value else replaceNested(roots, location, value)
        return document.copy(
            programs = document.programs.map { p -> p.copy(scripts = p.scripts.map { s -> if (s.id == location.ownerId) s.copy(blocks = ownerTransform(s.blocks)) else s }) },
            routines = document.routines.map { r -> if (r.id == location.ownerId) r.copy(blocks = ownerTransform(r.blocks)) else r },
        )
    }

    private fun replaceNested(roots: List<Block>, location: BlockLocation, value: List<Block>): List<Block> = roots.map { b ->
        if (b.id == location.parentId) {
            if (location.branch == BlockBranch.BODY) b.copy(body = value) else b.copy(otherwise = value)
        } else b.copy(body = replaceNested(b.body, location, value), otherwise = replaceNested(b.otherwise, location, value))
    }

    private fun find(roots: List<Block>, id: String): Block? = roots.firstNotNullOfOrNull { b ->
        if (b.id == id) b else find(b.body, id) ?: find(b.otherwise, id)
    }

    private fun locate(roots: List<Block>, id: String, parent: BlockLocation): BlockLocation? {
        roots.forEachIndexed { index, block ->
            if (block.id == id) return parent.copy(index = index)
            locate(block.body, id, BlockLocation(parent.ownerId, block.id, BlockBranch.BODY))?.let { return it }
            locate(block.otherwise, id, BlockLocation(parent.ownerId, block.id, BlockBranch.OTHERWISE))?.let { return it }
        }
        return null
    }

    private fun ids(block: Block): Set<String> = setOf(block.id) + block.body.flatMap(::ids) + block.otherwise.flatMap(::ids)
    private fun allIds(document: PipelineDocument): Set<String> = document.programs.flatMap { it.scripts.flatMap { it.blocks.flatMap(::ids) } }.toSet() + document.routines.flatMap { it.blocks.flatMap(::ids) }
}

/** One completed gesture is one transaction; viewport navigation does not pollute undo history. */
class EditorHistory(initial: PipelineDocument, private val capacity: Int = 100) {
    var document: PipelineDocument = initial
        private set
    private val past = ArrayDeque<PipelineDocument>()
    private val future = ArrayDeque<PipelineDocument>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun change(next: PipelineDocument, record: Boolean = true): PipelineDocument {
        if (document == next) return document
        if (record) {
            past.addLast(document)
            while (past.size > capacity) past.removeFirst()
            future.clear()
        }
        document = next
        return document
    }
    fun undo(): PipelineDocument { if (past.isNotEmpty()) { future.addLast(document); document = past.removeLast() }; return document }
    fun redo(): PipelineDocument { if (future.isNotEmpty()) { past.addLast(document); document = future.removeLast() }; return document }
}
