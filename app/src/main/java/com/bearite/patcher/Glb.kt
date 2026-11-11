package com.bearite.patcher

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Читает .glb (бинарный glTF 2.0): объединяет все меши сцены в один, применяет преобразования узлов,
 * переводит в систему координат Unity (X зеркально, обход треугольников переворачивается, V текстуры снизу вверх).
 * Из материалов берётся только первая картинка цвета (baseColorTexture): на всю модель.
 */
object Glb {
    /** anim: ключи анимации одного узла как смещения от позы покоя: t, dx dy dz, поворот x y z (градусы), масштаб x y z. */
    class Model(val v: FloatArray, val uv: FloatArray, val idx: IntArray, val texture: ByteArray?, val anim: List<DoubleArray>? = null)

    private const val MAX_VERTS = 65000

    fun parse(data: ByteArray): Model {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 20 || bb.getInt(0) != 0x46546C67) throw IllegalArgumentException("это не .glb")
        var pos = 12
        var json: JSONObject? = null
        var bin: ByteArray? = null
        while (pos + 8 <= data.size) {
            val len = bb.getInt(pos); val type = bb.getInt(pos + 4)
            val start = pos + 8
            if (len < 0 || start + len > data.size) break
            if (type == 0x4E4F534A) json = JSONObject(String(data, start, len, Charsets.UTF_8))
            else if (type == 0x004E4942 && bin == null) bin = data.copyOfRange(start, start + len)
            pos = start + ((len + 3) and 3.inv())
        }
        val j = json ?: throw IllegalArgumentException("в файле нет описания модели")
        val b = bin ?: throw IllegalArgumentException("в файле нет данных (нужен .glb, а не .gltf с внешними файлами)")

        val views = j.optJSONArray("bufferViews") ?: JSONArray()
        val accs = j.optJSONArray("accessors") ?: JSONArray()
        val meshes = j.optJSONArray("meshes") ?: JSONArray()
        val nodes = j.optJSONArray("nodes") ?: JSONArray()
        val mats = j.optJSONArray("materials") ?: JSONArray()

        val vs = ArrayList<Float>(); val us = ArrayList<Float>(); val ix = ArrayList<Int>()
        var texIdx = -1

