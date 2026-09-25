package com.ilkay.redmigateway.server

import android.content.Context
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Serves the local iPhone control page over the phone hotspot. Commands still require WebSocket auth. */
class GatewayWebPageServer(private val context: Context, private val port: Int = 8788) {
    companion object {
        private const val TAG = "GatewayWebPageServer"
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        val socket = ServerSocket(port)
        serverSocket = socket
        running = true
        worker = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Accept failed", e)
                    break
                }
                try {
                    serve(client)
                } catch (e: Exception) {
                    Log.w(TAG, "Request failed", e)
                } finally {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }, "RedmiGateway-LocalWeb").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        worker = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream()
        val request = ByteArray(4096)
        var length = 0
        var previous = 0
        while (length < request.size) {
            val next = input.read()
            if (next < 0) break
            request[length++] = next.toByte()
            previous = ((previous shl 8) or next) and 0xFFFFFFFF.toInt()
            if (previous == 0x0D0A0D0A) break
        }
        val firstLine = String(request, 0, length, StandardCharsets.ISO_8859_1)
            .lineSequence().firstOrNull() ?: ""
        val path = firstLine.split(' ').getOrNull(1)
        val asset = when {
            firstLine.startsWith("GET ") && (path == "/" || path == "/index.html") ->
                "gateway/index.html" to "text/html; charset=UTF-8"
            firstLine.startsWith("GET ") && path == "/app.css" ->
                "gateway/app.css" to "text/css; charset=UTF-8"
            firstLine.startsWith("GET ") && path == "/app.js" ->
                "gateway/app.js" to "text/javascript; charset=UTF-8"
            else -> null
        }
        val body = asset?.let { context.assets.open(it.first).use { file -> file.readBytes() } }
            ?: "Not found".toByteArray(StandardCharsets.UTF_8)
        val status = if (asset == null) "404 Not Found" else "200 OK"
        val output = client.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: ${asset?.second ?: "text/plain; charset=UTF-8"}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'self'; script-src 'self'; connect-src ws:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }
}
