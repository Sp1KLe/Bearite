package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.io.File

/**
 * Свои достижения. Описывают их моды в mod.json ("achievements": [{"id","name","desc"}]); код мода открывает их
 * через api->unlock_achievement(mod_id, id). Полученные хранятся в filesDir/achievements.txt (строки «мод:id»).
 */
class AchievementStore(private val ctx: Context) {
    class Def(val key: String, val modName: String, val name: String, val desc: String)

    private val file get() = File(ctx.filesDir, "achievements.txt")
    private val queue get() = File(ctx.filesDir, "_ach")
    private val taken get() = File(ctx.filesDir, "_ach.rd")

    fun unlocked(): Set<String> = try { if (file.isFile) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet() } catch (_: Throwable) { emptySet() }

    /** Все достижения установленных модов. */
    fun defs(): List<Def> {
        val out = ArrayList<Def>()
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
                    out.add(Def(d.name + ":" + id, mn, o.optString("name", id), o.optString("desc")))
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
                row.addView(ui.label(if (done) "★" else "🔒", 20f, if (done) M3.PRIMARY else M3.VARIANT), LinearLayout.LayoutParams(ui.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
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
