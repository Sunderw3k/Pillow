package rip.sunrise.server.config

import com.google.gson.Gson
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rip.sunrise.packets.clientbound.ScriptWrapper
import java.io.File
import java.nio.file.Path

class Config(private val configDir: Path) {
    val gson = Gson()

    var revisionData = ""
    var scripts = mutableListOf<Script>()
    var serverUrl = ""

    fun load() {
        runCatching {
            val configFile = configDir.resolve("config.json").toFile()
            if (!configFile.exists()) {
                error("File config.json doesn't exist!")
            }

            val config = gson.fromJson(configFile.reader(), Config::class.java)

            val revisionFile = File(config.revisionFile)
            if (!revisionFile.isFile) {
                error("Revision file ${revisionFile.absolutePath} isn't a normal file!")
            }

            val scriptConfigDirectory = File(config.scriptConfigDir)
            if (!scriptConfigDirectory.isDirectory) {
                error("Script config directory ${scriptConfigDirectory.absolutePath} is not a directory!")
            }

            scripts.clear()
            scriptConfigDirectory.listFiles()!!.forEachIndexed { index, file ->
                if (file.extension != "json") {
                    logger.warn("Skipping non-JSON file {}", file.name)
                    return@forEachIndexed
                }

                runCatching {
                    val scriptConfig =
                        Gson().fromJson(file.reader(), ScriptConfig::class.java) ?: error("Script config EOF")

                    val scriptJar = configDir.resolve(scriptConfig.jarFile).toFile()
                    if (!scriptJar.isFile) {
                        error("Script jar ${scriptJar.absolutePath} isn't a normal file!")
                    }

                    val optionFile = configDir.resolve(scriptConfig.optionFile).toFile()
                    if (!optionFile.isFile) {
                        error("Option file ${scriptJar.absolutePath} isn't a normal file!")
                    }

                    val metadata = ScriptWrapper(
                        0,
                        scriptConfig.description,
                        scriptConfig.name,
                        0,
                        scriptConfig.version,
                        "",
                        "",
                        scriptConfig.author,
                        scriptConfig.threadUrl,
                        scriptConfig.imageUrl,
                        index,
                        index,
                        false
                    )

                    scripts.add(Script(metadata, scriptJar.readBytes(), optionFile.readLines()))
                    logger.debug("Loaded script {}", scriptConfig.name)
                }.onFailure {
                    logger.error("Failed to load script config from file ${file.name}", it)
                }
            }

            this.serverUrl = config.serverUrl
            this.revisionData = revisionFile.readText()
        }.onFailure {
            logger.error("Failed to load config", it)
        }
    }

    fun getScript(id: Int): Script {
        return scripts.firstOrNull { it.metadata.d == id } ?: error("Couldn't find script with id $id")
    }

    private data class Config(val revisionFile: String, val scriptConfigDir: String, val serverUrl: String)
    class Script(val metadata: ScriptWrapper, val bytes: ByteArray, val options: List<String>)

    companion object {
        val logger: Logger = LoggerFactory.getLogger("Config")
    }
}