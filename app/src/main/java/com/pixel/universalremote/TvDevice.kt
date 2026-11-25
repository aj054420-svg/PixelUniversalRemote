package com.pixel.universalremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Brand {
    SAMSUNG, ROKU, LG;

    fun label(): String = when (this) {
        SAMSUNG -> "Samsung"
        ROKU -> "Roku"
        LG -> "LG webOS"
    }
}

/** Una TV guardada por el usuario. */
data class TvDevice(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var ip: String,
    var brand: Brand,
    /** Protocolo que funcionó la última vez: "legacy" | "tizen" | "ecp" | "webos". */
    var protocol: String? = null,
    /** Token Samsung Tizen o client-key LG webOS, si ya se emparejó. */
    var token: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ip", ip)
        put("brand", brand.name)
        if (protocol != null) put("protocol", protocol)
        if (token != null) put("token", token)
    }

    companion object {
        fun fromJson(o: JSONObject): TvDevice = TvDevice(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "TV"),
            ip = o.optString("ip", ""),
            brand = runCatching { Brand.valueOf(o.optString("brand", "SAMSUNG")) }.getOrDefault(Brand.SAMSUNG),
            protocol = o.optString("protocol", null),
            token = o.optString("token", null)
        )
    }

    override fun toString(): String {
        val proto = when (protocol) {
            "legacy" -> " · Samsung clásico"
            "tizen" -> " · Samsung Tizen"
            "ecp" -> " · Roku"
            "webos" -> " · LG webOS"
            else -> ""
        }
        return "$name — $ip$proto"
    }
}

/** Guarda la lista de TVs en SharedPreferences (JSON). */
class TvStore(context: Context) {
    private val prefs = context.getSharedPreferences("pixel_remote", Context.MODE_PRIVATE)

    fun load(): MutableList<TvDevice> {
        val raw = prefs.getString("tvs", "[]") ?: "[]"
        val list = mutableListOf<TvDevice>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(TvDevice.fromJson(arr.getJSONObject(i)))
            }
        }
        return list
    }

    fun save(list: List<TvDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("tvs", arr.toString()).apply()
    }
}
