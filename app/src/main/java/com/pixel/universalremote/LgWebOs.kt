package com.pixel.universalremote

import org.json.JSONObject

/**
 * LG webOS (SSAP, JSON por WebSocket):
 *   - Socket principal: wss://IP:3001 (o ws://IP:3000 en firmware viejo).
 *   - Registro: {"type":"register","payload":{"pairingType":"PROMPT",
 *     "client-key":"<clave>","manifest":{...}}}. La primera vez la TV pide
 *     permitir en pantalla y devuelve una client-key que se guarda.
 *   - Comandos: {"type":"request","id":"<n>","uri":"ssap://...","payload":{}}.
 *   - Botones (flechas, OK, atrás, inicio, dígitos...): NO van por SSAP;
 *     van por un segundo socket que se pide con
 *     ssap://com.webos.service.networkinput/getPointerInputSocket, que
 *     devuelve una socketPath (URL wss). Ahí cada botón es texto plano:
 *         type:button
 *         name:UP
 *       (líneas clave:valor terminadas con una línea en blanco).
 *   - Volumen/canal/apagado: ssap://audio/volumeUp, volumeDown,
 *     ssap://tv/channelUp, channelDown, ssap://system/turnOff.
 *   Nota: encender una LG apagada por red no funciona por SSAP (haría
 *   falta Wake-on-LAN); el botón de encendido solo apaga.
 */
class LgWebOsSession(
    private val host: String,
    private var clientKey: String?,
    private val onClientKey: (String) -> Unit
) : TvSession {
    private var ws: WsClient? = null
    private var pointerWs: WsClient? = null
    private var msgId = 0

    override fun open(): Boolean {
        for ((scheme, port) in listOf("wss" to 3001, "ws" to 3000)) {
            val client = WsClient()
            if (!client.connect("$scheme://$host:$port/", 4000)) continue
            val manifest = JSONObject()
                .put("manifestVersion", 1)
                .put("appVersion", "1.1")
                .put(
                    "signed", JSONObject()
                        .put("created", "20140509")
                        .put("appId", "com.pixel.remote")
                        .put("vendorId", "com.pixel")
                        .put(
                            "localizedAppNames",
                            JSONObject().put("", "Pixel Remote")
                        )
                        .put(
                            "localizedVendorNames",
                            JSONObject().put("", "Pixel")
                        )
                        .put(
                            "permissions", org.json.JSONArray(
                                listOf(
                                    "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
                                    "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
                                    "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
                                    "CONTROL_INPUT_MEDIA_RECORDING",
                                    "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                                    "CONTROL_POWER", "READ_APP_STATUS",
                                    "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
                                    "READ_NETWORK_STATE", "READ_RUNNING_APPS",
                                    "READ_TV_CHANNEL", "WRITE_NOTIFICATION_TOAST",
                                    "READ_POWER_STATE", "READ_COUNTRY_INFO"
                                )
                            )
                        )
                )
            val payload = JSONObject().put("pairingType", "PROMPT")
            if (!clientKey.isNullOrEmpty()) payload.put("client-key", clientKey)
            payload.put("manifest", manifest)
            val register = JSONObject()
                .put("type", "register")
                .put("payload", payload)
            if (!client.send(register.toString())) {
                client.close()
                continue
            }
            // Sin client-key, la TV pide permitir: damos hasta 15 s.
            val resp = client.receive(15000)
            if (resp != null && resp.contains("\"registered\"")) {
                try {
                    val key = JSONObject(resp)
                        .optJSONObject("payload")?.optString("client-key")
                    if (!key.isNullOrEmpty() && key != clientKey) {
                        clientKey = key
                        onClientKey(key)
                    }
                } catch (_: Exception) {
                }
                ws = client
                return true
            }
            client.close()
        }
        return false
    }

    override fun sendKey(key: RemoteKey): Boolean {
        return try {
            when (key) {
                RemoteKey.POWER -> request("ssap://system/turnOff")
                RemoteKey.VOL_UP -> request("ssap://audio/volumeUp")
                RemoteKey.VOL_DOWN -> request("ssap://audio/volumeDown")
                RemoteKey.CH_UP -> request("ssap://tv/channelUp")
                RemoteKey.CH_DOWN -> request("ssap://tv/channelDown")
                RemoteKey.SOURCE -> button("MENU")
                else -> {
                    val name = pointerName(key) ?: return false
                    button(name)
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun pointerName(key: RemoteKey): String? = when (key) {
        RemoteKey.UP -> "UP"
        RemoteKey.DOWN -> "DOWN"
        RemoteKey.LEFT -> "LEFT"
        RemoteKey.RIGHT -> "RIGHT"
        RemoteKey.OK -> "ENTER"
        RemoteKey.BACK -> "BACK"
        RemoteKey.HOME -> "HOME"
        RemoteKey.MUTE -> "MUTE"
        else -> key.digit()?.toString()
    }

    /** Petición SSAP sin esperar respuesta (dispara y sigue). */
    private fun request(uri: String, payload: JSONObject = JSONObject()): Boolean {
        val client = ws ?: return false
        val id = (++msgId).toString()
        val msg = JSONObject()
            .put("type", "request")
            .put("id", id)
            .put("uri", uri)
            .put("payload", payload)
        return client.send(msg.toString())
    }

    /** Botón por el socket de entrada (flechas, OK, dígitos...). */
    private fun button(name: String): Boolean {
        if (!ensurePointerSocket()) return false
        return pointerWs?.send("type:button\nname:$name\n\n") ?: false
    }

    private fun ensurePointerSocket(): Boolean {
        if (pointerWs != null) return true
        val client = ws ?: return false
        val id = (++msgId).toString()
        val msg = JSONObject()
            .put("type", "request")
            .put("id", id)
            .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            .put("payload", JSONObject())
        if (!client.send(msg.toString())) return false
        // La respuesta puede venir tras otras; buscamos la que trae socketPath.
        repeat(3) {
            val resp = client.receive(3000) ?: return false
            try {
                val obj = JSONObject(resp)
                val socketPath = obj.optJSONObject("payload")?.optString("socketPath")
                if (!socketPath.isNullOrEmpty()) {
                    val pointer = WsClient()
                    if (pointer.connect(socketPath, 4000)) {
                        pointerWs = pointer
                        return true
                    }
                    return false
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    override fun close() {
        pointerWs?.close()
        pointerWs = null
        ws?.close()
        ws = null
    }
}
