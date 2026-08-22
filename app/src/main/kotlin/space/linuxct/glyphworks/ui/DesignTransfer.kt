package space.linuxct.glyphworks.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import java.io.File

// `core/design/DesignCodec` is the trust boundary: nothing here validates a design.
// Everything that touches a stream blocks. Callers are on `Dispatchers.IO`.

internal const val DESIGN_MIME = "application/json"

internal const val DESIGN_FILE_EXTENSION = ".json"

private const val MAX_BASE_NAME = 48

private const val FALLBACK_BASE_NAME = "design"

// Must match the path in `res/xml/file_paths.xml` that the `FileProvider` exposes.
private const val SHARE_DIR = "shared"

private const val SHARE_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

private const val TAG = "DesignTransfer"

internal fun designFileName(design: Design): String {
    val base = sanitiseFileBaseName(design.name)
        .ifEmpty { sanitiseFileBaseName(design.id) }
        .ifEmpty { FALLBACK_BASE_NAME }
    return base + DESIGN_FILE_EXTENSION
}

/** Keeps letters and digits, turns every run of anything else into one hyphen. */
internal fun sanitiseFileBaseName(raw: String): String {
    val out = StringBuilder(minOf(raw.length, MAX_BASE_NAME))
    var pendingSeparator = false
    for (ch in raw) {
        if (ch.isLetterOrDigit()) {
            if (pendingSeparator && out.isNotEmpty()) out.append('-')
            pendingSeparator = false
            out.append(ch)
            if (out.length >= MAX_BASE_NAME) break
        } else {
            pendingSeparator = true
        }
    }
    return out.toString()
}

/** An import keeps its author and `createdAt`; only this phone's copy is new. */
internal fun importedDesign(incoming: Design, freshId: String, importedAt: String): Design =
    incoming.copy(id = freshId, modifiedAt = importedAt)

// Pass the stream, never a string read here: `DesignCodec.decode` stops one byte past
// its size cap, so an oversized file is never fully read.
internal fun readDesign(context: Context, uri: Uri): DesignCodec.Result = try {
    context.contentResolver.openInputStream(uri)?.use { DesignCodec.decode(it) }
        ?: DesignCodec.Result.Invalid(DesignCodec.REASON_UNREADABLE)
} catch (e: Exception) {
    DebugLog.w(TAG, "import from $uri failed: ${e.message}")
    DesignCodec.Result.Invalid(DesignCodec.REASON_UNREADABLE)
}

internal fun writeDesign(context: Context, uri: Uri, design: Design): Boolean = try {
    val bytes = DesignCodec.encode(design).toByteArray(Charsets.UTF_8)
    // "wt" truncates first, so overwriting a longer file leaves no tail behind. Some
    // providers reject the mode, and then the file has to do.
    val stream = try {
        context.contentResolver.openOutputStream(uri, "wt")
    } catch (e: Exception) {
        context.contentResolver.openOutputStream(uri)
    }
    if (stream == null) {
        false
    } else {
        stream.use {
            it.write(bytes)
            it.flush()
        }
        true
    }
} catch (e: Exception) {
    DebugLog.w(TAG, "export of ${design.id} failed: ${e.message}")
    false
}

internal fun shareCacheDir(context: Context): File = File(context.cacheDir, SHARE_DIR)

internal fun writeShareCopy(context: Context, design: Design): Uri? = try {
    val dir = shareCacheDir(context)
    if (!dir.isDirectory && !dir.mkdirs()) {
        DebugLog.w(TAG, "could not create $dir")
        null
    } else {
        val file = File(dir, designFileName(design))
        file.writeText(DesignCodec.encode(design), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
} catch (e: Exception) {
    DebugLog.w(TAG, "share copy of ${design.id} failed: ${e.message}")
    null
}

// The grant needs both parts: the flag authorises the receiver, and the ClipData says
// which URI it covers. Without the clip, some receivers get a SecurityException.
internal fun shareIntent(context: Context, uri: Uri, design: Design): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = DESIGN_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, design.name)
        clipData = ClipData.newUri(context.contentResolver, design.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/** [context] must be the hosting Activity, so the chooser needs no `NEW_TASK` flag. */
internal fun startShare(context: Context, uri: Uri, design: Design, chooserTitle: String): Boolean = try {
    context.startActivity(Intent.createChooser(shareIntent(context, uri, design), chooserTitle))
    true
} catch (e: Exception) {
    DebugLog.w(TAG, "share sheet for ${design.id} failed: ${e.message}")
    false
}

internal fun pruneSharedCache(
    dir: File,
    now: Long,
    maxAgeMs: Long = SHARE_CACHE_MAX_AGE_MS,
): Int {
    val files = dir.listFiles() ?: return 0
    var deleted = 0
    for (file in files) {
        val age = now - file.lastModified()
        val datedFromTheFuture = age < 0
        val stale = age >= maxAgeMs || datedFromTheFuture
        if (!stale) continue
        if (file.isFile && file.delete()) deleted++
    }
    return deleted
}
