package com.bearite.patcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import java.io.File

/**
 * Вкладка «Реплей» в меню паузы: запись пути медведя по кнопке (идёт, пока её не остановишь) и повтор призраком.
 * Команды уходят нативному моду через maps/_cmd («rp\tstart», «rp\tstop», «rp\tplay», «rp\tstopplay»),
 * состояние мод пишет в maps/_replay: «запись(0/1)\tповтор(0/1)\tточек\tсекунд».
 */
class ReplayTab(act: Activity, private val ui: GameUi) {
    private val dir = File(act.filesDir, "maps")
    private val main = Handler(Looper.getMainLooper())

    /** Меню нужно закрыть, чтобы увидеть повтор. */
    var onPlay: (() -> Unit)? = null

    private class State(val rec: Boolean, val playing: Boolean, val points: Int, val secs: Long)

    private fun read(): State? = try {
        val p = File(dir, "_replay").readText().trim().split('\t')
        State(p[0] == "1", p[1] == "1", p[2].toInt(), p[3].toLong())
    } catch (_: Throwable) { null }

    private fun cmd(what: String) {
        BLog.i("реплей: команда «$what»")
        try { val t = File(dir, "_cmd.tmp"); t.writeText("rp\t$what\n"); t.renameTo(File(dir, "_cmd")) } catch (_: Throwable) {}
    }

    fun render(into: LinearLayout, refresh: () -> Unit) {
        into.removeAllViews()
        val st = read()
        into.addView(ui.label("Реплей", 16f), ui.matchWrap().apply { topMargin = ui.dp(8) })
        into.addView(ui.label(
            "Нажми «Начать запись» и играй: путь твоего медведя пишется, пока не нажмёшь «Остановить запись». Потом включи повтор: призрак пробежит записанный путь.",
            13f, GameUi.MUTED, bold = false))
        val line = when {
            st == null -> "Нет данных от мода Bearite: он должен быть включён в «Установлено», игра должна быть перезапущена после включения."
            st.rec -> "Идёт запись: ${st.secs} с (${st.points} точек)"
            st.playing -> "Идёт повтор записи на ${st.secs} с"
            st.points >= 2 -> "Записано: ${st.secs} с (${st.points} точек)"
            else -> "Записи пока нет"
        }
        into.addView(ui.label(line, 14f, GameUi.CREAM), ui.matchWrap().apply { topMargin = ui.dp(10) })

        val rec = st?.rec == true
        val playing = st?.playing == true
        into.addView(
            ui.pill(if (rec) "■ Остановить запись" else "● Начать запись", primary = !rec, danger = rec) {
                cmd(if (rec) "stop" else "start")
                main.postDelayed({ refresh() }, 700)
            },
            ui.matchWrap().apply { topMargin = ui.dp(12) })
        val canPlay = st != null && (st.points >= 2 || playing) && !rec
        into.addView(
            ui.pill(if (playing) "■ Остановить повтор" else "▶ Смотреть повтор", primary = canPlay && !playing, danger = playing) {
                if (playing) { cmd("stopplay"); main.postDelayed({ refresh() }, 700) }
                else if (canPlay) { cmd("play"); onPlay?.invoke() }
                else main.postDelayed({ refresh() }, 100)
            },
            ui.matchWrap().apply { topMargin = ui.dp(8) })
        into.addView(ui.label("Скорость повтора меняется в настройках мода Bearite («Установлено»). Запись сбрасывается при смене сцены.", 12f, GameUi.MUTED, bold = false),
            ui.matchWrap().apply { topMargin = ui.dp(10) })
        // пока вкладка открыта, подтягиваем состояние записи
        if (rec || playing) main.postDelayed({ refresh() }, 1000)
    }
}
