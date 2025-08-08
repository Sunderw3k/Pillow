
package rip.sunrise.server.netty

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.compression.ZlibCodecFactory
import io.netty.handler.codec.compression.ZlibWrapper
import io.netty.handler.codec.serialization.ObjectDecoder
import io.netty.handler.timeout.ReadTimeoutHandler
import rip.sunrise.packets.serialization.ObfuscatedClassResolver
import rip.sunrise.packets.serialization.ObfuscatedEncoder
import rip.sunrise.server.config.Config
import rip.sunrise.server.http.JarHttpServer

class ServerInitializer(private val config: Config, private val http: JarHttpServer) : ChannelInitializer<SocketChannel>() {

    // Create a single shared handler instance
    private val serverHandler = ServerHandler(config, http)

    override fun initChannel(ch: SocketChannel) {
        val pipeline = ch.pipeline()

        pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB));
        pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.ZLIB));

        pipeline.addLast(LengthFieldBasedFrameDecoder(Int.MAX_VALUE, 0, 4, 0, 4))
        pipeline.addLast(LengthFieldPrepender(4))

        pipeline.addLast(ObfuscatedEncoder)
        pipeline.addLast(ObjectDecoder(ObfuscatedClassResolver))

        pipeline.addLast(ReadTimeoutHandler(600))

        // Use the shared handler instance (now properly marked as @Sharable)
        pipeline.addLast(serverHandler)
    }

    fun getServerHandler(): ServerHandler = serverHandler
}