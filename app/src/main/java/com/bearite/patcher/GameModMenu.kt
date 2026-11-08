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
import android.view.MotionEvent
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
 * Меню Bearite внутри игры в стиле игры: главный экран «Моды / Магазин / Редактор карт», яркие объёмные кнопки.
 * Вкладки «Установлено», «Все моды» (поиск и фильтр) и «Карты» (редактор карт). Рисуется обычными View
 * поверх Unity, открывается и закрывается с анимацией.
 */
class GameModMenu(private val act: Activity, private val mods: ModManager, private val games: MiniGames) {
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
    private lateinit var tabGames: TextView
    private lateinit var tabReplay: TextView
    private lateinit var footer: TextView
    private val head = LinearLayout(act)
    private val hub = FrameLayout(act)
    private val hubViews = ArrayList<View>()
    private lateinit var modsBadge: TextView
    private lateinit var mapsBadge: TextView
    private lateinit var hubStatus: TextView
    private var hubMods: View? = null
    private var hubEditor: View? = null
    private var hubBody: View? = null
    private lateinit var col: LinearLayout
    private var screen = SCR_HUB
    private lateinit var scroll: ScrollView

    private val maps = MapsTab(act, ui, MapStore(act))
    private val replay = ReplayTab(act, ui)
    private val expanded = HashSet<String>()
    private var catalog: List<ModCatalog.Item>? = null
    private var catalogErr = ""
    private var catalogBusy = false
    private val installing = HashSet<String>()
    private var tab = TAB_INSTALLED
    private var filter = 0
    private var query = ""
    private var closing = false

    val isOpen: Boolean get() = root.visibility == View.VISIBLE && !closing

    /** Вызывается, когда меню полностью закрылось. */
    var onClosed: (() -> Unit)? = null

    init {
        maps.hideMenu = { h -> root.alpha = if (h) 0f else 1f }
        games.onStarted = { hide() }
        replay.onPlay = { hide() }
        root.visibility = View.GONE
        root.setBackgroundColor(0xC0100B08.toInt())
        root.isClickable = true  // касания не уходят в Unity, пока меню открыто
        root.setOnClickListener { hide() }

        val dm = act.resources.displayMetrics
        panel.background = ui.panelBg()
        panel.clipToOutline = true
        panel.outlineProvider = ViewOutlineProvider.BACKGROUND
        panel.isClickable = true
        root.addView(
            panel,
            FrameLayout.LayoutParams((dm.widthPixels * 0.88f).toInt(), (dm.heightPixels * 0.88f).toInt(), Gravity.CENTER)
        )
        col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        panel.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // шапка раздела строится в buildHead(): красная кнопка «назад», название, вкладки или кнопки раздела
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        tabInstalled = tab("Установлено", TAB_INSTALLED)
        tabGames = tab("Мини-игры", TAB_GAMES)
        tabReplay = tab("Реплей", TAB_REPLAY)
        col.addView(head, ui.matchWrap())
        buildHub()

        // поиск и фильтр: только на вкладке «Все моды»
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        search.hint = "Поиск модов"
        search.setHintTextColor(GameUi.DIM)
        search.setTextColor(GameUi.CREAM)
        search.textSize = 15f
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.background = ui.round(GameUi.BG_DEEP, 20, GameUi.OUTLINE, 3)
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
        filterBtn = ui.pill("Обновить", primary = true) { loadCatalog() }
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
        applyScreen()
    }

    // ------------------------------------------------------------ главный экран и разделы

