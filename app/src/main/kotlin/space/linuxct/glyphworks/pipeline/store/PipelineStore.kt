package space.linuxct.glyphworks.pipeline.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.linuxct.glyphworks.designs.replaceViaBackup
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.pipeline.Diagnostic
import space.linuxct.pipeline.PipelineCodec
import space.linuxct.pipeline.PipelineDocument
import space.linuxct.pipeline.ProgramKind
import space.linuxct.pipeline.pipelineId
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Project data only. Execution state and activation preferences are deliberately not stored here. */
class PipelineStore internal constructor(private val root: File) {
    constructor(context: Context) : this(
        File(context.createDeviceProtectedStorageContext().filesDir, DIRECTORY),
    )

    data class Snapshot(val document: PipelineDocument, val generation: Long, val revision: Int? = null)
    data class ProjectSummary(
        val id: String,
        val name: String,
        val kind: ProgramKind,
        val generation: Long,
        val appliedRevision: Int?,
        val hasDraft: Boolean,
        val modifiedAt: String,
        val panels: Set<Int>,
    )
    sealed interface SaveResult {
        data class Saved(val snapshot: Snapshot) : SaveResult
        data class Conflict(val currentGeneration: Long) : SaveResult
        data class Invalid(val diagnostics: List<Diagnostic>) : SaveResult
        data class Failed(val message: String) : SaveResult
    }

    @Serializable
    private data class Head(val generation: Long = 0, val revision: Int = 0)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() { listeners.forEach { runCatching(it) } }

