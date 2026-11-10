package com.bearite.patcher

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Пропы сообщества: 3D-модели .glb. Магазин — релиз «props» в репозитории проекта: в нём лежит index.json и сами .glb.
 * Скачанная модель переводится в простой файл maps/props/<id>.mesh (его читает мод), текстура кладётся в maps/textures/cp_<id>.
 */
class PropShop(private val dir: File, private val store: MapStore) {
    class Entry(val id: String, val name: String, val author: String, val file: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())
    private val propsDir get() = File(dir, "props").also { it.mkdirs() }

    /** Установленные пропы: id → имя. */
    fun installed(): List<Pair<String, String>> =
        (propsDir.listFiles { f -> f.isFile && f.name.endsWith(".mesh") } ?: emptyArray()).map { f ->
            val id = f.name.removeSuffix(".mesh")
            val name = try { File(propsDir, "$id.name").readText().trim() } catch (_: Throwable) { id }
            id to name.ifEmpty { id }
        }.sortedBy { it.second.lowercase() }

    fun isInstalled(id: String) = File(propsDir, "$id.mesh").isFile

    fun remove(id: String) {
        File(propsDir, "$id.mesh").delete(); File(propsDir, "$id.name").delete()
        store.deleteTexture("cp_$id")
    }

    /** Меш пропа для предпросмотра в редакторе: вершины, uv, индексы. */
    fun loadMesh(id: String): Triple<FloatArray, FloatArray, IntArray>? = try {
        val bb = ByteBuffer.wrap(File(propsDir, "$id.mesh").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val nv = bb.getInt(); val ni = bb.getInt()
        val v = FloatArray(nv * 3); bb.asFloatBuffer().get(v); bb.position(bb.position() + nv * 12)
        val uv = FloatArray(nv * 2); bb.asFloatBuffer().get(uv); bb.position(bb.position() + nv * 8)
        val ix = IntArray(ni); bb.asIntBuffer().get(ix)
        Triple(v, uv, ix)
    } catch (_: Throwable) { null }

    /** Ставит модель из .glb (скачанного или выбранного из файла). */
    fun install(id: String, name: String, glb: ByteArray): String? = try {
        val m = Glb.parse(glb)
        val nv = m.v.size / 3
        val buf = ByteBuffer.allocate(8 + nv * 12 + nv * 8 + m.idx.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(nv).putInt(m.idx.size)
        for (f in m.v) buf.putFloat(f)
        for (f in m.uv) buf.putFloat(f)
        for (i in m.idx) buf.putInt(i)
        m.texture?.let { store.saveTextureBytes("cp_$id", it) }
        File(propsDir, "$id.name").writeText(name)
        File(propsDir, "$id.mesh").writeBytes(buf.array())   // последним: по нему пропу считается установленным
        null
    } catch (t: Throwable) { t.message ?: "не удалось разобрать файл" }

    private fun get(url: String, max: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Bearite")
        if (c.responseCode != 200) throw IllegalStateException("сервер ответил ${c.responseCode}")
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { ins -> val b = ByteArray(16384); while (true) { val n = ins.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > max) throw IllegalStateException("файл слишком большой") } }
        return out.toByteArray()
    }

    /** Список магазина. Колбэк приходит в главном потоке: (список, текст ошибки). */
    fun fetchIndex(cb: (List<Entry>?, String?) -> Unit) {
        Thread {
            try {
                val j = JSONObject(String(get("$BASE/index.json", 1_000_000), Charsets.UTF_8))
                val arr = j.optJSONArray("props")
                val list = ArrayList<Entry>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val file = o.optString("file")
                    if (file.isEmpty() || file.contains('/')) continue
                    val id = o.optString("id").ifEmpty { file.removeSuffix(".glb") }.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
                    if (id.isEmpty()) continue
                    list.add(Entry(id, o.optString("name", id), o.optString("author"), file, o.optLong("size", 0)))
                }
                main.post { cb(list, null) }
            } catch (t: Throwable) { main.post { cb(null, t.message ?: "нет связи") } }
        }.start()
    }

    fun download(e: Entry, cb: (String?) -> Unit) {
        Thread {
            val err = try { install(e.id, e.name, get("$BASE/${e.file}", 20_000_000)) } catch (t: Throwable) { t.message ?: "не скачалось" }
            main.post { cb(err) }
        }.start()
    }

    companion object {
        const val BASE = "https://github.com/Sp1KLe/Bearite/releases/download/props"
    }
}
