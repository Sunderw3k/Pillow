package rip.sunrise.client

import com.google.gson.Gson
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import rip.sunrise.client.utils.extensions.sha256
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.util.zip.ZipInputStream

object DBClientData {
    private data class ClientCache(val version: String, val hash: String, val sharedSecret: String, val etag: String)

    private const val CLIENT_URL = "https://downloads.dreambot.org/dreambot-latest.jar"
    private val CLIENT_CACHE_FILE = File(".db_client_cache")
    private val CLIENT_JAR_FILE = File("client.jar")

    // TODO: Technically unsafe. If you are running the client itself though, its just as unsafe as that.
    private val CLIENT_CL = URLClassLoader(arrayOf(CLIENT_JAR_FILE.toURI().toURL()))

    val version: String
    val hash: String
    val sharedSecret: String

    init {
        if (!CLIENT_CACHE_FILE.exists()) {
            CLIENT_CACHE_FILE.createNewFile()
        }

        val conn = URI(CLIENT_URL).toURL().openConnection() as HttpURLConnection
        val gson = Gson()

        val cache = runCatching { gson.fromJson(CLIENT_CACHE_FILE.readText(), ClientCache::class.java) }.getOrNull()
        if (cache != null) {
            conn.setRequestProperty("If-None-Match", cache.etag)
        }

        val clientCache = if (conn.responseCode != 304) {
            val bytes = conn.inputStream.use { it.readBytes() }
            CLIENT_JAR_FILE.writeBytes(bytes)

            @OptIn(ExperimentalStdlibApi::class)
            val hash = bytes.sha256().toHexString()

            val data = extractData(bytes)
            val version = data.first ?: error("Failed to find version!")
//            val sharedSecret = data.second ?: error("Failed to find shared secret!")
            val sharedSecret = "b46ef462bdc902730d0646"

            val clientCache = ClientCache(version, hash, sharedSecret, conn.getHeaderField("etag"))

            CLIENT_CACHE_FILE.writeText(gson.toJson(clientCache))
            clientCache
        } else cache!!

        version = clientCache.version
        hash = clientCache.hash
        sharedSecret = clientCache.sharedSecret
    }

    private fun extractData(bytes: ByteArray): Pair<String?, String?> {
        var version: String? = null
        var secret: String? = null

        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break

                if (entry.name.endsWith(".class")) {
                    val bytes = zis.readAllBytes()
                    val node = ClassNode()

                    ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

                    run version@{
                        if (node.superName == "javax/swing/JFrame") {
                            val init = node.methods.first { it.name == "<init>" }

                            version = init.instructions
                                .filterIsInstance<MethodInsnNode>()
                                .filter { it.name == "format" }
                                .map { runCatching { invokeStringDecryption(it.previous.previous) } }
                                .mapNotNull { it.getOrNull() }
                                .firstOrNull { it.matches("^3\\.\\d+\\.\\d+(?:\\.\\d+)?$".toRegex()) } ?: return@version
                        }
                    }

                    node.methods.forEach { method ->
                        val putField = method.instructions.filterIsInstance<FieldInsnNode>()
                            .filter { it.opcode == Opcodes.PUTFIELD }
                            .filter { it.owner == "org/dreambot/aQ" }
                            .firstOrNull { it.name == "p" } ?: return@forEach

                        secret = runCatching { invokeStringDecryption(putField.previous) }.getOrNull() ?: return@forEach
                    }
                }

                zis.closeEntry()
            }
        }

        return version to secret
    }

    private fun invokeStringDecryption(methodInsn: AbstractInsnNode): String {
        if (methodInsn !is MethodInsnNode || methodInsn.opcode != Opcodes.INVOKESTATIC) error("Bad pattern!")

        val const2 = methodInsn.previous
        if (const2 !is IntInsnNode) error("Bad pattern!")

        val const1 = const2.previous
        if (const1 !is IntInsnNode) error("Bad pattern!")

        val clazz = CLIENT_CL.loadClass(methodInsn.owner.replace("/", "."))
        val method = clazz.declaredMethods.filter { it.name == methodInsn.name }
            .first { Type.getMethodDescriptor(it) == methodInsn.desc }
            .also { it.isAccessible = true }

        return method.invoke(null, const1.operand, const2.operand) as String
    }
}