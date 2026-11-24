package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Свободный чат поверх игры: отдельная кнопка-пузырь (как в Roblox) и окно с перепиской.
 * Сообщения идут через сервер чата (server/chat-server.js) и привязаны к комнате игры: имя комнаты Photon
 * мод кладёт в maps/_room. Общего чата нет: вне настоящей комнаты (в том числе в одиночной игре, где Photon
 * держит фиктивную комнату offlineMode) кнопка и окно чата скрыты.
 */
class ChatOverlay(private val act: Activity, private val parent: FrameLayout) {
    private val d = UiScale.density(act)
    private fun dp(v: Int) = (v * d).toInt()
    private val prefs = act.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val roomFile = File(File(act.filesDir, "maps"), "_room")
    private val ui = GameUi(act)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var bubble: TextView
    private lateinit var badge: TextView
    private lateinit var panel: LinearLayout
    private lateinit var win: ResizableFrame
    private lateinit var feed: LinearLayout
    private lateinit var inputRow: View
    private lateinit var playersScroll: ScrollView
    private lateinit var playersBox: LinearLayout
    private lateinit var tabChat: TextView
    private lateinit var tabPlayers: TextView
    private var showPlayers = false
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var input: EditText
    private var unread = 0

    @Volatile private var running = false
    /** Сырое имя комнаты из файла (в одиночной игре там offlineMode): нужно проверке паузы. */
    @Volatile private var room = ""
    /** Настоящая комната мультиплеера: без неё чата нет. */
    @Volatile private var realRoom = ""
    val inRoom: Boolean get() = room.isNotEmpty()
    var onRoomChanged: (() -> Unit)? = null
    private var after = 0L

    private var url: String get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL; set(v) { prefs.edit().putString("url", v.trim().trimEnd('/')).apply() }
    private val cid: String get() = prefs.getString("cid", null) ?: java.util.UUID.randomUUID().toString().take(8).also { prefs.edit().putString("cid", it).apply() }
    private fun ntfyMode(u: String) = u.contains("ntfy")
    private fun topic(room: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(room.toByteArray()).joinToString("") { "%02x".format(it) }
        return "bearite-" + h.take(32)
    }
    private var nick: String get() = prefs.getString("nick", "") ?: ""; set(v) { prefs.edit().putString("nick", v.trim().take(24)).apply() }
    private var key: String get() = prefs.getString("key", "") ?: ""; set(v) { prefs.edit().putString("key", v.trim()).apply() }

    fun start() {
        build()
        running = true
        Thread { loop() }.apply { isDaemon = true; name = "bearite-chat" }.start()
    }

    fun stop() { running = false }

    // ------------------------------------------------------------------ интерфейс

