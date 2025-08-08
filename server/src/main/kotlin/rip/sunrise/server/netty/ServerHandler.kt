package rip.sunrise.server.netty

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.dreambot.*
import org.msgpack.core.MessagePack
import rip.sunrise.packets.clientbound.*
import rip.sunrise.packets.msgpack.LOGIN_REQUEST_PACKET_ID
import rip.sunrise.packets.msgpack.LoginResponse
import rip.sunrise.packets.msgpack.Packet
import rip.sunrise.packets.msgpack.REVISION_INFO_REQUEST_PACKET_ID
import rip.sunrise.packets.msgpack.RevisionInfoResponse
import rip.sunrise.packets.msgpack.unpackLoginRequest
import rip.sunrise.packets.msgpack.unpackRevisionInfoRequest
import rip.sunrise.packets.serverbound.*
import rip.sunrise.server.config.Config
import rip.sunrise.server.http.JarHttpServer
import rip.sunrise.server.utils.extensions.md5sum
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val ACCOUNT_SESSION_ID = "cMU/vYTQnRyD2cFx1i1J6aa+ZpRIINh5qkMxoTh8XoA"
const val SCRIPT_SESSION_ID = "dbsVbKA4mRLE4NaOMXCCnvPYEJsNsXdwek6hosbCiQ0"
const val SESSION_TOKEN = "DHGbNt9CZ2v6yNSPvsEq/zv/toLQmA7ET4Kdvq2xeZVol8UMMSyHk0QCyfyRHPf7hhvGvO/CA7M="
const val USER_ID = 1
const val SESSION_TIMEOUT_MINUTES = 60L

val SCRIPT_AES_KEY = ByteArray(32) { 0 }
val SCRIPT_IV = ByteArray(16) { 0 }

const val REVISION_INFO_JAVAAGENT_CONSTANT = -1640531527

data class ClientData(
    var currentScript: Int,
    var packetCount: Int,
    var lastActivity: Long = System.currentTimeMillis(),
    var clientReady: Boolean = false
) {
    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
    }

    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastActivity > TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES)
    }
}

