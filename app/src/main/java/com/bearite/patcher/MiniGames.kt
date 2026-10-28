package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Мини-игры по комнатам. Создатель комнаты (хост) ведёт игру, остальные подключаются по четырёхзначному коду.
 * Лимит игроков задаёт хост (0 = без ограничений). Подключиться могут только игроки с лаунчером Bearite.
 * Сообщения идут через тот же публичный ntfy, что и чат; тема комнаты зависит от кода.
 * Игры: крестики-нолики (двое играют, остальные смотрят), гонка тапов, викторина.
 */
class MiniGames(private val act: Activity, private val ui: GameUi) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val me: String = prefs.getString("cid", null) ?: java.util.UUID.randomUUID().toString().take(8).also { prefs.edit().putString("cid", it).apply() }
    private var name: String get() = (prefs.getString("nick", "") ?: "").ifBlank { "игрок" }; set(v) { prefs.edit().putString("nick", v.trim().take(24)).apply() }
    private val base get() = (prefs.getString("url", ChatOverlay.DEFAULT_URL) ?: ChatOverlay.DEFAULT_URL).ifBlank { ChatOverlay.DEFAULT_URL }

    private var list: LinearLayout? = null
    private var code = ""
    private var isHost = false
    private var state: JSONObject? = null
    private var maxInput = "0"
    private var codeInput = ""
    private var notice = ""
    @Volatile private var gen = 0          // номер подключения: старые потоки сами выходят
    private var hostState: JSONObject? = null
    private var timer: Runnable? = null

    // локальное состояние игр у каждого игрока
    private var tapRid = ""; private var tapCount = 0; private var tapLeft = 0; private var tapSent = false
    private var tapView: TextView? = null; private var tapTimeView: TextView? = null
    private var answered = -1; private var answeredQ = -1

    // ------------------------------------------------------------------ экран

    fun render(into: LinearLayout) {
        list = into
        draw()
    }

    private fun draw() {
        val l = list ?: return
        l.removeAllViews()
        tapView = null; tapTimeView = null
        val st = state
        if (code.isEmpty() || st == null) { drawStart(l); return }
        l.addView(ui.label("Комната $code" + (if (isHost) " · ты хост" else ""), 17f), ui.matchWrap().apply { topMargin = ui.dp(8) })
        val players = st.getJSONArray("players")
        val max = st.optInt("max", 0)
        l.addView(ui.label("Игроков: ${players.length()}" + (if (max > 0) " из $max" else " (без лимита)"), 13f, GameUi.MUTED, bold = false))
        val sb = StringBuilder()
        for (i in 0 until players.length()) { val p = players.getJSONObject(i); sb.append(p.getString("n")).append(" — ").append(p.optInt("s")).append("\n") }
        l.addView(ui.label(sb.toString().trim(), 14f, GameUi.CREAM, bold = false).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) })
        if (notice.isNotEmpty()) l.addView(ui.label(notice, 13f, GameUi.RUST, bold = false))
        when (st.optString("game")) {
            "" -> drawLobby(l, st)
            "ttt" -> drawTtt(l, st)
            "tap" -> drawTap(l, st)
            "quiz" -> drawQuiz(l, st)
        }
        l.addView(ui.pill("Выйти из комнаты", danger = true) { leave() }, ui.matchWrap().apply { topMargin = ui.dp(12) })
    }

    private fun drawStart(l: LinearLayout) {
        l.addView(ui.label("Мини-игры для игроков с лаунчером Bearite", 16f), ui.matchWrap().apply { topMargin = ui.dp(8) })
        l.addView(ui.label("Создай комнату и скажи друзьям код — или впиши код друга.", 13f, GameUi.MUTED, bold = false))
        l.addView(ui.row("Ник", ui.textInput(name, 200, "как тебя звать") { name = it }), ui.matchWrap().apply { topMargin = ui.dp(8) })
        l.addView(ui.row("Лимит", ui.textInput(maxInput, 120, "0 = без лимита") { maxInput = it.filter(Char::isDigit) }), ui.matchWrap().apply { topMargin = ui.dp(6) })
        l.addView(ui.pill("Создать комнату", primary = true) {
            val mx = maxInput.toIntOrNull() ?: 0
            create(if (mx in 1..1) 2 else mx)
        }, ui.matchWrap().apply { topMargin = ui.dp(6) })
        l.addView(ui.row("Код", ui.textInput(codeInput, 120, "4 цифры") { codeInput = it.filter(Char::isDigit).take(4) }), ui.matchWrap().apply { topMargin = ui.dp(14) })
        l.addView(ui.pill("Подключиться") { if (codeInput.length == 4) join(codeInput) else { notice = "Код состоит из 4 цифр."; draw() } }, ui.matchWrap().apply { topMargin = ui.dp(6) })
        if (notice.isNotEmpty()) l.addView(ui.label(notice, 13f, GameUi.RUST, bold = false))
    }

    private fun drawLobby(l: LinearLayout, st: JSONObject) {
        if (!isHost) { l.addView(ui.label("Ждём, пока хост выберет игру…", 14f, GameUi.MUTED, bold = false)); return }
        l.addView(ui.label("Выбери игру:", 14f))
        l.addView(ui.pill("Крестики-нолики (2 игрока)") { hostStart("ttt") }, ui.matchWrap().apply { topMargin = ui.dp(6) })
        l.addView(ui.pill("Гонка тапов (10 секунд)") { hostStart("tap") }, ui.matchWrap().apply { topMargin = ui.dp(6) })
        l.addView(ui.pill("Викторина (8 вопросов)") { hostStart("quiz") }, ui.matchWrap().apply { topMargin = ui.dp(6) })
    }

    private fun backToLobby(l: LinearLayout) {
        if (isHost) l.addView(ui.pill("В лобби", primary = true) { hostSet { it.put("game", "").put("phase", "lobby").put("d", JSONObject()) } }, ui.matchWrap().apply { topMargin = ui.dp(10) })
    }

    // ---------------- крестики-нолики
    private fun drawTtt(l: LinearLayout, st: JSONObject) {
        val d = st.getJSONObject("d")
        val b = d.getString("b"); val p = d.getJSONArray("p"); val t = d.getInt("t"); val w = d.optString("w")
        val names = (0 until st.getJSONArray("players").length()).associate { val o = st.getJSONArray("players").getJSONObject(it); o.getString("c") to o.getString("n") }
        l.addView(ui.label("Х: ${names[p.getString(0)] ?: "?"}   О: ${names[p.optString(1)] ?: "ждём игрока"}", 14f))
        val status = when {
            w == "=" -> "Ничья"
            w.isNotEmpty() -> "Победил ${if (w == "X") names[p.getString(0)] else names[p.getString(1)]}"
            p.length() < 2 -> "Нужен второй игрок"
            p.getString(t) == me -> "Твой ход"
            else -> "Ходит ${names[p.getString(t)]}" }
        l.addView(ui.label(status, 15f, GameUi.CREAM), ui.matchWrap().apply { topMargin = ui.dp(4) })
        for (r in 0 until 3) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val i = r * 3 + c
                val ch = b[i]
                row.addView(ui.label(if (ch == '.') " " else ch.toString(), 30f, if (ch == 'X') 0xFFF2C58E.toInt() else 0xFFB9D58A.toInt()).apply {
                    gravity = Gravity.CENTER; background = ui.round(GameUi.SURFACE3, 12)
                    setOnClickListener { if (w.isEmpty() && ch == '.') doAct("move", i) }
                }, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            }
            l.addView(row)
        }
        if (w.isNotEmpty()) backToLobby(l)
    }

    // ---------------- гонка тапов
    private fun drawTap(l: LinearLayout, st: JSONObject) {
        val d = st.getJSONObject("d")
        val rid = d.getString("rid")
        if (st.getString("phase") == "over") {
            l.addView(ui.label("Итог гонки:", 15f))
            val res = d.getJSONObject("res")
            val names = nameMap(st)
            val sorted = res.keys().asSequence().sortedByDescending { res.getInt(it) }.toList()
            sorted.forEachIndexed { i, c -> l.addView(ui.label("${i + 1}. ${names[c] ?: c} — ${res.getInt(c)} тапов", 14f, GameUi.CREAM, bold = false)) }
            backToLobby(l); return
        }
        if (tapRid != rid) { tapRid = rid; tapCount = 0; tapSent = false; tapLeft = d.getInt("dur"); runTapTimer() }
        tapTimeView = ui.label("Осталось: $tapLeft", 15f)
        l.addView(tapTimeView)
        tapView = ui.label(if (tapSent) "Результат отправлен: $tapCount. Ждём остальных…" else "ЖМИ!\n$tapCount", 26f, GameUi.ON_CREAM).apply {
            gravity = Gravity.CENTER; background = ui.round(GameUi.CREAM, 28)
            setOnClickListener { if (!tapSent && tapLeft > 0) { tapCount++; text = "ЖМИ!\n$tapCount" } }
        }
        l.addView(tapView, ui.matchWrap().apply { topMargin = ui.dp(8); height = ui.dp(180) })
    }

    private fun runTapTimer() {
        timer?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (tapLeft > 0) { tapLeft--; tapTimeView?.text = "Осталось: $tapLeft"; main.postDelayed(this, 1000) }
                if (tapLeft <= 0 && !tapSent) { tapSent = true; doAct("taps", tapCount); tapView?.text = "Результат отправлен: $tapCount. Ждём остальных…" }
            }
        }
        timer = r; main.postDelayed(r, 1000)
    }

    // ---------------- викторина
    private fun drawQuiz(l: LinearLayout, st: JSONObject) {
        val d = st.getJSONObject("d")
        if (st.getString("phase") == "over") {
            l.addView(ui.label("Викторина окончена. Очки видны в списке игроков.", 15f)); backToLobby(l); return
        }
        val qi = d.getInt("i")
        if (answeredQ != qi) { answeredQ = qi; answered = -1 }
        l.addView(ui.label("Вопрос ${qi + 1} из ${d.getInt("n")}", 13f, GameUi.MUTED, bold = false))
        l.addView(ui.label(d.getString("q"), 17f), ui.matchWrap().apply { topMargin = ui.dp(4) })
        val o = d.getJSONArray("o"); val r = d.optInt("r", -1)
        for (i in 0 until o.length()) {
            val col = when { r >= 0 && i == r -> GameUi.GREEN; r >= 0 && i == answered -> GameUi.RUST; i == answered -> GameUi.CREAM; else -> GameUi.SURFACE3 }
            l.addView(ui.label(o.getString(i), 15f, if (col == GameUi.SURFACE3) GameUi.CREAM else GameUi.ON_CREAM).apply {
                background = ui.round(col, 14); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                setOnClickListener { if (r < 0 && answered < 0) { answered = i; doAct("ans", i); draw() } }
            }, ui.matchWrap().apply { topMargin = ui.dp(6) })
        }
        if (r >= 0) l.addView(ui.label(if (answered == r) "Верно!" else "Правильный ответ подсвечен.", 13f, GameUi.MUTED, bold = false))
    }

    private fun nameMap(st: JSONObject): Map<String, String> {
        val a = st.getJSONArray("players"); return (0 until a.length()).associate { a.getJSONObject(it).getString("c") to a.getJSONObject(it).getString("n") }
    }

    // ------------------------------------------------------------------ сеть

    private fun topic() = "bearite-mg-" + MessageDigest.getInstance("SHA-256").digest(("mg" + code).toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun post(o: JSONObject) {
        val c = code; if (c.isEmpty()) return
        o.put("c", me)
        Thread {
            try {
                val h = URL("$base/${topic()}").openConnection() as HttpURLConnection
                h.connectTimeout = 8000; h.readTimeout = 8000; h.requestMethod = "POST"; h.doOutput = true
                h.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                h.outputStream.use { it.write(o.toString().toByteArray()) }
                h.responseCode; h.disconnect()
            } catch (_: Throwable) { main.post { notice = "Нет связи с сервером."; draw() } }
        }.start()
    }

    private fun listen() {
        val g = ++gen
        Thread {
            var since = "30s"
            while (gen == g) {
                try {
                    val h = URL("$base/${topic()}/json?since=$since").openConnection() as HttpURLConnection
                    h.connectTimeout = 8000; h.readTimeout = 95000
                    if (h.responseCode != 200) throw RuntimeException()
                    h.inputStream.bufferedReader().use { rd ->
                        while (gen == g) {
                            val ln = rd.readLine() ?: break
                            val j = try { JSONObject(ln) } catch (_: Throwable) { continue }
                            if (j.optString("event") != "message") continue
                            if (j.optString("id").isNotEmpty()) since = j.getString("id")
                            val m = try { JSONObject(j.getString("message")) } catch (_: Throwable) { continue }
                            if (m.optString("c") == me) continue
                            main.post { if (gen == g) onMessage(m) }
                        }
                    }
                    h.disconnect()
                } catch (_: Throwable) { try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread } }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun onMessage(m: JSONObject) {
        when (m.optString("k")) {
            "state" -> if (!isHost) { state = m.getJSONObject("s"); notice = ""; draw() }
            "full" -> if (m.optString("to") == me) { notice = "Комната заполнена."; leaveLocal() }
            "closed" -> if (!isHost) { notice = "Хост закрыл комнату."; leaveLocal() }
            "join" -> if (isHost) hostJoin(m.getString("c"), m.optString("n", "игрок"))
            "leave" -> if (isHost) hostLeave(m.getString("c"))
            "act" -> if (isHost) hostAct(m.getString("c"), m.getString("a"), m.optInt("v"))
        }
    }

    // ------------------------------------------------------------------ комната

    private fun create(max: Int) {
        code = (1000 + java.util.Random().nextInt(9000)).toString()
        isHost = true
        val st = JSONObject().put("max", max).put("game", "").put("phase", "lobby").put("d", JSONObject())
            .put("players", JSONArray().put(JSONObject().put("c", me).put("n", name).put("s", 0)))
        hostState = st; state = st
        listen(); notice = ""; draw()
    }

    private fun join(c: String) {
        code = c; isHost = false; state = null
        notice = "Подключаюсь…"
        listen()
        post(JSONObject().put("k", "join").put("n", name))
        main.postDelayed({ if (state == null && code == c) { notice = "Комната не отвечает. Проверь код."; leaveLocal() } }, 8000)
        draw()
    }

    private fun leaveLocal() {
        gen++; timer?.let { main.removeCallbacks(it) }
        code = ""; state = null; hostState = null; isHost = false; tapRid = ""
        draw()
    }

    private fun leave() {
        if (isHost) post(JSONObject().put("k", "closed")) else post(JSONObject().put("k", "leave"))
        notice = ""; leaveLocal()
    }

    private fun doAct(a: String, v: Int) {
        if (isHost) hostAct(me, a, v) else post(JSONObject().put("k", "act").put("a", a).put("v", v))
    }

    // ------------------------------------------------------------------ логика хоста

    private fun hostSet(f: (JSONObject) -> Unit) {
        val st = hostState ?: return
        f(st); state = st
        post(JSONObject().put("k", "state").put("s", st))
        draw()
    }

    private fun hostJoin(c: String, n: String) {
        val st = hostState ?: return
        val ps = st.getJSONArray("players")
        for (i in 0 until ps.length()) if (ps.getJSONObject(i).getString("c") == c) { hostSet { }; return }
        val max = st.optInt("max", 0)
        if (max > 0 && ps.length() >= max) { post(JSONObject().put("k", "full").put("to", c)); return }
        hostSet { ps.put(JSONObject().put("c", c).put("n", n).put("s", 0)) }
    }

    private fun hostLeave(c: String) {
        val st = hostState ?: return
        val ps = st.getJSONArray("players"); val keep = JSONArray()
        for (i in 0 until ps.length()) if (ps.getJSONObject(i).getString("c") != c) keep.put(ps.getJSONObject(i))
        hostSet { it.put("players", keep) }
    }

    private fun hostStart(game: String) {
        val st = hostState ?: return
        timer?.let { main.removeCallbacks(it) }
        val ps = st.getJSONArray("players")
        for (i in 0 until ps.length()) ps.getJSONObject(i).put("s", 0)
        when (game) {
            "ttt" -> {
                val p = JSONArray(); for (i in 0 until minOf(2, ps.length())) p.put(ps.getJSONObject(i).getString("c"))
                hostSet { it.put("game", "ttt").put("phase", "play").put("d", JSONObject().put("b", ".........").put("t", 0).put("p", p).put("w", "")) }
            }
            "tap" -> {
                val rid = System.currentTimeMillis().toString()
                hostSet { it.put("game", "tap").put("phase", "play").put("d", JSONObject().put("rid", rid).put("dur", 10).put("res", JSONObject())) }
                tapTimeoutHost(rid)
            }
            "quiz" -> {
                quizOrder = QUESTIONS.indices.shuffled().take(8)
                hostQuizQuestion(0)
            }
        }
    }

    private fun hostAct(c: String, a: String, v: Int) {
        val st = hostState ?: return
        val d = st.getJSONObject("d")
        when (st.optString("game")) {
            "ttt" -> if (a == "move" && d.getString("w").isEmpty()) {
                val p = d.getJSONArray("p"); val t = d.getInt("t")
                if (p.length() < 2 || p.getString(t) != c) return
                val b = d.getString("b").toCharArray()
                if (v !in 0..8 || b[v] != '.') return
                b[v] = if (t == 0) 'X' else 'O'
                val s = String(b)
                val lines = listOf(0,1,2, 3,4,5, 6,7,8, 0,3,6, 1,4,7, 2,5,8, 0,4,8, 2,4,6)
                var w = ""
                for (k in lines.indices step 3) if (s[lines[k]] != '.' && s[lines[k]] == s[lines[k+1]] && s[lines[k]] == s[lines[k+2]]) w = s[lines[k]].toString()
                if (w.isEmpty() && !s.contains('.')) w = "="
                hostSet {
                    d.put("b", s).put("t", 1 - t).put("w", w)
                    if (w == "X" || w == "O") { val ps = it.getJSONArray("players"); for (i in 0 until ps.length()) if (ps.getJSONObject(i).getString("c") == p.getString(if (w == "X") 0 else 1)) ps.getJSONObject(i).put("s", ps.getJSONObject(i).optInt("s") + 1) }
                    it.put("phase", if (w.isEmpty()) "play" else "over")
                }
            }
            "tap" -> if (a == "taps" && st.getString("phase") == "play") {
                val res = d.getJSONObject("res"); res.put(c, v.coerceIn(0, 400))
                if (res.length() >= st.getJSONArray("players").length()) hostSet { it.put("phase", "over") }
                else hostSet { }
            }
            "quiz" -> if (a == "ans" && d.optInt("r", -1) < 0) {
                val ans = quizAnswers; if (ans.containsKey(c)) return
                ans[c] = v
                if (ans.size >= st.getJSONArray("players").length()) hostQuizReveal()
            }
        }
    }

    private fun tapTimeoutHost(rid: String) {
        main.postDelayed({
            val st = hostState ?: return@postDelayed
            if (st.optString("game") == "tap" && st.getJSONObject("d").optString("rid") == rid && st.getString("phase") == "play") hostSet { it.put("phase", "over") }
        }, 16000)
    }

    private var quizOrder: List<Int> = emptyList()
    private var quizIdx = 0
    private val quizAnswers = HashMap<String, Int>()

    private fun hostQuizQuestion(i: Int) {
        quizIdx = i; quizAnswers.clear()
        if (i >= quizOrder.size) { hostSet { it.put("phase", "over") }; return }
        val q = QUESTIONS[quizOrder[i]]
        hostSet { it.put("game", "quiz").put("phase", "play").put("d", JSONObject().put("i", i).put("n", quizOrder.size).put("q", q.text).put("o", JSONArray(q.options))) }
        val mine = i
        main.postDelayed({ if (hostState?.optString("game") == "quiz" && quizIdx == mine && hostState!!.getJSONObject("d").optInt("r", -1) < 0) hostQuizReveal() }, 14000)
    }

    private fun hostQuizReveal() {
        val q = QUESTIONS[quizOrder[quizIdx]]
        hostSet {
            it.getJSONObject("d").put("r", q.correct)
            val ps = it.getJSONArray("players")
            for (k in 0 until ps.length()) { val p = ps.getJSONObject(k); if (quizAnswers[p.getString("c")] == q.correct) p.put("s", p.optInt("s") + 100) }
        }
        val mine = quizIdx
        main.postDelayed({ if (hostState?.optString("game") == "quiz" && quizIdx == mine) hostQuizQuestion(mine + 1) }, 2800)
    }

    private class Q(val text: String, val options: List<String>, val correct: Int)

    companion object {
        private val QUESTIONS = listOf(
            Q("Сколько планет в Солнечной системе?", listOf("7", "8", "9", "10"), 1),
            Q("Столица Франции?", listOf("Лион", "Марсель", "Париж", "Ницца"), 2),
            Q("Какой газ мы вдыхаем для жизни?", listOf("Кислород", "Азот", "Гелий", "Водород"), 0),
            Q("Сколько сторон у шестиугольника?", listOf("5", "6", "7", "8"), 1),
            Q("Самое большое животное на Земле?", listOf("Слон", "Жираф", "Синий кит", "Белая акула"), 2),
            Q("Сколько будет 12 × 12?", listOf("124", "132", "144", "164"), 2),
            Q("Какой океан самый большой?", listOf("Атлантический", "Тихий", "Индийский", "Северный Ледовитый"), 1),
            Q("Из какого материала делают стекло в основном?", listOf("Песок", "Глина", "Известь", "Железо"), 0),
            Q("Сколько цветов у радуги?", listOf("5", "6", "7", "8"), 2),
            Q("Какое животное известно как «король джунглей»?", listOf("Тигр", "Лев", "Медведь", "Волк"), 1),
            Q("Какая планета ближе всех к Солнцу?", listOf("Венера", "Марс", "Меркурий", "Земля"), 2),
            Q("Сколько минут в часе?", listOf("50", "60", "90", "100"), 1),
            Q("Что любят есть медведи в мультфильмах?", listOf("Мёд", "Сыр", "Морковь", "Орехи"), 0),
            Q("Какой язык программирования назван в честь змеи?", listOf("Java", "Ruby", "Python", "Swift"), 2),
            Q("Сколько континентов на Земле?", listOf("5", "6", "7", "8"), 2),
            Q("Какая птица не умеет летать?", listOf("Пингвин", "Ласточка", "Сова", "Голубь"), 0),
            Q("Чему равен корень из 81?", listOf("7", "8", "9", "11"), 2),
            Q("Какой металл жидкий при комнатной температуре?", listOf("Свинец", "Ртуть", "Олово", "Цинк"), 1),
        )
    }
}
