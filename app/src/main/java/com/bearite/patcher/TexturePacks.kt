package com.bearite.patcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class TexPack(
    val id: String,
    val name: String,
    val description: String,
    val count: Int,
    val enabled: Boolean,
    val icon: File?,
)

/**
 * Текстур-паки как в Minecraft. Пак — папка filesDir/maps/texturepacks/<id>/ с PNG-файлами: имя файла без .png
 * совпадает с именем текстуры в игре (регистр не важен, вложенные папки можно). Необязательно: pack.json
 * {"name":"…","description":"…"} и pack.png (значок). Порядок и включённость хранятся в prefs; для мода (он в игре)
 * лаунчер пишет maps/texturepacks.txt: id включённых паков по строке, первый главнее. Мод читает его при запуске игры.
 */
class TexturePacks(private val ctx: Context) {
    private val mapsDir = File(ctx.filesDir, "maps")
    val root = File(mapsDir, "texturepacks")
    private val prefs = ctx.getSharedPreferences("texture_packs", Context.MODE_PRIVATE)

    private fun order(): MutableList<String> =
        (prefs.getString("order", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()

    private fun disabled(): MutableSet<String> =
        (prefs.getString("off", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableSet()

    private fun saveOrder(o: List<String>) { prefs.edit().putString("order", o.joinToString("\n")).apply() }
    private fun saveDisabled(d: Set<String>) { prefs.edit().putString("off", d.joinToString("\n")).apply() }

    /** Установленные паки в порядке приоритета (первый главнее). */
    fun list(): List<TexPack> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.map { it.name }?.toSet() ?: emptySet()
        val ord = order().filter { it in dirs }.toMutableList()
        for (d in dirs.sorted()) if (d !in ord) ord.add(d)
        if (ord != order()) saveOrder(ord)
        val off = disabled()
        return ord.map { id ->
            val dir = File(root, id)
            var name = id
            var desc = ""
            try {
                val j = JSONObject(File(dir, "pack.json").readText())
                name = j.optString("name", id).ifBlank { id }
                desc = j.optString("description", "")
            } catch (_: Throwable) {}
            val icon = File(dir, "pack.png").takeIf { it.isFile }
            TexPack(id, name, desc, countPng(dir, 0), id !in off, icon)
        }
    }

    private fun countPng(dir: File, depth: Int): Int {
        var n = 0
        for (f in dir.listFiles() ?: return 0) {
            if (f.isDirectory) { if (depth < 6) n += countPng(f, depth + 1) }
            else if (f.name.endsWith(".png", true) && !f.name.equals("pack.png", true)) n++
        }
        return n
    }

    fun setEnabled(id: String, on: Boolean) {
        val d = disabled()
        if (on) d.remove(id) else d.add(id)
        saveDisabled(d)
        changed()
    }

    /** Поднимает пак на одну позицию выше (приоритет растёт). */
    fun moveUp(id: String) {
        val o = list().map { it.id }.toMutableList()
        val i = o.indexOf(id)
        if (i <= 0) return
        o.removeAt(i); o.add(i - 1, id)
        saveOrder(o)
        changed()
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        saveOrder(order().filter { it != id })
        saveDisabled(disabled().also { it.remove(id) })
        changed()
    }

    /** Файл для мода: включённые паки по порядку. Вызывается при любом изменении и перед запуском игры. */
    fun writeConfig() {
        mapsDir.mkdirs()
        val on = list().filter { it.enabled }.map { it.id }
        val f = File(mapsDir, "texturepacks.txt")
        if (on.isEmpty()) f.delete() else f.writeText(on.joinToString("\n"))
    }

    private fun changed() {
        File(mapsDir, "_tp_guard").delete()   // набор поменяли: даём моду ещё одну попытку после падения
        writeConfig()
    }

    /** Имя текстуры, на которой игра упала при подмене (мод оставил маркер и отключил паки), либо null. */
    fun crashedOn(): String? {
        val g = File(mapsDir, "_tp_guard")
        return if (g.isFile) try { g.readText().trim().ifEmpty { "?" } } catch (_: Throwable) { "?" } else null
    }

    // ------------------------------------------------------------- список текстур игры

    /** Файл со списком текстур игры (имя и размер), если мод его уже записал. */
    fun namesFile(): File? = File(mapsDir, "texture_names.txt").takeIf { it.isFile && it.length() > 0 }

    /** Просит мод обновить список при следующем запуске игры. */
    fun requestNames() {
        try { mapsDir.mkdirs(); File(mapsDir, "_dump_textures").writeText("1") } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------- установка

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun cleanId(raw: String): String {
        val s = raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' || it == '.' }.trim().trim('.')
        return s.ifEmpty { "pack" }.take(40)
    }

    private fun freeId(base: String): String {
        if (!File(root, base).exists()) return base
        var i = 2
        while (File(root, "$base-$i").exists()) i++
        return "$base-$i"
    }

    /** Ставит пак из zip. Берутся только PNG и pack.json; пути проверяются, чтобы архив не вышел за свою папку. */
    fun installZip(uri: Uri): String {
        val id = freeId(cleanId((displayName(uri) ?: "pack").removeSuffix(".zip").removeSuffix(".ZIP")))
        root.mkdirs()
        val tmp = File(root, ".tmp_$id").also { it.deleteRecursively(); it.mkdirs() }
        var files = 0
        var total = 0L
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("не удалось открыть файл")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                    if (parts.isEmpty() || parts.any { it == ".." || it.startsWith(".") }) continue
                    val leaf = parts.last()
                    val isPng = leaf.endsWith(".png", true)
                    if (!isPng && !leaf.equals("pack.json", true)) continue
                    if (parts.any { it.startsWith("__MACOSX") }) continue
                    if (++files > 20000) error("в архиве слишком много файлов")
                    val dest = File(tmp, parts.joinToString("/"))
                    if (!dest.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                    dest.parentFile?.mkdirs()
                    var size = 0L
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            size += n
                            total += n
                            if (size > 40L * 1024 * 1024 || total > 600L * 1024 * 1024) error("архив слишком большой")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            finish(tmp, id)
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
        return id
    }

    /** Ставит пак из папки на телефоне (выбор папки в системном окне). */
    fun installTree(treeUri: Uri): String {
        val rootDoc = DocumentsContract.getTreeDocumentId(treeUri)
        val nm = displayName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDoc)) ?: rootDoc.substringAfterLast(':').substringAfterLast('/')
        val id = freeId(cleanId(nm))
        root.mkdirs()
        val tmp = File(root, ".tmp_$id").also { it.deleteRecursively(); it.mkdirs() }
        try {
            val counter = IntArray(1)
            copyTree(treeUri, rootDoc, tmp, 0, counter)
            finish(tmp, id)
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
        return id
    }

    private fun copyTree(tree: Uri, docId: String, dest: File, depth: Int, counter: IntArray) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val cid = c.getString(0) ?: continue
                val nm = (c.getString(1) ?: continue).replace('/', '_')
                if (nm.startsWith(".")) continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 6) copyTree(tree, cid, File(dest, nm), depth + 1, counter)
                } else if (nm.endsWith(".png", true) || nm.equals("pack.json", true)) {
                    if (++counter[0] > 20000) error("в папке слишком много файлов")
                    dest.mkdirs()
                    val src = DocumentsContract.buildDocumentUriUsingTree(tree, cid)
                    ctx.contentResolver.openInputStream(src)?.use { i -> File(dest, nm).outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
    }

    /** Если внутри ровно одна папка (архив «МойПак/…»), берём её содержимое; ставим пак в конец списка включённым. */
    private fun finish(tmp: File, id: String) {
        val kids = tmp.listFiles()?.toList() ?: emptyList()
        val src = if (kids.size == 1 && kids[0].isDirectory) kids[0] else tmp
        if (countPng(src, 0) == 0 && !File(src, "pack.png").exists()) error("внутри нет PNG-файлов")
        val dest = File(root, id)
        if (!src.renameTo(dest)) { src.copyRecursively(dest, true) }
        tmp.deleteRecursively()
        val o = order(); o.remove(id); o.add(0, id)   // новый пак ставим сверху: он главнее старых
        saveOrder(o)
        saveDisabled(disabled().also { it.remove(id) })
        BLog.i("текстур-пак «$id» установлен, текстур: ${countPng(dest, 0)}")
        changed()
    }
}
