package com.pixel.universalremote

import org.json.JSONObject

/**
 * Samsung clásico (Orsay, aprox. 2014–2016, ej. UN40J5300AF 2015):
 * WebSocket sin cifrar al puerto 55000. Los mensajes van codificados en
 * base64. Formato verificado con samsungctl:
 *   ms.channel.connect { client, app: "com.samsung.tv.remote", device, type }
 *   ms.remote.control  { Cmd: "Click", DataOfCmd: "KEY_*", Option: "false",
 *                        TypeOfRemote: "SendRemoteKey" }
 */
class SamsungLegacySession(private val host: String) : TvSession {
    private var ws: WsClient? = null

    override fun open(): Boolean {
        val client = WsClient()
        client.base64Mode = true
        if (!client.connect("ws://$host:55000/", 3500)) return false
        val connect = JSONObject()
            .put("method", "ms.channel.connect")
            .put(
                "params", JSONObject()
                    .put("client", APP_NAME)
                    .put("app", "com.samsung.tv.remote")
                    .put("device", "Pixel")
                    .put("type", "iPhone")
            )
        if (!client.send(connect.toString())) {
            client.close()
            return false
        }
        val resp = client.receive(3500)
        if (resp == null || !resp.contains("ms.channel.connect")) {
            client.close()
            return false
        }
        ws = client
        return true
    }

    override fun sendKey(key: RemoteKey): Boolean {
        val code = SamsungKeys.map(key) ?: return false
        val msg = JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params", JSONObject()
                    .put("Cmd", "Click")
                    .put("DataOfCmd", code)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey")
            )
        return try {
            ws?.send(msg.toString()) ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        ws?.close()
        ws = null
    }
}

/**
 * Samsung Tizen (2016+): WebSocket al canal samsung.remote.control.
 *   wss://IP:8002/api/v2/channels/samsung.remote.control?name=<base64(app)>[&token=<token>]
 *   (o ws://IP:8001/... en modelos que no exigen TLS).
 * La primera vez sin token la TV muestra "Permitir" en pantalla y devuelve
 * un token en el evento ms.channel.connect; ese token se guarda y las
 * siguientes veces ya no pregunta. Las teclas van con ms.remote.control
 * (Cmd Click / SendRemoteKey), igual que en el protocolo clásico.
 */
class SamsungTizenSession(
    private val host: String,
    private var token: String?,
    private val onToken: (String) -> Unit
) : TvSession {
    private var ws: WsClient? = null

    override fun open(): Boolean {
        val nameB64 = android.util.Base64.encodeToString(
            APP_NAME.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        for (port in listOf(8002, 8001)) {
            val scheme = if (port == 8002) "wss" else "ws"
            val url = StringBuilder(
                "$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=$nameB64"
            )
            if (!token.isNullOrEmpty()) url.append("&token=").append(token)
            val client = WsClient()
            if (client.connect(url.toString(), 5000)) {
                // Sin token, la TV pide permitir en pantalla: damos hasta 10 s.
                val resp = client.receive(10000)
                if (resp != null && resp.contains("ms.channel.connect")) {
                    try {
                        val newToken = JSONObject(resp)
                            .optJSONObject("data")?.optString("token")
                        if (!newToken.isNullOrEmpty() && newToken != token) {
                            token = newToken
                            onToken(newToken)
                        }
                    } catch (_: Exception) {
                    }
                    ws = client
                    return true
                }
                client.close()
            }
        }
        return false
    }

    override fun sendKey(key: RemoteKey): Boolean {
        val code = SamsungKeys.map(key) ?: return false
        val msg = JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params", JSONObject()
                    .put("Cmd", "Click")
                    .put("DataOfCmd", code)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey")
            )
        return try {
            ws?.send(msg.toString()) ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        ws?.close()
        ws = null
    }
}

/**
 * Sesión Samsung: prueba el protocolo clásico primero (el de la J5300 de
 * 2015) y cae a Tizen si no responde. Recuerda cuál funcionó para ir
 * directo la próxima vez.
 */
class SamsungSession(
    private val device: TvDevice,
    private val onProtocol: (String) -> Unit,
    private val onToken: (String) -> Unit
) : TvSession {
    private var inner: TvSession? = null

    override fun open(): Boolean {
        // Ir directo si ya sabemos cuál funcionó antes.
        when (device.protocol) {
            "legacy" -> {
                val s = SamsungLegacySession(device.ip)
                if (s.open()) {
                    inner = s
                    return true
                }
            }
            "tizen" -> {
                val s = SamsungTizenSession(device.ip, device.token, onToken)
                if (s.open()) {
                    inner = s
                    return true
                }
            }
        }
        val legacy = SamsungLegacySession(device.ip)
        if (legacy.open()) {
            inner = legacy
            onProtocol("legacy")
            return true
        }
        val tizen = SamsungTizenSession(device.ip, device.token, onToken)
        if (tizen.open()) {
            inner = tizen
            onProtocol("tizen")
            return true
        }
        return false
    }

    override fun sendKey(key: RemoteKey): Boolean = inner?.sendKey(key) ?: false

    override fun close() {
        inner?.close()
        inner = null
    }
}
