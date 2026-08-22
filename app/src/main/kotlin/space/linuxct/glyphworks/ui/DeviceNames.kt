package space.linuxct.glyphworks.ui

import androidx.annotation.StringRes
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.PokemonCodename

@StringRes
fun PokemonCodename.displayNameRes(): Int = when (this) {
    PokemonCodename.BELLSPROUT -> R.string.device_bellsprout
    PokemonCodename.ARBOK -> R.string.device_arbok
}
