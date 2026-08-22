package space.linuxct.glyphworks.ai

import android.content.Context
import space.linuxct.glyphworks.designs.DesignStore

object DesignChatCleanup {

    fun install(context: Context, designs: DesignStore) {
        val app = context.applicationContext
        // Direct Boot: install runs from Core.init, so the store is only built once a
        // deletion arrives. A device-protected context has no chats to clean up.
        val chats = lazy {
            if (app.isDeviceProtectedStorage) null else ChatStore(app) { designs.storedIds() }
        }
        designs.addDeletionListener { id -> chats.value?.delete(id) }
    }
}
