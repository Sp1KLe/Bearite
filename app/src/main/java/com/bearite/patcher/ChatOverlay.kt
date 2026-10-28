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
 * Сообщения идут через сервер чата (server/chat-server.js) и привязаны к комнате игры:
 * имя комнаты Photon мод кладёт в maps/_room. Не в комнате — чат молчит.
 */
class ChatOverlay(private val act: Activity, private val parent: FrameLayout) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val prefs = act.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val roomFile = File(File(act.filesDir, "maps"), "_room")
    private val ui = GameUi(act)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var bubble: TextView
    private lateinit var badge: TextView
    private lateinit var panel: LinearLayout
    private lateinit var win: ResizableFrame
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var input: EditText
    private var unread = 0

    @Volatile private var running = false
    @Volatile private var room = ""
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
            text = "💬"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC241A15.toInt()); setStroke(dp(2), GameUi.CREAM) }
        }
        parent.addView(bubble, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START).apply { leftMargin = prefs.getInt("bx", dp(10)); topMargin = prefs.getInt("by", dp(72)) })
        // кнопку чата можно перетащить в любое место; короткое касание открывает окно
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var l = 0; var t = 0; var moved = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                val lp = bubble.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; l = lp.leftMargin; t = lp.topMargin; moved = false }
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
                    android.view.MotionEvent.ACTION_UP -> { if (moved) prefs.edit().putInt("bx", lp.leftMargin).putInt("by", lp.topMargin).apply() else toggle() }
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

        val dm = act.resources.displayMetrics
        val w = minOf(dp(340), (dm.widthPixels * 0.5f).toInt())
        val h = (dm.heightPixels * 0.62f).toInt()
        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xE61B1410.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), GameUi.LINE_TAN) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE; isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = ui.label("Чат", 15f, GameUi.CREAM)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.label("⚙", 20f, GameUi.MUTED).apply { setPadding(dp(8), 0, dp(8), 0); setOnClickListener { settings() } })
        head.addView(ui.label("✕", 18f, GameUi.MUTED).apply { setPadding(dp(8), 0, dp(4), 0); setOnClickListener { toggle() } })
        panel.addView(head)
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(act).apply { addView(list); isVerticalScrollBarEnabled = false }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(act).apply {
            hint = "Сообщение…"; setHintTextColor(GameUi.DIM); setTextColor(GameUi.CREAM); textSize = 14f
            maxLines = 2; imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { setColor(GameUi.SURFACE2); cornerRadius = dp(12).toFloat() }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.label("➤", 22f, GameUi.CREAM).apply { gravity = Gravity.CENTER; setOnClickListener { send() } }, LinearLayout.LayoutParams(dp(44), dp(40)))
        panel.addView(row)
        win = ResizableFrame(act).apply { visibility = View.GONE }
        win.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        win.addView(TextView(act).apply { text = "\u25E2"; textSize = 14f; setTextColor(GameUi.DIM); gravity = Gravity.END or Gravity.BOTTOM; setPadding(0, 0, dp(4), 0); isClickable = false },
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

    private fun toggle() {
        val open = win.visibility != View.VISIBLE
        win.visibility = if (open) View.VISIBLE else View.GONE
        panel.visibility = View.VISIBLE
        if (open) {
            unread = 0; updateBadge()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun updateBadge() {
        badge.visibility = if (unread > 0) View.VISIBLE else View.GONE
        badge.text = if (unread > 9) "9+" else unread.toString()
    }

    private fun line(who: String, text: String, own: Boolean) {
        val tv = TextView(act).apply {
            this.text = android.text.SpannableStringBuilder().apply {
                append(who); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(if (own) 0xFFB9D58A.toInt() else 0xFFF2C58E.toInt()), 0, length, 0)
                append(": "); append(text)
            }
            textSize = 14f; setTextColor(GameUi.CREAM); setPadding(0, dp(2), 0, dp(2))
        }
        list.addView(tv)
        if (list.childCount > 120) list.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sys(text: String) {
        list.addView(TextView(act).apply { this.text = text; textSize = 12f; setTextColor(GameUi.MUTED); setPadding(0, dp(2), 0, dp(2)) })
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
        val r = room; val u = url
        if (u.isEmpty()) { sys("Сначала укажи сервер (⚙)."); return }
        if (r.isEmpty()) { sys("Ты не в комнате игры — чат пока недоступен."); return }
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
        if (win.visibility != View.VISIBLE && !mine) { unread++; updateBadge() }
    }

    private fun loop() {
        var shownRoom = "?"
        var failed = 0
        var ntfySince = "5m"
        val seen = HashSet<String>()
        while (running) {
            try {
                val r = try { roomFile.readText().trim() } catch (_: Throwable) { "" }
                room = r
                if (r != shownRoom) {
                    shownRoom = r; after = 0; ntfySince = "5m"; seen.clear()
                    main.post { onRoomChanged?.invoke() }
                    main.post {
                        title.text = if (r.isEmpty()) "Чат · нет комнаты" else "Чат · $r"
                        list.removeAllViews()
                        sys(if (r.isEmpty()) "Зайди в игровую комнату, чтобы писать." else "Ты в комнате «$r».")
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
                            if (roomFile.exists() && (try { roomFile.readText().trim() } catch (_: Throwable) { r }) != r) break
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
    }
}
