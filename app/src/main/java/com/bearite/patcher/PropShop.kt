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
    class Entry(val id: String, val name: String, val author: String, val file: String, val size: Long, val acc: Acc? = null)

    /** Аксессуар для гардероба: слот (head, face, back, hand_l, hand_r, foot_l, foot_r), смещение, масштаб, поворот, цвет. */
    class Acc(val slot: String, val off: FloatArray, val scale: Float, val rot: FloatArray, val color: Int, val fit: Boolean = false) {
        fun toLine(): String = listOf(slot, off[0], off[1], off[2], scale, rot[0], rot[1], rot[2], "%06X".format(color and 0xFFFFFF), if (fit) "1" else "0").joinToString("|")
        companion object {
            fun fromLine(l: String): Acc? = try {
                val p = l.trim().split('|')
                Acc(p[0], floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()), p[4].toFloat(), floatArrayOf(p[5].toFloat(), p[6].toFloat(), p[7].toFloat()), p[8].toInt(16), p.getOrNull(9) == "1")
            } catch (_: Throwable) { null }
            fun fromJson(o: JSONObject): Acc? {
                val slot = o.optString("slot")
                if (slot !in SLOTS) return null
                fun v3(k: String): FloatArray { val a = o.optJSONArray(k); return floatArrayOf(a?.optDouble(0, 0.0)?.toFloat() ?: 0f, a?.optDouble(1, 0.0)?.toFloat() ?: 0f, a?.optDouble(2, 0.0)?.toFloat() ?: 0f) }
                val col = try { o.optString("color", "FFFFFF").trimStart('#').toInt(16) } catch (_: Throwable) { 0xFFFFFF }
                return Acc(slot, v3("offset"), o.optDouble("scale", 1.0).toFloat(), v3("rot"), col, o.optBoolean("fit", false))
            }
        }
    }

    /** Аксессуар установленной модели (сохраняется при скачивании) или null. */
    fun accOf(id: String): Acc? = try { File(propsDir, "$id.acc").takeIf { it.isFile }?.let { Acc.fromLine(it.readText()) } } catch (_: Throwable) { null }
    fun saveAcc(id: String, a: Acc) { try { File(propsDir, "$id.acc").writeText(a.toLine()) } catch (_: Throwable) {} }

    private val main = Handler(Looper.getMainLooper())
    private val propsDir get() = File(dir, "props").also { it.mkdirs() }

    /** Установленные пропы: id → имя. */
    fun installed(): List<Pair<String, String>> =
        (propsDir.listFiles { f -> f.isFile && f.name.endsWith(".mesh") } ?: emptyArray()).map { f ->
            val id = f.name.removeSuffix(".mesh")
            val name = try { File(propsDir, "$id.name").readText().trim() } catch (_: Throwable) { id }
            id to name.ifEmpty { id }
        }.sortedBy { it.second.lowercase() }

    /** Анимация из модели: строки t dx dy dz rx ry rz sx sy sz; null, если её нет. */
    fun loadAnim(id: String): List<DoubleArray>? = try {
        val f = File(propsDir, "$id.anim")
        if (!f.isFile) null else f.readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(' ').map { it.toDouble() }.toDoubleArray() }.takeIf { it.size >= 2 && it.all { r -> r.size == 10 } }
    } catch (_: Throwable) { null }

    fun isInstalled(id: String) = File(propsDir, "$id.mesh").isFile

    fun remove(id: String) {
        File(propsDir, "$id.mesh").delete(); File(propsDir, "$id.name").delete(); File(propsDir, "$id.acc").delete(); File(propsDir, "$id.anim").delete()
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
    fun install(id: String, name: String, glb: ByteArray, texture: ByteArray? = null): String? = try {
        val m = Glb.parse(glb)
        val nv = m.v.size / 3
        val buf = ByteBuffer.allocate(8 + nv * 12 + nv * 8 + m.idx.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(nv).putInt(m.idx.size)
        for (f in m.v) buf.putFloat(f)
        for (f in m.uv) buf.putFloat(f)
        for (i in m.idx) buf.putInt(i)
        (texture ?: m.texture)?.let { store.saveTextureBytes("cp_$id", it) }
        File(propsDir, "$id.anim").let { f -> if (m.anim != null) f.writeText(m.anim.joinToString("\n") { r -> r.joinToString(" ") { v -> "%.4f".format(java.util.Locale.US, v) } }) else f.delete() }
        File(propsDir, "$id.name").writeText(name)
        File(propsDir, "$id.mesh").writeBytes(buf.array())   // последним: по нему пропу считается установленным
        null
    } catch (t: Throwable) { t.message ?: "не удалось разобрать файл" }

    /**
     * Ставит пропы из .zip: внутри index.json и модели .glb. index.json: {"props":[{"id","name","author","model":"x.glb","texture":"t.png"}]}
     * или один пропс без обёртки; без index.json берётся первая .glb. Возвращает (сколько установлено, ошибка).
     */
    fun installZip(zip: ByteArray): Pair<Int, String?> {
        val files = HashMap<String, ByteArray>()
        try {
            var total = 0L
            java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val b = z.readBytes(); total += b.size
                    if (total > 60_000_000) return 0 to "архив слишком большой"
                    files[e.name.replace('\\', '/').trimStart('/')] = b
                }
            }
        } catch (t: Throwable) { return 0 to "это не zip-архив" }
        fun find(path: String?): ByteArray? {
            if (path.isNullOrBlank()) return null
            val p = path.replace('\\', '/').trimStart('/')
            return files[p] ?: files.entries.firstOrNull { it.key.endsWith("/$p") || it.key.substringAfterLast('/') == p.substringAfterLast('/') }?.value
        }
        val idxBytes = files.entries.firstOrNull { it.key == "index.json" || it.key.endsWith("/index.json") }?.value
        val metas = ArrayList<JSONObject>()
        if (idxBytes != null) {
            try {
                val j = JSONObject(String(idxBytes, Charsets.UTF_8))
                val arr = j.optJSONArray("props")
                if (arr != null) for (i in 0 until arr.length()) metas.add(arr.getJSONObject(i)) else metas.add(j)
            } catch (t: Throwable) { return 0 to "index.json не читается: ${t.message}" }
        } else {
            val g = files.keys.firstOrNull { it.endsWith(".glb", true) } ?: return 0 to "в архиве нет .glb и index.json"
            metas.add(JSONObject().put("model", g))
        }
        var ok = 0; var lastErr: String? = null
        for (m in metas) {
            val modelPath = m.optString("model").ifEmpty { files.keys.firstOrNull { it.endsWith(".glb", true) } ?: "" }
            val glb = find(modelPath)
            if (glb == null) { lastErr = "в архиве нет модели «$modelPath»"; continue }
            val base = modelPath.substringAfterLast('/').removeSuffix(".glb").removeSuffix(".GLB")
            val name = m.optString("name").ifEmpty { base }
            val id = (m.optString("id").ifEmpty { base }).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifEmpty { "prop" + System.currentTimeMillis() % 100000 }
            val err = install(id, name, glb, find(m.optString("texture")))
            if (err == null) { ok++; Acc.fromJson(m)?.let { saveAcc(id, it) }; find(m.optString("icon"))?.let { ic -> try { store.saveTextureBytes("ico_$id", ic) } catch (_: Throwable) {} } } else lastErr = "$name: $err"
        }
        return ok to (if (ok == 0) lastErr ?: "ничего не установилось" else null)
    }

    /** Файл, выбранный пользователем: .zip или .glb. */
    fun installFile(bytes: ByteArray, fileName: String): Pair<Int, String?> {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return installZip(bytes)
        val base = fileName.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifEmpty { "prop" }
        val err = install(base, fileName.substringBeforeLast('.').ifEmpty { base }, bytes)
        return (if (err == null) 1 else 0) to err
    }

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
    fun fetchIndex(base: String = BASE, cb: (List<Entry>?, String?) -> Unit) {
        Thread {
            try {
                val j = JSONObject(String(get("$base/index.json", 1_000_000), Charsets.UTF_8))
                val arr = j.optJSONArray("props")
                val list = ArrayList<Entry>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val file = o.optString("file")
                    if (file.isEmpty() || file.contains('/')) continue
                    val id = o.optString("id").ifEmpty { file.removeSuffix(".glb").removeSuffix(".zip") }.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
                    if (id.isEmpty()) continue
                    list.add(Entry(id, o.optString("name", id), o.optString("author"), file, o.optLong("size", 0), Acc.fromJson(o)))
                }
                main.post { cb(list, null) }
            } catch (t: Throwable) { main.post { cb(null, t.message ?: "нет связи") } }
        }.start()
    }

    /**
     * Синхронизация с релизом «accessories»: в нём лежат только .zip (внутри index.json и модель .glb).
     * Скачиваем новые и изменившиеся архивы и ставим; колбэк в главном потоке: (сколько обработано, ошибка).
     */
    fun syncAccessories(cb: (Int, String?) -> Unit) {
        Thread {
            var done = 0; var err: String? = null
            try {
                val j = JSONObject(String(get(ACC_API, 2_000_000), Charsets.UTF_8))
                val assets = j.optJSONArray("assets")
                val stampFile = File(propsDir, ".acc_stamps")
                val stamps = HashMap<String, String>()
                try { if (stampFile.isFile) stampFile.readLines().forEach { l -> val k = l.indexOf('='); if (k > 0) stamps[l.substring(0, k)] = l.substring(k + 1) } } catch (_: Throwable) {}
                // формат файла .acc менялся (добавилось поле fit): после смены формата перекачиваем все архивы, иначе у уже скачанных вещей новые поля не появятся
                if (stamps["_fmt"] != ACC_FMT) { stamps.clear(); stamps["_fmt"] = ACC_FMT }
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (!name.endsWith(".zip", true)) continue
                    val stamp = a.optLong("size").toString() + "/" + a.optString("updated_at")
                    if (stamps[name] == stamp) continue
                    val url = a.optString("browser_download_url").ifEmpty { "$ACC_BASE/$name" }
                    try {
                        val r = installZip(get(url, 40_000_000))
                        if (r.second == null) { stamps[name] = stamp; done += r.first } else err = "$name: ${r.second}"
                    } catch (t: Throwable) { err = "$name: ${t.message}" }
                }
                try { propsDir.mkdirs(); stampFile.writeText(stamps.entries.joinToString("\n") { it.key + "=" + it.value }) } catch (_: Throwable) {}
            } catch (t: Throwable) { err = t.message ?: "нет связи" }
            main.post { cb(done, err) }
        }.start()
    }

    fun download(e: Entry, base: String = BASE, cb: (String?) -> Unit) {
        Thread {
            val err = try {
                val b = get("$base/${e.file}", 40_000_000)
                (if (e.file.endsWith(".zip", true)) installZip(b).second else install(e.id, e.name, b)).also { r -> if (r == null && e.acc != null) saveAcc(e.id, e.acc) }
            } catch (t: Throwable) { t.message ?: "не скачалось" }
            main.post { cb(err) }
        }.start()
    }

    companion object {
        val SLOTS = listOf("head", "face", "back", "body", "hand_l", "hand_r", "foot_l", "foot_r")
        /** Версия формата сохранённых параметров аксессуаров (.acc): 2 — добавлено поле fit. */
        const val ACC_FMT = "2"
        /** Отдельный релиз «accessories»: index.json со списком и .zip-архивы (внутри index.json и .glb) или просто .glb. */
        const val ACC_API = "https://api.github.com/repos/Sp1KLe/Bearite/releases/tags/accessories"
        const val ACC_BASE = "https://github.com/Sp1KLe/Bearite/releases/download/accessories"
        const val BASE = "https://github.com/Sp1KLe/Bearite/releases/download/props"
    }
}
