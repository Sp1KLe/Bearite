package com.bearite.patcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Замена сцен игры своими картами. В корне репозитория лежит папка replace_scenes/: файл `<сцена>.glb` (или `<сцена>.map`)
 * заменяет сцену с таким именем (hub, level_1 …). Папка попадает в assets APK; при запуске каждый файл ставится как карта-мир
 * с id `replace_<сцена>`, привязанная к этой сцене. Необязательный `<сцена>.json`: {"spawn":[x,y,z],"sky":"87B8E8","scale":1}.
 * Файл поменялся — карта обновляется, не менялся — настройки (в том числе выключатель) не трогаем. Убрали файл — карта удаляется.
 */
object SceneReplace {
    private const val DIR = "replace_scenes"
    private const val PREFS = "scene_replace"

    fun sync(ctx: Context) {
        try {
            val names = ctx.assets.list(DIR)?.toList() ?: return
            val store = MapStore(ctx)
            val shop = PropShop(File(store.dirPath()), store)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val present = HashSet<String>()
            val bySceneJson = names.filter { it.endsWith(".json", true) }.associateBy { it.substringBeforeLast('.') }
            for (fn in names) {
                val ext = fn.substringAfterLast('.', "").lowercase()
                if (ext != "glb" && ext != "map") continue
                val scene = fn.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                if (scene.isEmpty()) continue
                present.add(scene)
                val bytes = ctx.assets.open("$DIR/$fn").use { it.readBytes() }
                val cfgBytes = bySceneJson[fn.substringBeforeLast('.')]?.let { ctx.assets.open("$DIR/$it").use { s -> s.readBytes() } }
                val hash = MessageDigest.getInstance("SHA-1").digest(bytes + (cfgBytes ?: ByteArray(0))).joinToString("") { "%02x".format(it) }
                val id = "replace_$scene"
                if (prefs.getString(scene, null) == hash && store.list().any { it.id == id }) continue
                val old = store.list().firstOrNull { it.id == id }
                val cfg = try { cfgBytes?.let { JSONObject(String(it, Charsets.UTF_8)) } } catch (_: Throwable) { null }
                val map: GameMap
                if (ext == "map") {
                    map = MapStore.parse(id, String(bytes, Charsets.UTF_8).removePrefix("BEARITE-MAP").trimStart())
                } else {
                    val pid = "scene_$scene"
                    val err = shop.install(pid, scene, bytes)
                    if (err != null) { BLog.i("замена сцены $scene: модель не разобрана: $err"); continue }
                    map = GameMap(id, "Замена: $scene")
                    val o = MapObj("Сцена $scene", "cprop:$pid")
                    o.solid = true
                    if (store.textureFile("cp_$pid") != null) o.tex = "cp_$pid"
                    map.objects.add(o)
                    // точка появления: над центром модели, чуть выше её верхней точки
                    shop.loadMesh(pid)?.let { (v, _, _) ->
                        var lx = 1e9; var hx = -1e9; var hy = -1e9; var lz = 1e9; var hz = -1e9; var i = 0
                        while (i + 2 < v.size) { lx = minOf(lx, v[i].toDouble()); hx = maxOf(hx, v[i].toDouble()); hy = maxOf(hy, v[i + 1].toDouble()); lz = minOf(lz, v[i + 2].toDouble()); hz = maxOf(hz, v[i + 2].toDouble()); i += 3 }
                        map.spx = (lx + hx) / 2; map.spz = (lz + hz) / 2; map.spy = hy + 2.0
                    }
                }
                map.name = if (ext == "map") map.name.ifEmpty { "Замена: $scene" } else map.name
                map.world = true; map.scene = scene; map.enabled = old?.enabled ?: true
                if (map.oy == 0.0) map.oy = 1000.0   // мир строится высоко над остальным, чтобы не пересекаться
                cfg?.optJSONArray("spawn")?.let { a -> if (a.length() >= 3) { map.spx = a.getDouble(0); map.spy = a.getDouble(1); map.spz = a.getDouble(2) } }
                cfg?.optString("sky", "")?.takeIf { it.isNotEmpty() }?.let { s -> s.removePrefix("#").toIntOrNull(16)?.let { c -> map.sky = c } }
                cfg?.optDouble("scale", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }?.let { map.scale = it }
                store.save(map)
                prefs.edit().putString(scene, hash).apply()
                BLog.i("замена сцены «$scene»: карта установлена (${map.objects.size} объектов)")
            }
            // файл убрали из папки — карту замены удаляем
            for (m in store.list()) if (m.id.startsWith("replace_") && m.id.removePrefix("replace_") !in present) {
                store.delete(m); prefs.edit().remove(m.id.removePrefix("replace_")).apply()
            }
        } catch (t: Throwable) { try { BLog.i("замена сцен: ${t.message}") } catch (_: Throwable) {} }
    }
}
