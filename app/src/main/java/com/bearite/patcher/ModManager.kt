package com.bearite.patcher

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class Mod(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val dir: File,
    val bundles: List<File>,
    val enabled: Boolean,
)

/**
 * Моды лежат в <files>/mods/<id>/mod.json. Формат:
 * { "id": "my-mod", "name": "...", "version": "1.0", "author": "...",
 *   "bundles": [ { "src": "maps/arena.bundle" } ] }
 */
class ModManager(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mods", Context.MODE_PRIVATE)

    val modsDir: File
        get() = (ctx.getExternalFilesDir("mods") ?: File(ctx.filesDir, "mods")).also { it.mkdirs() }

    val configFile: File get() = File(ctx.filesDir, "bearite_bundles.txt")

    fun scan(): List<Mod> {
        val out = ArrayList<Mod>()
        val dirs = modsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return out
        for (dir in dirs.sortedBy { it.name }) {
            val mf = File(dir, "mod.json")
            if (!mf.isFile) continue
            try {
                val meta = JSONObject(mf.readText())
                val id = meta.optString("id", dir.name)
                val bundles = ArrayList<File>()
                val arr = meta.optJSONArray("bundles")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.get(i)
                        val src = if (item is JSONObject) item.optString("src") else item.toString()
                        val f = File(dir, src).canonicalFile
                        // бандл обязан лежать внутри папки мода
                        if (f.path.startsWith(dir.canonicalPath + File.separator) && f.isFile) {
                            bundles.add(f)
                        } else {
                            BLog.e("мод $id: бандл '$src' не найден или вне папки мода")
                        }
                    }
                }
                out.add(
                    Mod(
                        id = id,
                        name = meta.optString("name", id),
                        version = meta.optString("version", "?"),
                        author = meta.optString("author", ""),
                        dir = dir,
                        bundles = bundles,
                        enabled = prefs.getBoolean("enabled_$id", true),
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

    /** Удаляет папку мода (только внутри папки модов) и его настройку. */
    fun delete(mod: Mod): Boolean {
        val dir = mod.dir.canonicalFile
        if (!dir.path.startsWith(modsDir.canonicalPath + File.separator)) {
            BLog.e("отказ удалять ${dir.path}: вне папки модов")
            return false
        }
        val ok = dir.deleteRecursively()
        prefs.edit().remove("enabled_${mod.id}").apply()
        BLog.i("мод удалён: ${mod.id} (${if (ok) "ok" else "не полностью"})")
        return ok
    }

    /** Пишет конфиг для нативной части: по одному пути бандла на строку. */
    fun writeConfig(): File {
        val lines = scan().filter { it.enabled }.flatMap { m -> m.bundles.map { it.absolutePath } }
        configFile.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        BLog.i("конфиг записан, бандлов: ${lines.size}")
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
        if (!Regex("[A-Za-z0-9_.-]+").matches(id)) {
            tmp.deleteRecursively()
            throw IllegalArgumentException("в mod.json нет корректного id")
        }
        val dest = File(modsDir, id)
        dest.deleteRecursively()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
        tmp.deleteRecursively()
        BLog.i("мод установлен: $id")
        return id
    }
}
