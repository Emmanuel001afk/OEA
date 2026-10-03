package app.lawnchair.oea.gameboost

data class OeaGameProfile(
    val packageName: String,
    val boostEnabled: Boolean = true,
    val dndEnabled: Boolean = true,
    val overlayEnabled: Boolean = true,
    val monitorMemory: Boolean = true,
    val monitorThermal: Boolean = true
)
