package com.github.ssquadteam.kmap.pack

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class PackHost(private val bindIp: String, private val port: Int, private val publicAddress: String) {
    private val files = ConcurrentHashMap<String, ByteArray>()
    private var server: HttpServer? = null

    fun start() {
        val http = HttpServer.create(InetSocketAddress(bindIp, port), 0)
        http.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "kMap-pack-host").apply { isDaemon = true } }
        http.createContext("/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/")
            val bytes = files[name]
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        http.start()
        server = http
    }

    fun publish(name: String, bytes: ByteArray): String {
        files[name] = bytes
        return "http://$publicAddress:$port/$name"
    }

    fun unpublish(name: String) {
        files.remove(name)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }
}
