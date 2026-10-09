package com.zarinet.netcontrol.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8728,
    val user: String,
    val password: String,
    val wanInterface: String = "",
    /** الوصول السحابي (نفق عبر خادمنا) — العنوان العام ومنفذا API و Winbox. */
    val cloudHost: String = "",
    val cloudPort: Int = 0,
    val cloudWinbox: Int = 0
) {
    val hasCloud: Boolean get() = cloudHost.isNotBlank() && cloudPort > 0
}

/** حفظ الأجهزة محلياً (SharedPreferences + JSON). */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun load(): List<Device> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(
                id = o.getString("id"),
                name = o.optString("name"),
                host = o.optString("host"),
                port = o.optInt("port", 8728),
                user = o.optString("user"),
                password = o.optString("password"),
                wanInterface = o.optString("wan"),
                cloudHost = o.optString("cloudHost"),
                cloudPort = o.optInt("cloudPort", 0),
                cloudWinbox = o.optInt("cloudWinbox", 0)
            )
        }
    }

    fun save(list: List<Device>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("host", it.host)
                put("port", it.port); put("user", it.user); put("password", it.password)
                put("wan", it.wanInterface)
                put("cloudHost", it.cloudHost); put("cloudPort", it.cloudPort); put("cloudWinbox", it.cloudWinbox)
            })
        }
        runCatching { prefs.edit().putString("list", arr.toString()).apply() }
    }

    fun upsert(d: Device) {
        val l = load().toMutableList()
        val i = l.indexOfFirst { it.id == d.id }
        if (i >= 0) l[i] = d else l += d
        save(l)
    }

    fun delete(id: String) = save(load().filterNot { it.id == id })

    fun getString(key: String, def: String = "") = prefs.getString("s_$key", def) ?: def
    fun putString(key: String, v: String) { runCatching { prefs.edit().putString("s_$key", v).apply() } }
}
