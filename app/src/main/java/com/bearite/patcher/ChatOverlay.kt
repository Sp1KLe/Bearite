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
    /** Единица размера = 1/360 меньшей стороны экрана. Чат выглядит одинаково на любом телефоне, dpi и «размер интерфейса» мода не используются. */
    private val d = act.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) / 360f }
    private fun dp(v: Int) = (v * d).toInt()
    private val prefs = act.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val roomFile = File(File(act.filesDir, "maps"), "_room")
    private val ui = GameUi(act)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var bubble: TextView
    /** Всё, что лежит в левом верхнем углу: лента сообщений и под ней поле ввода. */
    private lateinit var box: LinearLayout
    private lateinit var feed: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var inputRow: View
    private lateinit var input: EditText
    private var chatOpen = false

    private class Msg(val who: String, val text: String, val mine: Boolean, val sys: Boolean)
    private val history = ArrayList<Msg>()

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
    private var nick: String get() = prefs.getString("nick", "") ?: ""; set(v) { prefs.edit().putString("nick", v.trim().take(24)).apply(); main.removeCallbacks(nickApply); main.postDelayed(nickApply, 700L) }
    private var key: String get() = prefs.getString("key", "") ?: ""; set(v) { prefs.edit().putString("key", v.trim()).apply() }

    private val nickApply = Runnable { applyNick(true) }

    /**
     * Ник для мультиплеера: файл maps/_nick мод читает при подключении к Photon, а команда «nick» ставит его сразу,
     * если игрок уже в комнате. Раньше файл писал только лаунчер при запуске, поэтому ник из ⚙ чата до игры не доходил.
     */
    private fun applyNick(sendNow: Boolean) {
        try {
            val dir = File(act.filesDir, "maps").also { it.mkdirs() }
            val n = nick
            if (n.isEmpty()) { File(dir, "_nick").delete(); return }
            File(dir, "_nick").writeText(n)
            if (sendNow) {
                val cmd = File(dir, "_cmd")
                val old = if (cmd.exists()) cmd.readText() else ""
                val t = File(dir, "_cmd.tmp")
                t.writeText(old + "nick\t$n\n")
                t.renameTo(cmd)
            }
        } catch (_: Throwable) {}
    }

    fun start() {
        build()
        applyNick(false)
        running = true
        Thread { loop() }.apply { isDaemon = true; name = "bearite-chat" }.start()
    }

    fun stop() { running = false }

    // ------------------------------------------------------------------ интерфейс

    private fun build() {
        val dm = act.resources.displayMetrics
        val bs = dp(54)

        // лента и поле ввода: левый верхний угол, как на видео; без окна и рамки
        box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(4)); isClickable = false }
        feed = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; isClickable = false }
        scroll = ChatScroll(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isClickable = false }
        scroll.addView(feed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        input = EditText(act).apply {
            hint = "Нажми, чтобы написать…"; setHintTextColor(M3.VARIANT); setTextColor(M3.ON_SURF); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12f * d)
            maxLines = 2; imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { setColor(M3.HIGH); cornerRadius = dp(20).toFloat() }
            elevation = dp(2).toFloat(); minHeight = dp(36)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendAndClose(); true } else false }
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(txt("➤", 15f, M3.ON_PRIMARY, true).apply { gravity = Gravity.CENTER; background = circle(M3.PRIMARY); elevation = dp(2).toFloat(); setOnClickListener { sendAndClose() } },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6) })
        row.addView(txt("⚙", 15f, M3.ON_TONAL).apply { gravity = Gravity.CENTER; background = circle(M3.TONAL); elevation = dp(2).toFloat(); setOnClickListener { settings() } },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6) })
        inputRow = row
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        parent.addView(box, FrameLayout.LayoutParams((dm.widthPixels * 0.46f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { leftMargin = dp(6); topMargin = (dm.heightPixels * 0.115f).toInt() })

        // кнопка чата: большая FAB Material 3, лежит на месте родной кнопки чата игры (справа сверху)
        bubble = TextView(act).apply {
            text = "•••"; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * d); gravity = Gravity.CENTER; setTextColor(M3.ON_PRIMARY); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(M3.PRIMARY) }
            elevation = dp(4).toFloat()
        }
        bubble.visibility = View.GONE   // покажем, когда игрок окажется в настоящей комнате
        parent.addView(bubble, FrameLayout.LayoutParams(bs, bs, Gravity.TOP or Gravity.START).apply {
            leftMargin = prefs.getInt("pbx", (dm.widthPixels * 0.959f - bs / 2f).toInt())
            topMargin = prefs.getInt("pby", (dm.heightPixels * 0.247f - bs / 2f).toInt())
        })
        // когда размер родителя известен, ставим кнопку точно на родную кнопку чата игры (если игрок её не двигал)
        parent.post {
            if (!prefs.contains("pbx")) {
                val bl = bubble.layoutParams as FrameLayout.LayoutParams
                bl.leftMargin = (parent.width * 0.959f - bs / 2f).toInt(); bl.topMargin = (parent.height * 0.247f - bs / 2f).toInt()
                bubble.layoutParams = bl
            }
        }
        // кнопку можно перетащить; короткое касание открывает и закрывает поле ввода
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
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        bubble.animate().cancel()
                        bubble.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                        if (moved) prefs.edit().putInt("pbx", lp.leftMargin).putInt("pby", lp.topMargin).apply() else toggle()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        bubble.animate().cancel()
                        bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                }
                return true
            }
        })
    }

    /** Кнопка и лента чата видны только в настоящей комнате. */
    private fun applyVisible(inRealRoom: Boolean) {
        bubble.visibility = if (inRealRoom) View.VISIBLE else View.GONE
        if (!inRealRoom) {
            if (chatOpen) closeChat()
            feed.removeAllViews()
        }
    }

    // ------------------------------------------------------------------ открыть и закрыть ввод

    private fun toggle() { if (chatOpen) closeChat() else openChat() }

    /** Под лентой выезжает поле ввода, показывается вся история (до 60 сообщений, листается пальцем), открывается клавиатура. */
    private fun openChat() {
        chatOpen = true
        feed.removeAllViews()
        for (m in history) addLine(m, false, false)
        if (nick.isBlank()) sys("Нажми ⚙ и впиши ник. «/игроки» покажет, кто в комнате.")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        inputRow.animate().cancel()
        inputRow.pivotX = 0f; inputRow.pivotY = 0f
        inputRow.alpha = 0f; inputRow.translationY = -dp(8).toFloat(); inputRow.scaleX = 0.92f; inputRow.scaleY = 0.92f; inputRow.visibility = View.VISIBLE
        inputRow.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EMPH_IN).start()
        bubble.text = "✕"
        input.requestFocus()
        main.postDelayed({
            if (chatOpen) (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }, 150L)
    }

    private fun closeChat() {
        chatOpen = false
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        inputRow.animate().cancel()
        inputRow.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(180).setInterpolator(EMPH_OUT)
            .withEndAction { if (!chatOpen) inputRow.visibility = View.GONE }.start()
        bubble.text = "•••"
        // стопка остаётся на экране: последние MAX_LINES строк, каждая гаснет сама через HOLD_MS
        val kids = (0 until feed.childCount).map { feed.getChildAt(it) }
        for (v in kids.dropLast(MAX_LINES)) feed.removeView(v)
        scroll.scrollTo(0, 0)
        for (v in kids.takeLast(MAX_LINES)) if (v.tag != "x") armFade(v)
    }

    private fun sendAndClose() {
        if (input.text.toString().trim() == "/игроки") { input.setText(""); showPlayers(); return }
        send()
        closeChat()
    }

    /** Список игроков комнаты, как TAB в Minecraft. Данные пишет мод в maps/_players. */
    private fun showPlayers() {
        val lines = try { File(File(act.filesDir, "maps"), "_players").readText().lines().filter { it.isNotBlank() } } catch (_: Throwable) { emptyList() }
        if (lines.isEmpty()) { sys("Ты не в игровой комнате."); return }
        val head = lines[0].split('\t')
        val n = head.getOrNull(0)?.toIntOrNull() ?: (lines.size - 1)
        val mx = head.getOrNull(1)?.toIntOrNull() ?: 0
        sys("Онлайн: $n" + (if (mx > 0) " из $mx" else ""))
        for (l in lines.drop(1)) {
            val p = l.split('\t')
            val nm = p.getOrElse(1) { "" }.ifBlank { "игрок ${p.getOrElse(0) { "?" }}" }
            sys("• $nm" + (if (p.getOrElse(2) { "0" } == "1") "  (ты)" else ""))
        }
    }

    // ------------------------------------------------------------------ лента сообщений

    /** Текст чата в цветах Material 3 (палитра в M3.kt). */
    private fun txt(t: String, sp: Float, color: Int = M3.ON_SURF, bold: Boolean = false) = TextView(act).apply {
        text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * d); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Круглая кнопка Material 3 (заливка [color]). */
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** «Ник: текст», ник жирным и своим пастельным цветом, свой ник цветом primary. */
    private fun chatSpan(who: String, text: String, mine: Boolean) = android.text.SpannableStringBuilder().apply {
        append(who); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
        setSpan(android.text.style.ForegroundColorSpan(if (mine) M3.PRIMARY else nickColor(who)), 0, length, 0)
        append(": "); append(text)
    }

    private fun nickColor(who: String): Int {
        val colors = intArrayOf(0xFFA8DAB5.toInt(), 0xFFF2BC73.toInt(), 0xFF9ECAFF.toInt(), 0xFFE2B5F0.toInt(), 0xFFFFB4AB.toInt(), 0xFFEBDE9C.toInt())
        return colors[Math.abs(who.hashCode()) % colors.size]
    }

    /** Новое сообщение (только в главном потоке): в историю и на экран. */
    private fun addMsg(m: Msg) {
        history.add(m)
        if (history.size > 60) history.removeAt(0)
        if (chatOpen) addLine(m, false)
        else if (bubble.visibility == View.VISIBLE) addLine(m, true)
    }

    /**
     * Строка в ленте: плавно выезжает ([animate]). Если [fading] (чат закрыт), стопка из [MAX_LINES] строк держится на экране,
     * каждая строка гаснет через [HOLD_MS]. В открытом чате строки не гаснут, ленту можно листать.
     */
    private fun addLine(m: Msg, fading: Boolean, animate: Boolean = true) {
        val tv = txt("", 12f, if (m.sys) M3.VARIANT else M3.ON_SURF).apply {
            text = if (m.sys) m.text else chatSpan(m.who, m.text, m.mine)
            // тональный скруглённый пузырь за каждой строкой; строки идут стопкой, новые снизу
            background = GradientDrawable().apply { setColor(0xE61E1F25.toInt()); cornerRadius = dp(10).toFloat() }
            setPadding(dp(9), dp(4), dp(9), dp(4)); isClickable = false
            pivotX = 0f
            if (animate) { alpha = 0f; translationY = dp(10).toFloat(); scaleX = 0.94f; scaleY = 0.94f }
        }
        // если читатель уже внизу ленты, новые сообщения прокручивают её сами; если он листает историю вверх, не мешаем
        val atBottom = scroll.scrollY + scroll.height >= feed.height - dp(24)
        feed.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
        if (animate) tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EMPH_IN).start()
        if (fading) {
            val live = (0 until feed.childCount).map { feed.getChildAt(it) }.filter { it.tag != "x" }
            for (v in live.dropLast(MAX_LINES)) feedFade(v, 180L)
            armFade(tv)
        } else {
            while (feed.childCount > 60) feed.removeViewAt(0)
            if (animate && atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Через [HOLD_MS] строка плавно гаснет, если чат закрыт. */
    private fun armFade(v: View) {
        main.postDelayed({ if (v.tag != "x" && v.parent != null && !chatOpen) feedFade(v, 1000L) }, HOLD_MS)
    }

    /** Лента с прокруткой: листается только при открытом чате, иначе касания проходят в игру. Высота не больше 58% экрана. */
    private inner class ChatScroll(c: Context) : ScrollView(c) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.58f).toInt(), View.MeasureSpec.AT_MOST))
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean = chatOpen && super.onTouchEvent(e)
        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean = chatOpen && super.onInterceptTouchEvent(e)
    }

    private fun feedFade(v: View, ms: Long) {
        v.tag = "x"
        v.animate().alpha(0f).setDuration(ms).setInterpolator(EMPH_OUT).withEndAction { feed.removeView(v) }.start()
    }

    private fun sys(text: String) = addMsg(Msg("", text, false, true))

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

    private fun show(who: String, text: String, mine: Boolean) = main.post { addMsg(Msg(who, text, mine, false)) }

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
                        history.clear(); feed.removeAllViews()
                        applyVisible(r.isNotEmpty())
                        if (r.isNotEmpty()) applyNick(true)
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

        /** Сколько строк чата видно одновременно. */
        const val MAX_LINES = 8

        /** Сколько миллисекунд строка висит на экране при закрытом чате, потом секунду гаснет (всего около 5,5 секунды). */
        const val HOLD_MS = 4500L

        /** Кривые движения Material 3: «emphasized decelerate» для появления и «emphasized accelerate» для исчезновения. */
        private val EMPH_IN = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPH_OUT = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
