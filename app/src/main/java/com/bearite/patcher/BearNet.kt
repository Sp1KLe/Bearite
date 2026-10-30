package com.bearite.patcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Клиент мультиплеера Bearite: список серверов, комнаты, игроки и чат комнаты.
 * Один экземпляр на процесс игры (BearNet.of). Все колбэки приходят в главном потоке.
 * Протокол — server/bearite-server.js (HTTP + JSON, длинный опрос /poll).
 */
class BearNet private constructor(ctx: Context) {
    class Srv(val url: String, val name: String)
    class Pl(val cid: String, val nick: String, val actor: Int, val host: Boolean)
    class RoomInfo(val id: String, val name: String, val players: Int, val max: Int, val host: String, val game: String)
    class ServerInfo(val name: String, val motd: String, val rooms: Int, val players: Int, val pingMs: Int)

    private val prefs = ctx.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val net = ctx.getSharedPreferences("bearnet", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val cid: String get() = prefs.getString("cid", null) ?: java.util.UUID.randomUUID().toString().take(8).also { prefs.edit().putString("cid", it).apply() }
    var nick: String
        get() = prefs.getString("nick", "") ?: ""
        set(v) { prefs.edit().putString("nick", v.trim().take(24)).apply() }

    // ------------------------------------------------------------------ состояние
    @Volatile var url = ""; private set
    @Volatile var room = ""; private set
    var roomName = ""; private set
    var players: List<Pl> = emptyList(); private set
    var max = 0; private set
    var hostCid = ""; private set
    var myActor = 0; private set
    val connected: Boolean get() = room.isNotEmpty()
    val isHost: Boolean get() = hostCid == cid
    @Volatile private var gen = 0
    private var after = 0L

    private val stateL = ArrayList<() -> Unit>()
    /** Подписка на смену комнаты/состава игроков (вызывается в главном потоке). */
    fun listenState(f: () -> Unit) { stateL.add(f) }
    private fun fireState() { for (f in stateL.toList()) try { f() } catch (_: Throwable) {} }
    var onChat: ((who: String, text: String, mine: Boolean) -> Unit)? = null
    var onSys: ((String) -> Unit)? = null
    var onData: ((kind: String, from: Pl?, data: JSONObject?) -> Unit)? = null

    // ------------------------------------------------------------------ список серверов
    fun servers(): List<Srv> {
        val a = try { JSONArray(net.getString("servers", "[]")) } catch (_: Throwable) { JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); Srv(o.getString("url"), o.optString("name")) }
    }
    private fun saveServers(l: List<Srv>) {
        val a = JSONArray(); for (s in l) a.put(JSONObject().put("url", s.url).put("name", s.name))
        net.edit().putString("servers", a.toString()).apply()
    }
    fun normalize(u: String): String {
        var s = u.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = (if (s.contains("localhost") || Regex("^[0-9.]+(:\\d+)?$").matches(s)) "http://" else "https://") + s
        return s
    }
    fun addServer(u: String, name: String = ""): Boolean {
        val n = normalize(u); if (n.isEmpty()) return false
        val l = servers().toMutableList()
        if (l.any { it.url == n }) return false
        l.add(Srv(n, name)); saveServers(l); return true
    }
    fun removeServer(u: String) = saveServers(servers().filter { it.url != u })
    fun renameServer(u: String, name: String) = saveServers(servers().map { if (it.url == u) Srv(it.url, name) else it })

