package rip.sunrise.server.netty

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelId
import io.netty.channel.SimpleChannelInboundHandler
import org.dreambot.*
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import rip.sunrise.packets.clientbound.*
import rip.sunrise.packets.msgpack.LOGIN_REQUEST_PACKET_ID
import rip.sunrise.packets.msgpack.LoginResponse
import rip.sunrise.packets.msgpack.Packet
import rip.sunrise.packets.msgpack.REVISION_INFO_REQUEST_PACKET_ID
import rip.sunrise.packets.msgpack.RevisionInfoResponse
import rip.sunrise.packets.msgpack.WRAPPED_PACKET_ID
import rip.sunrise.packets.msgpack.unpackLoginRequest
import rip.sunrise.packets.msgpack.unpackRevisionInfoRequest
import rip.sunrise.packets.serverbound.*
import rip.sunrise.server.config.Config
import rip.sunrise.server.http.JarHttpServer
import rip.sunrise.server.utils.extensions.md5sum
import java.util.Base64
import rip.sunrise.server.logger
import rip.sunrise.server.utils.extensions.generateFakeScriptWrappers
import rip.sunrise.server.utils.extensions.isRealScriptId

const val ACCOUNT_SESSION_ID = "cMU/vYTQnRyD2cFx1i1J6aa+ZpRIINh5qkMxoTh8XoA"
const val SCRIPT_SESSION_ID = "dbsVbKA4mRLE4NaOMXCCnvPYEJsNsXdwek6hosbCiQ0"
const val SESSION_TOKEN = "DHGbNt9CZ2v6yNSPvsEq/zv/toLQmA7ET4Kdvq2xeZVol8UMMSyHk0QCyfyRHPf7hhvGvO/CA7M="
const val AUTHENTICATION_CODE = "cZVrdjeOPvd1Ozh1sfzb0z7imnearVJs1CE+Wm12mBV+ekWVOeNVFOz42ZXqYMhmDYa48mebi4DRMOqH8mc="

val SCRIPT_AES_KEY = ByteArray(32) { 0 }
val SCRIPT_IV = ByteArray(16) { 0 }

const val REVISION_INFO_JAVAAGENT_CONSTANT = -1640531527

data class ClientData(var currentScript: Int, var packetCount: Int)

