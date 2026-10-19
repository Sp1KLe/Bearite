package com.bearite.patcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Кнопки модов поверх игры (поле "hud" в mod.json). Нажатие меняет настройку мода, а нативная часть
 * подхватывает новое значение сразу. Кнопки есть только у включённых модов и скрыты на паузе.
 */
class GameHud(private val act: Activity, mods: ModManager) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** Прозрачный слой на весь экран: касания мимо кнопок уходят в игру. */
    val root = FrameLayout(act)

    private class Entry(val mod: Mod, val button: HudButton, val view: TextView)

    private val entries = ArrayList<Entry>()
    private val hint = TextView(act)
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(250).start() }

    init {
        val anchors = HashMap<String, LinearLayout>()
        for (m in mods.scan()) {
            if (!m.enabled || m.hud.isEmpty()) continue
            if (m.libs.isNotEmpty() && !mods.isNativeAccepted(m.id)) continue
            for (b in m.hud) {
                val v = TextView(act).apply {
                    text = b.label
                    textSize = if (b.label.length > 2) 13f else 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    isClickable = true
                }
                val e = Entry(m, b, v)
                v.setOnClickListener { click(e) }
                entries.add(e)
                val anchor = anchors.getOrPut(b.pos) { newAnchor(b.pos) }
                anchor.addView(v, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(8) })
                paint(e)
            }
        }

        hint.textSize = 16f
        hint.typeface = Typeface.DEFAULT_BOLD
        hint.setTextColor(Color.WHITE)
        hint.setPadding(dp(16), dp(8), dp(16), dp(8))
        hint.background = round(0xCC2E2670.toInt(), 16)
        hint.alpha = 0f
        root.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(70) }
        )
    }

    /** Перерисовать состояние кнопок (например, после изменения настроек в мод-меню). */
    fun refresh() = entries.forEach { paint(it) }

    private fun newAnchor(pos: String): LinearLayout {
        val g = when (pos) {
            "right" -> Gravity.END or Gravity.CENTER_VERTICAL
            "top-left" -> Gravity.TOP or Gravity.START
            "top-right" -> Gravity.TOP or Gravity.END
            "bottom-left" -> Gravity.BOTTOM or Gravity.START
            "bottom-right" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.START or Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            col,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, g)
                .apply { setMargins(dp(12), dp(12), dp(12), dp(12)) }
        )
        return col
    }

    private fun setting(e: Entry): Setting? = e.mod.settings.firstOrNull { it.id == e.button.setting }

    private fun paint(e: Entry) {
        val on = e.button.action == "toggle" &&
            (ModSettings.load(e.mod)[e.button.setting] as? Boolean) == true
        e.view.background = round(if (on) 0xF23FA866.toInt() else 0xCC4F43AE.toInt(), 16).apply {
            setStroke(dp(2), 0xAAFFFFFF.toInt())
        }
    }

    private fun click(e: Entry) {
        val s = setting(e) ?: return
        val values = ModSettings.load(e.mod)
        val cur = values[s.id] ?: ModSettings.defaultOf(s)
        val shown: String = when (e.button.action) {
            "toggle" -> {
                val next = !(cur as? Boolean ?: false)
                ModSettings.set(e.mod, s.id, next)
                if (next) "вкл" else "выкл"
            }
            "step" -> {
                val next = (cur as? Number)?.toDouble()?.plus(e.button.step) ?: return
                ModSettings.set(e.mod, s.id, if (s.type == "int") Math.round(next) else next)
                fmt(ModSettings.load(e.mod)[s.id])
            }
            else -> {
                if (s.options.isEmpty()) return
                val next = s.options[(s.options.indexOf(cur.toString()) + 1).mod(s.options.size)]
                ModSettings.set(e.mod, s.id, next)
                next
            }
        }
        paint(e)
        // короткая «пружинка» на кнопке и подпись с новым значением
        e.view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
            e.view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
        hint.text = "${s.name}: $shown"
        hint.removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.alpha = 1f
        hint.postDelayed(hideHint, 1200)
    }

    private fun fmt(v: Any?): String = when (v) {
        is Double -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        else -> v?.toString() ?: ""
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}
