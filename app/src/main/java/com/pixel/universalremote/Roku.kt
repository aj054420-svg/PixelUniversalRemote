package com.pixel.universalremote

import java.net.HttpURLConnection
import java.net.URL

/**
 * Roku ECP: HTTP sin cifrar al puerto 8060.
 *   POST http://IP:8060/keypress/<tecla>
 * Sin emparejamiento. Si no responde, activa "control de red" en la TV.
 */
class RokuSession(private val host: String) : TvSession {

    override fun open(): Boolean {
        return try {
            val url = URL("http://$host:8060/query/device-info")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    override fun sendKey(key: RemoteKey): Boolean {
        val rokuKey = map(key) ?: return false
        return try {
            val url = URL("http://$host:8060/keypress/$rokuKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {}

    companion object {
        fun map(key: RemoteKey): String? = when (key) {
            RemoteKey.POWER -> "Power"
            RemoteKey.HOME -> "Home"
            RemoteKey.UP -> "Up"
            RemoteKey.DOWN -> "Down"
            RemoteKey.LEFT -> "Left"
            RemoteKey.RIGHT -> "Right"
            RemoteKey.OK -> "Select"
            RemoteKey.BACK -> "Back"
            RemoteKey.VOL_UP -> "VolumeUp"
            RemoteKey.VOL_DOWN -> "VolumeDown"
            RemoteKey.MUTE -> "VolumeMute"
            RemoteKey.CH_UP -> "ChannelUp"
            RemoteKey.CH_DOWN -> "ChannelDown"
            RemoteKey.SOURCE -> "InputHDMI1"
            else -> key.digit()?.let { "Lit_$it" }
        }
    }
}
