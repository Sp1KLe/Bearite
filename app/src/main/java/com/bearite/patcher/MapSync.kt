package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Карты-дополнения в мультиплеере: карты с флагом «Делиться» отправляются в публичный ntfy (как чат и мини-игры) по
 * теме комнаты Photon, остальные игроки получают их, чистят от опасного (отдельный мир, привязка к объектам игры,
 * телепорты на чужие карты) и кладут в maps/net_*.map. Нативный мод грузит такие файлы как обычные карты, поэтому
 * платформы, анимации и телепорты внутри карты работают у всех. При выходе из комнаты чужие карты удаляются.
 * Текстуры не передаются (у остальных объекты рисуются цветом).
 */
class MapSync(private val act: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(act.filesDir, "maps")
    private val chat = act.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val prefs = act.getSharedPreferences("mapsync", Context.MODE_PRIVATE)
    private val cid: String get() = chat.getString("cid", null) ?: java.util.UUID.randomUUID().toString().take(8).also { chat.edit().putString("cid", it).apply() }
    private val base get() = (chat.getString("url", ChatOverlay.DEFAULT_URL) ?: ChatOverlay.DEFAULT_URL).ifBlank { ChatOverlay.DEFAULT_URL }

    /** Показывать чужие карты (настройка в «Настройки Bearite»). */
    var acceptRemote: Boolean
        get() = prefs.getBoolean("accept", true)
        set(v) { prefs.edit().putBoolean("accept", v).apply(); if (!v) clearRemote() }

    private var room = ""
    @Volatile private var gen = 0
    private var lastSent = 0L
    private val sentHash = HashMap<String, String>()

    private class Asm(val n: Int) { val parts = arrayOfNulls<String>(n); var got = 0 }
    private val asm = HashMap<String, Asm>()        // ключ: отправитель/карта/хэш
    private val current = HashMap<String, String>() // отправитель/карта -> хэш принятой версии
    private val lastSeen = HashMap<String, Long>()  // отправитель/карта -> время

    fun start() {
        clearRemote()
        main.post(object : Runnable { override fun run() { try { tick() } catch (t: Throwable) { BLog.e("MapSync", t) }; main.postDelayed(this, 1500) } })
    }

    private fun readRoom(): String {
        val raw = try { File(dir, "_room").readText().trim() } catch (_: Throwable) { "" }
        return if (raw == Rooms.OFFLINE) "" else raw
    }

    private fun topic(r: String) = "bearite-maps-" + MessageDigest.getInstance("SHA-256").digest(("bmaps" + r).toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun clearRemote() {
        dir.listFiles { f -> f.name.startsWith("net_") && f.name.endsWith(".map") }?.forEach { it.delete() }
        asm.clear(); current.clear(); lastSeen.clear()
        try { File(dir, "_dirty").writeText("1") } catch (_: Throwable) {}
    }

    private fun tick() {
        val r = readRoom()
        if (r != room) {
            room = r; gen++; sentHash.clear(); lastSent = 0
            clearRemote()
            if (r.isNotEmpty()) listen(r, gen)
        }
        if (r.isEmpty()) return
        val now = System.currentTimeMillis()
        // удаляем карты игроков, которые давно не присылают обновления (вышли)
        val old = lastSeen.filter { now - it.value > 150_000 }.keys
        for (k in old) { lastSeen.remove(k); current.remove(k); File(dir, "net_${k.replace('/', '_')}.map").delete() }
        if (old.isNotEmpty()) touch()
        publish(now)
    }

    private fun touch() { try { File(dir, "_dirty").writeText("1") } catch (_: Throwable) {} }

    // ------------------------------------------------------------------ отправка

    private fun publish(now: Long) {
        val store = MapStore(dir)
        val mine = store.list().filter { it.shared && it.enabled && !it.world }
        val resend = now - lastSent > 60_000
        var any = false
        for (m in mine) {
            val text = MapStore.serialize(m)
            val h = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
            if (!resend && sentHash[m.id] == h) continue
            sentHash[m.id] = h; any = true
            send(room, m.id, h, text)
        }
        if (resend || any) lastSent = now
    }

    private fun send(r: String, id: String, h: String, text: String) {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(text.toByteArray()) }
        val b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        if (b64.length > 120_000) { BLog.i("MapSync: карта «$id» слишком большая для отправки"); return }
        val chunks = b64.chunked(2800)
        Thread {
            for ((i, c) in chunks.withIndex()) {
                try {
                    val j = JSONObject().put("c", cid).put("id", id).put("h", h).put("i", i).put("n", chunks.size).put("d", c)
                    val con = URL("$base/${topic(r)}").openConnection() as HttpURLConnection
                    con.connectTimeout = 8000; con.readTimeout = 8000; con.requestMethod = "POST"; con.doOutput = true
                    con.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    con.outputStream.use { it.write(j.toString().toByteArray()) }
                    con.responseCode; con.disconnect()
                } catch (_: Throwable) {}
            }
        }.start()
    }

    // ------------------------------------------------------------------ приём

    private fun listen(r: String, g: Int) {
        Thread {
            var since = "3m"
            while (gen == g) {
                try {
                    val con = URL("$base/${topic(r)}/json?since=$since").openConnection() as HttpURLConnection
                    con.connectTimeout = 8000; con.readTimeout = 95000
                    if (con.responseCode != 200) throw RuntimeException()
                    con.inputStream.bufferedReader().use { rd ->
                        while (gen == g) {
                            val ln = rd.readLine() ?: break
                            val j = try { JSONObject(ln) } catch (_: Throwable) { continue }
                            if (j.optString("event") != "message") continue
                            if (j.optString("id").isNotEmpty()) since = j.getString("id")
                            val m = try { JSONObject(j.getString("message")) } catch (_: Throwable) { continue }
                            if (m.optString("c") == cid) continue
                            main.post { if (gen == g) onChunk(m) }
                        }
                    }
                    con.disconnect()
                } catch (_: Throwable) { try { Thread.sleep(2500) } catch (_: InterruptedException) { return@Thread } }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun safeId(s: String) = s.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)

    private fun onChunk(m: JSONObject) {
        if (!acceptRemote) return
        val sender = safeId(m.optString("c")); val id = safeId(m.optString("id")); val h = safeId(m.optString("h"))
        val i = m.optInt("i", -1); val n = m.optInt("n", 0); val d = m.optString("d")
        if (sender.isEmpty() || id.isEmpty() || h.isEmpty() || n !in 1..60 || i !in 0 until n || d.length > 4000) return
        val key = "$sender/$id"
        lastSeen[key] = System.currentTimeMillis()
        if (current[key] == h) return
        if (asm.size > 64) asm.clear()
        val a = asm.getOrPut("$key/$h") { Asm(n) }
        if (a.n != n || a.parts[i] != null) return
        a.parts[i] = d; a.got++
        if (a.got < a.n) return
        asm.remove("$key/$h")
        // не больше 8 отправителей и 4 карт у каждого
        val senders = current.keys.map { it.substringBefore('/') }.toSet()
        if (sender !in senders && senders.size >= 8) return
        if (current.keys.count { it.startsWith("$sender/") } >= 4 && key !in current) return
        try {
            val raw = Base64.decode(a.parts.joinToString(""), Base64.NO_WRAP)
            val bo = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(raw)).use { gi ->
                val buf = ByteArray(8192); var total = 0
                while (true) { val k = gi.read(buf); if (k < 0) break; total += k; if (total > 400_000) throw RuntimeException("слишком большая карта"); bo.write(buf, 0, k) }
            }
            val map = MapStore.parse("net_${sender}_$id", bo.toString("UTF-8"))
            val clean = sanitize(map)
            val tmp = File(dir, ".net_${sender}_$id.tmp")
            tmp.writeText(MapStore.serialize(clean))
            tmp.renameTo(File(dir, "net_${sender}_$id.map"))
            current[key] = h
            touch()
            BLog.i("MapSync: принята карта «${clean.name}» от $sender (${clean.objects.size} объектов)")
        } catch (t: Throwable) { BLog.e("MapSync: карта от $sender отклонена", t) }
    }

    /** Чужая карта не может ломать мир, уводить на свои карты и цепляться к объектам игры. */
    private fun sanitize(m: GameMap): GameMap {
        m.world = false; m.shared = false; m.enabled = true; m.cover = ""; m.layer = 0
        m.ox = m.ox.coerceIn(-5000.0, 5000.0); m.oy = m.oy.coerceIn(-5000.0, 5000.0); m.oz = m.oz.coerceIn(-5000.0, 5000.0)
        m.scale = m.scale.coerceIn(0.05, 20.0)
        while (m.objects.size > 400) m.objects.removeAt(m.objects.size - 1)
        for (o in m.objects) {
            o.bind = ""; o.clone = false; o.tex = ""
            if (o.dest != "") o.dest = ""
            o.sx = o.sx.coerceIn(-200.0, 200.0); o.sy = o.sy.coerceIn(-200.0, 200.0); o.sz = o.sz.coerceIn(-200.0, 200.0)
            o.name = o.name.take(40)
        }
        return m
    }
}

/** Служебные значения комнат мультиплеера. */
object Rooms { const val OFFLINE = "offlineMode" }
