package com.bearite.patcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Меню модов внутри игры в её стиле: фиолетовая панель, вкладки «Установлено» / «Все моды»,
 * карточка мода с шестерёнкой, под ней строки настроек. Рисуется обычными View поверх Unity.
 */
class GameModMenu(private val act: Activity, private val mods: ModManager) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** Полноэкранная подложка; добавляется в корневой FrameLayout GameActivity. */
    val root = FrameLayout(act)
    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val expanded = HashSet<String>()
    private var tab = 0  // 0 = установлено (включённые), 1 = все
    private lateinit var tabInstalled: TextView
    private lateinit var tabAll: TextView

    init {
        root.visibility = View.GONE
        root.setBackgroundColor(0xB3000000.toInt())
        root.isClickable = true  // не пускаем касания в Unity
        root.setOnClickListener { hide() }

        val dm = act.resources.displayMetrics
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(PANEL, 22)
            setPadding(dp(14), dp(14), dp(14), dp(10))
            isClickable = true
        }
        val lp = FrameLayout.LayoutParams((dm.widthPixels * 0.86f).toInt(), (dm.heightPixels * 0.86f).toInt(), Gravity.CENTER)
        root.addView(panel, lp)

        // шапка: красный крестик и вкладки
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val close = label("X", 26f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(RED, 14)
            setOnClickListener { hide() }
        }
        head.addView(close, LinearLayout.LayoutParams(dp(60), dp(52)))
        tabInstalled = tabView("Установлено") { selectTab(0) }
        tabAll = tabView("Все моды") { selectTab(1) }
        head.addView(tabInstalled, tabLp())
        head.addView(tabAll, tabLp())
        panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sv = ScrollView(act).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val svLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        svLp.topMargin = dp(10)
        panel.addView(sv, svLp)

        panel.addView(
            label("Включение модов и нативный код применяются после перезапуска игры", 12f, 0xFFD6D0FF.toInt(), bold = false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
        )
    }

    fun show() {
        refresh()
        root.visibility = View.VISIBLE
    }

    fun hide() {
        hideKeyboard()
        root.visibility = View.GONE
    }

    private fun hideKeyboard() {
        try {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    private fun selectTab(t: Int) {
        tab = t
        refresh()
    }

    private fun refresh() {
        tabInstalled.background = round(if (tab == 0) TAB_ON else TAB_OFF, 14)
        tabAll.background = round(if (tab == 1) TAB_ON else TAB_OFF, 14)
        list.removeAllViews()
        val all = mods.scan()
        val shown = if (tab == 0) all.filter { it.enabled } else all
        if (shown.isEmpty()) {
            list.addView(
                label(
                    if (all.isEmpty()) "Модов пока нет. Установи их в приложении Bearite, вкладка «Моды»."
                    else "Нет включённых модов. Включи нужные на вкладке «Все моды».",
                    16f, 0xFFD6D0FF.toInt(),
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(40), dp(12), dp(40))
                }
            )
            return
        }
        for (m in shown) list.addView(card(m), cardLp())
    }

    // ----------------------------------------------------------------- карточка

    private fun card(m: Mod): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = label(m.name.take(1).uppercase(), 24f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(iconColor(m.id), 8)
        }
        head.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(m.name, 20f, Color.WHITE))
        val meta = buildString {
            append("v").append(m.version)
            if (m.author.isNotEmpty()) append(" · ").append(m.author)
            if (m.libs.isNotEmpty()) append(" · нативный код")
        }
        texts.addView(label(meta, 13f, 0xFFC9C2F5.toInt(), bold = false))
        val tl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tl.leftMargin = dp(10)
        head.addView(texts, tl)

        val gear = label("⚙", 22f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(GREEN, 12)
            setOnClickListener {
                if (!expanded.add(m.id)) expanded.remove(m.id)
                refresh()
            }
        }
        head.addView(gear, LinearLayout.LayoutParams(dp(64), dp(42)))
        box.addView(head)

        if (m.id in expanded) {
            if (m.description.isNotBlank()) {
                box.addView(
                    label(m.description, 13f, 0xFFD6D0FF.toInt(), bold = false).apply { setPadding(dp(4), dp(8), dp(4), 0) },
                )
            }
            box.addView(row("Включено", togglePill(m.enabled) { on -> toggleMod(m, on) }), rowLp())
            if (m.settings.isEmpty()) {
                box.addView(
                    label("У этого мода нет настроек", 13f, 0xFFD6D0FF.toInt(), bold = false).apply {
                        setPadding(dp(4), dp(8), dp(4), 0)
                    }
                )
            }
            val values = ModSettings.load(m)
            for (s in m.settings) {
                val cur = values[s.id] ?: ModSettings.defaultOf(s)
                box.addView(row(s.name, control(m, s, cur)), rowLp())
            }
        }
        return box
    }

    private fun toggleMod(m: Mod, on: Boolean) {
        if (on && m.libs.isNotEmpty() && !mods.isNativeAccepted(m.id)) {
            AlertDialog.Builder(act)
                .setTitle("Нативный код")
                .setMessage(
                    "Мод «${m.name}» содержит нативные библиотеки (.so). Они выполняются внутри игры " +
                        "и имеют полный доступ к её процессу. Включай только моды, которым доверяешь."
                )
                .setNegativeButton("Отмена") { _, _ -> refresh() }
                .setPositiveButton("Включить") { _, _ ->
                    mods.acceptNative(m.id)
                    mods.setEnabled(m.id, true)
                    refresh()
                }
                .setOnCancelListener { refresh() }
                .show()
        } else {
            mods.setEnabled(m.id, on)
            // в «Установлено» выключенный мод исчезает, в «Все моды» остаётся
            refresh()
        }
    }

    // ---------------------------------------------------------------- настройки

    private fun control(m: Mod, s: Setting, cur: Any): View = when (s.type) {
        "bool" -> togglePill(cur as? Boolean ?: false) { ModSettings.set(m, s.id, it) }
        "int", "float" -> stepper(m, s, cur)
        "choice" -> choicePill(m, s, cur.toString())
        else -> textField(m, s, cur.toString())
    }

    private fun togglePill(initial: Boolean, onChange: (Boolean) -> Unit): View {
        var state = initial
        val t = label("", 16f, Color.WHITE).apply { gravity = Gravity.CENTER }
        fun paint() {
            t.text = if (state) "Вкл" else "Выкл"
            t.background = round(if (state) GREEN else OFF, 14)
        }
        paint()
        t.setOnClickListener {
            state = !state
            paint()
            onChange(state)
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(250), dp(38))
        return t
    }

    private fun stepper(m: Mod, s: Setting, cur: Any): View {
        val isInt = s.type == "int"
        var v = (cur as? Number)?.toDouble() ?: 0.0
        val min = s.min
        val max = s.max
        val step = when {
            isInt -> 1.0
            min != null && max != null && max > min -> ((max - min) / 20.0).coerceAtLeast(0.01)
            else -> 0.1
        }
        fun fmt(x: Double): String =
            if (isInt) x.toLong().toString()
            else String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.').ifEmpty { "0" }

        val value = label(fmt(v), 17f, Color.WHITE).apply { gravity = Gravity.CENTER }
        fun apply(nv: Double) {
            var x = nv
            if (min != null) x = x.coerceAtLeast(min)
            if (max != null) x = x.coerceAtMost(max)
            if (!isInt) x = Math.round(x * 1000.0) / 1000.0
            v = x
            value.text = fmt(v)
            ModSettings.set(m, s.id, if (isInt) v.toLong() else v)
        }
        fun btn(text: String, delta: Double) = label(text, 22f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(PILL, 12)
            setOnClickListener { apply(v + delta) }
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("−", -step), LinearLayout.LayoutParams(dp(70), dp(38)))
            addView(value, LinearLayout.LayoutParams(dp(110), dp(38)))
            addView(btn("+", step), LinearLayout.LayoutParams(dp(70), dp(38)))
        }
    }

    private fun choicePill(m: Mod, s: Setting, cur: String): View {
        var idx = s.options.indexOf(cur).coerceAtLeast(0)
        val t = label(s.options.getOrElse(idx) { cur }, 16f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(PILL, 14)
        }
        t.setOnClickListener {
            if (s.options.isEmpty()) return@setOnClickListener
            idx = (idx + 1) % s.options.size
            t.text = s.options[idx]
            ModSettings.set(m, s.id, s.options[idx])
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(250), dp(38))
        return t
    }

    private fun textField(m: Mod, s: Setting, cur: String): View {
        val e = EditText(act).apply {
            setText(cur)
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            background = round(PILL, 14)
            setPadding(dp(10), 0, dp(10), 0)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(38))
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(x: Editable?) {
                ModSettings.set(m, s.id, x?.toString() ?: "")
            }
        })
        return e
    }

    // ------------------------------------------------------------------ рисование

    private fun row(name: String, control: View): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = round(ROW, 12)
        setPadding(dp(14), dp(5), dp(8), dp(5))
        addView(label(name, 18f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(control)
    }

    private fun rowLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(6) }

    private fun cardLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(10) }

    private fun tabLp() = LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(10) }

    private fun tabView(text: String, onClick: () -> Unit) = label(text, 19f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = true) = TextView(act).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconColor(id: String): Int {
        val palette = intArrayOf(0xFFE0883A.toInt(), 0xFF3FA6C8.toInt(), 0xFFC84F7A.toInt(), 0xFF5BAA4A.toInt(), 0xFF8E6AD8.toInt())
        return palette[(id.hashCode() and 0x7fffffff) % palette.size]
    }

    private companion object {
        const val PANEL = 0xFF6455C7.toInt()
        const val CARD = 0xFF4B3FA8.toInt()
        const val ROW = 0xFF3F3590.toInt()
        const val PILL = 0xFF6A5BD0.toInt()
        const val TAB_ON = 0xFF7D6EE3.toInt()
        const val TAB_OFF = 0xFF4F43AE.toInt()
        const val GREEN = 0xFF3FA866.toInt()
        const val OFF = 0xFF8A4A5A.toInt()
        const val RED = 0xFFD94545.toInt()
    }
}
