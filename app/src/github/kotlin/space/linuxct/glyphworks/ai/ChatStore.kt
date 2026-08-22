package space.linuxct.glyphworks.ai

import android.content.Context
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.ChatTranscript
import space.linuxct.glyphworks.core.ai.ChatTranscriptCodec
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.designs.replaceViaBackup
import java.io.File
import java.io.FileOutputStream

/** One conversation per design, in credential-protected storage. Nothing here throws. */
class ChatStore(
    context: Context,
    private val designIds: () -> Set<String>,
) {

    private val app: Context

    init {
        val application = context.applicationContext
        check(!application.isDeviceProtectedStorage) {
            "ChatStore must be built from a credential-protected Context; " +
                "a conversation must not be readable before the first unlock"
        }
        app = application
    }

    // Direct Boot: credential-protected filesDir cannot be created while the device is
    // locked, and Core.init runs in that state. Every method below runs after unlock.
    private val dir: File by lazy {
        File(app.filesDir, DIRECTORY_NAME).also { pruneOrphans(it) }
    }

    fun load(designId: String): ChatTranscript? {
        val file = fileFor(designId) ?: return null
        if (!file.isFile) recoverBackup(file)
        return readTranscript(file)
    }

    fun save(transcript: ChatTranscript): Boolean {
        val target = fileFor(transcript.designId) ?: return false
        val tmp = File(dir, target.name + TMP_SUFFIX)
        val backup = File(dir, target.name + BAK_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                DebugLog.w(TAG, "could not create $dir")
                return false
            }
            val bytes = ChatTranscriptCodec.encode(transcript).toByteArray(Charsets.UTF_8)
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
            DebugLog.w(TAG, "save ${transcript.designId} failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    fun delete(designId: String): Boolean {
        val name = chatFileName(designId) ?: return false
        return deleteTranscript(dir, name)
    }

    private fun recoverBackup(target: File) {
        val backup = File(dir, target.name + BAK_SUFFIX)
        if (!backup.isFile) return
        if (!backup.renameTo(target)) {
            DebugLog.w(TAG, "could not recover ${target.name} from ${backup.name}")
        }
    }

    private fun pruneOrphans(directory: File) {
        val files = directory.listFiles() ?: return
        val live = try {
            designIds()
        } catch (e: Exception) {
            DebugLog.w(TAG, "not pruning: could not list designs (${e.message})")
            return
        }
        var gone = 0
        for (file in orphanChats(files.map { it.name }, live)) {
            try {
                if (File(directory, file).delete()) gone++
            } catch (e: Exception) {
                DebugLog.w(TAG, "could not delete the orphaned $file: ${e.message}")
            }
        }
        if (gone > 0) DebugLog.i(TAG, "removed $gone conversation file(s) with no design")
    }

    private fun fileFor(designId: String): File? =
        chatFileName(designId)?.let { File(dir, it) }

    private companion object {
        const val TAG = "ChatStore"
    }
}

internal const val DIRECTORY_NAME = "chats"

private const val FILE_SUFFIX = ".json"
private const val TMP_SUFFIX = ".tmp"
private const val BAK_SUFFIX = ".bak"

/** Null for an id that may not name a file. Imported ids are somebody else's input. */
internal fun chatFileName(designId: String): String? =
    if (DesignCodec.isSafeId(designId)) designId + FILE_SUFFIX else null

internal fun orphanChats(fileNames: List<String>, liveDesignIds: Set<String>): List<String> =
    fileNames.filter { name ->
        val id = when {
            name.endsWith(FILE_SUFFIX + BAK_SUFFIX) -> name.removeSuffix(FILE_SUFFIX + BAK_SUFFIX)
            name.endsWith(FILE_SUFFIX + TMP_SUFFIX) -> name.removeSuffix(FILE_SUFFIX + TMP_SUFFIX)
            name.endsWith(FILE_SUFFIX) -> name.removeSuffix(FILE_SUFFIX)
            else -> return@filter false
        }
        DesignCodec.isSafeId(id) && id !in liveDesignIds
    }

internal fun deleteTranscript(directory: File, fileName: String): Boolean = try {
    val backupGone = File(directory, fileName + BAK_SUFFIX).delete()
    val tmpGone = File(directory, fileName + TMP_SUFFIX).delete()
    File(directory, fileName).delete() || backupGone || tmpGone
} catch (e: Exception) {
    DebugLog.w("ChatStore", "could not delete $fileName: ${e.message}")
    false
}

internal fun readTranscript(file: File): ChatTranscript? = try {
    when {
        !file.isFile -> null
        file.length() > ChatTranscriptCodec.MAX_BYTES -> {
            DebugLog.w("ChatStore", "ignoring ${file.name}: ${file.length()} bytes")
            null
        }

        else -> ChatTranscriptCodec.decode(file.readText(Charsets.UTF_8))
    }
} catch (e: Exception) {
    DebugLog.w("ChatStore", "could not read ${file.name}: ${e.message}")
    null
}