    @Synchronized
    fun list(): List<ProjectSummary> = root.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory && safeId(it.name) }
        .mapNotNull { directory ->
            val applied = loadApplied(directory.name)
            val draft = loadDraft(directory.name)
            val latest = draft ?: applied ?: return@mapNotNull null
            ProjectSummary(
                directory.name, latest.document.name, latest.document.entry()?.kind ?: ProgramKind.TOY,
                generation(directory.name), applied?.revision, draft != null,
                latest.document.modifiedAt, latest.document.panels,
            )
        }.sortedByDescending { it.modifiedAt }.toList()

    /** Dirty drafts are separate from applied revisions and may contain incomplete blocks. */
    @Synchronized
    fun loadDraft(id: String): Snapshot? {
        val dir = directory(id) ?: return null
        val head = head(dir)
        val file = recovered(File(dir, DRAFT))
        if (!file.isFile) return null
        val marker = readMarker(file) ?: return null
        if (marker.generation <= head.generation) return null
        val document = readDocument(File(dir, "draft-${marker.generation}.json"), draft = true) ?: return null
        if (document.id != id) return null
        return Snapshot(document, marker.generation, head.revision.takeIf { it > 0 })
    }

    @Synchronized
    fun loadApplied(id: String): Snapshot? {
        val dir = directory(id) ?: return null
        val head = head(dir)
        if (head.revision <= 0) return null
        val document = readDocument(revisionFile(dir, head.revision), draft = false) ?: return null
        if (document.id != id) return null
        return Snapshot(document, generation(id), head.revision)
    }

    @Synchronized
    fun generation(id: String): Long {
        val dir = directory(id) ?: return 0
        return maxOf(head(dir).generation, readMarker(recovered(File(dir, DRAFT)))?.generation ?: 0)
    }

    @Synchronized
    fun saveDraft(document: PipelineDocument, expectedGeneration: Long? = null): SaveResult =
        save(document, expectedGeneration, apply = false)

    @Synchronized
    fun apply(document: PipelineDocument, expectedGeneration: Long? = null): SaveResult =
        save(document, expectedGeneration, apply = true)

    private fun save(document: PipelineDocument, expected: Long?, apply: Boolean): SaveResult {
        val dir = directory(document.id) ?: return invalid("Invalid project identity.")
        val current = generation(document.id)
        if (expected != null && expected != current) return SaveResult.Conflict(current)
        val structural = PipelineCodec.validateDraft(document)
        if (structural.isNotEmpty()) return SaveResult.Invalid(structural)
        if (apply) {
            val artworkErrors = validateArtwork(document)
            if (artworkErrors.isNotEmpty()) return SaveResult.Invalid(artworkErrors)
        }
        val encoded = runCatching { PipelineCodec.encode(document) }.getOrNull()
            ?: return invalid("The project could not be encoded.")
        val validation = if (apply) PipelineCodec.decode(encoded) else PipelineCodec.decodeDraft(encoded)
        if (validation is PipelineCodec.Result.Invalid) return SaveResult.Invalid(validation.diagnostics)
        val normalized = (validation as PipelineCodec.Result.Ok).document.copy(modifiedAt = Instant.now().toString())
        val next = current + 1
        val oldHead = head(dir)
        val revision = if (apply) oldHead.revision + 1 else oldHead.revision
        val payload = if (apply) revisionFile(dir, revision) else File(dir, "draft-$next.json")
        if (!atomicWrite(payload, PipelineCodec.encode(normalized))) return SaveResult.Failed("Could not save the project.")
        val pointer = File(dir, if (apply) APPLIED else DRAFT)
        if (!atomicWrite(pointer, json.encodeToString(Head.serializer(), Head(next, revision)))) {
            return SaveResult.Failed("Could not finish saving the project. The previous version is unchanged.")
        }
        prune(dir, next, revision)
        changed()
        return SaveResult.Saved(Snapshot(normalized, next, revision.takeIf { it > 0 }))
    }

    /** Imports always create inactive local copies. This store has no activation API. */
    @Synchronized
    fun importDocument(document: PipelineDocument): SaveResult {
        val diagnostics = PipelineCodec.validate(document)
        if (diagnostics.isNotEmpty()) return SaveResult.Invalid(diagnostics)
        val fresh = PipelineReferences.remap(document, ::allocateId)
        return apply(fresh, expectedGeneration = 0)
    }

    @Synchronized
    fun duplicate(id: String, name: String): SaveResult {
        val source = loadDraft(id) ?: loadApplied(id) ?: return SaveResult.Failed("Project not found.")
        val fresh = PipelineReferences.remap(source.document, ::allocateId).copy(name = name)
        return if (source == loadDraft(id)) saveDraft(fresh, 0) else apply(fresh, 0)
    }

    @Synchronized
    fun revisions(id: String): List<Int> {
        val dir = directory(id) ?: return emptyList()
        val applied = head(dir).revision
        return dir.listFiles().orEmpty().mapNotNull { file ->
            file.name.removePrefix("revision-").removeSuffix(".json").toIntOrNull()
                ?.takeIf { file.name == "revision-$it.json" && it in 1..applied }
        }.sortedDescending()
    }

    @Synchronized
    fun loadRevision(id: String, revision: Int): PipelineDocument? {
        if (revision !in revisions(id)) return null
        val dir = directory(id) ?: return null
        return readDocument(revisionFile(dir, revision), false)?.takeIf { it.id == id }
    }

    @Synchronized
    fun restoreRevision(id: String, revision: Int, expectedGeneration: Long? = null): SaveResult {
        val document = loadRevision(id, revision) ?: return SaveResult.Failed("Revision not found.")
        return apply(document, expectedGeneration)
    }

    @Synchronized
    fun discardDraft(id: String): Boolean {
        val dir = directory(id) ?: return false
        val draft = readMarker(recovered(File(dir, DRAFT))) ?: return true
        val oldHead = head(dir)
        // Discard is a new generation too, or stale editors could silently become current again.
        if (!atomicWrite(File(dir, APPLIED), json.encodeToString(Head.serializer(), oldHead.copy(generation = generation(id) + 1)))) return false
        val deleted = File(dir, DRAFT).delete()
        if (deleted) {
            File(dir, "$DRAFT.bak").delete()
            File(dir, "draft-${draft.generation}.json").delete()
            changed()
        }
        return deleted
    }

    /** Caller detaches assignments before deleting. Documents embed dependencies, so no live file references remain. */
    @Synchronized
    fun delete(id: String): Boolean {
        val dir = directory(id) ?: return false
        if (!dir.isDirectory) return false
        // Delete only store-owned regular files; never follow a directory tree supplied by an import.
        val files = dir.listFiles().orEmpty()
        if (files.any { !it.isFile || !ownedFile.matches(it.name) || it.canonicalFile.parentFile != dir.canonicalFile }) return false
        val deleted = files.all { it.delete() } && dir.delete()
        if (deleted) changed()
        return deleted
    }

    @Synchronized
    fun allocateId(): String {
        var id = pipelineId()
        while (directory(id)?.exists() == true) id = pipelineId()
        return id
    }

    private fun directory(id: String): File? {
        if (!safeId(id)) return null
        val dir = File(root, id)
        return dir.takeIf { runCatching { it.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false) }
    }

    private fun head(dir: File): Head = readMarker(recovered(File(dir, APPLIED))) ?: Head()
    private fun readMarker(file: File): Head? = runCatching {
        if (!file.isFile || file.length() > 1024) return null
        json.decodeFromString(Head.serializer(), file.readText()).takeIf { it.generation >= 0 && it.revision >= 0 }
    }.getOrNull()

    private fun readDocument(file: File, draft: Boolean): PipelineDocument? = runCatching {
        recovered(file).inputStream().use { stream ->
            val result = if (draft) PipelineCodec.decodeDraft(stream) else PipelineCodec.decode(stream)
            (result as? PipelineCodec.Result.Ok)?.document
        }
    }.getOrNull()

    private fun revisionFile(dir: File, revision: Int) = File(dir, "revision-$revision.json")

    private fun prune(dir: File, generation: Long, revision: Int) {
        dir.listFiles().orEmpty().forEach { file ->
            val oldDraft = file.name.removePrefix("draft-").removeSuffix(".json").toLongOrNull()
            if (oldDraft != null && file.name == "draft-$oldDraft.json" && oldDraft < generation) file.delete()
            val oldRevision = file.name.removePrefix("revision-").removeSuffix(".json").toIntOrNull()
            if (oldRevision != null && file.name == "revision-$oldRevision.json" && oldRevision <= revision - HISTORY_LIMIT) file.delete()
        }
    }

    companion object {
        const val DIRECTORY = "pipelines"
        const val HISTORY_LIMIT = 12
        private const val DRAFT = "draft-head.json"
        private const val APPLIED = "applied-head.json"
        private val ownedFile = Regex("(?:draft-head|applied-head|draft-[0-9]+|revision-[0-9]+)\\.json(?:\\.tmp|\\.bak)?")
        internal fun validateArtwork(document: PipelineDocument): List<Diagnostic> = document.designs.mapNotNull { (id, art) ->
            when (val result = DesignCodec.decode(art.toString())) {
                is DesignCodec.Result.Invalid -> Diagnostic("Artwork $id: ${result.reason}", fatal = true)
                is DesignCodec.Result.Ok -> if (result.design.id != id) Diagnostic("Artwork identity does not match $id", fatal = true) else null
            }
        }
        fun safeId(id: String): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(id)
        private fun invalid(message: String) = SaveResult.Invalid(listOf(Diagnostic(message)))
    }
}

/** Temp contents are fsynced before the pointer rename becomes visible. */
internal fun atomicWrite(target: File, content: String): Boolean {
    val parent = target.parentFile ?: return false
    val tmp = File(parent, target.name + ".tmp")
    return try {
        if (!parent.isDirectory && !parent.mkdirs()) return false
        FileOutputStream(tmp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        replaceViaBackup(tmp, target, File(target.parentFile, target.name + ".bak"), File::renameTo)
    } catch (_: Exception) {
        false
    } finally {
        tmp.delete()
    }
}

internal fun recovered(target: File): File {
    val backup = File(target.parentFile, target.name + ".bak")
    if (!target.isFile && backup.isFile) backup.renameTo(target)
    return target
}
