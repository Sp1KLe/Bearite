package com.bearite.patcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Меню модов внутри игры в её стиле: фиолетовая панель, вкладки «Установлено» / «Все моды»,
 * во второй — поиск и фильтр. Карточка мода: аватарка (icon.png), переключатель, раскрывающиеся
 * описание и настройки. Рисуется обычными View поверх Unity, открывается и закрывается с анимацией.
 */
class GameModMenu(private val act: Activity, private val mods: ModManager) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** Полноэкранная подложка; добавляется в корневой FrameLayout GameActivity. */
    val root = FrameLayout(act)
    private val panel = LinearLayout(act)
    private val list = LinearLayout(act)
    private val searchRow = LinearLayout(act)
    private val search = EditText(act)
    private lateinit var filterBtn: TextView
    private lateinit var tabInstalled: TextView
    private lateinit var tabAll: TextView

    private val expanded = HashSet<String>()
    private var tab = TAB_INSTALLED
    private var filter = 0
    private var query = ""
    private var closing = false

    val isOpen: Boolean get() = root.visibility == View.VISIBLE && !closing

    init {
        root.visibility = View.GONE
        root.setBackgroundColor(0xB3000000.toInt())
        root.isClickable = true  // касания не уходят в Unity, пока меню открыто
        root.setOnClickListener { hide() }

        panel.orientation = LinearLayout.VERTICAL
        panel.background = round(PANEL, 22)
        panel.setPadding(dp(14), dp(14), dp(14), dp(10))
        panel.isClickable = true
        val dm = act.resources.displayMetrics
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                (dm.widthPixels * 0.86f).toInt(), (dm.heightPixels * 0.86f).toInt(), Gravity.CENTER
            )
        )

        // шапка: красный крестик и вкладки
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            label("X", 26f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = round(RED, 14)
                setOnClickListener { hide() }
            },
            LinearLayout.LayoutParams(dp(60), dp(52))
        )
        tabInstalled = label("Установлено", 19f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { selectTab(TAB_INSTALLED) }
        }
        tabAll = label("Все моды", 19f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { selectTab(TAB_ALL) }
        }
        head.addView(tabInstalled, tabLp())
        head.addView(tabAll, tabLp())
        panel.addView(head, matchWrap())

        // поиск и фильтр: только на вкладке «Все моды»
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        search.hint = "Поиск модов"
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 16f
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.background = round(PILL, 14)
        search.setPadding(dp(14), 0, dp(14), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                query = e?.toString().orEmpty().trim()
                renderList()
            }
        })
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
        filterBtn = label("Фильтр", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(PILL, 14)
            setOnClickListener { pickFilter() }
        }
        searchRow.addView(filterBtn, LinearLayout.LayoutParams(dp(112), dp(44)).apply { leftMargin = dp(8) })
        panel.addView(searchRow, matchWrap().apply { topMargin = dp(10) })

        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        panel.addView(
            label("Включение модов и нативный код применяются после перезапуска игры", 12f, HINT, bold = false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
        )
    }

    // ------------------------------------------------------------ открытие и закрытие

    fun show() {
        closing = false
        root.animate().cancel()
        panel.animate().cancel()
        refresh()
        root.alpha = 0f
        panel.scaleX = 0.82f
        panel.scaleY = 0.82f
        panel.alpha = 0f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(200).start()
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(280).setInterpolator(OvershootInterpolator(0.8f)).start()
    }

    fun hide() {
        if (root.visibility != View.VISIBLE || closing) return
        closing = true
        hideKeyboard()
        panel.animate().cancel()
        panel.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f)
            .setDuration(160).setInterpolator(AccelerateInterpolator()).start()
        root.animate().alpha(0f).setDuration(180).withEndAction {
            if (closing) {
                root.visibility = View.GONE
                root.alpha = 1f
                closing = false
            }
        }.start()
    }

    private fun hideKeyboard() {
        try {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ списки

    private fun selectTab(t: Int) {
        if (tab == t) return
        tab = t
        refresh()
        list.alpha = 0f
        list.animate().alpha(1f).setDuration(200).start()
    }

    private fun refresh() {
        tabInstalled.background = round(if (tab == TAB_INSTALLED) TAB_ON else TAB_OFF, 14)
        tabAll.background = round(if (tab == TAB_ALL) TAB_ON else TAB_OFF, 14)
        searchRow.visibility = if (tab == TAB_ALL) View.VISIBLE else View.GONE
        filterBtn.text = if (filter == 0) "Фильтр" else "Фильтр •"
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        val all = mods.scan()
        val shown = if (tab == TAB_INSTALLED) {
            all.filter { it.enabled }
        } else {
            all.filter { matchesFilter(it) && matchesQuery(it) }
        }
        if (shown.isEmpty()) {
            val text = when {
                tab == TAB_INSTALLED -> "Нет включённых модов. Включи их на вкладке «Все моды»."
                all.isEmpty() -> "Модов пока нет. Установи их в приложении Bearite, вкладка «Моды»."
                else -> "Ничего не найдено по поиску или фильтру."
            }
            list.addView(
                label(text, 16f, HINT, bold = false).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(40), dp(12), dp(40))
                }
            )
            return
        }
        for (m in shown) list.addView(card(m), cardLp())
    }

    private fun matchesFilter(m: Mod): Boolean = when (filter) {
        1 -> m.enabled
        2 -> !m.enabled
        3 -> m.settings.isNotEmpty()
        4 -> m.libs.isNotEmpty()
        else -> true
    }

    private fun matchesQuery(m: Mod): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase(Locale.ROOT)
        return listOf(m.name, m.id, m.author, m.description).any { it.lowercase(Locale.ROOT).contains(q) }
    }

    private fun pickFilter() {
        AlertDialog.Builder(act)
            .setTitle("Фильтр модов")
            .setSingleChoiceItems(FILTER_NAMES, filter) { dlg, which ->
                filter = which
                dlg.dismiss()
                refresh()
            }
            .setNegativeButton("Закрыть", null)
            .show()
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
        head.addView(avatar(m), LinearLayout.LayoutParams(dp(52), dp(52)))

        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(m.name, 19f, Color.WHITE))
        texts.addView(label(meta(m), 13f, SUBTEXT, bold = false))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })

        head.addView(togglePill(m.enabled, 104, 6) { on -> toggleMod(m, on) })

        val hasDetails = m.description.isNotBlank() || m.settings.isNotEmpty()
        if (!hasDetails) {
            box.addView(head)
            return box
        }
        val open = m.id in expanded
        val chevron = label("›", 26f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            rotation = if (open) 90f else 0f
        }
        head.addView(chevron, LinearLayout.LayoutParams(dp(30), dp(38)))
        val details = buildDetails(m)
        details.visibility = if (open) View.VISIBLE else View.GONE
        head.isClickable = true
        head.setOnClickListener { toggleDetails(m.id, details, chevron) }
        box.addView(head)
        box.addView(details, matchWrap())
        return box
    }

    private fun meta(m: Mod): String = buildString {
        append("v").append(m.version)
        if (m.author.isNotEmpty()) append(" · ").append(m.author)
        if (m.builtin) append(" · встроенный") else if (m.libs.isNotEmpty()) append(" · нативный код")
    }

    /** Раскрыть/свернуть карточку с плавной анимацией. */
    private fun toggleDetails(id: String, details: View, chevron: View) {
        val opening = details.visibility != View.VISIBLE
        if (opening) expanded.add(id) else expanded.remove(id)
        chevron.animate().rotation(if (opening) 90f else 0f).setDuration(200).start()
        val shift = -dp(8).toFloat()
        if (opening) {
            details.animate().cancel()
            details.visibility = View.VISIBLE
            details.alpha = 0f
            details.translationY = shift
            details.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            details.animate().cancel()
            details.animate().alpha(0f).translationY(shift).setDuration(160)
                .withEndAction {
                    details.visibility = View.GONE
                    details.alpha = 1f
                    details.translationY = 0f
                }.start()
        }
    }

    private fun buildDetails(m: Mod): LinearLayout {
        val details = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        if (m.description.isNotBlank()) {
            details.addView(
                label(m.description, 13f, HINT, bold = false).apply { setPadding(0, 0, 0, dp(6)) }
            )
        }
        val values = ModSettings.load(m)
        for (s in m.settings) {
            val cur = values[s.id] ?: ModSettings.defaultOf(s)
            details.addView(row(s.name, control(m, s, cur)), rowLp())
        }
        return details
    }

    /** Аватарка: icon.png из папки мода, иначе буква на цветной плашке. */
    private fun avatar(m: Mod): View {
        val bmp = m.icon?.let { try { BitmapFactory.decodeFile(it.path) } catch (_: Throwable) { null } }
        if (bmp != null) {
            return ImageView(act).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = round(iconColor(m.id), 12)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
        }
        return label(m.name.take(1).uppercase(Locale.ROOT), 24f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(iconColor(m.id), 12)
        }
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
            refresh()
        }
    }

    // ---------------------------------------------------------------- настройки

    private fun control(m: Mod, s: Setting, cur: Any): View = when (s.type) {
        "bool" -> togglePill(cur as? Boolean ?: false, 250) { ModSettings.set(m, s.id, it) }
        "int", "float" -> stepper(m, s, cur)
        "choice" -> choicePill(m, s, cur.toString())
        else -> textField(m, s, cur.toString())
    }

    private fun togglePill(initial: Boolean, widthDp: Int, marginDp: Int = 0, onChange: (Boolean) -> Unit): TextView {
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
        t.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(38)).apply { leftMargin = dp(marginDp) }
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

    private fun rowLp() = matchWrap().apply { topMargin = dp(6) }

    private fun cardLp() = matchWrap().apply { bottomMargin = dp(10) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun tabLp() = LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(10) }

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
        val palette = intArrayOf(
            0xFFE0883A.toInt(), 0xFF3FA6C8.toInt(), 0xFFC84F7A.toInt(), 0xFF5BAA4A.toInt(), 0xFF8E6AD8.toInt()
        )
        return palette[(id.hashCode() and 0x7fffffff) % palette.size]
    }

    private companion object {
        const val TAB_INSTALLED = 0
        const val TAB_ALL = 1
        val FILTER_NAMES = arrayOf("Все моды", "Только включённые", "Только выключенные", "С настройками", "С нативным кодом")

        const val PANEL = 0xFF6455C7.toInt()
        const val CARD = 0xFF4B3FA8.toInt()
        const val ROW = 0xFF3F3590.toInt()
        const val PILL = 0xFF6A5BD0.toInt()
        const val TAB_ON = 0xFF7D6EE3.toInt()
        const val TAB_OFF = 0xFF4F43AE.toInt()
        const val GREEN = 0xFF3FA866.toInt()
        const val OFF = 0xFF8A4A5A.toInt()
        const val RED = 0xFFD94545.toInt()
        const val HINT = 0xFFD6D0FF.toInt()
        const val SUBTEXT = 0xFFC9C2F5.toInt()
    }
}
