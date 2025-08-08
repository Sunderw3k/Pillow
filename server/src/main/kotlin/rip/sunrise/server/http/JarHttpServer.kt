
package rip.sunrise.server.http

import com.sun.net.httpserver.HttpServer
import rip.sunrise.server.config.Config
import rip.sunrise.server.netty.SCRIPT_AES_KEY
import rip.sunrise.server.netty.SCRIPT_IV
import rip.sunrise.server.utils.extensions.encryptScriptAES
import rip.sunrise.server.utils.extensions.md5sum
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

class JarHttpServer(private val port: Int, val config: Config) {
    private val endpoints = ConcurrentHashMap<String, Int>()

    // Cache for checksums only, not the actual encrypted scripts
    private val checksumCache = ConcurrentHashMap<Int, String>()

    // Simple LRU cache for recently accessed encrypted scripts (limited size)
    private val encryptedScriptCache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean {
            return size > MAX_CACHED_SCRIPTS
        }
    }

    private var started = false
    private var server: HttpServer? = null

    companion object {
        private const val MAX_CACHED_SCRIPTS = 10 // Keep only 10 most recently used scripts in memory
    }

    fun start() {
        if (started) {
            println("WARNING: HTTP Server is already started")
            return
        }

        try {
            println("Starting HTTP Server on port $port")

            server = HttpServer.create(InetSocketAddress(port), 0)
            server?.createContext("/") { exchange ->
                val path = exchange.requestURI.path.removePrefix("/")
                println("HTTP request for path: $path")

                try {
                    val scriptId = endpoints[path]
                    if (scriptId == null) {
                        println("WARNING: No script found for path: $path")
                        exchange.sendResponseHeaders(404, 0)
                        exchange.responseBody.close()
                        return@createContext
                    }

                    val bytes = getEncryptedScriptOnDemand(scriptId)

                    // Add security headers
                    exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                    exchange.responseHeaders.set("Cache-Control", "no-cache, no-store, must-revalidate")
                    exchange.responseHeaders.set("Pragma", "no-cache")
                    exchange.responseHeaders.set("Expires", "0")

                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { output ->
                        output.write(bytes)
                    }

                    println("Served encrypted script for ID: $scriptId (${bytes.size} bytes)")

                } catch (e: Exception) {
                    println("ERROR: Failed to serve script for path $path: ${e.message}")
                    e.printStackTrace()

                    try {
                        exchange.sendResponseHeaders(500, 0)
                        exchange.responseBody.close()
                    } catch (closeEx: Exception) {
                        println("ERROR: Failed to send error response: ${closeEx.message}")
                    }
                }
            }

            loadEndpoints()

            server?.executor = null
            server?.start()
            started = true
            println("HTTP Server started successfully on port $port")

        } catch (e: Exception) {
            println("ERROR: Failed to start HTTP server: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun stop() {
        try {
            server?.stop(5)
            started = false
            println("HTTP Server stopped")
        } catch (e: Exception) {
            println("ERROR: Failed to stop HTTP server: ${e.message}")
            e.printStackTrace()
        }
    }

    fun loadEndpoints() {
        synchronized(endpoints) {
            try {
                val previousEndpointCount = endpoints.size

                endpoints.clear()
                checksumCache.clear()
                synchronized(encryptedScriptCache) {
                    encryptedScriptCache.clear()
                }

                val scriptIds = config.getScriptIds()
                scriptIds.forEach { scriptId ->
                    try {
                        registerEndpoint(scriptId)
                    } catch (e: Exception) {
                        println("ERROR: Failed to register endpoint for script ID $scriptId: ${e.message}")
                        e.printStackTrace()
                    }
                }

                println("Loaded ${endpoints.size} script endpoints (previously had $previousEndpointCount)")

            } catch (e: Exception) {
                println("ERROR: Failed to load endpoints: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    fun getScriptEndpoint(scriptId: Int): String {
        return synchronized(endpoints) {
            val entry = endpoints.entries.firstOrNull { (_, value) -> value == scriptId }
                ?: throw IllegalArgumentException("Couldn't find endpoint for script $scriptId")
            entry.key
        }
    }

    fun getScriptChecksum(scriptId: Int): String {
        return checksumCache[scriptId] ?: run {
            try {
                // Calculate checksum on demand
                val checksum = calculateScriptChecksum(scriptId)
                checksumCache[scriptId] = checksum
                println("Calculated checksum for script $scriptId: $checksum")
                checksum
            } catch (e: Exception) {
                println("ERROR: Failed to calculate checksum for script $scriptId: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    // Get encrypted script on-demand, with caching for recently accessed scripts
    private fun getEncryptedScriptOnDemand(scriptId: Int): ByteArray {
        synchronized(encryptedScriptCache) {
            encryptedScriptCache[scriptId]?.let { cached ->
                println("Serving cached encrypted script for ID: $scriptId")
                return cached
            }
        }

        try {
            // Not in cache, encrypt on demand
            println("Encrypting script on demand for ID: $scriptId")
            val scriptBytes = config.getScriptBytes(scriptId)
            val encrypted = scriptBytes.encryptScriptAES(SCRIPT_AES_KEY, SCRIPT_IV)

            synchronized(encryptedScriptCache) {
                encryptedScriptCache[scriptId] = encrypted
            }

            println("Script $scriptId encrypted and cached (${encrypted.size} bytes)")
            return encrypted

        } catch (e: Exception) {
            println("ERROR: Failed to encrypt script $scriptId: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun calculateScriptChecksum(scriptId: Int): String {
        return try {
            getEncryptedScriptOnDemand(scriptId).md5sum().toHexString()
        } catch (e: Exception) {
            println("ERROR: Failed to calculate checksum for script $scriptId: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun registerEndpoint(scriptId: Int): String {
        return try {
            val path = Base64.UrlSafe.encode(Random.nextBytes(16))
            endpoints[path] = scriptId
            println("Registered endpoint for script $scriptId: $path")
            path
        } catch (e: Exception) {
            println("ERROR: Failed to register endpoint for script $scriptId: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun clearCache() {
        try {
            synchronized(encryptedScriptCache) {
                val cacheSize = encryptedScriptCache.size
                encryptedScriptCache.clear()
                println("Cleared encrypted script cache ($cacheSize entries)")
            }

            val checksumCacheSize = checksumCache.size
            checksumCache.clear()
            println("Cleared checksum cache ($checksumCacheSize entries)")

        } catch (e: Exception) {
            println("ERROR: Failed to clear caches: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "started" to started,
            "port" to port,
            "endpointCount" to endpoints.size,
            "checksumCacheSize" to checksumCache.size,
            "encryptedScriptCacheSize" to synchronized(encryptedScriptCache) { encryptedScriptCache.size }
        )
    }
}