package com.bearite.patcher

import org.json.JSONObject
import java.io.File

/**
 * Значения настроек мода. Источник правды: <мод>/settings.json.
 * Для нативной части рядом пишется settings.txt (строки key=value),
 * его libbearite перечитывает при каждом обращении мода к настройке.
 */
object ModSettings {
    private fun jsonFile(m: Mod) = File(m.dir, "settings.json")
    private fun txtFile(m: Mod) = File(m.dir, "settings.txt")

    fun defaultOf(s: Setting): Any = when (s.type) {
        "bool" -> (s.default as? Boolean) ?: false
        "int" -> (s.default as? Number)?.toLong() ?: s.min?.toLong() ?: 0L
        "float" -> (s.default as? Number)?.toDouble() ?: s.min ?: 0.0
        "choice" -> (s.default as? String)?.takeIf { it in s.options } ?: s.options.firstOrNull() ?: ""
        else -> (s.default as? String) ?: ""
    }

    private fun coerce(s: Setting, v: Any?): Any {
        val d = defaultOf(s)
        if (v == null || v == JSONObject.NULL) return d
        return when (s.type) {
            "bool" -> (v as? Boolean) ?: d
            "int" -> (v as? Number)?.toLong()?.let { clamp(s, it.toDouble()).toLong() } ?: d
            "float" -> (v as? Number)?.toDouble()?.let { clamp(s, it) } ?: d
            "choice" -> (v as? String)?.takeIf { it in s.options } ?: d
            else -> (v as? String) ?: d
        }
    }

    private fun clamp(s: Setting, x: Double): Double {
        var r = x
        s.min?.let { if (r < it) r = it }
        s.max?.let { if (r > it) r = it }
        return r
    }

    fun load(m: Mod): MutableMap<String, Any> {
        val saved = try {
            JSONObject(jsonFile(m).readText())
        } catch (_: Throwable) {
            JSONObject()
        }
        val out = LinkedHashMap<String, Any>()
        for (s in m.settings) out[s.id] = coerce(s, if (saved.has(s.id)) saved.get(s.id) else null)
        return out
    }

    fun set(m: Mod, id: String, value: Any) {
        val s = m.settings.firstOrNull { it.id == id } ?: return
        val cur = load(m)
        cur[id] = coerce(s, value)
        save(m, cur)
    }

    private fun save(m: Mod, values: Map<String, Any>) {
        try {
            jsonFile(m).writeText(JSONObject(values).toString(2))
            writeTxt(m, values)
        } catch (t: Throwable) {
            BLog.e("не удалось сохранить настройки ${m.id}", t)
        }
    }

    fun writeTxt(m: Mod, values: Map<String, Any> = load(m)) {
        val text = values.entries.joinToString("\n") {
            it.key + "=" + it.value.toString().replace('\n', ' ').replace('\r', ' ')
        }
        txtFile(m).writeText(if (text.isEmpty()) "" else text + "\n")
    }
}
