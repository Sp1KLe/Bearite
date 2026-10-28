package com.bearite.patcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
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
 * Меню Bearite внутри игры в стиле лаунчера: тёмная кофейная панель, сливочный текст, карамельные акценты.
 * Вкладки «Установлено», «Все моды» (поиск и фильтр) и «Карты» (редактор карт). Рисуется обычными View
 * поверх Unity, открывается и закрывается с анимацией.
 */
class GameModMenu(private val act: Activity, private val mods: ModManager) {
    private val ui = GameUi(act)
    private fun dp(v: Int) = ui.dp(v)

    /** Полноэкранная подложка; добавляется в корневой FrameLayout GameActivity. */
    val root = FrameLayout(act)
    private val panel = FrameLayout(act)
    private val list = LinearLayout(act)
    private val searchRow = LinearLayout(act)
    private val search = EditText(act)
    private lateinit var filterBtn: TextView
    private lateinit var tabInstalled: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabMaps: TextView
    private lateinit var tabGames: TextView
    private lateinit var footer: TextView
    private lateinit var scroll: ScrollView

    private val maps = MapsTab(act, ui, MapStore(act))
    private val games = MiniGames(act, ui)
    private val expanded = HashSet<String>()
    private var tab = TAB_INSTALLED
    private var filter = 0
    private var query = ""
    private var closing = false

    val isOpen: Boolean get() = root.visibility == View.VISIBLE && !closing

    /** Вызывается, когда меню полностью закрылось. */
    var onClosed: (() -> Unit)? = null

