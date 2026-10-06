package space.linuxct.glyphworks.pipeline.store

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import space.linuxct.glyphworks.ui.pruneSharedCache
import space.linuxct.glyphworks.ui.sanitiseFileBaseName
import space.linuxct.pipeline.Diagnostic
import space.linuxct.pipeline.PipelineCodec
import space.linuxct.pipeline.PipelineDocument
import java.io.File

/** SAF and sharing use the same bounded codec as persistence. Call on Dispatchers.IO. */
object PipelineTransfer {
    const val MIME = "application/json"
    const val EXTENSION = ".glyph.pipeline.json"

    fun fileName(document: PipelineDocument): String =
        sanitiseFileBaseName(document.name).ifEmpty { "pipeline" } + EXTENSION

    fun read(context: Context, uri: Uri): PipelineCodec.Result = try {
        context.contentResolver.openInputStream(uri)?.use(PipelineCodec::decode)
            ?: unreadable()
    } catch (_: Exception) { unreadable() }

    fun write(context: Context, uri: Uri, document: PipelineDocument): Boolean {
        if ((PipelineCodec.validate(document) + PipelineStore.validateArtwork(document)).isNotEmpty()) return false
        return try {
            val output = try { context.contentResolver.openOutputStream(uri, "wt") }
                catch (_: Exception) { context.contentResolver.openOutputStream(uri) }
            if (output == null) false else {
                output.use { it.write(PipelineCodec.encode(document).toByteArray(Charsets.UTF_8)); it.flush() }
                true
            }
        } catch (_: Exception) { false }
    }

    /** The existing provider exposes only cache/shared; project storage remains private. */
    fun shareCopy(context: Context, document: PipelineDocument): Uri? {
        if ((PipelineCodec.validate(document) + PipelineStore.validateArtwork(document)).isNotEmpty()) return null
        return try {
            val dir = File(context.cacheDir, "shared")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val now = System.currentTimeMillis()
            pruneSharedCache(dir, now)
            dir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("pipeline-") && now - it.lastModified() >= 24L * 60 * 60 * 1000 }.forEach { old ->
                val children = old.listFiles().orEmpty()
                if (children.all { it.isFile && it.canonicalFile.parentFile == old.canonicalFile }) {
                    children.forEach { it.delete() }; old.delete()
                }
            }
            // A unique subdirectory prevents a second share overwriting a still-open grant.
            val shareDir = File(dir, "pipeline-${space.linuxct.pipeline.pipelineId()}")
            if (!shareDir.mkdir()) return null
            val file = File(shareDir, fileName(document))
            if (!atomicWrite(file, PipelineCodec.encode(document))) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) { null }
    }

    fun shareIntent(context: Context, uri: Uri, document: PipelineDocument): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, document.name)
            clipData = ClipData.newUri(context.contentResolver, document.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun unreadable() = PipelineCodec.Result.Invalid(listOf(Diagnostic("The pipeline file could not be read.")))
}
