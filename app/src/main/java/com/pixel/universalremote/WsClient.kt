package com.pixel.universalremote

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Cliente WebSocket mínimo (RFC 6455) sin dependencias externas.
 * Soporta ws:// y wss:// (las TVs usan certificados autofirmados, por eso
 * se acepta cualquier certificado: solo red local, nunca Internet).
 *
 * Modo base64: los mensajes de texto se envían y se reciben codificados en
 * base64 (protocolo clásico Samsung, puerto 55000).
 */
class WsClient {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    var base64Mode: Boolean = false

    fun connect(url: String, timeoutMs: Int = 4000): Boolean {
        return try {
            val uri = URI(url)
            val secure = uri.scheme.equals("wss", ignoreCase = true)
            val host = uri.host ?: return false
            val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
            val path = buildString {
                append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
            }
            val raw: Socket = if (secure) {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(TrustAllManager()), SecureRandom())
                val factory: SSLSocketFactory = ctx.socketFactory
                factory.createSocket()
            } else {
                Socket()
            }
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            socket = raw
            input = BufferedInputStream(raw.getInputStream())
            output = BufferedOutputStream(raw.getOutputStream())

            val keyBytes = ByteArray(16)
            SecureRandom().nextBytes(keyBytes)
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val req = StringBuilder()
            req.append("GET $path HTTP/1.1\r\n")
            req.append("Host: $host:$port\r\n")
            req.append("Upgrade: websocket\r\n")
            req.append("Connection: Upgrade\r\n")
            req.append("Sec-WebSocket-Key: $key\r\n")
            req.append("Sec-WebSocket-Version: 13\r\n")
            req.append("Origin: http://$host\r\n")
            req.append("\r\n")
            output!!.write(req.toString().toByteArray(Charsets.UTF_8))
            output!!.flush()

            val statusLine = readLine() ?: return false
            if (!statusLine.contains("101")) {
                closeQuietly()
                return false
            }
            var line: String?
            do {
                line = readLine()
            } while (line != null && line.isNotEmpty())
            true
        } catch (e: Exception) {
            closeQuietly()
            false
        }
    }

    /** Envía un mensaje de texto (en base64 si [base64Mode] está activo). */
    fun send(text: String): Boolean {
        return try {
            val payload = if (base64Mode) {
                Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    .toByteArray(Charsets.UTF_8)
            } else {
                text.toByteArray(Charsets.UTF_8)
            }
            sendFrame(0x1, payload)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Recibe un mensaje de texto (decodificado de base64 si [base64Mode]).
     * Devuelve null si se acaba el tiempo o se cierra la conexión.
     */
    fun receive(timeoutMs: Int = 5000): String? {
        return try {
            socket?.soTimeout = timeoutMs
            val text = StringBuilder()
            while (true) {
                val (opcode, payload) = readFrame() ?: return null
                when (opcode) {
                    0x9 -> { // ping -> pong
                        sendFrame(0xA, payload)
                        continue
                    }
                    0x8 -> return null // close
                    0x1, 0x2 -> {
                        val s = String(payload, Charsets.UTF_8)
                        text.append(s)
                        return decodeIfNeeded(text.toString())
                    }
                    0x0 -> { // continuación
                        text.append(String(payload, Charsets.UTF_8))
                    }
                    else -> continue
                }
            }
            decodeIfNeeded(text.toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeIfNeeded(s: String): String {
        if (!base64Mode) return s
        return runCatching {
            String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(s)
    }

    fun close() {
        try {
            sendFrame(0x8, ByteArray(0))
        } catch (_: Exception) {
        }
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input?.read() ?: return null
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** Envía un frame WebSocket (el cliente siempre enmascara, según RFC 6455). */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("sin conexión")
        val mask = ByteArray(4)
        SecureRandom().nextBytes(mask)
        val header = mutableListOf<Byte>()
        header.add((0x80 or opcode).toByte())
        when {
            payload.size < 126 -> header.add((0x80 or payload.size).toByte())
            payload.size <= 0xFFFF -> {
                header.add((0x80 or 126).toByte())
                header.add((payload.size shr 8).toByte())
                header.add((payload.size and 0xFF).toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                val len = payload.size.toLong()
                for (i in 7 downTo 0) header.add(((len shr (8 * i)) and 0xFF).toByte())
            }
        }
        out.write(header.toByteArray())
        out.write(mask)
        val masked = ByteArray(payload.size)
        for (i in payload.indices) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        out.write(masked)
        out.flush()
    }

    /** Lee un frame y devuelve (opcode, payload). Null si se cierra o hay error. */
    private fun readFrame(): Pair<Int, ByteArray>? {
        val inp = input ?: return null
        val b0 = inp.read()
        val b1 = inp.read()
        if (b0 == -1 || b1 == -1) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val h = inp.read()
            val l = inp.read()
            if (h == -1 || l == -1) return null
            len = ((h shl 8) or l).toLong()
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) {
                val b = inp.read()
                if (b == -1) return null
                v = (v shl 8) or b.toLong()
            }
            len = v
        }
        val maskKey = if (masked) {
            val m = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = inp.read(m, read, 4 - read)
                if (n == -1) return null
                read += n
            }
            m
        } else {
            null
        }
        if (len > 16 * 1024 * 1024) return null
        val payload = ByteArray(len.toInt())
        var read = 0
        while (read < payload.size) {
            val n = inp.read(payload, read, payload.size - read)
            if (n == -1) return null
            read += n
        }
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
