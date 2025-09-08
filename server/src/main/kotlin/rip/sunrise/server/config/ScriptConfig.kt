package rip.sunrise.server.config

data class ScriptConfig(
    val name: String,
    val description: String,
    val version: Double,
    val author: String,
    val imageUrl: String,
    val threadUrl: String,
    val scriptId: Int,
    val storeId: Int,

    val jarFile: String,
    val optionFile: String
)