class ServerHandler(private val config: Config, private val http: JarHttpServer) : SimpleChannelInboundHandler<Any>() {
    private val sessions = mutableMapOf<ChannelId, ClientData>()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        logger.debug("Got message: {}", msg)
        when (msg) {
            is String -> {
                handleJson(ctx, msg)
            }

            is ByteArray -> {
                MessagePack.newDefaultUnpacker(msg).use { unpacker ->
                    val id = unpacker.unpackByte()

                    if (id == WRAPPED_PACKET_ID) {
                        val timestamp = unpacker.unpackTimestamp()
                        val id = unpacker.unpackByte()

                        logger.info("Got wrapped MsgPack packet! Timestamp: $timestamp ID: $id")

                        ctx.handleMsgpackPacket(id, unpacker)
                    } else {
                        ctx.handleMsgpackPacket(id, unpacker)
                    }
                }
            }

            is EncryptedScriptRequest -> {
                // In case you start a fake script, it'll only error in the client.
                if (!config.isRealScriptId(msg.f)) {
                    logger.debug("Started fake script with ID ${msg.f}")
                    ctx.writeAndFlush(EncryptedScriptResp("", null, null, null, 0))
                    return
                }

                val endpoint = http.getScriptEndpoint(msg.f)
                val serverUrl = config.serverUrl.removeSuffix("/")

                @OptIn(ExperimentalStdlibApi::class)
                val checksum = http.getEncryptedScript(msg.f).md5sum().toHexString()

                val script = config.getScript(msg.f)

                (sessions[ctx.channel().id()] ?: error("Couldn't find session $ctx")).currentScript = msg.f
                ctx.writeAndFlush(EncryptedScriptResp("$serverUrl/$endpoint", sanitizeName(script.metadata.m), checksum, Base64.getEncoder().encodeToString(SCRIPT_AES_KEY), -1))
            }

            is FreeScriptListRequest -> ctx.writeAndFlush(ScriptListResp(emptyList()))
            is PaidScriptListRequest -> ctx.writeAndFlush(ScriptListResp(config.scripts.map { it.metadata } + config.generateFakeScriptWrappers()))

            is ScriptSessionRequest -> ctx.writeAndFlush(ScriptSessionResp(0, SCRIPT_SESSION_ID))

            is ScriptStartRequest -> ctx.writeAndFlush(ScriptStartResp(false))

            is AuthenticationCodeRequest -> ctx.writeAndFlush(AuthenticationCodeResp(AUTHENTICATION_CODE))

            is PurchasedScriptIdsRequest -> ctx.writeAndFlush(PurchasedScriptIdsResp(TreeSet<Int>().apply {
                addAll(config.purchasedScripts.map { it.storeId })
            }))

            is a5 -> ctx.writeAndFlush(am(0))

            is aY -> ctx.writeAndFlush(bs(config.userId))

            is GetActiveInstancesRequest -> ctx.writeAndFlush(GetInstancesResp(0))
            is GetTotalInstancesRequest -> ctx.writeAndFlush(GetInstancesResp(1))

            is ScriptOptionsRequest -> {
                val scriptId = sessions[ctx.channel().id()]?.currentScript ?: error("Couldn't find session $ctx")

                val options = config.getScript(scriptId).options
                    .map { it.split("=") }
                    .map { (key, value) -> key to encryptOption(value.toInt(), SCRIPT_SESSION_ID, config.userId) }
                    .joinToString(",") { (key, value) -> "$key=$value" }

                ctx.writeAndFlush(ScriptOptionsResp(options))
            }

            else -> logger.error("Unknown packet $msg")
        }
    }

    fun ChannelHandlerContext.handleMsgpackPacket(id: Byte, unpacker: MessageUnpacker) {
        logger.info("Received MsgPack Packet ID: $id")

        when (id) {
            LOGIN_REQUEST_PACKET_ID -> {
                val request = unpacker.unpackLoginRequest()
                sendPacket(
                    LoginResponse(
                        request.username,
                        ACCOUNT_SESSION_ID,
                        SESSION_TOKEN,
                        config.userId,
                        hashSetOf(10)
                    )
                )
            }

            REVISION_INFO_REQUEST_PACKET_ID -> {
                val request = unpacker.unpackRevisionInfoRequest()

                val responseChecksum = request.javaagentFlags.hashCode() xor (config.userId * REVISION_INFO_JAVAAGENT_CONSTANT)
                sendPacket(RevisionInfoResponse(config.revisionData, responseChecksum))
            }
        }
    }

    // TODO: Still used?
    fun handleJson(ctx: ChannelHandlerContext, msg: String) {
        println("Got JSON Message!")

        val json = Gson().fromJson(msg, JsonObject::class.java)
        val code = json.get("m").asString

        when (code) {
            // Sent when requesting revision info.
            "a" -> {
//                ctx.writeAndFlush(RevisionInfoResp(config.revisionData))
            }
            "b" -> TODO()
            "z" -> TODO()
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        sessions[ctx.channel().id()] = ClientData(-1, 0)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        sessions.remove(ctx.channel().id())
    }

    private fun ChannelHandlerContext.sendPacket(packet: Packet<*>) {
        writeAndFlush(packet.pack(sessions[channel().id()]!!.packetCount++, false))
    }

    private fun encryptOption(value: Int, scriptSessionId: String, userId: Int): Int {
        return value xor scriptSessionId.hashCode() xor userId
    }

    private fun sanitizeName(name: String): String {
        return name.replace(" ", "_").replace("[^A-Za-z0-9_]".toRegex(), "")
    }
}