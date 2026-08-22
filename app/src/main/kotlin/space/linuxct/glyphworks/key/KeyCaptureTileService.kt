package space.linuxct.glyphworks.key

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys

/** Named in AndroidManifest.xml, so never rename this class. */
class KeyCaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Core.init(this)
        refresh()
    }

    override fun onClick() {
        super.onClick()
        Core.init(this)
        val newValue = !Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)
        Core.prefs.putBoolean(PrefKeys.MASTER_TOGGLE, newValue)
        DebugLog.i(C, "essential key capture toggled -> $newValue")
        refresh()
    }

    private fun refresh() {
        val on = Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
            updateTile()
        }
    }

    private companion object {
        const val C = "Tile"
    }
}