        fun readAcc(ai: Int): Triple<FloatArray, Int, Int>? {
            val a = accs.optJSONObject(ai) ?: return null
            val count = a.getInt("count")
            val comps = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> return null }
            val ct = a.getInt("componentType")
            val norm = a.optBoolean("normalized", false)
            val bv = views.optJSONObject(a.optInt("bufferView", -1)) ?: return null
            val csz = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val stride = bv.optInt("byteStride", 0).let { if (it == 0) csz * comps else it }
            val base = bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val out = FloatArray(count * comps)
            val src = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) for (c in 0 until comps) {
                val p = base + i * stride + c * csz
                out[i * comps + c] = when (ct) {
                    5126 -> src.getFloat(p)
                    5125 -> (src.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    5123 -> (src.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                    5121 -> (src.get(p).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                    5122 -> src.getShort(p).toFloat().let { if (norm) maxOf(it / 32767f, -1f) else it }
                    else -> src.get(p).toFloat().let { if (norm) maxOf(it / 127f, -1f) else it }
                }
            }
            return Triple(out, count, comps)
        }

        fun mul(a: FloatArray, c: FloatArray): FloatArray {   // столбцовые 4x4
            val r = FloatArray(16)
            for (col in 0 until 4) for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * c[col * 4 + k]
                r[col * 4 + row] = s
            }
            return r
        }
        fun local(n: JSONObject): FloatArray {
            n.optJSONArray("matrix")?.let { m -> if (m.length() == 16) return FloatArray(16) { m.getDouble(it).toFloat() } }
            val t = n.optJSONArray("translation"); val r = n.optJSONArray("rotation"); val s = n.optJSONArray("scale")
            val tx = t?.getDouble(0)?.toFloat() ?: 0f; val ty = t?.getDouble(1)?.toFloat() ?: 0f; val tz = t?.getDouble(2)?.toFloat() ?: 0f
            val qx = r?.getDouble(0)?.toFloat() ?: 0f; val qy = r?.getDouble(1)?.toFloat() ?: 0f; val qz = r?.getDouble(2)?.toFloat() ?: 0f; val qw = r?.getDouble(3)?.toFloat() ?: 1f
            val sx = s?.getDouble(0)?.toFloat() ?: 1f; val sy = s?.getDouble(1)?.toFloat() ?: 1f; val sz = s?.getDouble(2)?.toFloat() ?: 1f
            val m = FloatArray(16)
            m[0] = (1 - 2 * (qy * qy + qz * qz)) * sx; m[1] = (2 * (qx * qy + qz * qw)) * sx; m[2] = (2 * (qx * qz - qy * qw)) * sx
            m[4] = (2 * (qx * qy - qz * qw)) * sy; m[5] = (1 - 2 * (qx * qx + qz * qz)) * sy; m[6] = (2 * (qy * qz + qx * qw)) * sy
            m[8] = (2 * (qx * qz + qy * qw)) * sz; m[9] = (2 * (qy * qz - qx * qw)) * sz; m[10] = (1 - 2 * (qx * qx + qy * qy)) * sz
            m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
            return m
        }

        fun addMesh(mi: Int, w: FloatArray) {
            val prims = meshes.optJSONObject(mi)?.optJSONArray("primitives") ?: return
            for (pi in 0 until prims.length()) {
                val p = prims.getJSONObject(pi)
                if (p.optInt("mode", 4) != 4) continue
                val attrs = p.optJSONObject("attributes") ?: continue
                val pos = readAcc(attrs.optInt("POSITION", -1)) ?: continue
                val n = pos.second
                val uvA = if (attrs.has("TEXCOORD_0")) readAcc(attrs.getInt("TEXCOORD_0")) else null
                val base = vs.size / 3
                if (base + n > MAX_VERTS) throw IllegalArgumentException("слишком сложная модель (больше $MAX_VERTS вершин)")
                for (i in 0 until n) {
                    val x = pos.first[i * 3]; val y = pos.first[i * 3 + 1]; val z = pos.first[i * 3 + 2]
                    vs.add(-(w[0] * x + w[4] * y + w[8] * z + w[12])); vs.add(w[1] * x + w[5] * y + w[9] * z + w[13]); vs.add(w[2] * x + w[6] * y + w[10] * z + w[14])
                    if (uvA != null && uvA.third >= 2) { us.add(uvA.first[i * 2]); us.add(1f - uvA.first[i * 2 + 1]) } else { us.add(0f); us.add(0f) }
                }
                val ia = if (p.has("indices")) readAcc(p.getInt("indices")) else null
                val cnt = ia?.second ?: n
                var t = 0
                while (t + 2 < cnt) {
                    val a = ia?.first?.get(t)?.toInt() ?: t; val bI = ia?.first?.get(t + 1)?.toInt() ?: (t + 1); val c = ia?.first?.get(t + 2)?.toInt() ?: (t + 2)
                    ix.add(base + a); ix.add(base + c); ix.add(base + bI)   // зеркало по X переворачивает обход
                    t += 3
                }
                if (texIdx < 0) {
                    val mt = p.optInt("material", -1)
                    val tx = mats.optJSONObject(mt)?.optJSONObject("pbrMetallicRoughness")?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    if (tx >= 0) texIdx = tx
                }
            }
        }
        fun walk(ni: Int, parent: FloatArray, depth: Int) {
            if (depth > 64) return
            val n = nodes.optJSONObject(ni) ?: return
            val w = mul(parent, local(n))
            if (n.has("mesh")) addMesh(n.getInt("mesh"), w)
            n.optJSONArray("children")?.let { ch -> for (c in 0 until ch.length()) walk(ch.getInt(c), w, depth + 1) }
        }
        val ident = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val sc = j.optJSONArray("scenes")?.optJSONObject(j.optInt("scene", 0))?.optJSONArray("nodes")
        if (sc != null) for (i in 0 until sc.length()) walk(sc.getInt(i), ident, 0)
        else for (i in 0 until meshes.length()) addMesh(i, ident)
        if (vs.isEmpty() || ix.isEmpty()) throw IllegalArgumentException("в модели нет треугольников")

        var tex: ByteArray? = null
        if (texIdx >= 0) {
            val src = j.optJSONArray("textures")?.optJSONObject(texIdx)?.optInt("source", -1) ?: -1
            val bvI = j.optJSONArray("images")?.optJSONObject(src)?.optInt("bufferView", -1) ?: -1
            val bv = views.optJSONObject(bvI)
            if (bv != null) {
                val o = bv.optInt("byteOffset", 0); val l = bv.getInt("byteLength")
                if (o >= 0 && o + l <= b.size) tex = b.copyOfRange(o, o + l)
            }
        }
        val anim = try { readAnim(j, nodes, ::readAcc) } catch (_: Throwable) { null }
        return Model(vs.toFloatArray(), us.toFloatArray(), ix.toIntArray(), tex, anim)
    }

    private fun quatToEuler(x: Double, y: Double, z: Double, w: Double): DoubleArray {   // порядок Unity (Z, X, Y), градусы
        val r12 = 2 * (y * z - x * w); val r02 = 2 * (x * z + y * w); val r22 = 1 - 2 * (x * x + y * y)
        val r10 = 2 * (x * y + z * w); val r11 = 1 - 2 * (x * x + z * z)
        return doubleArrayOf(Math.toDegrees(Math.asin((-r12).coerceIn(-1.0, 1.0))), Math.toDegrees(Math.atan2(r02, r22)), Math.toDegrees(Math.atan2(r10, r11)))
    }

    private fun qmul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2])

    /** Первая анимация модели: берётся узел с наибольшим числом каналов, остальные узлы игнорируются (модель движется целиком). */
    private fun readAnim(j: JSONObject, nodes: JSONArray, readAcc: (Int) -> Triple<FloatArray, Int, Int>?): List<DoubleArray>? {
        val a = j.optJSONArray("animations")?.optJSONObject(0) ?: return null
        val chans = a.optJSONArray("channels") ?: return null
        val samplers = a.optJSONArray("samplers") ?: return null
        val count = HashMap<Int, Int>()
        for (i in 0 until chans.length()) { val n = chans.getJSONObject(i).optJSONObject("target")?.optInt("node", -1) ?: -1; if (n >= 0) count[n] = (count[n] ?: 0) + 1 }
        val node = count.maxByOrNull { it.value }?.key ?: return null
        val nd = nodes.optJSONObject(node) ?: return null
        class Ch(val path: String, val t: FloatArray, val v: FloatArray, val comps: Int, val cubic: Boolean, val step: Boolean)
        val list = ArrayList<Ch>()
        for (i in 0 until chans.length()) {
            val c = chans.getJSONObject(i)
            val tg = c.optJSONObject("target") ?: continue
            if (tg.optInt("node", -1) != node) continue
            val path = tg.optString("path")
            if (path != "translation" && path != "rotation" && path != "scale") continue
            val sm = samplers.optJSONObject(c.optInt("sampler", -1)) ?: continue
            val inp = readAcc(sm.getInt("input")) ?: continue
            val out = readAcc(sm.getInt("output")) ?: continue
            val interp = sm.optString("interpolation", "LINEAR")
            list.add(Ch(path, inp.first, out.first, out.third, interp == "CUBICSPLINE", interp == "STEP"))
        }
        if (list.isEmpty()) return null
        val times = java.util.TreeSet<Float>()
        for (c in list) for (t in c.t) times.add(t)
        var ts = times.toList()
        if (ts.size > 40) { val st = ts.size / 40.0; ts = (0 until 40).map { ts[(it * st).toInt()] } + ts.last() }
        if (ts.isEmpty() || ts.last() <= 0f) return null
        val tr0 = nd.optJSONArray("translation"); val r0 = nd.optJSONArray("rotation"); val s0 = nd.optJSONArray("scale")
        val restT = doubleArrayOf(tr0?.getDouble(0) ?: 0.0, tr0?.getDouble(1) ?: 0.0, tr0?.getDouble(2) ?: 0.0)
        val restR = doubleArrayOf(r0?.getDouble(0) ?: 0.0, r0?.getDouble(1) ?: 0.0, r0?.getDouble(2) ?: 0.0, r0?.getDouble(3) ?: 1.0)
        val restS = doubleArrayOf(s0?.getDouble(0) ?: 1.0, s0?.getDouble(1) ?: 1.0, s0?.getDouble(2) ?: 1.0)
        fun sample(c: Ch, t: Float): DoubleArray {
            val n = c.t.size
            fun at(i: Int) = DoubleArray(c.comps) { k -> c.v[(if (c.cubic) i * 3 + 1 else i) * c.comps + k].toDouble() }
            if (t <= c.t[0]) return at(0)
            if (t >= c.t[n - 1]) return at(n - 1)
            var i = 0
            while (i < n - 2 && c.t[i + 1] <= t) i++
            val a0 = at(i); if (c.step) return a0
            val b0 = at(i + 1); val u = ((t - c.t[i]) / (c.t[i + 1] - c.t[i])).toDouble()
            val r = DoubleArray(c.comps) { a0[it] + (b0[it] - a0[it]) * u }
            if (c.path == "rotation") { val l = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2] + r[3] * r[3]); if (l > 1e-9) for (k in 0..3) r[k] /= l }
            return r
        }
        val out = ArrayList<DoubleArray>()
        for (t in ts) {
            var tv = restT.copyOf(); var rv = restR.copyOf(); var sv = restS.copyOf()
            for (c in list) when (c.path) { "translation" -> tv = sample(c, t); "rotation" -> rv = sample(c, t); else -> sv = sample(c, t) }
            // дельта поворота от покоя, зеркало по X: (qx, -qy, -qz, qw)
            val inv = doubleArrayOf(-restR[0], -restR[1], -restR[2], restR[3])
            val d = qmul(rv, inv)
            val eu = quatToEuler(d[0], -d[1], -d[2], d[3])
            out.add(doubleArrayOf(t.toDouble(), -(tv[0] - restT[0]), tv[1] - restT[1], tv[2] - restT[2], eu[0], eu[1], eu[2],
                if (restS[0] != 0.0) sv[0] / restS[0] else 1.0, if (restS[1] != 0.0) sv[1] / restS[1] else 1.0, if (restS[2] != 0.0) sv[2] / restS[2] else 1.0))
        }
        return out
    }
}