    /** Картинка-кнопка с анимацией: при нажатии проседает, при отпускании пружинит обратно. */
    private fun imgBtn(res: Int, onClick: () -> Unit): ImageView = ImageView(act).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().cancel().also { v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3.5f)).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP && e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) v.performClick()
                }
            }
            true
        }
        setOnClickListener { onClick() }
    }

    private fun buildHub() {
        panel.addView(hub, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // как в референсе: красная кнопка в углу, слева высокая «Моды», справа «Магазин» и «Редактор карт», шестерёнка справа внизу
        val close = imgBtn(R.drawable.btn_close_red) { hide() }
        val gear = imgBtn(R.drawable.btn_settings) { openScreen(SCR_SETTINGS) }
        hub.addView(close, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.START))
        hub.addView(gear, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.START))
        val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        hub.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val mods = imgBtn(R.drawable.btn_mods) { openScreen(SCR_MODS) }
        val shop = imgBtn(R.drawable.btn_shop) { openShopPopup() }
        val editor = imgBtn(R.drawable.btn_mapeditor) { openScreen(SCR_MAPS) }
        body.addView(mods, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 260f))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        right.addView(shop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 175f))
        right.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 180f).apply { topMargin = dp(10) })
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 605f).apply { leftMargin = dp(14) })
        val hubTitle = android.widget.ImageView(act).apply { setImageResource(R.drawable.title_menu); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        hub.addView(hubTitle, FrameLayout.LayoutParams(dp(180), dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        // бейджи-счётчики на углах кнопок и строка состояния внизу
        fun badge(): TextView = ui.outlined("", 15f).apply {
            gravity = Gravity.CENTER
            background = ui.chunky(GameUi.GREEN, 14, 3)
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
        }
        modsBadge = badge(); mapsBadge = badge()
        hub.addView(modsBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30), Gravity.TOP or Gravity.START))
        hub.addView(mapsBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30), Gravity.TOP or Gravity.START))
        hubStatus = ui.outlined("", 12f).apply { gravity = Gravity.CENTER; alpha = 0.9f }
        hub.addView(hubStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        hubViews.addAll(listOf(hubTitle, close, mods, shop, editor, gear))
        hubMods = mods; hubEditor = editor; hubBody = body
        // положение и размеры — в долях панели, как на референсе пользователя
        hub.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ ->
            val w = r - l; val h = bt - t
            if (w > 0 && h > 0) {
                val pl = (w * 0.11f).toInt(); val pt = (h * 0.235f).toInt(); val pr = (w * 0.22f).toInt(); val pb = (h * 0.085f).toInt()
                if (body.paddingLeft != pl || body.paddingTop != pt) body.setPadding(pl, pt, pr, pb)
                fun place(v: View, x: Float, y: Float, size: Float) {
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    val px = (w * size).toInt()
                    if (lp.width != px || lp.leftMargin != (w * x).toInt()) {
                        lp.width = px; lp.height = px; lp.leftMargin = (w * x).toInt(); lp.topMargin = (h * y).toInt()
                        v.layoutParams = lp
                    }
                }
                place(close, 0.026f, 0.054f, 0.07f)
                val th = (h * 0.17f).toInt(); val tlp = hubTitle.layoutParams as FrameLayout.LayoutParams
                if (tlp.height != th) { tlp.height = th; tlp.width = (th * 3.7f).toInt(); tlp.topMargin = (h * 0.04f).toInt(); hubTitle.layoutParams = tlp }
                place(gear, 0.84f, 0.65f, 0.113f)
                hub.post { positionBadges() }
            }
        }
    }

    private fun offsetIn(root: View, v: View): IntArray {
        var x = 0; var y = 0; var cur: View = v
        while (cur !== root) { x += cur.left; y += cur.top; cur = (cur.parent as? View) ?: break }
        return intArrayOf(x, y)
    }

    /** Счётчик у правого верхнего угла кнопки. */
    private fun placeBadge(b: TextView, target: View?) {
        if (target == null || b.visibility != View.VISIBLE || target.width == 0) return
        val o = offsetIn(hub, target)
        b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val lp = b.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = o[0] + target.width - b.measuredWidth - dp(10); lp.topMargin = o[1] + dp(6)
        b.layoutParams = lp
    }

    private fun positionBadges() { placeBadge(modsBadge, hubMods); placeBadge(mapsBadge, hubEditor) }

    /** Числа на кнопках и состояние мультиплеера; вызывается при показе главного экрана. */
    private fun refreshHub() {
        try {
            val on = mods.scan().count { it.enabled }
            modsBadge.text = on.toString(); modsBadge.visibility = if (on > 0) View.VISIBLE else View.GONE
            val mc = MapStore(act).list().count { it.enabled }
            mapsBadge.text = mc.toString(); mapsBadge.visibility = if (mc > 0) View.VISIBLE else View.GONE
            val room = try { java.io.File(act.filesDir, "maps/_room").readText().trim() } catch (_: Throwable) { "" }
            val online = room.isNotEmpty() && room != MiniGames.OFFLINE_ROOM
            hubStatus.text = "Bearite · " + (if (online) "мультиплеер: комната «$room»" else "одиночная игра")
            hub.post { positionBadges() }
        } catch (t: Throwable) { BLog.e("главный экран", t) }
    }

    /** Появление главного экрана: кнопки одна за другой выпрыгивают с пружинкой. */
    private var bobAnim: android.animation.ObjectAnimator? = null

    private fun animateHubIn() {
        bobAnim?.cancel(); bobAnim = null
        hubViews.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f; v.scaleX = 0.55f; v.scaleY = 0.55f; v.translationY = dp(24).toFloat()
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(60L + i * 70L).setDuration(380)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        for ((i, b) in listOf(modsBadge, mapsBadge).withIndex()) {
            b.animate().cancel(); b.scaleX = 0f; b.scaleY = 0f
            b.animate().scaleX(1f).scaleY(1f).setStartDelay(520L + i * 90L).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(4f)).start()
        }
        // заголовок слегка парит
        hubViews.firstOrNull()?.let { t ->
            t.postDelayed({
                if (screen == SCR_HUB) android.animation.ObjectAnimator.ofFloat(t, View.TRANSLATION_Y, 0f, -dp(3).toFloat()).apply {
                    duration = 1800; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                    bobAnim = this; start()
                }
            }, 900)
        }
    }

    private fun openShopPopup() {
        val colv = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        colv.addView(ui.gameBtn("Магазин модов", GameUi.GREEN, 20f, 20) { dlg?.dismiss(); openScreen(SCR_SHOP) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        colv.addView(ui.gameBtn("Магазин карт", GameUi.YELLOW, 20f, 20) { dlg?.dismiss(); ui.soon("Магазин карт") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(10) })
        dlg = ui.dialog("Магазин", colv, listOf(GameUi.Btn("Закрыть")), widthDp = 360)
        dlg.show()
    }

    private fun openScreen(s: Int) {
        screen = s
        tab = when (s) {
            SCR_MODS -> if (tab == TAB_GAMES || tab == TAB_REPLAY) tab else TAB_INSTALLED
            SCR_SHOP -> TAB_ALL
            SCR_MAPS -> TAB_MAPS
            SCR_SETTINGS -> TAB_INSTALLED
            else -> tab
        }
        if (s == SCR_SHOP && catalog == null && !catalogBusy) loadCatalog()
        applyScreen()
        scroll.scrollTo(0, 0)
        if (s != SCR_HUB) {
            col.translationX = dp(56).toFloat()
            col.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
            staggerList()
        }
        panel.animate().cancel()
        panel.alpha = 0.4f
        panel.animate().alpha(1f).setDuration(180).start()
    }

    /** Элементы списка появляются по очереди снизу. */
    private fun staggerList() {
        list.post {
            for (i in 0 until minOf(list.childCount, 14)) {
                val v = list.getChildAt(i)
                v.alpha = 0f; v.translationY = dp(26).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 45L).setDuration(300)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun applyScreen() {
        if (screen == SCR_HUB) { refreshHub(); panel.post { animateHubIn() } }
        if (screen != SCR_HUB) { bobAnim?.cancel(); bobAnim = null }
        hub.visibility = if (screen == SCR_HUB) View.VISIBLE else View.GONE
        col.visibility = if (screen == SCR_HUB) View.GONE else View.VISIBLE
        if (screen != SCR_HUB) refresh()
    }

    private fun buildHead() {
        head.removeAllViews()
        head.addView(ui.gameBtn("‹", GameUi.RUST, 28f, 14) { hideKeyboard(); openScreen(SCR_HUB) }, LinearLayout.LayoutParams(dp(50), dp(50)))
        val title = when (screen) {
            SCR_MODS -> "Моды"
            SCR_SHOP -> "Магазин модов"
            SCR_SETTINGS -> "Настройки Bearite"
            else -> "Редактор карт"
        }
        val titleRes = when (screen) { SCR_MODS -> R.drawable.title_mods; SCR_SHOP -> R.drawable.title_shop; SCR_SETTINGS -> R.drawable.title_settings; else -> 0 }
        val titleView: View = if (titleRes != 0) android.widget.ImageView(act).apply {
            setImageResource(titleRes); scaleType = android.widget.ImageView.ScaleType.FIT_START; adjustViewBounds = true
            setPadding(dp(12), 0, dp(8), 0)
        } else ui.outlined(title, 22f).apply { setPadding(dp(12), 0, dp(8), 0) }
        if (screen == SCR_MODS) {
            head.addView(titleView, LinearLayout.LayoutParams(dp(150), dp(40)))
            for (t in listOf(tabInstalled, tabGames, tabReplay)) {
                (t.parent as? ViewGroup)?.removeView(t)
                head.addView(t, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
            }
        } else {
            head.addView(titleView, LinearLayout.LayoutParams(0, if (titleRes != 0) dp(40) else ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (screen == SCR_MAPS) {
                head.addView(ui.gameBtn("Новая карта", GameUi.YELLOW, 16f, 16) { maps.newMapDialog { renderList() } },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { leftMargin = dp(6) })
                head.addView(ui.gameBtn("Загрузить карту", GameUi.GREEN, 16f, 16) { maps.importFromClipboard(); renderList() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { leftMargin = dp(6) })
            }
        }
    }

    private fun blob(color: Int, sizeDp: Int) = View(act).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun logo(): View = ImageView(act).apply {
        setImageResource(R.drawable.bear_hero)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun tab(text: String, id: Int) = ui.outlined(text, 15f).apply {
        gravity = Gravity.CENTER
        setOnClickListener { selectTab(id) }
    }

    // ------------------------------------------------------------ открытие и закрытие

    fun show() {
        closing = false
        root.animate().cancel()
        panel.animate().cancel()
        screen = SCR_HUB
        applyScreen()
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
        if (t == TAB_ALL && catalog == null && !catalogBusy) loadCatalog()
        refresh()
        list.alpha = 0f
        list.translationY = dp(10).toFloat()
        list.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        scroll.scrollTo(0, 0)
    }

    private fun refresh() {
        if (screen == SCR_HUB) return
        buildHead()
        for ((t, id) in listOf(tabInstalled to TAB_INSTALLED, tabGames to TAB_GAMES, tabReplay to TAB_REPLAY)) {
            t.background = ui.chunky(if (tab == id) GameUi.YELLOW else GameUi.SURFACE3, 16)
        }
        searchRow.visibility = if (tab == TAB_ALL && screen == SCR_SHOP) View.VISIBLE else View.GONE
        footer.text = if (tab == TAB_MAPS) "Карты применяются в игре сразу, как только Bearite их увидит"
        else "Включение модов и нативный код применяются после перезапуска игры"
        renderList()
    }

    private fun renderList() {
        if (screen == SCR_SETTINGS) {
            // только настройки встроенного мода Bearite; настройки модов сообщества остаются в «Моды» → «Установлено»
            list.removeAllViews()
            val own = mods.scan().firstOrNull { it.builtin }
            if (own == null) {
                list.addView(ui.label("Настройки Bearite недоступны", 14f, GameUi.MUTED, bold = false).apply { setPadding(dp(12), dp(30), dp(12), dp(30)) })
            } else {
                val card = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ui.round(GameUi.ROW, 18, GameUi.OUTLINE, 3)
                    setPadding(dp(14), dp(10), dp(14), dp(14))
                }
                (act as? GameActivity)?.mapSync?.let { ms ->
                    card.addView(ui.row("Карты других игроков", ui.togglePill(ms.acceptRemote, 84) { on -> ms.acceptRemote = on }), ui.matchWrap().apply { bottomMargin = dp(8) })
                }
                card.addView(buildDetails(own), ui.matchWrap())
                list.addView(card, cardLp())
            }
            return
        }
        if (tab == TAB_REPLAY) {
            try { replay.render(list) { if (tab == TAB_REPLAY && isOpen) renderList() } } catch (t: Throwable) { BLog.e("вкладка «Реплей»", t) }
            return
        }
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
        if (tab == TAB_ALL) { renderCatalog(); return }
        list.removeAllViews()
        val all = mods.scan()
        val shown = all   // выключенный мод не пропадает из списка: его карточка просто полупрозрачная
        if (shown.isEmpty()) {
            val text = "Модов пока нет. Открой вкладку «Все моды» и установи их с сервера."
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

    // ----------------------------------------------------------------- каталог модов на сервере

    private fun loadCatalog() {
        if (catalogBusy) return
        catalogBusy = true; catalogErr = ""
        if (tab == TAB_ALL) renderList()
        ModCatalog.fetch { items, err ->
            catalogBusy = false
            if (items == null) { catalogErr = err; catalog = null } else catalog = items
            if (tab == TAB_ALL) renderList()
        }
    }

    private fun renderCatalog() {
        list.removeAllViews()
        val c = catalog
        if (c == null) {
            val text = when {
                catalogBusy -> "Загружаю каталог модов…"
                catalogErr.isNotEmpty() -> catalogErr
                else -> "Каталог ещё не загружен."
            }
            list.addView(ui.label(text, 15f, if (catalogErr.isNotEmpty()) GameUi.RUST else GameUi.MUTED, bold = false).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(40), dp(12), dp(16))
            })
            if (!catalogBusy) list.addView(ui.pill("Повторить", primary = true) { loadCatalog() }, ui.matchWrap())
            return
        }
        val inst = mods.scan().associateBy { it.id }
        val q = query.lowercase(Locale.ROOT)
        val shown = c.filter { q.isEmpty() || listOf(it.name, it.id, it.author, it.description).any { s -> s.lowercase(Locale.ROOT).contains(q) } }
        if (shown.isEmpty()) {
            list.addView(ui.label(if (c.isEmpty()) "На сервере пока нет модов." else "Ничего не найдено.", 15f, GameUi.MUTED, bold = false).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(40), dp(12), dp(40))
            })
            return
        }
        for (it in shown) list.addView(catalogCard(it, inst[it.id]), cardLp())
    }

    private fun catalogCard(it: ModCatalog.Item, have: Mod?): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(GameUi.SURFACE2, 18, GameUi.LINE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.label(it.name.take(1).uppercase(Locale.ROOT), 22f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; background = ui.round(GameUi.tileColor(it.id), 14)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label(it.name, 18f))
        texts.addView(ui.label("v${it.version}" + (if (it.author.isNotEmpty()) " · ${it.author}" else "") + (if (it.size > 0) " · ${it.size / 1024} КБ" else ""), 12f, GameUi.MUTED, bold = false))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        val busy = it.id in installing
        val label = when {
            busy -> "Ставлю…"
            have == null -> "Установить"
            have.version != it.version -> "Обновить"
            else -> "Установлен"
        }
        val actionable = !busy && (have == null || have.version != it.version)
        head.addView(ui.pill(label, primary = actionable, sizeSp = 13f) { if (actionable) installFromCatalog(it) }, LinearLayout.LayoutParams(dp(112), dp(40)))
        box.addView(head)
        if (it.description.isNotBlank()) box.addView(ui.label(it.description, 13f, GameUi.MUTED, bold = false).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        return box
    }

    private fun installFromCatalog(it: ModCatalog.Item) {
        if (!installing.add(it.id)) return
        renderList()
        ModCatalog.install(act, mods, it) { err ->
            installing.remove(it.id)
            android.widget.Toast.makeText(act, if (err.isEmpty()) "Мод «${it.name}» установлен: включи его на вкладке «Установлено»" else "Не установился: $err", android.widget.Toast.LENGTH_LONG).show()
            renderList()
        }
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
            alpha = if (m.enabled) 1f else 0.45f
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
        const val TAB_REPLAY = 4
        const val SCR_HUB = 0
        const val SCR_MODS = 1
        const val SCR_SHOP = 2
        const val SCR_MAPS = 3
        const val SCR_SETTINGS = 4
        val FILTER_NAMES = arrayOf("Все моды", "Только включённые", "Только выключенные", "С настройками", "С нативным кодом")
    }
}
