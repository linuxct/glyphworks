package space.linuxct.glyphworks.ai

import android.content.Context
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.PendingApply
import space.linuxct.glyphworks.core.ai.PendingApplyCodec
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.designs.replaceViaBackup
import java.io.File
import java.io.FileOutputStream

/** Designs the assistant finished while nobody was looking. At most one per design. */
class PendingApplyStore(context: Context) {

    private val app: Context

    init {
        val application = context.applicationContext
        check(!application.isDeviceProtectedStorage) {
            "PendingApplyStore must be built from a credential-protected Context, " +
                "like the chat store it sits beside"
        }
        app = application
    }

    // Direct Boot: credential-protected filesDir cannot be created before the first
    // unlock. Open the directory on first use.
    private val dir: File by lazy { File(app.filesDir, PENDING_DIRECTORY_NAME) }

    /** Reads the record for [designId] and deletes it, so a bad one cannot retry forever. */
    fun take(designId: String): PendingApply? {
        val file = fileFor(designId) ?: return null
        val record = readPendingApply(file)
        deletePendingApply(dir, file.name)
        return record
    }

    fun put(record: PendingApply): Boolean {
        val target = fileFor(record.designId) ?: return false
        val tmp = File(dir, target.name + PENDING_TMP_SUFFIX)
        val backup = File(dir, target.name + PENDING_BAK_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                DebugLog.w(TAG, "could not create $dir")
                return false
            }
            val bytes = PendingApplyCodec.encode(record).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!replaceViaBackup(tmp, target, backup, File::renameTo)) {
                DebugLog.w(TAG, "could not put ${tmp.name} in place of ${target.name}")
                tmp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "could not record a deferred apply: ${e.message}")
            tmp.delete()
            false
        }
    }

    private fun fileFor(designId: String): File? =
        pendingApplyFileName(designId)?.let { File(dir, it) }

    private companion object {
        const val TAG = "PendingApply"
    }
}

internal const val PENDING_DIRECTORY_NAME = "pending_designs"

private const val PENDING_FILE_SUFFIX = ".json"
private const val PENDING_TMP_SUFFIX = ".tmp"
private const val PENDING_BAK_SUFFIX = ".bak"

/** Null for an id that may not name a file. Imported ids are somebody else's input. */
internal fun pendingApplyFileName(designId: String): String? =
    if (DesignCodec.isSafeId(designId)) designId + PENDING_FILE_SUFFIX else null

internal fun readPendingApply(file: File): PendingApply? = try {
    when {
        !file.isFile -> null
        file.length() > PendingApplyCodec.MAX_BYTES -> {
            DebugLog.w("PendingApply", "ignoring ${file.name}: ${file.length()} bytes")
            null
        }

        else -> PendingApplyCodec.decode(file.readText(Charsets.UTF_8))
    }
} catch (e: Exception) {
    DebugLog.w("PendingApply", "could not read ${file.name}: ${e.message}")
    null
}

internal fun deletePendingApply(directory: File, fileName: String): Boolean = try {
    val backupGone = File(directory, fileName + PENDING_BAK_SUFFIX).delete()
    val tmpGone = File(directory, fileName + PENDING_TMP_SUFFIX).delete()
    File(directory, fileName).delete() || backupGone || tmpGone
} catch (e: Exception) {
    DebugLog.w("PendingApply", "could not delete $fileName: ${e.message}")
    false
}