    init {
        root.visibility = View.GONE
        root.setBackgroundColor(0xC0100B08.toInt())
        root.isClickable = true  // касания не уходят в Unity, пока меню открыто
        root.setOnClickListener { hide() }

        val dm = act.resources.displayMetrics
        panel.background = ui.round(GameUi.SURFACE, 28, GameUi.LINE_TAN)
        panel.clipToOutline = true
        panel.outlineProvider = ViewOutlineProvider.BACKGROUND
        panel.isClickable = true
        root.addView(
            panel,
            FrameLayout.LayoutParams((dm.widthPixels * 0.88f).toInt(), (dm.heightPixels * 0.88f).toInt(), Gravity.CENTER)
        )
        // мягкие пятна в углах, как на витрине концепта
        panel.addView(blob(0x338D5338, 190), FrameLayout.LayoutParams(dp(190), dp(190), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = -dp(55); bottomMargin = -dp(70)
        })
        panel.addView(blob(0x26B78B68, 150), FrameLayout.LayoutParams(dp(150), dp(150), Gravity.END or Gravity.TOP).apply {
            rightMargin = -dp(40); topMargin = -dp(60)
        })

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // шапка: логотип, вкладки, закрыть
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(logo(), LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(ui.label("Bearite", 20f).apply { setPadding(dp(10), 0, dp(14), 0) })
        tabInstalled = tab("Установлено", TAB_INSTALLED)
        tabAll = tab("Все моды", TAB_ALL)
        tabMaps = tab("Карты", TAB_MAPS)
        tabGames = tab("Мини-игры", TAB_GAMES)
        for (t in listOf(tabInstalled, tabAll, tabMaps, tabGames)) {
            head.addView(t, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })
        }
        head.addView(
            ui.label("✕", 20f).apply {
                gravity = Gravity.CENTER
                background = ui.round(GameUi.RUST, 21)
                setOnClickListener { hide() }
            },
            LinearLayout.LayoutParams(dp(54), dp(42)).apply { leftMargin = dp(10) }
        )
        col.addView(head, ui.matchWrap())

        // поиск и фильтр: только на вкладке «Все моды»
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        search.hint = "Поиск модов"
        search.setHintTextColor(GameUi.DIM)
        search.setTextColor(GameUi.CREAM)
        search.textSize = 15f
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.background = ui.round(GameUi.BG_DEEP, 20, GameUi.LINE)
        search.setPadding(dp(16), 0, dp(16), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                query = e?.toString().orEmpty().trim()
                renderList()
            }
        })
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f))
        filterBtn = ui.pill("Фильтр") { pickFilter() }
        searchRow.addView(filterBtn, LinearLayout.LayoutParams(dp(112), dp(42)).apply { leftMargin = dp(8) })
        col.addView(searchRow, ui.matchWrap().apply { topMargin = dp(10) })

        list.orientation = LinearLayout.VERTICAL
        scroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        footer = ui.label("", 11f, GameUi.DIM, bold = false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        col.addView(footer)
    }

    private fun blob(color: Int, sizeDp: Int) = View(act).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun logo(): View = ImageView(act).apply {
        setImageResource(R.drawable.bear_hero)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun tab(text: String, id: Int) = ui.label(text, 15f).apply {
        gravity = Gravity.CENTER
        setOnClickListener { selectTab(id) }
    }

    // ------------------------------------------------------------ открытие и закрытие

    fun show() {
        closing = false
        root.animate().cancel()
        panel.animate().cancel()
        refresh()
        root.alpha = 0f
        panel.scaleX = 0.84f
        panel.scaleY = 0.84f
        panel.alpha = 0f
        panel.translationY = dp(24).toFloat()
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(200).start()
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(300).setInterpolator(OvershootInterpolator(0.7f)).start()
    }

    fun hide() {
        if (root.visibility != View.VISIBLE || closing) return
        closing = true
        hideKeyboard()
        panel.animate().cancel()
        panel.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).translationY(dp(16).toFloat())
            .setDuration(170).setInterpolator(AccelerateInterpolator()).start()
        root.animate().alpha(0f).setDuration(190).withEndAction {
            if (closing) {
                root.visibility = View.GONE
                root.alpha = 1f
                closing = false
                onClosed?.invoke()
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
        list.translationY = dp(10).toFloat()
        list.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        scroll.scrollTo(0, 0)
    }

    private fun refresh() {
        for ((t, id) in listOf(tabInstalled to TAB_INSTALLED, tabAll to TAB_ALL, tabMaps to TAB_MAPS, tabGames to TAB_GAMES)) {
            val on = tab == id
            t.setTextColor(if (on) GameUi.ON_CREAM else GameUi.MUTED)
            t.background = ui.round(if (on) GameUi.CREAM else GameUi.SURFACE2, 21, if (on) 0 else GameUi.LINE)
        }
        searchRow.visibility = if (tab == TAB_ALL) View.VISIBLE else View.GONE
        filterBtn.text = if (filter == 0) "Фильтр" else "Фильтр •"
        footer.text = if (tab == TAB_MAPS) "Карты применяются в игре сразу, как только Bearite их увидит"
        else "Включение модов и нативный код применяются после перезапуска игры"
        renderList()
    }

    private fun renderList() {
        if (tab == TAB_GAMES) {
            list.removeAllViews()
            try { games.render(list) } catch (t: Throwable) { BLog.e("вкладка «Мини-игры»", t) }
            return
        }
        if (tab == TAB_MAPS) {
            try {
                maps.render(list) { renderList() }
            } catch (t: Throwable) {
                // редактор не должен ронять игру: показываем ошибку и пишем её в лог
                BLog.e("вкладка «Карты»", t)
                list.removeAllViews()
                list.addView(ui.label("Ошибка во вкладке «Карты»: $t\nПодробности в логе лаунчера.", 14f, GameUi.RUST, bold = false).apply {
                    setPadding(dp(12), dp(30), dp(12), dp(30))
                })
            }
            return
        }
        list.removeAllViews()
        val all = mods.scan()
        val shown = if (tab == TAB_INSTALLED) all.filter { it.enabled }
        else all.filter { matchesFilter(it) && matchesQuery(it) }
        if (shown.isEmpty()) {
            val text = when {
                tab == TAB_INSTALLED -> "Нет включённых модов. Включи их на вкладке «Все моды»."
                all.isEmpty() -> "Модов пока нет. Установи их в приложении Bearite, вкладка «Моды»."
                else -> "Ничего не найдено по поиску или фильтру."
            }
            list.addView(
                ui.label(text, 15f, GameUi.MUTED, bold = false).apply {
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
        ui.choose("Фильтр модов", FILTER_NAMES.toList(), filter) { filter = it; refresh() }
    }

    // ----------------------------------------------------------------- карточка

    private fun card(m: Mod): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(GameUi.SURFACE2, 18, GameUi.LINE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(avatar(m), LinearLayout.LayoutParams(dp(52), dp(52)))

        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label(m.name, 18f))
        texts.addView(ui.label(meta(m), 12f, GameUi.MUTED, bold = false))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        head.addView(ui.togglePill(m.enabled, 92, 6) { on -> toggleMod(m, on) })

        val hasDetails = m.description.isNotBlank() || m.settings.isNotEmpty()
        if (!hasDetails) {
            box.addView(head)
            return box
        }
        val open = m.id in expanded
        val chevron = ui.label("›", 26f).apply { gravity = Gravity.CENTER; rotation = if (open) 90f else 0f }
        head.addView(chevron, LinearLayout.LayoutParams(dp(30), dp(38)))
        val details = buildDetails(m)
        details.visibility = if (open) View.VISIBLE else View.GONE
        head.isClickable = true
        head.setOnClickListener { toggleDetails(m.id, details, chevron) }
        box.addView(head)
        box.addView(details, ui.matchWrap())
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
        details.animate().cancel()
        if (opening) {
            details.visibility = View.VISIBLE
            details.alpha = 0f
            details.translationY = shift
            details.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } else {
            details.animate().alpha(0f).translationY(shift).setDuration(160).withEndAction {
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
            details.addView(ui.label(m.description, 13f, GameUi.MUTED, bold = false).apply { setPadding(0, 0, 0, dp(6)) })
        }
        val values = ModSettings.load(m)
        for (s in m.settings) {
            val cur = values[s.id] ?: ModSettings.defaultOf(s)
            details.addView(ui.row(s.name, control(m, s, cur)), ui.matchWrap().apply { topMargin = dp(6) })
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
                background = ui.round(GameUi.tileColor(m.id), 14)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
        }
        return ui.label(m.name.take(1).uppercase(Locale.ROOT), 22f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            background = ui.round(GameUi.tileColor(m.id), 14)
        }
    }

    private fun toggleMod(m: Mod, on: Boolean) {
        if (on && m.libs.isNotEmpty() && !mods.isNativeAccepted(m.id)) {
            ui.dialog(
                "Нативный код",
                ui.label(
                    "Мод «${m.name}» содержит нативные библиотеки (.so). Они выполняются внутри игры " +
                        "и имеют полный доступ к её процессу. Включай только моды, которым доверяешь.",
                    14f, GameUi.MUTED, bold = false
                ),
                listOf(
                    GameUi.Btn("Отмена") { refresh() },
                    GameUi.Btn("Включить", primary = true) {
                        mods.acceptNative(m.id)
                        mods.setEnabled(m.id, true)
                        refresh()
                    }
                ),
                onCancel = { refresh() }
            ).show()
        } else {
            mods.setEnabled(m.id, on)
            refresh()
        }
    }

    // ---------------------------------------------------------------- настройки

    private fun control(m: Mod, s: Setting, cur: Any): View = when (s.type) {
        "bool" -> ui.togglePill(cur as? Boolean ?: false, 230) { ModSettings.set(m, s.id, it) }
        "int", "float" ->
            if (s.control == "slider" && s.min != null && s.max != null && s.max > s.min) {
                val isInt = s.type == "int"
                ui.slider(s.min, s.max, (cur as? Number)?.toDouble() ?: s.min, if (isInt) 0 else 2, 186) {
                    ModSettings.set(m, s.id, if (isInt) it.toLong() else it)
                }
            } else stepper(m, s, cur)
        "choice" -> choicePill(m, s, cur.toString())
        else -> ui.textInput(cur.toString(), 230) { ModSettings.set(m, s.id, it) }
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
        val value = ui.label(ui.fmt(v, if (isInt) 0 else 2), 16f).apply { gravity = Gravity.CENTER }
        fun apply(nv: Double) {
            var x = nv
            if (min != null) x = x.coerceAtLeast(min)
            if (max != null) x = x.coerceAtMost(max)
            if (!isInt) x = Math.round(x * 1000.0) / 1000.0
            v = x
            value.text = ui.fmt(v, if (isInt) 0 else 2)
            ModSettings.set(m, s.id, if (isInt) v.toLong() else v)
        }
        fun btn(text: String, delta: Double) = ui.label(text, 22f).apply {
            gravity = Gravity.CENTER
            background = ui.round(GameUi.SURFACE3, 14)
            setOnClickListener { apply(v + delta) }
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("−", -step), LinearLayout.LayoutParams(dp(70), dp(38)))
            addView(value, LinearLayout.LayoutParams(dp(90), dp(38)))
            addView(btn("+", step), LinearLayout.LayoutParams(dp(70), dp(38)))
        }
    }

    private fun choicePill(m: Mod, s: Setting, cur: String): View {
        var idx = s.options.indexOf(cur).coerceAtLeast(0)
        val t = ui.pill(s.options.getOrElse(idx) { cur }) {}
        t.setOnClickListener {
            if (s.options.isEmpty()) return@setOnClickListener
            idx = (idx + 1) % s.options.size
            t.text = s.options[idx]
            ModSettings.set(m, s.id, s.options[idx])
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(230), dp(38))
        return t
    }

    private fun cardLp() = ui.matchWrap().apply { bottomMargin = dp(10) }

    private companion object {
        const val TAB_INSTALLED = 0
        const val TAB_ALL = 1
        const val TAB_MAPS = 2
        const val TAB_GAMES = 3
        val FILTER_NAMES = arrayOf("Все моды", "Только включённые", "Только выключенные", "С настройками", "С нативным кодом")
    }
}
