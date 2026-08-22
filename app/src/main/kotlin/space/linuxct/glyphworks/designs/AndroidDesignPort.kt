package space.linuxct.glyphworks.designs

import space.linuxct.glyphworks.core.DesignPort
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.design.Design

class AndroidDesignPort(
    private val prefs: Prefs,
    private val store: DesignStore,
) : DesignPort {

    override fun selected(): Design? {
        val selectedId = prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)
        if (selectedId.isNotEmpty()) store.load(selectedId)?.let { return it }
        store.invalidate()
        return store.list().firstOrNull()
    }
}
