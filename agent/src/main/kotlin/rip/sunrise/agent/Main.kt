package rip.sunrise.agent

import org.objectweb.asm.Opcodes
import rip.sunrise.injectapi.InjectApi
import rip.sunrise.injectapi.backends.InstrumentationBackend
import rip.sunrise.injectapi.global.Context
import rip.sunrise.injectapi.hooks.CapturedArgument
import rip.sunrise.injectapi.hooks.TargetMethod
import rip.sunrise.injectapi.hooks.inject.InjectHook
import rip.sunrise.injectapi.hooks.inject.modes.HeadInjection
import rip.sunrise.injectapi.managers.HookManager
import rip.sunrise.injectapi.utils.setAccessibleUnsafe
import java.lang.instrument.Instrumentation
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.URL
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext

const val SERVER_HOST = "localhost"
const val HTTP_HOST = "localhost"
const val SERVER_PORT = 43831

@OptIn(ExperimentalStdlibApi::class)
val SSL_CERT_FINGERPRINT = "a7ef2a6effc7df71abe00d522a3122fe150b9bdf95d604273733cad61764b716".hexToByteArray()

const val DREAMBOT_DOMAIN = "cdn.dreambot.org"
const val DREAMBOT_PORT = 43831

fun premain(args: String?, inst: Instrumentation) {
    HookManager.addHook(InjectHook(
        HeadInjection(),
        SSLContext::class.java,
        TargetMethod("createSSLEngine", "(Ljava/lang/String;I)Ljavax/net/ssl/SSLEngine;"),
        listOf(CapturedArgument(Opcodes.ALOAD, 1), CapturedArgument(Opcodes.ILOAD, 2)),
    ) { ctx: Context, peerHost: String, peerPort: Int ->
        if (peerHost != DREAMBOT_DOMAIN || peerPort != DREAMBOT_PORT) return@InjectHook

        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(GullibleTrustManager), SecureRandom())

        val engine = context.createSSLEngine(DREAMBOT_DOMAIN, DREAMBOT_PORT)
        log("Made fake engine!")

        ctx.setReturnValue(engine)
    })

    HookManager.addHook(InjectHook(
        HeadInjection(),
        MessageDigest::class.java,
        TargetMethod("digest", "([B)[B"),
        listOf(CapturedArgument(Opcodes.ALOAD, 0), CapturedArgument(Opcodes.ALOAD, 1))
    ) { ctx: Context, instance: MessageDigest, bytes: ByteArray ->
        val digested = instance.digest(bytes)
        ctx.setReturnValue(digested)

        if (digested.contentEquals(SSL_CERT_FINGERPRINT)) {
            log("Cert fingerprint check!")
            // NOTE: Hey DreamBot, get a real cert brokie.
            ctx.setReturnValue(byteArrayOf(-13, 71, -18, 102, -62, -15, -37, 24, 15, 39, 57, 43, -37, 107, 46, -49, 73, -85, -2, 112, -40, 67, 77, 33, 50, 89, 111, 13, 15, -37, -98, -81))
        }
    })

    val javaModule = ClassLoader::class.java.module
    Module::class.java.getDeclaredMethod(
        "implAddExportsOrOpens",
        String::class.java,
        Module::class.java,
        Boolean::class.java,
        Boolean::class.java
    ).also {
        it.setAccessibleUnsafe(true)
    }.invoke(javaModule, "sun.nio.ch", InjectApi::class.java.module, true, true)

    HookManager.addHook(InjectHook(
        HeadInjection(),
        Class.forName("sun.nio.ch.SocketChannelImpl"),
        TargetMethod("connect", "(Ljava/net/SocketAddress;)Z"),
        listOf(CapturedArgument(Opcodes.ALOAD, 0), CapturedArgument(Opcodes.ALOAD, 1))
    ) { ctx: Context, instance: SocketChannel, remote: SocketAddress ->
        if (remote !is InetSocketAddress || remote.hostName != DREAMBOT_DOMAIN) return@InjectHook

        println("Redirecting connection from ${remote.hostName}:${remote.port} to $SERVER_HOST:$SERVER_PORT!")

        val returnValue = instance.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT))

        val remoteAddressField = instance::class.java.getDeclaredField("remoteAddress").also { it.isAccessible = true }
        remoteAddressField.set(instance, remote)

        ctx.setReturnValue(returnValue)
    })

    HookManager.addHook(
        InjectHook(
            HeadInjection(),
            URL::class.java,
            TargetMethod("getAuthority", "()Ljava/lang/String;"),
            listOf(CapturedArgument(Opcodes.ALOAD, 0))
        ) { ctx: Context, instance: URL ->
            // TODO:
            //  Get the caller in a better way: 0 is lambda, 1 is hooked method,
            //  2 is caller (invoke handler), 3 is Method.invoke, 4 is actual caller
            val caller = Throwable().stackTrace[4].className
            if (instance.host == HTTP_HOST && (caller.startsWith("org.dreambot.5QR") || caller.startsWith("org.dreambot.67"))) {
                log("Spoofing script host!")
                ctx.setReturnValue("cloudflarestorage.com")
            }
        }
    )

    HookManager.addHook(
        InjectHook(
            HeadInjection(),
            URL::class.java,
            TargetMethod("getProtocol", "()Ljava/lang/String;"),
            listOf(CapturedArgument(Opcodes.ALOAD, 0))
        ) { ctx: Context, instance: URL ->
            val caller = Throwable().stackTrace[2].className
            if (instance.host == HTTP_HOST && caller.startsWith("org.dreambot")) {
                log("Spoofing protocol!")
                ctx.setReturnValue("https")
            }
        }
    )

    InjectApi.transform(InstrumentationBackend(inst))
}

// NOTE: If this is set, the client closes. Good fight DreamBot, checking for a VM prop LOL
val DEBUG = System.getProperty("pillow.debug")?.isNotEmpty() == true
fun log(message: String) {
    if (DEBUG) {
        println(message)
    }
}