package space.linuxct.glyphworks.ui.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.newDesignId
import space.linuxct.glyphworks.pipeline.native.NativeCatalog
import space.linuxct.pipeline.*

/** Materialize local artwork and inherited settings before producing an independent share copy. */
internal fun portablePipeline(
    source: PipelineDocument,
    prefs: Prefs,
    selectedDesign: () -> Design?,
    loadDesign: (String) -> Design?,
): PipelineDocument {
    // Explicitly clearing the custom design is distinct from inheriting the current toy.
    val explicitlyEmpty = allEditorBlocks(source).filter { block ->
        ((block.arguments["parameters"]?.value as? Value.Record)?.fields?.get("asset") as? Value.Text)?.value == ""
    }.mapTo(mutableSetOf()) { it.id }
    val frozen = NativeCatalog.exportSettings(source, prefs)
    val designs = frozen.designs.toMutableMap()
    val bindings = frozen.bindings.toMutableMap()
    var selected: Design? = null
    var queried = false
    fun active(): Design? { if (!queried) { selected = selectedDesign(); queried = true }; return selected }
    fun freeze(block: Block, inheritedEmpty: Boolean = false): Block {
        val empty = inheritedEmpty || block.id in explicitlyEmpty
        val nested = block.copy(body = block.body.map { freeze(it, empty) }, otherwise = block.otherwise.map { freeze(it, empty) })
        if (nested.op != "display.toy" || nested.arguments["toy"]?.value?.text()?.removePrefix("glyphworks.") != "custom") return nested
        if (nested.arguments["binding:design"]?.value?.text() in bindings) return nested
        val expression = nested.arguments["parameters"]
        if (expression != null && expression.op != "literal") return nested
        val fields = (expression?.value as? Value.Record)?.fields.orEmpty()
        val requested = fields["asset"]?.text().orEmpty()
        val embedded = designs[requested]?.let { (DesignCodec.decode(it.toString()) as? DesignCodec.Result.Ok)?.design }
        val original = embedded ?: if (requested.isNotBlank()) loadDesign(requested) else if (empty) null else active()
        if (original == null) { require(requested.isBlank()) { "Referenced Custom Design is unavailable." }; return nested }
        val encoded = Json.parseToJsonElement(DesignCodec.encode(original)) as JsonObject
        val copy = if (original.id in designs && designs[original.id] != encoded) original.copy(id = newDesignId()) else original
        designs[copy.id] = Json.parseToJsonElement(DesignCodec.encode(copy)) as JsonObject
        val bindingId = pipelineId()
        bindings[bindingId] = AssetBinding(copy.id)
        return nested.copy(arguments = nested.arguments + ("parameters" to Expression.literal(Value.Record(fields - "asset"))) + ("binding:design" to Expression.str(bindingId)))
    }
    val programs = frozen.programs.map { p -> p.copy(scripts = p.scripts.map { it.copy(blocks = it.blocks.map { block -> freeze(block) }) }) }
    val routines = frozen.routines.map { it.copy(blocks = it.blocks.map { block -> freeze(block) }) }
    return frozen.copy(programs = programs, routines = routines, designs = designs, bindings = bindings)
}
