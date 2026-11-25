package com.pixel.universalremote

/** Sesión abierta con una TV: abrir, mandar teclas, cerrar. */
interface TvSession {
    fun open(): Boolean
    fun sendKey(key: RemoteKey): Boolean
    fun close()
}

/** Códigos KEY_* de Samsung (sirven para clásico y para Tizen). */
object SamsungKeys {
    fun map(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "KEY_POWER"
        RemoteKey.HOME -> "KEY_HOME"
        RemoteKey.UP -> "KEY_UP"
        RemoteKey.DOWN -> "KEY_DOWN"
        RemoteKey.LEFT -> "KEY_LEFT"
        RemoteKey.RIGHT -> "KEY_RIGHT"
        RemoteKey.OK -> "KEY_ENTER"
        RemoteKey.BACK -> "KEY_RETURN"
        RemoteKey.VOL_UP -> "KEY_VOLUP"
        RemoteKey.VOL_DOWN -> "KEY_VOLDOWN"
        RemoteKey.MUTE -> "KEY_MUTE"
        RemoteKey.CH_UP -> "KEY_CHUP"
        RemoteKey.CH_DOWN -> "KEY_CHDOWN"
        RemoteKey.SOURCE -> "KEY_SOURCE"
        RemoteKey.DIGIT_0 -> "KEY_0"
        RemoteKey.DIGIT_1 -> "KEY_1"
        RemoteKey.DIGIT_2 -> "KEY_2"
        RemoteKey.DIGIT_3 -> "KEY_3"
        RemoteKey.DIGIT_4 -> "KEY_4"
        RemoteKey.DIGIT_5 -> "KEY_5"
        RemoteKey.DIGIT_6 -> "KEY_6"
        RemoteKey.DIGIT_7 -> "KEY_7"
        RemoteKey.DIGIT_8 -> "KEY_8"
        RemoteKey.DIGIT_9 -> "KEY_9"
    }
}
