package rip.sunrise.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import rip.sunrise.server.config.Config
import rip.sunrise.server.config.ConfigWatcher
import rip.sunrise.server.http.JarHttpServer
import rip.sunrise.server.netty.ServerInitializer
import kotlin.concurrent.thread
import kotlin.io.path.Path

const val HTTP_PORT = 6666
const val NETTY_PORT = 1337
const val CONFIG_ENV = "CONFIG_DIR"

fun main() {
    val configDir = Path(System.getenv(CONFIG_ENV) ?: run {
        println("ERROR: $CONFIG_ENV environment variable not set!")
        return
    })

    if (!configDir.toFile().exists()) {
        println("ERROR: Config directory does not exist: $configDir")
        return
    }

    val config = Config(configDir).also {
        try {
            it.load()
            println("Configuration loaded successfully")
        } catch (e: Exception) {
            println("ERROR: Failed to load configuration: ${e.message}")
            e.printStackTrace()
            return
        }
    }

    val http = try {
        JarHttpServer(HTTP_PORT, config).apply {
            start()
            println("HTTP Server started on port $HTTP_PORT")
        }
    } catch (e: Exception) {
        println("ERROR: Failed to start HTTP server: ${e.message}")
        e.printStackTrace()
        return
    }

    val configWatcher = ConfigWatcher(configDir, config, http)
    val configWatcherThread = thread(isDaemon = true, name = "Config Watcher Thread") {
        try {
            configWatcher.run()
        } catch (e: Exception) {
            println("ERROR: Config watcher failed: ${e.message}")
            e.printStackTrace()
        }
    }

    val initializer = ServerInitializer(config, http)
    val group = NioEventLoopGroup()

    try {
        val bootstrap = ServerBootstrap()
        bootstrap.group(group)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(initializer)

        println("Starting Netty Server on port $NETTY_PORT")
        val f = bootstrap.bind(NETTY_PORT).sync()
        println("Netty Server started successfully")

        // Add shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(thread(start = false, name = "Shutdown Hook") {
            println("Shutting down server...")

            try {
                // Stop config watcher
                configWatcherThread.interrupt()
                println("Config watcher stopped")

                // Shutdown server handler
                initializer.getServerHandler()?.shutdown()
                println("Server handler stopped")

                // Stop HTTP server
                http.stop()
                println("HTTP server stopped")

                // Clear caches
                http.clearCache()
                println("HTTP server caches cleared")

                // Shutdown Netty gracefully
                group.shutdownGracefully().sync()
                println("Netty server stopped")

                println("Server shutdown complete")
            } catch (e: Exception) {
                println("Error during shutdown: ${e.message}")
                e.printStackTrace()
            }
        })

        f.channel().closeFuture().sync()
    } catch (e: Exception) {
        println("ERROR: Failed to start Netty server: ${e.message}")
        e.printStackTrace()
    } finally {
        try {
            group.shutdownGracefully()
        } catch (e: Exception) {
            println("Error during cleanup: ${e.message}")
        }
    }
}