package com.bearite.patcher

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/**
 * Окно «Достижения» (только просмотр). Список даёт нативный мод в maps/_achievements:
 * строки «1 или 0 (получено)\tназвание\tописание». Своих достижений не добавляем.
 */
class AchievementsWindow(private val act: Activity, private val ui: GameUi) {
    fun show() {
        val rows = try {
            File(File(act.filesDir, "maps"), "_achievements").readLines().filter { it.isNotBlank() }.map { it.split('\t') }.filter { it.size >= 2 }
        } catch (_: Throwable) { emptyList() }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        if (rows.isEmpty()) {
            col.addView(ui.label("Список достижений игры пока недоступен", 14f, M3.VARIANT, bold = false).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(24), 0, ui.dp(24)) })
        } else {
            val got = rows.count { it[0] == "1" }
            col.addView(ui.label("Получено: $got из ${rows.size}", 14f, M3.VARIANT, bold = false).apply { setPadding(0, 0, 0, ui.dp(6)) })
            for (r in rows) {
                val done = r[0] == "1"
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = ui.round(M3.HIGH, 14, M3.OUTLINE_V); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    alpha = if (done) 1f else 0.65f
                }
                row.addView(ui.label(if (done) "★" else "🔒", 20f, if (done) M3.PRIMARY else M3.VARIANT), LinearLayout.LayoutParams(ui.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
                val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(ui.label(r[1], 15f))
                if (r.size > 2 && r[2].isNotBlank()) tx.addView(ui.label(r[2], 12f, M3.VARIANT, bold = false))
                row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            }
        }
        val sv = ScrollView(act).apply { addView(col) }
        ui.dialog("Достижения", sv, listOf(GameUi.Btn("Закрыть")), widthDp = 520).show()
    }
}