    // ------------------------------------------------------------------ http
    private fun call(base: String, path: String, post: JSONObject?, timeoutMs: Int = 8000): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 25000; c.readTimeout = maxOf(timeoutMs, 25000)  // бесплатный хостинг «просыпается» до минуты
        c.setRequestProperty("Accept", "application/json")
        val key = net.getString("key:$base", "") ?: ""
        if (key.isNotEmpty()) c.setRequestProperty("X-Key", key)
        if (post != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(post.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        val j = try { JSONObject(text) } catch (_: Throwable) { JSONObject() }
        if (code !in 200..299) throw RuntimeException(j.optString("error", "код $code"))
        return j
    }
    fun setKey(base: String, key: String) { net.edit().putString("key:$base", key.trim()).apply() }
    fun key(base: String) = net.getString("key:$base", "") ?: ""

    private fun bg(f: () -> Unit) { Thread { try { f() } catch (_: Throwable) {} }.apply { isDaemon = true }.start() }

    fun ping(base: String, cb: (ServerInfo?, String) -> Unit) = bg {
        val t0 = System.currentTimeMillis()
        try {
            val j = call(base, "/info", null, 6000)
            val info = ServerInfo(j.optString("name", base), j.optString("motd"), j.optInt("rooms"), j.optInt("players"), (System.currentTimeMillis() - t0).toInt())
            main.post { cb(info, "") }
        } catch (t: Throwable) { main.post { cb(null, t.message ?: "нет связи") } }
    }

    fun rooms(base: String, cb: (List<RoomInfo>?, String) -> Unit) = bg {
        try {
            val a = call(base, "/rooms", null).getJSONArray("rooms")
            val l = (0 until a.length()).map { val o = a.getJSONObject(it); RoomInfo(o.getString("id"), o.optString("name"), o.optInt("players"), o.optInt("max"), o.optString("host"), o.optString("game")) }
            main.post { cb(l, "") }
        } catch (t: Throwable) { main.post { cb(null, t.message ?: "нет связи") } }
    }

    fun create(base: String, name: String, max: Int, cb: (String) -> Unit) = bg {
        try {
            val j = call(base, "/create", JSONObject().put("name", name).put("max", max).put("nick", nick).put("cid", cid))
            enter(base, j)
            main.post { cb("") }
        } catch (t: Throwable) { main.post { cb(t.message ?: "нет связи") } }
    }

    fun join(base: String, roomId: String, cb: (String) -> Unit) = bg {
        try {
            val j = call(base, "/join", JSONObject().put("room", roomId).put("nick", nick).put("cid", cid))
            enter(base, j)
            main.post { cb("") }
        } catch (t: Throwable) { main.post { cb(t.message ?: "нет связи") } }
    }

    private fun parsePlayers(a: JSONArray?): List<Pl> = if (a == null) emptyList() else (0 until a.length()).map { val o = a.getJSONObject(it); Pl(o.getString("cid"), o.optString("nick"), o.optInt("actor"), o.optBoolean("host")) }

    private fun enter(base: String, j: JSONObject) {
        leaveQuiet()
        val g = ++gen
        url = base; room = j.getString("room"); roomName = j.optString("name"); max = j.optInt("max")
        players = parsePlayers(j.optJSONArray("players")); myActor = j.optInt("actor")
        hostCid = players.firstOrNull { it.host }?.cid ?: ""
        after = j.optLong("last", 0L)
        main.post { fireState() }
        Thread { pollLoop(g) }.apply { isDaemon = true; name = "bearnet-poll" }.start()
    }

    private fun leaveQuiet() {
        if (room.isEmpty()) return
        val b = url; val r = room
        gen++; room = ""; players = emptyList(); hostCid = ""
        bg { call(b, "/leave", JSONObject().put("room", r).put("cid", cid), 4000) }
    }

    fun leave() { leaveQuiet(); main.post { fireState() } }

    private fun pollLoop(g: Int) {
        var fails = 0
        while (gen == g) {
            try {
                val b = url; val r = room
                val j = call(b, "/poll?room=${URLEncoder.encode(r, "UTF-8")}&cid=${URLEncoder.encode(cid, "UTF-8")}&after=$after&wait=20", null, 35000)
                if (gen != g) return
                if (fails > 0) { fails = 0; main.post { onSys?.invoke("Связь с сервером восстановлена.") } }
                val pl = parsePlayers(j.optJSONArray("players"))
                val hc = j.optString("host")
                val mx = j.optInt("max", max)
                val msgs = j.getJSONArray("msgs")
                after = maxOf(after, j.optLong("last", after))
                main.post {
                    if (gen != g) return@post
                    val changed = pl.map { it.cid } != players.map { it.cid } || hc != hostCid || mx != max
                    players = pl; hostCid = hc; max = mx
                    for (i in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(i)
                        when (val k = m.optString("k")) {
                            "chat" -> onChat?.invoke(m.optString("name"), m.optString("text"), m.optString("cid") == cid)
                            "sys" -> onSys?.invoke(m.optString("text"))
                            else -> if (m.optString("cid") != cid) onData?.invoke(k, pl.firstOrNull { it.cid == m.optString("cid") }, m.optJSONObject("data"))
                        }
                    }
                    if (changed) fireState()
                }
            } catch (t: Throwable) {
                if (gen != g) return
                val msg = t.message ?: ""
                if (msg.contains("не в комнате") || msg.contains("комнаты нет")) {
                    main.post { if (gen == g) { onSys?.invoke("Тебя убрали из комнаты (нет связи или комнату закрыли)."); room = ""; players = emptyList(); gen++; fireState() } }
                    return
                }
                if (fails++ == 0) main.post { onSys?.invoke("Нет связи с сервером, пробую снова…") }
                try { Thread.sleep(minOf(8000L, 1000L * fails)) } catch (_: InterruptedException) { return }
            }
        }
    }

    fun sendChat(text: String, cb: (String) -> Unit = {}) {
        val b = url; val r = room; if (r.isEmpty()) return
        bg {
            try { call(b, "/send", JSONObject().put("room", r).put("cid", cid).put("k", "chat").put("text", text), 6000); main.post { cb("") } }
            catch (t: Throwable) { main.post { cb(t.message ?: "нет связи") } }
        }
    }

    /** Служебное событие комнаты (позиции, мини-игры и т.п.): получат остальные игроки. */
    fun sendData(kind: String, data: JSONObject) {
        val b = url; val r = room; if (r.isEmpty()) return
        bg { call(b, "/send", JSONObject().put("room", r).put("cid", cid).put("k", kind).put("data", data), 6000) }
    }

    companion object {
        @Volatile private var inst: BearNet? = null
        fun of(ctx: Context): BearNet = inst ?: synchronized(this) { inst ?: BearNet(ctx.applicationContext).also { inst = it } }
    }
}