    private fun build() {
        bubble = TextView(act).apply {
            // голубой пузырь с тремя точками, как кнопка чата в самой игре
            text = "•••"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(dp(2).toFloat(), dp(1).toFloat(), dp(1).toFloat(), 0x99000000.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF9EDDF2.toInt()); setStroke(dp(3), 0xFF3C7FA8.toInt()) }
        }
        bubble.visibility = View.GONE   // покажем, когда игрок окажется в настоящей комнате
        parent.addView(bubble, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START).apply { leftMargin = prefs.getInt("bx", dp(10)); topMargin = prefs.getInt("by", dp(72)) })
        // кнопку чата можно перетащить в любое место; короткое касание открывает окно
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var l = 0; var t = 0; var moved = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                val lp = bubble.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; l = lp.leftMargin; t = lp.topMargin; moved = false
                        bubble.animate().cancel()
                        bubble.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start()   // кнопка «вдавливается»
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) > dp(6) || Math.abs(e.rawY - sy) > dp(6)) moved = true
                        if (moved) {
                            lp.leftMargin = (l + e.rawX - sx).toInt().coerceIn(0, maxOf(0, parent.width - lp.width))
                            lp.topMargin = (t + e.rawY - sy).toInt().coerceIn(0, maxOf(0, parent.height - lp.height))
                            bubble.layoutParams = lp
                            val bl = badge.layoutParams as FrameLayout.LayoutParams
                            bl.leftMargin = lp.leftMargin + dp(30); bl.topMargin = lp.topMargin - dp(4); badge.layoutParams = bl
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        bubble.animate().cancel()
                        bubble.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                        if (moved) prefs.edit().putInt("bx", lp.leftMargin).putInt("by", lp.topMargin).apply() else toggle()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        bubble.animate().cancel()
                        bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                }
                return true
            }
        })
        badge = TextView(act).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5483A.toInt()) }
            visibility = View.GONE; isClickable = false
        }
        parent.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START).apply { leftMargin = prefs.getInt("bx", dp(10)) + dp(30); topMargin = prefs.getInt("by", dp(72)) - dp(4) })
        // лента новых сообщений поверх игры (пока окно закрыто): строки плавно появляются и гаснут сами
        feed = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; isClickable = false }
        parent.addView(feed, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = prefs.getInt("bx", dp(10)) + dp(54); topMargin = prefs.getInt("by", dp(72)) })

        val dm = act.resources.displayMetrics
        val w = minOf(dp(340), (dm.widthPixels * 0.5f).toInt())
        val h = (dm.heightPixels * 0.62f).toInt()
        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // плоское тёмное полупрозрачное окно, как чат в Minecraft Bedrock
            background = GradientDrawable().apply { setColor(0xB8000000.toInt()); cornerRadius = dp(3).toFloat(); setStroke(dp(2), 0x66FFFFFF) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE; isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = txt("Чат", 15f, Color.WHITE, true)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(txt("⚙", 20f, 0xFFDDDDDD.toInt()).apply { setPadding(dp(8), 0, dp(8), 0); setOnClickListener { settings() } })
        head.addView(txt("✕", 18f, 0xFFDDDDDD.toInt()).apply { setPadding(dp(8), 0, dp(4), 0); setOnClickListener { toggle() } })
        panel.addView(head)
        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        tabChat = txt("Чат", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setOnClickListener { setTab(false) } }
        tabPlayers = txt("Игроки", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setOnClickListener { setTab(true) } }
        tabs.addView(tabChat, LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(3) })
        tabs.addView(tabPlayers, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(3) })
        panel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(act).apply { addView(list); isVerticalScrollBarEnabled = false }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })
        playersBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        playersScroll = ScrollView(act).apply { addView(playersBox); isVerticalScrollBarEnabled = false; visibility = View.GONE }
        panel.addView(playersScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(act).apply {
            hint = "Нажми, чтобы написать…"; setHintTextColor(0xFF9A9A9A.toInt()); setTextColor(Color.WHITE); textSize = 14f
            maxLines = 2; imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { setColor(0xFF161616.toInt()); cornerRadius = dp(2).toFloat(); setStroke(dp(2), 0xFF8B8B8B.toInt()) }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(txt("➤", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = flatBtn(true); setOnClickListener { send() } },
            LinearLayout.LayoutParams(dp(44), dp(40)).apply { leftMargin = dp(4) })
        inputRow = row
        panel.addView(row)
        setTab(false)
        main.post(object : Runnable { override fun run() { if (running) { updateCount(); if (winOpen && showPlayers) renderPlayers(); main.postDelayed(this, 1000) } } })
        win = ResizableFrame(act).apply { visibility = View.GONE }
        win.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        win.addView(TextView(act).apply { text = "\u25E2"; textSize = 14f; setTextColor(0xFF9A9A9A.toInt()); gravity = Gravity.END or Gravity.BOTTOM; setPadding(0, 0, dp(4), 0); isClickable = false },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val sw = prefs.getInt("w", w); val sh = prefs.getInt("h", h)
        parent.addView(win, FrameLayout.LayoutParams(sw, sh, Gravity.TOP or Gravity.START).apply { leftMargin = prefs.getInt("x", dp(64)); topMargin = prefs.getInt("y", dp(60)) })
        // перетаскивание окна за заголовок
        head.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var l = 0; var t = 0
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                val lp = win.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; l = lp.leftMargin; t = lp.topMargin }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        lp.leftMargin = (l + e.rawX - sx).toInt().coerceIn(0, maxOf(0, parent.width - lp.width))
                        lp.topMargin = (t + e.rawY - sy).toInt().coerceIn(0, maxOf(0, parent.height - lp.height))
                        win.layoutParams = lp
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> saveWin()
                }
                return true
            }
        })
        sys("Нажми ⚙ и впиши ник. Тяни окно за заголовок, размер меняй за края и углы.")
    }

    /** Кнопка и окно чата видны только в настоящей комнате. */
    private fun applyVisible(inRealRoom: Boolean) {
        bubble.visibility = if (inRealRoom) View.VISIBLE else View.GONE
        if (!inRealRoom) {
            badge.visibility = View.GONE
            clearFeed()
            if (winOpen) toggle()
        } else updateBadge()
    }

    private fun saveWin() {
        val lp = win.layoutParams as FrameLayout.LayoutParams
        prefs.edit().putInt("w", lp.width).putInt("h", lp.height).putInt("x", lp.leftMargin).putInt("y", lp.topMargin).apply()
    }

    /** Окно с изменением размера «как в Windows»: тяни за любой край или угол. */
    private inner class ResizableFrame(ctx: Context) : FrameLayout(ctx) {
        private var edge = 0   // 1 слева, 2 сверху, 4 справа, 8 снизу
        private var sx = 0f; private var sy = 0f
        private var l = 0; private var t = 0; private var w0 = 0; private var h0 = 0
        private val grip = dp(18)
        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                var ed = 0
                if (e.x < grip) ed = ed or 1
                if (e.y < grip && e.x > dp(120)) ed = ed or 2   // верхний край не мешает заголовку с кнопками
                if (e.x > width - grip) ed = ed or 4
                if (e.y > height - grip) ed = ed or 8
                edge = ed
                if (ed != 0) {
                    val lp = layoutParams as LayoutParams
                    sx = e.rawX; sy = e.rawY; l = lp.leftMargin; t = lp.topMargin; w0 = lp.width; h0 = lp.height
                    return true
                }
            }
            return false
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (edge == 0) return false
            val lp = layoutParams as LayoutParams
            val minW = dp(220); val minH = dp(150)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    var nl = l; var nt = t; var nw = w0; var nh = h0
                    if (edge and 4 != 0) nw = (w0 + dx).coerceIn(minW, this@ChatOverlay.parent.width - l)
                    if (edge and 8 != 0) nh = (h0 + dy).coerceIn(minH, this@ChatOverlay.parent.height - t)
                    if (edge and 1 != 0) { nw = (w0 - dx).coerceIn(minW, l + w0); nl = l + w0 - nw }
                    if (edge and 2 != 0) { nh = (h0 - dy).coerceIn(minH, t + h0); nt = t + h0 - nh }
                    lp.leftMargin = nl; lp.topMargin = nt; lp.width = nw; lp.height = nh
                    layoutParams = lp
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { edge = 0; saveWin() }
            }
            return true
        }
    }

    /** Число игроков комнаты в заголовке чата и на вкладке. */
    private fun updateCount() {
        val lines = try { File(File(act.filesDir, "maps"), "_players").readText().lines().filter { it.isNotBlank() } } catch (_: Throwable) { emptyList() }
        if (lines.isEmpty()) { tabPlayers.text = "Игроки"; return }
        val h = lines[0].split('\t')
        val n = h.getOrNull(0)?.toIntOrNull() ?: 0
        val mx = h.getOrNull(1)?.toIntOrNull() ?: 0
        tabPlayers.text = "Игроки ($n" + (if (mx > 0) "/$mx" else "") + ")"
        title.text = "Чат · $n " + (if (mx > 0) "из $mx" else "онлайн")
    }

    private fun setTab(players: Boolean) {
        showPlayers = players
        scroll.visibility = if (players) View.GONE else View.VISIBLE
        inputRow.visibility = if (players) View.GONE else View.VISIBLE
        playersScroll.visibility = if (players) View.VISIBLE else View.GONE
        for ((t, on) in listOf(tabChat to !players, tabPlayers to players)) {
            t.setTextColor(0xFFFFFFFF.toInt())
            t.background = flatBtn(on)
        }
        if (players) renderPlayers()
    }

    /** Список игроков комнаты, как TAB в Minecraft: ник и номер, ты отмечен. Данные пишет мод в maps/_players. */
    private fun renderPlayers() {
        val lines = try { File(File(act.filesDir, "maps"), "_players").readText().lines().filter { it.isNotBlank() } } catch (_: Throwable) { emptyList() }
        playersBox.removeAllViews()
        if (lines.isEmpty()) { playersBox.addView(txt("Ты не в игровой комнате.", 13f, 0xFFC8C8C8.toInt())); tabPlayers.text = "Игроки"; return }
        val head = lines[0].split('\t')
        val n = head.getOrNull(0)?.toIntOrNull() ?: (lines.size - 1)
        val mx = head.getOrNull(1)?.toIntOrNull() ?: 0
        tabPlayers.text = "Игроки ($n)"
        playersBox.addView(txt("Онлайн: $n" + (if (mx > 0) " из $mx" else ""), 13f, 0xFFC8C8C8.toInt()))
        for (l in lines.drop(1)) {
            val p = l.split('\t')
            val nm = p.getOrElse(1) { "" }.ifBlank { "игрок ${p.getOrElse(0) { "?" }}" }
            val mine = p.getOrElse(2) { "0" } == "1"
            playersBox.addView(txt("• $nm" + (if (mine) "  (ты)" else ""), 14f, if (mine) 0xFFB9D58A.toInt() else Color.WHITE, mine).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
    }

    /** Окно чата открыто или открывается (сама анимация может ещё идти). */
    private var winOpen = false

    /** Открыть или закрыть окно: оно вырастает из кнопки чата и уменьшается обратно. */
    private fun toggle() {
        val open = !winOpen
        winOpen = open
        panel.visibility = View.VISIBLE
        win.animate().cancel()
        if (open) {
            clearFeed()
            unread = 0; updateBadge()
            if (win.visibility != View.VISIBLE) {
                val bl = bubble.layoutParams as FrameLayout.LayoutParams
                val wl = win.layoutParams as FrameLayout.LayoutParams
                win.pivotX = (bl.leftMargin + bl.width / 2f - wl.leftMargin).coerceIn(0f, wl.width.toFloat())
                win.pivotY = (bl.topMargin + bl.height / 2f - wl.topMargin).coerceIn(0f, wl.height.toFloat())
                win.alpha = 0f; win.scaleX = 0.7f; win.scaleY = 0.7f
                win.visibility = View.VISIBLE
            }
            win.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            win.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    if (!winOpen) { win.visibility = View.GONE; win.alpha = 1f; win.scaleX = 1f; win.scaleY = 1f }
                }.start()
        }
    }

    // ------------------------------------------------------------------ лента сообщений

    /** Текст чата: белый, с тенью, чтобы читался поверх игры. */
    private fun txt(t: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(dp(2).toFloat(), dp(1).toFloat(), dp(1).toFloat(), 0xCC000000.toInt())
    }

    /** Плоская кнопка в духе Bedrock: серая, с рамкой; выбранная подсвечена белой рамкой. */
    private fun flatBtn(on: Boolean) = GradientDrawable().apply {
        setColor(if (on) 0xFF7A7A7A.toInt() else 0xFF2E2E2E.toInt())
        cornerRadius = dp(2).toFloat()
        setStroke(dp(2), if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
    }

    /** «Ник: текст», ник жирным и своим цветом, свои сообщения зелёные. */
    private fun chatSpan(who: String, text: String, mine: Boolean) = android.text.SpannableStringBuilder().apply {
        append(who); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
        setSpan(android.text.style.ForegroundColorSpan(if (mine) 0xFFB9D58A.toInt() else nickColor(who)), 0, length, 0)
        append(": "); append(text)
    }

    private fun nickColor(who: String): Int {
        val colors = intArrayOf(0xFF6FE06F.toInt(), 0xFFF2A93B.toInt(), 0xFF55D6F2.toInt(), 0xFFB07CFF.toInt(), 0xFFFF7B7B.toInt(), 0xFFF2E25A.toInt())
        return colors[Math.abs(who.hashCode()) % colors.size]
    }

    /** Новая строка в ленте: плавно выезжает, держится около 6 секунд и гаснет. Видно максимум 3 строки. */
    private fun feedAdd(who: String, text: String, mine: Boolean) {
        val lp = feed.layoutParams as FrameLayout.LayoutParams
        val bl = bubble.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = bl.leftMargin + bl.width + dp(8); lp.topMargin = bl.topMargin
        lp.width = minOf(dp(300), maxOf(dp(120), parent.width - lp.leftMargin - dp(8)))
        feed.layoutParams = lp
        val tv = txt("", 13f).apply {
            this.text = chatSpan(who, text, mine)
            setPadding(0, dp(1), 0, dp(1))
            isClickable = false
            alpha = 0f; translationY = dp(8).toFloat()
        }
        feed.addView(tv)
        tv.animate().alpha(1f).translationY(0f).setDuration(220).start()
        val live = (0 until feed.childCount).map { feed.getChildAt(it) }.filter { it.tag != "x" }
        for (v in live.dropLast(3)) feedFade(v, 180L)
        main.postDelayed({ if (tv.tag != "x" && tv.parent != null) feedFade(tv, 700L) }, 6000L)
    }

    private fun feedFade(v: View, ms: Long) {
        v.tag = "x"
        v.animate().alpha(0f).setDuration(ms).withEndAction { feed.removeView(v) }.start()
    }

    private fun clearFeed() {
        for (v in (0 until feed.childCount).map { feed.getChildAt(it) }) if (v.tag != "x") feedFade(v, 120L)
    }

    private fun updateBadge() {
        badge.visibility = if (unread > 0) View.VISIBLE else View.GONE
        badge.text = if (unread > 9) "9+" else unread.toString()
    }

    private fun line(who: String, text: String, own: Boolean) {
        val tv = txt("", 14f).apply { this.text = chatSpan(who, text, own); setPadding(0, dp(2), 0, dp(2)) }
        list.addView(tv)
        if (list.childCount > 120) list.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sys(text: String) {
        list.addView(txt(text, 12f, 0xFFC8C8C8.toInt()).apply { setPadding(0, dp(2), 0, dp(2)) })
    }

    private fun settings() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.label("Сервер (по умолчанию общий ntfy.sh, трогать не нужно)", 12f, GameUi.MUTED))
        col.addView(ui.textInput(url, 300, "https://chat.example.com") { url = it })
        col.addView(ui.label("Ник", 12f, GameUi.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(ui.textInput(nick, 300, "как тебя звать") { nick = it })
        col.addView(ui.label("Ключ сервера (если задан)", 12f, GameUi.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(ui.textInput(key, 300, "необязательно") { key = it })
        ui.dialog("Чат", col, listOf(GameUi.Btn("Готово", primary = true) { sys("Настройки сохранены.") })).show()
    }

    // ------------------------------------------------------------------ сеть

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val r = realRoom; val u = url
        if (u.isEmpty()) { sys("Сначала укажи сервер (⚙)."); return }
        if (r.isEmpty()) { sys("Ты не в комнате игры — чат недоступен."); return }
        input.setText("")
        val who = nick.ifBlank { "игрок" }
        val payload = JSONObject().put("n", who).put("t", text.take(200)).put("c", cid).toString()
        Thread {
            try {
                val c: HttpURLConnection
                if (ntfyMode(u)) {
                    c = open("$u/${topic(r)}")
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    c.outputStream.use { it.write(payload.toByteArray()) }
                } else {
                    c = open("$u/send")
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(JSONObject().put("room", r).put("name", who).put("text", text).toString().toByteArray()) }
                }
                val code = c.responseCode
                if (code == 429) main.post { sys("Слишком часто, подожди немного.") }
                else if (code !in 200..299) main.post { sys("Сообщение не ушло (код $code).") }
                c.disconnect()
            } catch (t: Throwable) { main.post { sys("Нет связи с сервером.") } }
        }.start()
    }

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 95000
        if (key.isNotEmpty()) c.setRequestProperty("X-Key", key)
        return c
    }

    private fun show(who: String, text: String, mine: Boolean) = main.post {
        line(who, text, mine)
        if (!winOpen) feedAdd(who, text, mine)
        if (!winOpen && !mine) { unread++; updateBadge() }
    }

    private fun loop() {
        var shownRoom = "?"
        var failed = 0
        var ntfySince = "5m"
        val seen = HashSet<String>()
        var shownRaw = "?"
        while (running) {
            try {
                val raw = try { roomFile.readText().trim() } catch (_: Throwable) { "" }
                room = raw
                val r = if (raw == OFFLINE_ROOM) "" else raw   // одиночная игра — это не комната
                realRoom = r
                if (raw != shownRaw) { shownRaw = raw; main.post { onRoomChanged?.invoke() } }
                if (r != shownRoom) {
                    shownRoom = r; after = 0; ntfySince = "5m"; seen.clear()
                    main.post {
                        applyVisible(r.isNotEmpty())
                        title.text = if (r.isEmpty()) "Чат" else "Чат · $r"
                        list.removeAllViews()
                        if (r.isNotEmpty()) sys("Ты в комнате «$r».")
                    }
                }
                val u = url
                if (u.isEmpty() || r.isEmpty()) { Thread.sleep(1000); continue }
                if (ntfyMode(u)) {
                    // публичный ntfy: поток событий по теме комнаты, с продолжением с последнего сообщения
                    val c = open("$u/${topic(r)}/json?since=$ntfySince")
                    val code = c.responseCode
                    if (code != 200) { c.disconnect(); throw RuntimeException("код $code") }
                    if (failed > 0) { failed = 0; main.post { sys("Связь восстановлена.") } }
                    c.inputStream.bufferedReader().use { rd ->
                        while (running) {
                            val ln = rd.readLine() ?: break
                            if (realRoom != r) break   // сменилась комната
                            val j = try { JSONObject(ln) } catch (_: Throwable) { continue }
                            if (j.optString("event") != "message") continue
                            val id = j.optString("id")
                            if (id.isNotEmpty()) { ntfySince = id; if (!seen.add(id)) continue }
                            val m = try { JSONObject(j.optString("message")) } catch (_: Throwable) { continue }
                            show(m.optString("n", "игрок"), m.optString("t"), m.optString("c") == cid)
                        }
                    }
                    c.disconnect()
                } else {
                    val c = open("$u/poll?room=${URLEncoder.encode(r, "UTF-8")}&after=$after&wait=20")
                    val code = c.responseCode
                    if (code != 200) { c.disconnect(); throw RuntimeException("код $code") }
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    c.disconnect()
                    if (failed > 0) { failed = 0; main.post { sys("Связь восстановлена.") } }
                    val arr = j.getJSONArray("msgs")
                    var last = after
                    val me = nick.ifBlank { "игрок" }
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        last = maxOf(last, m.getLong("id"))
                        val who = m.getString("name")
                        show(who, m.getString("text"), who == me)
                    }
                    after = maxOf(last, j.optLong("last", last))
                }
            } catch (t: InterruptedException) { return
            } catch (t: Throwable) {
                if (failed++ == 0) main.post { sys("Нет связи с сервером, пробую снова…") }
                try { Thread.sleep(minOf(10000L, 1500L * failed)) } catch (_: InterruptedException) { return }
            }
        }
    }

    companion object {
        /** Общий бесплатный публичный сервер ntfy: свой сервер поднимать не нужно. */
        const val DEFAULT_URL = "https://ntfy.sh"

        /** Имя фиктивной комнаты Photon в одиночной игре. */
        const val OFFLINE_ROOM = "offlineMode"
    }
}
