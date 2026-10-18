package com.bearite.patcher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Настройка мода из mod.json: bool | int | float | string | choice. */
data class Setting(
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val default: Any?,
    val min: Double?,
    val max: Double?,
    val options: List<String>,
)

data class Mod(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val dir: File,
    val bundles: List<File>,
    /** Нативные библиотеки мода (arm64-v8a .so). */
    val libs: List<File>,
    val settings: List<Setting>,
    val enabled: Boolean,
    /** Картинка мода icon.png из папки мода (если есть). */
    val icon: File? = null,
)

/**
 * Моды лежат в <files>/mods/<id>/mod.json. Формат:
 * { "id": "my-mod", "name": "...", "version": "1.0", "author": "...", "description": "...",
 *   "bundles":  [ { "src": "maps/arena.bundle" } ],
 *   "libs":     [ "libs/arm64-v8a/libmymod.so" ],
 *   "settings": [ { "id": "speed", "type": "float", "name": "Скорость",
 *                   "min": 0.5, "max": 3, "default": 1 } ] }
 */
class ModManager(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mods", Context.MODE_PRIVATE)

    val modsDir: File
        get() = (ctx.getExternalFilesDir("mods") ?: File(ctx.filesDir, "mods")).also { it.mkdirs() }

    val configFile: File get() = File(ctx.filesDir, "bearite_bundles.txt")

    private val idRegex = Regex("[A-Za-z0-9_.-]+")
    private val settingTypes = setOf("bool", "int", "float", "string", "choice")

    private fun resolveInside(dir: File, rel: String, id: String, what: String): File? {
        val f = File(dir, rel).canonicalFile
        if (f.path.startsWith(dir.canonicalPath + File.separator) && f.isFile) return f
        BLog.e("мод $id: $what '$rel' не найден или вне папки мода")
        return null
    }

    private fun parseSettings(arr: JSONArray?): List<Setting> {
        val out = ArrayList<Setting>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!idRegex.matches(id)) continue
            var type = o.optString("type", "string")
            if (type !in settingTypes) continue
            val options = ArrayList<String>()
            o.optJSONArray("options")?.let { a -> for (j in 0 until a.length()) options.add(a.optString(j)) }
            if (type == "choice" && options.isEmpty()) type = "string"
            val min = o.optDouble("min", Double.NaN).takeIf { !it.isNaN() }
            val max = o.optDouble("max", Double.NaN).takeIf { !it.isNaN() }
            out.add(
                Setting(
                    id = id,
                    type = type,
                    name = o.optString("name", id),
                    description = o.optString("description", ""),
                    default = if (o.has("default")) o.get("default") else null,
                    min = min,
                    max = max,
                    options = options,
                )
            )
        }
        return out
    }

    fun scan(): List<Mod> {
        val out = ArrayList<Mod>()
        val dirs = modsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return out
        for (dir in dirs.sortedBy { it.name }) {
            val mf = File(dir, "mod.json")
            if (!mf.isFile) continue
            try {
                val meta = JSONObject(mf.readText())
                val id = meta.optString("id", dir.name)
                if (!idRegex.matches(id)) {
                    BLog.e("некорректный id мода '$id' в ${dir.name}")
                    continue
                }
                val bundles = ArrayList<File>()
                meta.optJSONArray("bundles")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.get(i)
                        val src = if (item is JSONObject) item.optString("src") else item.toString()
                        resolveInside(dir, src, id, "бандл")?.let { bundles.add(it) }
                    }
                }
                val libs = ArrayList<File>()
                meta.optJSONArray("libs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val src = arr.optString(i)
                        if (!src.endsWith(".so")) {
                            BLog.e("мод $id: '$src' не .so, пропускаю")
                            continue
                        }
                        resolveInside(dir, src, id, "библиотека")?.let { libs.add(it) }
                    }
                }
                out.add(
                    Mod(
                        id = id,
                        name = meta.optString("name", id),
                        version = meta.optString("version", "?"),
                        author = meta.optString("author", ""),
                        description = meta.optString("description", ""),
                        dir = dir,
                        bundles = bundles,
                        libs = libs,
                        settings = parseSettings(meta.optJSONArray("settings")),
                        // моды с нативным кодом выключены, пока пользователь их не подтвердит
                        enabled = prefs.getBoolean("enabled_$id", libs.isEmpty()),
                        icon = File(dir, "icon.png").takeIf { it.isFile },
                    )
                )
            } catch (t: Throwable) {
                BLog.e("не удалось прочитать ${mf.path}", t)
            }
        }
        return out
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun isNativeAccepted(id: String): Boolean = prefs.getBoolean("native_ok_$id", false)

    fun acceptNative(id: String) {
        prefs.edit().putBoolean("native_ok_$id", true).apply()
    }

    /** Удаляет папку мода (только внутри папки модов) и его настройки. */
    fun delete(mod: Mod): Boolean {
        val dir = mod.dir.canonicalFile
        if (!dir.path.startsWith(modsDir.canonicalPath + File.separator)) {
            BLog.e("отказ удалять ${dir.path}: вне папки модов")
            return false
        }
        val ok = dir.deleteRecursively()
        prefs.edit().remove("enabled_${mod.id}").remove("native_ok_${mod.id}").apply()
        BLog.i("мод удалён: ${mod.id} (${if (ok) "ok" else "не полностью"})")
        return ok
    }

    /**
     * Пишет конфиг для нативной части (по строке на запись, поля через TAB):
     *   B <путь бандла>
     *   M <id> <путь к settings.txt>
     *   L <id> <путь к .so>   (библиотека скопирована во внутреннюю папку)
     */
    fun writeConfig(): File {
        val lines = ArrayList<String>()
        val libRoot = File(ctx.filesDir, "modlibs")
        libRoot.deleteRecursively()
        var bundles = 0
        var libs = 0
        for (m in scan().filter { it.enabled }) {
            ModSettings.writeTxt(m)
            lines.add("M\t${m.id}\t${File(m.dir, "settings.txt").absolutePath}")
            for (b in m.bundles) {
                lines.add("B\t${b.absolutePath}")
                bundles++
            }
            if (m.libs.isEmpty()) continue
            if (!isNativeAccepted(m.id)) {
                BLog.e("мод ${m.id}: нативный код не подтверждён, библиотеки пропущены")
                continue
            }
            // dlopen из внешней памяти запрещён: копируем во внутреннюю папку, только для чтения
            val dest = File(libRoot, m.id).also { it.mkdirs() }
            for (l in m.libs) {
                val d = File(dest, l.name)
                l.copyTo(d, overwrite = true)
                d.setReadOnly()
                lines.add("L\t${m.id}\t${d.absolutePath}")
                libs++
            }
        }
        configFile.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        BLog.i("конфиг записан: бандлов $bundles, нативных библиотек $libs")
        return configFile
    }

    /** Ставит мод из zip (mod.json в корне архива или в единственной папке). Возвращает id. */
    fun installZip(uri: Uri): String {
        val tmp = File(modsDir, ".install_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator
        var total = 0L
        var entries = 0
        ctx.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "не удалось открыть файл" }
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > 20000) throw IllegalStateException("слишком много файлов в архиве")
                    val target = File(tmp, e.name).canonicalFile
                    if (!target.path.startsWith(tmpPath)) {
                        throw SecurityException("небезопасный путь в архиве: ${e.name}")
                    }
                    if (e.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 1_500_000_000L) throw IllegalStateException("архив слишком большой")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        }
        var root = tmp
        if (!File(root, "mod.json").isFile) {
            val subs = tmp.listFiles { f -> f.isDirectory }
            if (subs != null && subs.size == 1 && File(subs[0], "mod.json").isFile) root = subs[0]
        }
        val mf = File(root, "mod.json")
        if (!mf.isFile) {
            tmp.deleteRecursively()
            throw IllegalArgumentException("в архиве нет mod.json")
        }
        val id = JSONObject(mf.readText()).optString("id", "")
        if (!idRegex.matches(id)) {
            tmp.deleteRecursively()
            throw IllegalArgumentException("в mod.json нет корректного id")
        }
        val dest = File(modsDir, id)
        // сохраняем настройки пользователя при обновлении мода
        val oldSettings = File(dest, "settings.json").takeIf { it.isFile }?.readBytes()
        dest.deleteRecursively()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
        oldSettings?.let { File(dest, "settings.json").writeBytes(it) }
        tmp.deleteRecursively()
        // новая версия мода с нативным кодом должна быть подтверждена заново
        prefs.edit().remove("native_ok_$id").apply()
        BLog.i("мод установлен: $id")
        return id
    }
}
