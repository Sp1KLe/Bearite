package com.bearite.patcher

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Отдельное меню читов поверх игры. Кнопка «Читы» видна всё время, пока открыта игра:
 * в хабе, на уровнях, в меню паузы и в мультиплеере. Переключатели пишут настройки мода «sba-cheats»
 * (те же, что на вкладке «Установлено»), нативный мод читает их при каждом обращении.
 */
class CheatOverlay(private val act: Activity, private val parent: FrameLayout) {
    private val d = UiScale.density(act)
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = GameUi(act)
    private val mods = ModManager(act)
    private var button: TextView? = null
    private var panel: View? = null

    companion object {
        const val MOD_ID = "sba-cheats"
    }

    fun start() {
        val b = ui.gameBtn("Читы", GameUi.YELLOW, 15f, 14) { toggle() }
        button = b
        parent.addView(b, FrameLayout.LayoutParams(dp(92), dp(38), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10)
            topMargin = dp(10)
        })
    }

    val isOpen: Boolean get() = panel != null

    fun toggle() = if (isOpen) hide() else show()

    fun hide() {
        panel?.let { parent.removeView(it) }
        panel = null
    }

    private fun show() {
        hide()
        val mod = mods.scan().firstOrNull { it.id == MOD_ID }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(GameUi.SURFACE, 20, GameUi.OUTLINE, 3)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.label("Читы", 22f, GameUi.CREAM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.label("✕", 20f, GameUi.MUTED).apply {
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { hide() }
        })
        box.addView(head)

        if (mod == null) {
            box.addView(ui.label(
                "Мод «Читы» не установлен. Поставь его во вкладке «Все моды» в меню паузы.",
                15f, GameUi.MUTED, bold = false
            ).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            val note = if (mod.enabled) {
                "Работает в одиночной игре. Переключатели сохраняются между запусками."
            } else {
                "Мод выключен. Включи его во вкладке «Установлено» в меню паузы, иначе читы не работают."
            }
            box.addView(ui.label(note, 14f, GameUi.MUTED, bold = false).apply { setPadding(0, dp(8), 0, dp(6)) })

            val values = ModSettings.load(mod)
            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            for (s in mod.settings) {
                val control: View = when (s.type) {
                    "bool" -> {
                        val cur = (values[s.id] as? Boolean) ?: false
                        ui.togglePill(cur, 84) { v -> ModSettings.set(mod, s.id, v) }
                    }
                    "int", "float" -> {
                        val lo = s.min ?: 0.0
                        val hi = s.max ?: 100.0
                        val cur = (values[s.id] as? Number)?.toDouble() ?: lo
                        val isInt = s.type == "int"
                        ui.slider(lo, hi, cur, if (isInt) 0 else 2, 150) { v ->
                            ModSettings.set(mod, s.id, if (isInt) Math.round(v) else v)
                        }
                    }
                    else -> continue
                }
                val row = ui.row(s.name, control)
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                if (s.description.isNotBlank()) {
                    list.addView(ui.label(s.description, 13f, GameUi.DIM, bold = false).apply {
                        setPadding(dp(14), dp(2), dp(8), 0)
                    })
                }
            }
            val scroll = ScrollView(act).apply { addView(list); isVerticalScrollBarEnabled = false }
            box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val wrap = FrameLayout(act).apply {
            isClickable = true
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { hide() }
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dp(16); rightMargin = dp(16)
            })
        }
        box.isClickable = true
        panel = wrap
        parent.addView(wrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
