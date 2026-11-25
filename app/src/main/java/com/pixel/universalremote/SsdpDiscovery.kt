package com.pixel.universalremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

data class DiscoveredTv(val ip: String, val brand: Brand, val label: String)

/**
 * Descubrimiento por SSDP (M-SEARCH a 239.255.255.250:1900).
 * Samsung responde a urn:samsung.com:device:RemoteControlReceiver:1,
 * Roku a roku:ecp y LG a urn:lge-com:service:webos-second-screen:1.
 * La IP se saca del encabezado LOCATION de la respuesta.
 */
object SsdpDiscovery {
    fun discover(timeoutMs: Int = 4000): List<DiscoveredTv> {
        val results = LinkedHashMap<String, DiscoveredTv>()
        val targets = listOf(
            "urn:samsung.com:device:RemoteControlReceiver:1" to Brand.SAMSUNG,
            "roku:ecp" to Brand.ROKU,
            "urn:lge-com:service:webos-second-screen:1" to Brand.LG
        )
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 500
            val group = InetAddress.getByName("239.255.255.250")
            for ((st, _) in targets) {
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ns=01\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n" +
                        "USER-AGENT: PixelRemote/2.0\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(DatagramPacket(msg, msg.size, group, 1900))
                }
            }
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = Regex("(?i)LOCATION:\\s*(\\S+)")
                        .find(text)?.groupValues?.getOrNull(1)
                    val ip = location?.let {
                        runCatching { URI(it).host }.getOrNull()
                    } ?: packet.address?.hostAddress ?: continue
                    val server = Regex("(?i)SERVER:\\s*(.+)")
                        .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    val brand = when {
                        text.contains("samsung", ignoreCase = true) ||
                            text.contains("RemoteControlReceiver") -> Brand.SAMSUNG
                        text.contains("roku", ignoreCase = true) -> Brand.ROKU
                        text.contains("lge", ignoreCase = true) ||
                            text.contains("webos", ignoreCase = true) -> Brand.LG
                        else -> null
                    } ?: continue
                    if (!results.containsKey(ip)) {
                        results[ip] = DiscoveredTv(ip, brand, server.ifEmpty { ip })
                    }
                } catch (_: SocketTimeoutException) {
                    // Seguir esperando hasta el plazo.
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
        return results.values.toList()
    }
}
