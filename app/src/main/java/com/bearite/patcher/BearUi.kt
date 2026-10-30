package com.bearite.patcher

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Вкладка «Мультиплеер»: ник, список серверов Bearite (как в Chicken Gun), комнаты выбранного сервера,
 * создание комнаты с любым лимитом и текущая комната. Ниже — обычная комната игры (Photon) со своим лимитом.
 */
class BearUi(private val act: Activity, private val net: BearNet, private val games: MiniGames) {
    private val ui = GameUi(act)
    private fun dp(v: Int) = ui.dp(v)

    var active = false
    private var again: () -> Unit = {}
    private var selected = ""                        // выбранный сервер
    private val info = HashMap<String, String>()     // url -> строка «имя · N игроков · 40 мс» или ошибка
    private val infoOk = HashMap<String, Boolean>()
    private var rooms: List<BearNet.RoomInfo>? = null
    private var status = ""
    private var busy = false

    init {
        net.listenState { if (active) again() }
    }

    fun render(into: LinearLayout, redraw: () -> Unit) {
        active = true; again = redraw
        into.removeAllViews()
        val wm = ui.matchWrap()

        into.addView(ui.label("Мой ник", 14f), wm.apply { topMargin = dp(6) })
        into.addView(ui.row("Ник в мультиплеере", ui.textInput(net.nick, 150, "как тебя звать") { net.nick = it }), ui.matchWrap().apply { topMargin = dp(4) })

        into.addView(ui.label("Bearite-мультиплеер", 16f), ui.matchWrap().apply { topMargin = dp(16) })
        if (net.connected) renderRoom(into) else renderServers(into)
        if (status.isNotEmpty()) into.addView(ui.label(status, 13f, GameUi.RUST, bold = false), ui.matchWrap().apply { topMargin = dp(6) })

        into.addView(ui.label("Комната игры (Photon)", 16f), ui.matchWrap().apply { topMargin = dp(20) })
        games.renderMulti(into, clear = false)
    }

    // ------------------------------------------------------------------ в комнате

    private fun renderRoom(into: LinearLayout) {
        into.addView(ui.label("Комната «${net.roomName}» · код ${net.room}", 15f, GameUi.GREEN), ui.matchWrap().apply { topMargin = dp(4) })
        into.addView(ui.label("Сервер: ${net.url}", 12f, GameUi.MUTED, bold = false))
        into.addView(ui.label("Игроков: ${net.players.size}" + (if (net.max > 0) " из ${net.max}" else " (без ограничения)") + (if (net.isHost) " · ты хост" else ""), 14f, GameUi.CREAM, bold = false))
        for (p in net.players.sortedBy { it.actor }) {
            val mine = p.cid == net.cid
            into.addView(ui.label("• ${p.nick}" + (if (p.host) "  ★хост" else "") + (if (mine) "  (ты)" else ""), 14f, if (mine) GameUi.GREEN else GameUi.CREAM, bold = mine))
        }
        into.addView(ui.label("Чат комнаты — кнопка 💬. Игроки видят друг друга в списке.", 12f, GameUi.MUTED, bold = false), ui.matchWrap().apply { topMargin = dp(6) })
        into.addView(ui.pill("Выйти из комнаты", danger = true) { net.leave(); status = ""; rooms = null; again() }, ui.matchWrap().apply { topMargin = dp(10) })
    }

    // ------------------------------------------------------------------ серверы и комнаты

    private fun renderServers(into: LinearLayout) {
        val list = net.servers()
        if (list.isEmpty()) into.addView(ui.label("Серверов пока нет. Добавь адрес сервера Bearite — его даёт тот, кто его запустил (node server/bearite-server.js).", 13f, GameUi.MUTED, bold = false), ui.matchWrap().apply { topMargin = dp(4) })
        for (s in list) {
            val on = s.url == selected
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.round(if (on) GameUi.SURFACE3 else GameUi.ROW, 12, if (on) GameUi.CREAM else 0)
                setPadding(dp(14), dp(8), dp(10), dp(8))
                setOnClickListener { selected = s.url; rooms = null; status = ""; refreshServer(s.url); refreshRooms(); again() }
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ui.label(s.name.ifBlank { s.url }, 15f), LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(ui.label("⚙", 18f, GameUi.MUTED).apply { setPadding(dp(10), 0, dp(6), 0); setOnClickListener { editServer(s) } })
            line.addView(head)
            val st = info[s.url]
            if (st == null) { info[s.url] = "проверяю…"; refreshServer(s.url) }
            line.addView(ui.label(info[s.url] ?: "", 12f, if (infoOk[s.url] == false) GameUi.RUST else GameUi.MUTED, bold = false))
            into.addView(line, ui.matchWrap().apply { topMargin = dp(6) })
            if (on) renderRooms(into, s)
        }
        into.addView(ui.pill("+ Добавить сервер") { addServerDialog() }, ui.matchWrap().apply { topMargin = dp(10) })
    }

