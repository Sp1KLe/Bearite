package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.io.File

/**
 * Свои достижения. Встроенные (мод Bearite) описаны в коде (builtin), аватарки — по желанию. Моды описывают их в mod.json ("achievements": [{"id","name","desc"}]); код мода открывает их
 * через api->unlock_achievement(mod_id, id). Полученные хранятся в filesDir/achievements.txt (строки «мод:id»).
 */
class AchievementStore(private val ctx: Context) {
    /** icon: имя png в assets/achievements (встроенные) или путь внутри папки мода; пусто — значок по умолчанию. */
    class Def(val key: String, val modName: String, val name: String, val desc: String, val icon: String = "", val modDir: File? = null)

    private val iconCache = HashMap<String, Bitmap?>()

    /** Аватарка достижения или null. */
    fun icon(d: Def): Bitmap? = iconCache.getOrPut(d.key) {
        if (d.icon.isBlank()) return@getOrPut null
        try {
            if (d.modDir == null) ctx.assets.open("achievements/" + d.icon).use { BitmapFactory.decodeStream(it) }
            else { val f = File(d.modDir, d.icon); if (f.canonicalPath.startsWith(d.modDir.canonicalPath)) BitmapFactory.decodeFile(f.path) else null }
        } catch (_: Throwable) { null }
    }

    private val file get() = File(ctx.filesDir, "achievements.txt")
    private val queue get() = File(ctx.filesDir, "_ach")
    private val taken get() = File(ctx.filesDir, "_ach.rd")

    fun unlocked(): Set<String> = try { if (file.isFile) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet() } catch (_: Throwable) { emptySet() }

    /** Достижения самого Bearite (встроенный мод): добавляются прямо здесь. */
    private val builtin: List<Def> = listOf(
        Def("bearite:first_coin", "Bearite", "Первая монета", "Подбери монету на карте"),
        Def("bearite:coins_10", "Bearite", "Коллекционер", "Собери 10 монет"),
        Def("bearite:builder", "Bearite", "Строитель", "Поставь объект на своей карте"),
        Def("bearite:modeler", "Bearite", "Модельер", "Добавь на карту модель из каталога"),
        Def("bearite:finish", "Bearite", "Финишная прямая", "Выиграй мини-игру на карте"),
        Def("bearite:curious", "Bearite", "Любопытный", "Загляни в список достижений"),
    )

    /** Открывает достижение из лаунчера; возвращает его название, если оно только что открыто. */
    fun unlockKey(key: String): String? {
        val def = defs().firstOrNull { it.key == key } ?: return null
        val have = unlocked().toMutableSet()
        if (!have.add(key)) return null
        try { file.writeText(have.joinToString("\n") + "\n") } catch (_: Throwable) {}
        return def.name
    }

    /** Все достижения: встроенные и установленных модов. */
    fun defs(): List<Def> {
        val out = ArrayList<Def>(builtin)
        val dirs = ModManager(ctx).modsDir.listFiles { f -> f.isDirectory } ?: return out
        for (d in dirs.sortedBy { it.name }) {
            try {
                val meta = JSONObject(File(d, "mod.json").readText())
                val arr = meta.optJSONArray("achievements") ?: continue
                val mn = meta.optString("name", d.name)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    out.add(Def(d.name + ":" + id, mn, o.optString("name", id), o.optString("desc"), o.optString("icon"), d))
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    /** Забирает команды от модов; возвращает названия только что открытых достижений. */
    fun poll(): List<String> {
        if (!queue.isFile) return emptyList()
        taken.delete()
        if (!queue.renameTo(taken)) return emptyList()
        val lines = try { taken.readLines() } catch (_: Throwable) { emptyList() }
        taken.delete()
        if (lines.isEmpty()) return emptyList()
        val have = unlocked().toMutableSet()
        val defs = defs().associateBy { it.key }
        val fresh = ArrayList<String>()
        for (l in lines.take(32)) {
            val p = l.split('\t')
            if (p.size < 2) continue
            val key = p[0] + ":" + p[1]
            val def = defs[key] ?: continue        // открыть можно только описанное в mod.json
            if (have.add(key)) fresh.add(def.name)
        }
        if (fresh.isNotEmpty()) try { file.writeText(have.joinToString("\n") + "\n") } catch (_: Throwable) {}
        return fresh
    }
}

/** Окно «Достижения» (кнопка «Достижения» в паузе игры). */
class AchievementsWindow(private val act: Activity, private val ui: GameUi) {
    fun show() {
        val store = AchievementStore(act)
        store.unlockKey("bearite:curious")
        val defs = store.defs()
        val have = store.unlocked()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        if (defs.isEmpty()) {
            col.addView(ui.label("Достижений пока нет: их добавляют моды", 14f, M3.VARIANT, bold = false).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(24), 0, ui.dp(24)) })
        } else {
            col.addView(ui.label("Получено: ${defs.count { it.key in have }} из ${defs.size}", 14f, M3.VARIANT, bold = false).apply { setPadding(0, 0, 0, ui.dp(6)) })
            for (d in defs) {
                val done = d.key in have
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = ui.round(M3.HIGH, 14, M3.OUTLINE_V); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    alpha = if (done) 1f else 0.65f
                }
                val bmp = store.icon(d)
                if (bmp != null) {
                    val iv = ImageView(act).apply {
                        setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                        if (!done) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })   // не получено — серая
                    }
                    row.addView(iv, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { rightMargin = ui.dp(10) })
                } else row.addView(ui.label(if (done) "★" else "🔒", 20f, if (done) M3.PRIMARY else M3.VARIANT), LinearLayout.LayoutParams(ui.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
                val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(ui.label(d.name, 15f))
                tx.addView(ui.label((if (d.desc.isNotBlank()) d.desc + " · " else "") + d.modName, 12f, M3.VARIANT, bold = false))
                row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            }
        }
        ui.dialog("Достижения", ScrollView(act).apply { addView(col) }, listOf(GameUi.Btn("Закрыть")), widthDp = 520).show()
    }
}
