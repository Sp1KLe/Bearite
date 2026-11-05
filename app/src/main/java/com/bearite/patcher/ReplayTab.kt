package com.bearite.patcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Вкладка «Реплей» в меню паузы (по мотивам Replay Mod): запись всех игроков комнаты по кнопке, сохранение записей в файлы,
 * список сохранённых (смотреть, переименовать, удалить). Сам просмотр с полосой времени, свободной камерой и путём камеры
 * показывает ReplayOverlay поверх игры. Команды и состояние: ReplayLink.
 */
class ReplayTab(act: Activity, private val ui: GameUi) {
    private val act = act
    private val link = ReplayLink(act)
    private val main = Handler(Looper.getMainLooper())
    private var pendingName = ""

    /** Меню нужно закрыть, чтобы увидеть повтор. */
    var onPlay: (() -> Unit)? = null

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun render(into: LinearLayout, refresh: () -> Unit) {
        into.removeAllViews()
        val st = link.status()
        into.addView(ui.label("Реплей", 16f), ui.matchWrap().apply { topMargin = ui.dp(8) })
        into.addView(ui.label(
            "Запись пути всех игроков комнаты (в одиночной игре — твоего медведя). В просмотре: пауза, перемотка, скорость, свободная камера и путь камеры по ключевым кадрам. Смотреть можно на той же карте, где записано.",
            13f, GameUi.MUTED, bold = false))
        if (st == null) {
            into.addView(ui.label("Нет данных от мода Bearite: он должен быть включён во вкладке «Установлено», а игра перезапущена после включения.", 13f, GameUi.RUST, bold = false),
                ui.matchWrap().apply { topMargin = ui.dp(10) })
        }
        val rec = st?.rec == true
        val line = when {
            st == null -> ""
            rec -> "Идёт запись: ${ReplayLink.fmtTime(st.durMs)} (${st.frames} кадров)"
            st.sess -> "Идёт просмотр записи на ${ReplayLink.fmtTime(st.durMs)}"
            st.frames >= 2 -> "Текущая запись: ${ReplayLink.fmtTime(st.durMs)}"
            else -> "Записи пока нет"
        }
        if (line.isNotEmpty()) into.addView(ui.label(line, 14f, GameUi.CREAM), ui.matchWrap().apply { topMargin = ui.dp(10) })
        if (!st?.msg.isNullOrEmpty()) into.addView(ui.label(st!!.msg, 13f, GameUi.RUST, bold = false), ui.matchWrap().apply { topMargin = ui.dp(4) })

        if (st != null) {
            into.addView(
                ui.pill(if (rec) "■ Остановить запись" else "● Начать запись", primary = !rec, danger = rec) {
                    if (rec) link.cmds("stop")
                    else { link.writeKeys("_current", emptyList()); ReplayLink.keyName = "_current"; link.cmds("start") }
                    main.postDelayed({ refresh() }, 700)
                },
                ui.matchWrap().apply { topMargin = ui.dp(12) })

            // текущая запись в памяти: сохранить или посмотреть
            if (!rec && st.frames >= 2) {
                if (pendingName.isEmpty()) pendingName = "Запись " + SimpleDateFormat("dd.MM HH-mm", Locale.US).format(Date())
                into.addView(ui.label("Название для сохранения", 12f, GameUi.MUTED), ui.matchWrap().apply { topMargin = ui.dp(12) })
                into.addView(ui.textInput(pendingName, 300, "название записи") { pendingName = it }, ui.matchWrap())
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(ui.pill("Сохранить", sizeSp = 13f) {
                    val n = link.safeName(pendingName)
                    link.cmds("save\t$n")
                    val keys = link.readKeys("_current")
                    if (keys.isNotEmpty()) link.writeKeys(n, keys)
                    pendingName = ""
                    main.postDelayed({ refresh() }, 800)
                }, wrap().apply { topMargin = ui.dp(8); rightMargin = ui.dp(8) })
                row.addView(ui.pill("▶ Смотреть", primary = true, sizeSp = 13f) {
                    ReplayLink.keyName = "_current"
                    link.cmds("keys\t_current", "play")
                    onPlay?.invoke()
                }, wrap().apply { topMargin = ui.dp(8) })
                into.addView(row)
            }
        }

        // сохранённые записи
        val items = link.list()
        into.addView(ui.label("Сохранённые записи", 14f), ui.matchWrap().apply { topMargin = ui.dp(16) })
        if (items.isEmpty()) into.addView(ui.label("Пока нет. Запиши игру и нажми «Сохранить».", 13f, GameUi.MUTED, bold = false))
        for (item in items) {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.round(GameUi.ROW, 16)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10))
            }
            box.addView(ui.label(item.name, 14f, GameUi.CREAM))
            box.addView(ui.label(ReplayLink.fmtTime(item.durMs) + " · " + item.frames + " кадров" + (if (item.scene.isNotEmpty()) " · " + item.scene else ""), 12f, GameUi.MUTED, bold = false))
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = item.name
            row.addView(ui.pill("▶ Смотреть", primary = st != null, sizeSp = 13f) {
                ReplayLink.keyName = name
                link.cmds("load\t$name", "play")
                onPlay?.invoke()
            }, wrap().apply { topMargin = ui.dp(6); rightMargin = ui.dp(6) })
            row.addView(ui.pill("✎", sizeSp = 13f) { rename(name, refresh) }, wrap().apply { topMargin = ui.dp(6); rightMargin = ui.dp(6) })
            row.addView(ui.pill("🗑", danger = true, sizeSp = 13f) {
                ui.confirm("Удалить запись", "Запись «$name» и её ключи камеры будут удалены.", "Удалить", danger = true) {
                    link.delete(name)
                    refresh()
                }
            }, wrap().apply { topMargin = ui.dp(6) })
            box.addView(row)
            into.addView(box, ui.matchWrap().apply { topMargin = ui.dp(8) })
        }
        if (rec) main.postDelayed({ refresh() }, 1000)   // пока идёт запись, обновляем время
    }

    private fun rename(old: String, refresh: () -> Unit) {
        var text = old
        val field = ui.textInput(old, 300, "новое название") { text = it }
        ui.dialog("Переименовать", field, listOf(
            GameUi.Btn("Отмена"),
            GameUi.Btn("Готово", primary = true) {
                if (!link.rename(old, text)) android.widget.Toast.makeText(act, "Такое название уже есть", android.widget.Toast.LENGTH_SHORT).show()
                refresh()
            },
        )).show()
    }
}
