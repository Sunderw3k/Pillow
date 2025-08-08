package rip.sunrise.server.config

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import rip.sunrise.packets.clientbound.ScriptWrapper
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class Config(private val configDir: Path) {
    var revisionData = ""
    var serverUrl = ""

    // Use ReentrantReadWriteLock approach for consistency
    // Store only metadata and file paths, not the actual JAR bytes
    private val scriptMetadata = HashMap<Int, ScriptWrapper>()
    private val scriptPaths = HashMap<Int, ScriptPaths>()

    // Simple cache for recently accessed script bytes and options
    private val scriptBytesCache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean {
            return size > MAX_CACHED_SCRIPTS
        }
    }

    private val scriptOptionsCache = HashMap<Int, List<String>>()
    private val lock = ReentrantReadWriteLock()

    companion object {
        private const val MAX_CACHED_SCRIPTS = 5 // Keep only 5 most recently used script JARs in memory
    }

    data class ScriptPaths(val jarPath: String, val optionPath: String)

    fun load() {
        lock.write {
            try {
                val configFile = configDir.resolve("config.json").toFile()
                if (!configFile.exists()) {
                    throw IllegalStateException("File config.json doesn't exist at ${configFile.absolutePath}")
                }

                val gson = Gson()
                val config = runCatching {
                    gson.fromJson(configFile.reader(), ConfigFile::class.java)
                        ?: throw IllegalStateException("Config file is empty or invalid")
                }.getOrElse { e ->
                    throw IllegalStateException("Invalid JSON in config file: ${e.message}", e)
                }

                // Validate revision file
                val revisionFile = File(config.revisionFile)
                if (!revisionFile.isFile) {
                    throw IllegalStateException("Revision file ${revisionFile.absolutePath} isn't a normal file!")
                }

                // Validate script config directory
                val scriptConfigDirectory = File(config.scriptConfigDir)
                if (!scriptConfigDirectory.isDirectory) {
                    throw IllegalStateException("Script config directory ${scriptConfigDirectory.absolutePath} is not a directory!")
                }

                // Clear all caches when reloading
                clearCaches()

                val scriptFiles = scriptConfigDirectory.listFiles()
                if (scriptFiles == null) {
                    println("WARNING: Cannot list files in script config directory")
                    return
                }

                if (scriptFiles.isEmpty()) {
                    println("WARNING: No script configuration files found")
                }

                scriptFiles.forEachIndexed { index, file ->
                    try {
                        if (!file.isFile || !file.name.endsWith(".json")) {
                            println("WARNING: Skipping non-JSON file: ${file.name}")
                            return@forEachIndexed
                        }

                        val scriptConfig = runCatching {
                            Gson().fromJson(file.reader(), ScriptConfig::class.java)
                                ?: throw IllegalStateException("Script config file is empty: ${file.name}")
                        }.getOrElse { e ->
                            throw IllegalStateException("Invalid JSON in script config file ${file.name}: ${e.message}", e)
                        }

                        // Validate script JAR file
                        val scriptJarPath = configDir.resolve(scriptConfig.jarFile).toString()
                        val scriptJar = File(scriptJarPath)
                        if (!scriptJar.isFile) {
                            throw IllegalStateException("Script jar ${scriptJar.absolutePath} isn't a normal file!")
                        }

                        // Validate option file
                        val optionFilePath = configDir.resolve(scriptConfig.optionFile).toString()
                        val optionFile = File(optionFilePath)
                        if (!optionFile.isFile) {
                            throw IllegalStateException("Option file ${optionFile.absolutePath} isn't a normal file!")
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

                        scriptMetadata[index] = metadata
                        scriptPaths[index] = ScriptPaths(scriptJarPath, optionFilePath)

                        println("Loaded script: ${scriptConfig.name} (ID: $index)")

                    } catch (e: Exception) {
                        println("ERROR: Failed to load script config from ${file.name}: ${e.message}")
                        e.printStackTrace()
                        // Continue loading other scripts instead of failing completely
                    }
                }

                this.serverUrl = config.serverUrl
                this.revisionData = runCatching {
                    revisionFile.readText()
                }.getOrElse { e ->
                    throw IllegalStateException("Failed to read revision file: ${e.message}", e)
                }

                println("Loaded ${scriptMetadata.size} scripts successfully (metadata only)")

            } catch (e: Exception) {
                println("ERROR: Failed to load configuration: ${e.message}")
                throw e
            }
        }
    }

    fun getScriptMetadata(id: Int): ScriptWrapper {
        return lock.read {
            scriptMetadata[id] ?: throw IllegalArgumentException("Couldn't find script with id $id")
        }
    }

    fun getScriptMetadataList(): List<ScriptWrapper> {
        return lock.read {
            scriptMetadata.values.toList()
        }
    }

    fun getScriptIds(): List<Int> {
        return lock.read {
            scriptMetadata.keys.toList()
        }
    }

    // Load script bytes on-demand with caching
    fun getScriptBytes(id: Int): ByteArray {
        // Check cache first with separate synchronization
        synchronized(scriptBytesCache) {
            scriptBytesCache[id]?.let { cached ->
                return cached
            }
        }

        // Get script path under read lock
        val scriptPath = lock.read {
            scriptPaths[id] ?: throw IllegalArgumentException("Couldn't find script path for id $id")
        }

        // Read file outside of locks to avoid blocking
        val bytes = runCatching {
            File(scriptPath.jarPath).readBytes()
        }.getOrElse { e ->
            throw IllegalStateException("Failed to read script JAR file: ${scriptPath.jarPath}", e)
        }

        // Update cache
        synchronized(scriptBytesCache) {
            scriptBytesCache[id] = bytes
        }

        return bytes
    }

    // Load script options on-demand with caching
    fun getScriptOptions(id: Int): List<String> {
        // Check cache first with separate synchronization
        synchronized(scriptOptionsCache) {
            scriptOptionsCache[id]?.let { cached ->
                return cached
            }
        }

        // Get script path under read lock
        val scriptPath = lock.read {
            scriptPaths[id] ?: throw IllegalArgumentException("Couldn't find script path for id $id")
        }

        // Read file outside of locks to avoid blocking
        val options = runCatching {
            File(scriptPath.optionPath).readLines()
        }.onFailure { e ->
            println("WARNING: Failed to read options file for script $id: ${e.message}")
        }.getOrElse { emptyList() }

        // Update cache
        synchronized(scriptOptionsCache) {
            scriptOptionsCache[id] = options
        }

        return options
    }

    private fun clearCaches() {
        scriptBytesCache.clear()
        scriptOptionsCache.clear()
        scriptMetadata.clear()
        scriptPaths.clear()
    }

    private data class ConfigFile(
        val revisionFile: String,
        val scriptConfigDir: String,
        val serverUrl: String
    )
}