    private fun renderRooms(into: LinearLayout, s: BearNet.Srv) {
        val rs = rooms
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), 0, 0) }
        if (rs == null) box.addView(ui.label("Загружаю комнаты…", 13f, GameUi.MUTED, bold = false))
        else if (rs.isEmpty()) box.addView(ui.label("Комнат нет — создай свою.", 13f, GameUi.MUTED, bold = false))
        else for (r in rs) {
            val full = r.max > 0 && r.players >= r.max
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ui.round(GameUi.ROW, 10); setPadding(dp(12), dp(6), dp(8), dp(6))
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ui.label("${r.name} · #${r.id}", 14f))
            col.addView(ui.label("${r.players}" + (if (r.max > 0) "/${r.max}" else "") + " игроков" + (if (r.host.isNotEmpty()) " · хост ${r.host}" else "") + (if (r.game.isNotEmpty()) " · идёт игра" else ""), 12f, GameUi.MUTED, bold = false))
            row.addView(col, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.pill(if (full) "Полная" else "Войти", primary = !full, sizeSp = 13f) { if (!full) joinRoom(s.url, r.id) }, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            box.addView(row, ui.matchWrap().apply { topMargin = dp(5) })
        }
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(ui.pill("Создать комнату", primary = true, sizeSp = 13f) { createDialog(s.url) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        btns.addView(ui.pill("Обновить", sizeSp = 13f) { refreshRooms(); again() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8) })
        box.addView(btns, ui.matchWrap().apply { topMargin = dp(8) })
        box.addView(ui.pill("Войти по коду комнаты", sizeSp = 13f) { codeDialog(s.url) }, ui.matchWrap().apply { topMargin = dp(8) })
        into.addView(box, ui.matchWrap())
    }

    private fun refreshServer(url: String) {
        net.ping(url) { i, err ->
            if (i == null) { info[url] = "не отвечает: $err"; infoOk[url] = false }
            else { info[url] = (i.name + " · комнат ${i.rooms} · игроков ${i.players} · ${i.pingMs} мс" + (if (i.motd.isNotBlank()) "\n" + i.motd else "")); infoOk[url] = true }
            if (active) again()
        }
    }

    private fun refreshRooms() {
        val u = selected; if (u.isEmpty()) return
        net.rooms(u) { l, err ->
            if (u != selected) return@rooms
            rooms = l ?: emptyList(); if (l == null) status = "Не получилось загрузить комнаты: $err"
            if (active) again()
        }
    }

    private fun joinRoom(url: String, id: String) {
        if (busy) return
        if (net.nick.isBlank()) { status = "Сначала впиши ник сверху."; again(); return }
        busy = true; status = "Захожу…"; again()
        net.join(url, id) { err -> busy = false; status = if (err.isEmpty()) "" else "Не вошёл: $err"; again() }
    }

    private fun codeDialog(url: String) {
        var code = ""
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.row("Код", ui.textInput("", 140, "например 788006") { code = it.filter(Char::isDigit).take(8) }))
        ui.dialog("Вход по коду", col, listOf(GameUi.Btn("Отмена"), GameUi.Btn("Войти", primary = true) { if (code.isNotEmpty()) joinRoom(url, code) })).show()
    }

    private fun createDialog(url: String) {
        if (net.nick.isBlank()) { status = "Сначала впиши ник сверху."; again(); return }
        var name = "${net.nick}: игра"; var max = ""
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.row("Название", ui.textInput(name, 170, "название комнаты") { name = it }))
        col.addView(ui.row("Лимит игроков", ui.textInput(max, 120, "пусто = без лимита") { max = it.filter(Char::isDigit).take(3) }), ui.matchWrap().apply { topMargin = dp(6) })
        col.addView(ui.label("До 255. Пусто или 0 — без ограничения.", 12f, GameUi.MUTED, bold = false))
        ui.dialog("Новая комната", col, listOf(GameUi.Btn("Отмена"), GameUi.Btn("Создать", primary = true) {
            busy = true; status = "Создаю…"; again()
            net.create(url, name.trim(), max.toIntOrNull() ?: 0) { err -> busy = false; status = if (err.isEmpty()) "" else "Не создал: $err"; again() }
        })).show()
    }

    private fun addServerDialog() {
        var url = ""; var name = ""
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.label("Адрес сервера", 12f, GameUi.MUTED))
        col.addView(ui.textInput("", 300, "например bearite.example.com") { url = it })
        col.addView(ui.label("Название (необязательно)", 12f, GameUi.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(ui.textInput("", 300, "мой сервер") { name = it })
        ui.dialog("Добавить сервер", col, listOf(GameUi.Btn("Отмена"), GameUi.Btn("Добавить", primary = true) {
            if (net.addServer(url, name.trim())) { val n = net.normalize(url); selected = n; info.remove(n); rooms = null; refreshRooms(); status = "" } else status = "Такой сервер уже есть или адрес пустой."
            again()
        })).show()
    }

    private fun editServer(s: BearNet.Srv) {
        var name = s.name; var key = net.key(s.url)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.label(s.url, 12f, GameUi.MUTED, bold = false))
        col.addView(ui.label("Название", 12f, GameUi.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(ui.textInput(name, 300, "название") { name = it })
        col.addView(ui.label("Ключ сервера (если он закрытый)", 12f, GameUi.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(ui.textInput(key, 300, "необязательно") { key = it })
        ui.dialog("Сервер", col, listOf(
            GameUi.Btn("Удалить", danger = true) { net.removeServer(s.url); if (selected == s.url) { selected = ""; rooms = null }; again() },
            GameUi.Btn("Сохранить", primary = true) { net.renameServer(s.url, name.trim()); net.setKey(s.url, key); info.remove(s.url); again() },
        )).show()
    }
}
