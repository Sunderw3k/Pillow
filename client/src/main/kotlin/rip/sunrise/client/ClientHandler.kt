package rip.sunrise.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.concurrent.Promise
import org.msgpack.core.MessagePack
import rip.sunrise.client.config.ScriptConfig
import rip.sunrise.client.upstream.User
import rip.sunrise.client.upstream.UserStorage
import rip.sunrise.client.utils.extensions.decryptScript
import rip.sunrise.packets.clientbound.*
import rip.sunrise.packets.msgpack.LOGIN_RESPONSE_PACKET_ID
import rip.sunrise.packets.msgpack.LoginRequest
import rip.sunrise.packets.msgpack.LoginResponse
import rip.sunrise.packets.msgpack.Packet
import rip.sunrise.packets.msgpack.REVISION_INFO_RESPONSE_PACKET_ID
import rip.sunrise.packets.msgpack.RevisionInfoRequest
import rip.sunrise.packets.msgpack.RevisionInfoResponse
import rip.sunrise.packets.msgpack.unpackLoginResponse
import rip.sunrise.packets.msgpack.unpackRevisionInfoResponse
import rip.sunrise.packets.serverbound.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.Path

const val REVISION_INFO_JAVAAGENT_CONSTANT = -1640531527

class ClientHandler(val user: User, val password: String) :
    ChannelInboundHandlerAdapter() {
    val pending = ConcurrentHashMap<Class<*>, Promise<Any>>()

    private var packetCount = 0

    override fun channelActive(ctx: ChannelHandlerContext) {
        println("Open")

        thread {
            // TODO: Use the session token, if possible.
            val loginResponse = ctx.sendPacketAndWait(
                LoginRequest(
                    user.name,
                    password,
                    user.sessionId,
                    DREAMBOT_SHARED_SECRET,
                    user.hardwareId
                ),
                LoginResponse::class.java
            )

            // BANNED and BANNED_2
            if (5 in loginResponse.ranks || 42 in loginResponse.ranks) error("This account is banned. Change the IP before making a new one.")

            if (loginResponse.userId <= 0 || loginResponse.accountSessionToken.isEmpty()) {
                error("Something went wrong logging in! Try changing the HARDWARE_ID, IP, or account. $loginResponse")
            }

            user.sessionId = loginResponse.sessionId
            UserStorage.addUser(user)

            val accountSession = loginResponse.accountSessionToken

            val revisionResponse = ctx.sendPacketAndWait(
                RevisionInfoRequest(accountSession, DREAMBOT_SHA256, ""),
                RevisionInfoResponse::class.java
            )
            if (revisionResponse.javaagentChecksum xor (loginResponse.userId * REVISION_INFO_JAVAAGENT_CONSTANT) != 0) {
                println("Failed Javaagent checksum. Constant most likely changed. Latest client will NOT work.")
            }
            writeRevisionData(revisionResponse.revisionData)
            println("Dumped revision info")

            val scripts = ctx.sendPacketAndWait(
                FreeScriptListRequest(accountSession),
                ScriptListResp::class.java
            ).v + ctx.sendPacketAndWait(
                PaidScriptListRequest(accountSession),
                ScriptListResp::class.java
            ).v

            if (scripts.isEmpty()) {
                ctx.disconnect()
                return@thread
            }

            val scriptSessionResp = ctx.sendPacketAndWait(
                ScriptSessionRequest("$accountSession:MID:${user.hardwareId}"),
                ScriptSessionResp::class.java
            )
            val scriptSession = scriptSessionResp.c ?: error("Failed to get script session. Try again in a few seconds.")

            scripts.forEach { script ->
                println("Dumping script ${script.m} (ID ${script.x})")

                val encryptedScript = ctx.sendPacketAndWait(
                    EncryptedScriptRequest(script.x, accountSession, scriptSession),
                    EncryptedScriptResp::class.java
                )
                val bytes = getScriptBytes(encryptedScript)

                val scriptOptions = ctx.sendPacketAndWait(
                    ScriptOptionsRequest(accountSession, scriptSession),
                    ScriptOptionsResp::class.java
                )
                val options = getScriptOptions(scriptOptions, scriptSession, loginResponse.userId)

                writeScriptData(script, bytes, options)
            }

            ctx.disconnect()
        }
    }

    fun getScriptBytes(msg: EncryptedScriptResp): ByteArray {
        val builder = HttpRequest.newBuilder(URI(msg.w))
            .header("User-agent", "Java/11.0.25")

        msg.l?.let {
            builder.header("If-match", "\"$it\"")
        }

        val encrypted = HttpClient.newHttpClient()
            .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            .body()
            .readAllBytes()

        return msg.z?.let {
            encrypted.decryptScript(Base64.getDecoder().decode(it))
        } ?: encrypted
    }

    fun getScriptOptions(msg: ScriptOptionsResp, scriptSession: String, userId: Int): String {
        if (msg.c.isBlank()) {
            return ""
        }
        return msg.c.trim().split(",").map { p -> p.split("=") }.joinToString(separator = "\n") { (key, value) ->
            "$key=${value.toInt() xor scriptSession.hashCode() xor userId}"
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.disconnect()
        throw RuntimeException(cause)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is ByteArray -> {
                val unpacker = MessagePack.newDefaultUnpacker(msg)

                val id = unpacker.unpackByte()
                println("Got packet ID $id")

                val packet = when (id) {
                    LOGIN_RESPONSE_PACKET_ID -> unpacker.unpackLoginResponse()
                    REVISION_INFO_RESPONSE_PACKET_ID -> unpacker.unpackRevisionInfoResponse()
                    else -> error("Unknown msgpack packet ID $id")
                }

                pending.remove(packet::class.java)?.setSuccess(packet)
            }

            is String -> {
                handleJson(ctx, msg)
            }

            else -> {
                pending.remove(msg::class.java)?.setSuccess(msg)
            }
        }
    }

    // TODO: Still used?
    fun handleJson(ctx: ChannelHandlerContext, msg: String) {
        val json = Gson().fromJson(msg, JsonObject::class.java)
        val code = json.get("m").asString

        val obj = JsonObject().apply {
            addProperty("m", code)
        }
        when (code) {
            // Sent when requesting revision info.
            "a" -> {
                obj.addProperty("r", DREAMBOT_SHA256)
                obj.addProperty("c", user.hardwareId)
                obj.addProperty("e", "")
            }
            "b" -> TODO()
            "z" -> TODO()
        }

        ctx.writeAndFlush(obj.toString())
    }

    private fun writeRevisionData(data: String) {
        val outputPath = Path("output")

        outputPath.resolve("revision.txt").toFile().also {
            it.parentFile.mkdirs()
            it.createNewFile()
            it.writeText(data)
        }
    }

    private fun writeScriptData(script: ScriptWrapper, data: ByteArray, options: String) {
        val name = sanitizeName(script.m)

        val gson = Gson().newBuilder().setPrettyPrinting().create()

        val outputPath = Path("output")

        outputPath.resolve("configs/$name.json").toFile().also {
            it.parentFile.mkdirs()
            it.createNewFile()
            it.writeText(gson.toJson(ScriptConfig(
                name = script.m,
                description = script.w,
                version = script.e,
                author = script.l,
                imageUrl = script.q,
                threadUrl = script.v,
                storeId = script.d,
                scriptId = script.x,

                jarFile = "jars/$name.jar",
                optionFile = "options/$name.txt",
            )))
        }

        outputPath.resolve("jars/$name.jar").toFile().also {
            it.parentFile.mkdirs()
            it.createNewFile()
            it.writeBytes(data)
        }

        outputPath.resolve("options/$name.txt").toFile().also {
            it.parentFile.mkdirs()
            it.createNewFile()
            it.writeText(options)
        }
    }

    private fun <T> ChannelHandlerContext.sendPacketAndWait(
        packet: Any,
        responseClass: Class<T>,
        timeout: Long = 5000L
    ): T {
        val promise = channel().eventLoop().newPromise<Any>()
        pending[responseClass] = promise

        if (packet is Packet<*>) {
            writeAndFlush(packet.pack(packetCount++, true))
        } else {
            writeAndFlush(packet)
        }.addListener {
            if (!it.isSuccess) {
                pending.remove(responseClass)
                promise.setFailure(it.cause())
            }
        }

        val msg = promise.get(timeout, TimeUnit.MILLISECONDS)
        return responseClass.cast(msg)
    }

    private fun sanitizeName(name: String): String {
        return name.replace(" ", "_").replace("[^A-Za-z0-9_]".toRegex(), "")
    }
}