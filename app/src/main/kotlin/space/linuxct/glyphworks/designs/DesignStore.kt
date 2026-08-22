package space.linuxct.glyphworks.designs

import android.content.Context
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.newDesignId
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Designs live in device-protected storage, like the settings, because the two
 * `directBootAware` services read them before the first unlock after a reboot.
 */
class DesignStore(context: Context) {

    private val dir: File =
        File(context.createDeviceProtectedStorageContext().filesDir, DIRECTORY_NAME)

    private val hooks = DesignDeletionHooks()

    /** [listener] runs inside [delete], on the caller's thread and with this monitor held. */
    fun addDeletionListener(listener: (id: String) -> Unit) {
        hooks.add(listener)
    }

    private var index: List<Design>? = null

    @Synchronized
    fun list(): List<Design> {
        index?.let { return it }
        recoverBackups()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: emptyArray()
        val designs = files.mapNotNull { readFile(it) }
            .sortedByDescending { it.modifiedAt }
        index = designs
        return designs
    }

    @Synchronized
    fun load(id: String): Design? {
        val file = fileFor(id) ?: return null
        if (!file.isFile) recoverBackup(file)
        return readFile(file)
    }

    @Synchronized
    fun exists(id: String): Boolean = fileFor(id)?.isFile == true

    @Synchronized
    fun save(design: Design): Boolean {
        val validated = when (val result = DesignCodec.validate(design)) {
            is DesignCodec.Result.Ok -> result.design
            is DesignCodec.Result.Invalid -> {
                DebugLog.w(TAG, "refusing to save ${design.id}: ${result.reason}")
                return false
            }
        }
        val target = fileFor(validated.id) ?: return false
        val tmp = File(dir, validated.id + FILE_SUFFIX + TMP_SUFFIX)
        val backup = File(dir, target.name + BAK_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                DebugLog.w(TAG, "could not create $dir")
                return false
            }
            val bytes = DesignCodec.encode(validated).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // Without the fsync the rename can be durable while the contents are not.
                out.fd.sync()
            }
            if (!replaceViaBackup(tmp, target, backup, File::renameTo)) {
                DebugLog.w(TAG, "could not put ${tmp.name} in place of ${target.name}")
                tmp.delete()
                return false
            }
            index = null
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "save ${validated.id} failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val file = fileFor(id) ?: return false
        // Drop the index first, or a listener asking what exists reads a stale cache.
        index = null
        return deleteDesignFile(file, id, hooks)
    }

    @Synchronized
    fun storedIds(): Set<String> {
        val files = dir.listFiles() ?: return emptySet()
        return files.mapNotNullTo(HashSet(files.size)) { storedDesignId(it.name) }
    }

    @Synchronized
    fun allocateId(): String {
        var id = newDesignId()
        while (exists(id)) id = newDesignId()
        return id
    }

    @Synchronized
    fun invalidate() {
        index = null
    }

    // Never call this with an existing [target]: a backup beside an intact target is the
    // superseded copy, not the survivor.
    private fun recoverBackup(target: File) {
        val backup = File(dir, target.name + BAK_SUFFIX)
        if (!backup.isFile) return
        if (backup.renameTo(target)) {
            DebugLog.i(TAG, "recovered ${target.name} from its backup")
            index = null
        } else {
            DebugLog.w(TAG, "could not recover ${target.name} from ${backup.name}")
        }
    }

    private fun recoverBackups() {
        val suffix = FILE_SUFFIX + BAK_SUFFIX
        val backups = dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) } ?: return
        for (backup in backups) {
            val target = File(dir, backup.name.removeSuffix(BAK_SUFFIX))
            if (target.isFile) backup.delete() else recoverBackup(target)
        }
    }

    private fun readFile(file: File): Design? = try {
        file.inputStream().use { input ->
            when (val result = DesignCodec.decode(input)) {
                is DesignCodec.Result.Ok -> result.design
                is DesignCodec.Result.Invalid -> {
                    DebugLog.w(TAG, "skipping ${file.name}: ${result.reason}")
                    null
                }
            }
        }
    } catch (e: Exception) {
        DebugLog.w(TAG, "could not read ${file.name}: ${e.message}")
        null
    }

    private fun fileFor(id: String): File? =
        if (DesignCodec.isSafeId(id)) File(dir, id + FILE_SUFFIX) else null

    private companion object {
        const val TAG = "DesignStore"
        const val DIRECTORY_NAME = "designs"
    }
}

internal const val FILE_SUFFIX = ".json"
internal const val TMP_SUFFIX = ".tmp"

internal const val BAK_SUFFIX = ".bak"

/** This decides what counts as an orphan, so a wrong answer deletes a live design's data. */
internal fun storedDesignId(fileName: String): String? {
    val id = when {
        fileName.endsWith(FILE_SUFFIX + BAK_SUFFIX) -> fileName.removeSuffix(FILE_SUFFIX + BAK_SUFFIX)
        fileName.endsWith(FILE_SUFFIX + TMP_SUFFIX) -> fileName.removeSuffix(FILE_SUFFIX + TMP_SUFFIX)
        fileName.endsWith(FILE_SUFFIX) -> fileName.removeSuffix(FILE_SUFFIX)
        else -> return null
    }
    return id.takeIf { DesignCodec.isSafeId(it) }
}

// `CopyOnWriteArrayList` because registration happens at process start while deletes happen
// on whatever thread the UI used.
internal class DesignDeletionHooks {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun add(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun notifyDeleted(id: String) {
        for (listener in listeners) {
            try {
                listener(id)
            } catch (e: Exception) {
                DebugLog.w("DesignStore", "a deletion listener failed for $id: ${e.message}")
            }
        }
    }
}

internal fun deleteDesignFile(file: File, id: String, hooks: DesignDeletionHooks): Boolean {
    val deleted = try {
        file.delete()
    } catch (e: Exception) {
        DebugLog.w("DesignStore", "delete $id failed: ${e.message}")
        false
    }
    hooks.notifyDeleted(id)
    return deleted
}

// The old file moves aside, never away, and comes back if the replacement does not land. The
// first rename replaces atomically on POSIX and is normally the only step that runs.
internal fun replaceViaBackup(
    tmp: File,
    target: File,
    backup: File,
    rename: (File, File) -> Boolean,
): Boolean {
    if (rename(tmp, target)) return true
    if (!target.isFile) return false
    // Clear a stale backup, or it blocks the move aside on some filesystems.
    backup.delete()
    if (!rename(target, backup)) return false
    if (rename(tmp, target)) {
        backup.delete()
        return true
    }
    rename(backup, target)
    return false
}
