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
    class Model(val v: FloatArray, val uv: FloatArray, val idx: IntArray, val texture: ByteArray?)

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
        return Model(vs.toFloatArray(), us.toFloatArray(), ix.toIntArray(), tex)
    }
}
