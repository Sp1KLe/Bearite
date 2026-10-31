package com.bearite.patcher

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Загрузка сохранения .sba с телефона: файл выбирается в проводнике и кладётся в папку сохранений игры.
 * Игра работает внутри лаунчера, поэтому её данные лежат в папках лаунчера (внутренней и в Android/data/.../files).
 * Папку сохранений лаунчер определяет по уже существующим файлам .sba; если сохранений ещё нет, предлагает положить файл в папку данных игры.
 */
class SaveImport(private val act: AppCompatActivity) {

    private fun roots(): List<File> = listOfNotNull(act.getExternalFilesDir(null), act.filesDir).distinctBy { it.absolutePath }

    private fun walk(dir: File, depth: Int, out: MutableList<File>, all: Boolean) {
        if (depth > 5 || out.size > 400) return
        val kids = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
        for (f in kids) {
            if (f.isDirectory) { if (f.name != "maps" && f.name != "cache") walk(f, depth + 1, out, all) }
            else if (all || f.name.endsWith(".sba", ignoreCase = true)) out.add(f)
        }
    }

    private fun existing(): List<File> { val l = ArrayList<File>(); for (r in roots()) walk(r, 0, l, false); return l }

    /** Пишет в лог, что лежит в папках игры: по этому видно, куда игра кладёт сохранения. */
    fun logLayout() {
        for (r in roots()) {
            val l = ArrayList<File>(); walk(r, 0, l, true)
            BLog.i("сохранения: папка ${r.absolutePath}, файлов ${l.size}: " + l.take(40).joinToString("; ") { it.relativeTo(r).path + " (" + it.length() + ")" })
        }
    }

    private fun nameOf(uri: Uri): String {
        try {
            act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "save.sba"
    }

    fun handle(uri: Uri) {
        val name = nameOf(uri)
        val data = try {
            act.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) { BLog.e("сохранение: не прочитал файл", t); null }
        if (data == null || data.isEmpty()) { toast("Не получилось прочитать файл"); return }
        if (data.size > 30_000_000) { toast("Файл слишком большой для сохранения"); return }
        BLog.i("сохранение: выбран «$name», ${data.size} байт")
        logLayout()
        if (!name.endsWith(".sba", ignoreCase = true)) {
            AlertDialog.Builder(act).setTitle("Это не .sba").setMessage("Файл «$name» не похож на сохранение .sba. Всё равно загрузить?")
                .setNegativeButton("Отмена", null).setPositiveButton("Загрузить") { _, _ -> choose(name, data) }.show()
        } else choose(name, data)
    }

    private fun choose(name: String, data: ByteArray) {
        val have = existing()
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        if (have.isEmpty()) {
            val target = File(roots().first(), name)
            AlertDialog.Builder(act).setTitle("Сохранений игры пока нет")
                .setMessage("Лаунчер ещё не видит папку сохранений. Лучше сначала запусти игру, создай новое сохранение, выйди в меню и повтори загрузку: тогда файл заменит настоящий слот.\\n\\nМожно положить файл сразу в папку данных игры:\\n${target.parent}")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Положить сюда") { _, _ -> write(target, data) }.show()
            return
        }
        val items = ArrayList<String>(); val targets = ArrayList<File>()
        for (f in have) {
            val rel = roots().firstOrNull { f.absolutePath.startsWith(it.absolutePath) }?.let { f.relativeTo(it).path } ?: f.name
            items.add("Заменить: $rel (${f.length() / 1024} КБ, ${fmt.format(Date(f.lastModified()))})"); targets.add(f)
        }
        items.add("Добавить как новый файл «$name»"); targets.add(File(have.first().parentFile, name))
        AlertDialog.Builder(act).setTitle("Куда загрузить сохранение?")
            .setItems(items.toTypedArray()) { _, i -> write(targets[i], data) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun write(target: File, data: ByteArray) {
        try {
            target.parentFile?.mkdirs()
            if (target.exists()) target.copyTo(File(target.parentFile, target.name + ".bak-" + System.currentTimeMillis() / 1000), overwrite = true)
            target.writeBytes(data)
            BLog.i("сохранение: записано ${target.absolutePath}, ${data.size} байт")
            AlertDialog.Builder(act).setTitle("Сохранение загружено")
                .setMessage("Файл записан: ${target.name}. Прежний файл, если был, сохранён рядом с пометкой .bak. Запусти игру и открой слот сохранения.")
                .setPositiveButton("Ок", null).show()
        } catch (t: Throwable) {
            BLog.e("сохранение: не записалось", t)
            toast("Не получилось записать: ${t.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
}