@io.netty.channel.ChannelHandler.Sharable
class ServerHandler(private val config: Config, private val http: JarHttpServer) : SimpleChannelInboundHandler<Any>() {
    private val sessions = ConcurrentHashMap<ChannelHandlerContext, ClientData>()
    private val sessionCleanupExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Session-Cleanup").apply { isDaemon = true }
    }

    init {
        // Schedule periodic cleanup of expired sessions
        sessionCleanupExecutor.scheduleAtFixedRate(
            ::cleanupExpiredSessions,
            SESSION_TIMEOUT_MINUTES,
            SESSION_TIMEOUT_MINUTES / 2,
            TimeUnit.MINUTES
        )
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        try {
            println("Got message: $msg")

            // Ensure session exists and update activity
            ensureSession(ctx)

            when (msg) {
                is String -> {
                    handleJson(ctx, msg)
                }

                is ByteArray -> {
                    handleBinaryMessage(ctx, msg)
                }

                is EncryptedScriptRequest -> {
                    handleEncryptedScriptRequest(ctx, msg)
                }

                is FreeScriptListRequest -> ctx.writeAndFlush(ScriptListResp(emptyList()))
                is PaidScriptListRequest -> ctx.writeAndFlush(ScriptListResp(config.getScriptMetadataList()))

                is ScriptSessionRequest -> ctx.writeAndFlush(ScriptSessionResp(0, SCRIPT_SESSION_ID))

                is ScriptStartRequest -> {
                    // Mark client as ready when script starts
                    sessions[ctx]?.clientReady = true
                    ctx.writeAndFlush(ScriptStartResp(false))
                }

                is a5 -> ctx.writeAndFlush(am(6))

                // TODO: I don't think this exists anymore
                is af -> ctx.writeAndFlush(am(6))

                is aY -> ctx.writeAndFlush(bs(USER_ID))

                is GetActiveInstancesRequest -> ctx.writeAndFlush(GetInstancesResp(0))
                is GetTotalInstancesRequest -> ctx.writeAndFlush(GetInstancesResp(1))

                is ScriptOptionsRequest -> {
                    handleScriptOptionsRequest(ctx)
                }

                else -> {
                    println("WARNING: Unknown packet type: ${msg::class.simpleName}")
                }
            }
        } catch (e: Exception) {
            println("ERROR: Exception in channelRead0: ${e.message}")
            e.printStackTrace()
            // Don't close the channel immediately, let the client handle the error
        }
    }

    private fun ensureSession(ctx: ChannelHandlerContext): ClientData {
        return sessions.computeIfAbsent(ctx) {
            println("Creating new session for channel: $ctx")
            ClientData(-1, 0)
        }.apply {
            updateActivity()
        }
    }

    private fun handleBinaryMessage(ctx: ChannelHandlerContext, msg: ByteArray) {
        try {
            MessagePack.newDefaultUnpacker(msg).use { unpacker ->
                val id = unpacker.unpackByte()
                println("Got binary message with ID: $id")

                when (id) {
                    LOGIN_REQUEST_PACKET_ID -> {
                        // Ensure session exists
                        ensureSession(ctx)

                        val request = unpacker.unpackLoginRequest()
                        ctx.sendPacket(
                            LoginResponse(
                                request.username,
                                ACCOUNT_SESSION_ID,
                                SESSION_TOKEN,
                                USER_ID,
                                hashSetOf(10)
                            )
                        )
                        println("Login response sent for user: ${request.username}")
                    }

                    REVISION_INFO_REQUEST_PACKET_ID -> {
                        val request = unpacker.unpackRevisionInfoRequest()

                        val responseChecksum = request.javaagentFlags.hashCode() xor (USER_ID * REVISION_INFO_JAVAAGENT_CONSTANT)
                        ctx.sendPacket(RevisionInfoResponse(config.revisionData, responseChecksum))
                        println("Revision info response sent")
                    }

                    else -> {
                        println("WARNING: Unknown binary message ID: $id")
                    }
                }
            }
        } catch (e: Exception) {
            println("ERROR: Failed to handle binary message: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleEncryptedScriptRequest(ctx: ChannelHandlerContext, msg: EncryptedScriptRequest) {
        try {
            val endpoint = http.getScriptEndpoint(msg.f)
            val serverUrl = config.serverUrl.removeSuffix("/")

            // Get checksum on-demand instead of pre-computing
            @OptIn(ExperimentalStdlibApi::class)
            val checksum = http.getScriptChecksum(msg.f)

            val script = config.getScriptMetadata(msg.f)

            // Ensure session exists before using it
            val session = ensureSession(ctx)
            session.currentScript = msg.f

            ctx.writeAndFlush(
                EncryptedScriptResp(
                    "$serverUrl/$endpoint",
                    sanitizeName(script.m),
                    checksum,
                    Base64.getEncoder().encodeToString(SCRIPT_AES_KEY),
                    -1
                )
            )

            println("Encrypted script response sent for script ID: ${msg.f}")

        } catch (e: Exception) {
            println("ERROR: Failed to handle encrypted script request for ID ${msg.f}: ${e.message}")
            e.printStackTrace()
            // Send error response instead of crashing
            ctx.writeAndFlush(EncryptedScriptResp("", "", "", "", -1))
        }
    }

    private fun handleScriptOptionsRequest(ctx: ChannelHandlerContext) {
        try {
            // Ensure session exists before using it
            val session = ensureSession(ctx)
            val scriptId = session.currentScript

            if (scriptId == -1) {
                println("WARNING: Script options requested but no current script set")
                ctx.writeAndFlush(ScriptOptionsResp(""))
                return
            }

            val options = config.getScriptOptions(scriptId)
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        try {
                            val key = parts[0]
                            val value = encryptOption(parts[1].toInt(), SCRIPT_SESSION_ID, USER_ID)
                            "$key=$value"
                        } catch (e: NumberFormatException) {
                            println("WARNING: Invalid option value in script $scriptId: $line")
                            null
                        }
                    } else {
                        println("WARNING: Invalid option format in script $scriptId: $line")
                        null
                    }
                }
                .joinToString(",")

            ctx.writeAndFlush(ScriptOptionsResp(options))
            println("Script options sent for script ID: $scriptId")

        } catch (e: Exception) {
            println("ERROR: Failed to handle script options request: ${e.message}")
            e.printStackTrace()
            ctx.writeAndFlush(ScriptOptionsResp(""))
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        println("Channel became active: $ctx")
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // Clean up session when channel becomes inactive
        sessions.remove(ctx)?.let {
            println("Session removed for inactive channel: $ctx")
        }
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        println("ERROR: Exception caught in channel $ctx: ${cause.message}")
        cause.printStackTrace()

        // Clean up session on exception
        sessions.remove(ctx)
        ctx.close()
        super.exceptionCaught(ctx, cause)
    }

    private fun handleJson(ctx: ChannelHandlerContext, msg: String) {
        try {
            println("Got JSON Message: $msg")

            val json = try {
                Gson().fromJson(msg, JsonObject::class.java)
            } catch (e: JsonSyntaxException) {
                println("ERROR: Invalid JSON received: ${e.message}")
                return
            }

            val code = json.get("m")?.asString
            if (code == null) {
                println("WARNING: JSON message missing 'm' field")
                return
            }

            when (code) {
                // Sent when requesting revision info.
                "a" -> {
                    // Handle revision info request
                    println("Revision info requested via JSON")
                }
                "b" -> {
                    println("WARNING: Unhandled JSON code 'b'")
                }
                "z" -> {
                    println("WARNING: Unhandled JSON code 'z'")
                }
                else -> {
                    println("WARNING: Unknown JSON code: $code")
                }
            }
        } catch (e: Exception) {
            println("ERROR: Failed to handle JSON message: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun ChannelHandlerContext.sendPacket(packet: Packet<*>) {
        try {
            val session = ensureSession(this)
            writeAndFlush(packet.pack(session.packetCount++))
        } catch (e: Exception) {
            println("ERROR: Failed to send packet: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun encryptOption(value: Int, scriptSessionId: String, userId: Int): Int {
        return value xor scriptSessionId.hashCode() xor userId
    }

    private fun sanitizeName(name: String): String {
        return name.replace(" ", "_").replace("[^A-Za-z0-9_]".toRegex(), "")
    }

    private fun cleanupExpiredSessions() {
        try {
            val expiredSessions = sessions.entries.filter { (_, data) -> data.isExpired() }
            expiredSessions.forEach { (ctx, _) ->
                println("Closing expired session: $ctx")
                sessions.remove(ctx)
                ctx.close()
            }
            if (expiredSessions.isNotEmpty()) {
                println("Cleaned up ${expiredSessions.size} expired sessions")
            }
        } catch (e: Exception) {
            println("ERROR: Failed to cleanup expired sessions: ${e.message}")
            e.printStackTrace()
        }
    }

    fun shutdown() {
        try {
            sessionCleanupExecutor.shutdown()
            if (!sessionCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                sessionCleanupExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            sessionCleanupExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}