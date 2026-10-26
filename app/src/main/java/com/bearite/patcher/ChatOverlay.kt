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
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var input: EditText
    private var unread = 0

    @Volatile private var running = false
    @Volatile private var room = ""
    private var after = 0L

    private var url: String get() = prefs.getString("url", "") ?: ""; set(v) { prefs.edit().putString("url", v.trim().trimEnd('/')).apply() }
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
            setOnClickListener { toggle() }
        }
        parent.addView(bubble, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START).apply { leftMargin = dp(10); topMargin = dp(72) })
        badge = TextView(act).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5483A.toInt()) }
            visibility = View.GONE; isClickable = false
        }
        parent.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START).apply { leftMargin = dp(40); topMargin = dp(68) })

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
        parent.addView(panel, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply { leftMargin = dp(64); topMargin = dp(60) })
        sys("Нажми ⚙, чтобы указать адрес сервера чата и ник.")
    }

    private fun toggle() {
        val open = panel.visibility != View.VISIBLE
        panel.visibility = if (open) View.VISIBLE else View.GONE
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
        col.addView(ui.label("Адрес сервера (https://…)", 12f, GameUi.MUTED))
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
        Thread {
            try {
                val c = open("$u/send")
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("room", r).put("name", who).put("text", text).toString()
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                if (code == 429) main.post { sys("Слишком часто, подожди секунду.") }
                else if (code != 200) main.post { sys("Сообщение не ушло (код $code).") }
                c.disconnect()
            } catch (t: Throwable) { main.post { sys("Нет связи с сервером.") } }
        }.start()
    }

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 35000
        if (key.isNotEmpty()) c.setRequestProperty("X-Key", key)
        return c
    }

    private fun loop() {
        var shownRoom = "?"
        var failed = 0
        while (running) {
            try {
                val r = try { roomFile.readText().trim() } catch (_: Throwable) { "" }
                room = r
                if (r != shownRoom) {
                    shownRoom = r; after = 0
                    main.post {
                        title.text = if (r.isEmpty()) "Чат · нет комнаты" else "Чат · $r"
                        list.removeAllViews()
                        sys(if (r.isEmpty()) "Зайди в игровую комнату, чтобы писать." else "Ты в комнате «$r».")
                    }
                }
                val u = url
                if (u.isEmpty() || r.isEmpty()) { Thread.sleep(1000); continue }
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
                    val who = m.getString("name"); val text = m.getString("text")
                    main.post {
                        line(who, text, who == me)
                        if (panel.visibility != View.VISIBLE && who != me) { unread++; updateBadge() }
                    }
                }
                after = maxOf(last, j.optLong("last", last))
            } catch (t: InterruptedException) { return
            } catch (t: Throwable) {
                if (failed++ == 0) main.post { sys("Нет связи с сервером, пробую снова…") }
                try { Thread.sleep(minOf(10000L, 1500L * failed)) } catch (_: InterruptedException) { return }
            }
        }
    }
}
