package com.bearite.patcher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Экспорт карты в glTF 2.0 (.glb): объекты как узлы с примитивами, цветами и PNG-текстурами, анимации — как
 * ключевые кадры одного цикла (проигрываются зациклено в Blender, three.js, Godot и других). Координаты переводятся
 * из левосторонних Unity в правосторонние glTF отражением оси X; масштаб карты применён к позициям и размерам.
 */
object MapExport {
    private class Geo(val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray)

    private class Buf {
        val out = ByteArrayOutputStream()
        val views = JSONArray()
        val accessors = JSONArray()

        private fun align() { while (out.size() % 4 != 0) out.write(0) }

        fun view(bytes: ByteArray, target: Int?): Int {
            align()
            val v = JSONObject().put("buffer", 0).put("byteOffset", out.size()).put("byteLength", bytes.size)
            if (target != null) v.put("target", target)
            out.write(bytes)
            views.put(v)
            return views.length() - 1
        }

        fun floats(data: FloatArray, type: String, comps: Int, target: Int?, minmax: Boolean): Int {
            val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in data) bb.putFloat(f)
            val vi = view(bb.array(), target)
            val acc = JSONObject().put("bufferView", vi).put("componentType", 5126).put("count", data.size / comps).put("type", type)
            if (minmax) {
                val mn = FloatArray(comps) { Float.MAX_VALUE }; val mx = FloatArray(comps) { -Float.MAX_VALUE }
                for (i in data.indices) { val c = i % comps; mn[c] = minOf(mn[c], data[i]); mx[c] = maxOf(mx[c], data[i]) }
                acc.put("min", JSONArray(mn.map { it.toDouble() })).put("max", JSONArray(mx.map { it.toDouble() }))
            }
            accessors.put(acc)
            return accessors.length() - 1
        }
    }

    private fun lin(c: Int): Double = Math.pow(c / 255.0, 2.2)

    /** Плоская (грани) сетка примитива в координатах glTF; u,v — проекция по доминирующей оси нормали с повторами tu, tv. */
    private fun geo(shape: String, tu: Float, tv: Float): Geo {
        val m = PrimMeshes.get(shape)
        val n = m.t.size / 3
        val pos = FloatArray(n * 9); val nrm = FloatArray(n * 9); val uv = FloatArray(n * 6)
        val ext = if (shape == "plane") 10f else 1f   // у плоскости размер 10×10
        for (i in 0 until n) {
            val idx = intArrayOf(m.t[i * 3], m.t[i * 3 + 1], m.t[i * 3 + 2])
            val p = Array(3) { k -> floatArrayOf(-m.v[idx[k] * 3], m.v[idx[k] * 3 + 1], m.v[idx[k] * 3 + 2]) }  // отражение X
            fun cross(): FloatArray {
                val ux = p[1][0] - p[0][0]; val uy = p[1][1] - p[0][1]; val uz = p[1][2] - p[0][2]
                val wx = p[2][0] - p[0][0]; val wy = p[2][1] - p[0][1]; val wz = p[2][2] - p[0][2]
                return floatArrayOf(uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx)
            }
            var nn = cross()
            val cx = (p[0][0] + p[1][0] + p[2][0]) / 3; val cy = (p[0][1] + p[1][1] + p[2][1]) / 3; val cz = (p[0][2] + p[1][2] + p[2][2]) / 3
            val outward = if (m.doubleSided) nn[1] >= 0 else nn[0] * cx + nn[1] * cy + nn[2] * cz >= 0
            if (!outward) { val t = p[1]; p[1] = p[2]; p[2] = t; nn = cross() }
            val len = sqrt(nn[0] * nn[0] + nn[1] * nn[1] + nn[2] * nn[2]).let { if (it < 1e-9f) 1f else it }
            for (k in 0 until 3) {
                pos[i * 9 + k * 3] = p[k][0]; pos[i * 9 + k * 3 + 1] = p[k][1]; pos[i * 9 + k * 3 + 2] = p[k][2]
                nrm[i * 9 + k * 3] = nn[0] / len; nrm[i * 9 + k * 3 + 1] = nn[1] / len; nrm[i * 9 + k * 3 + 2] = nn[2] / len
                val ax = abs(nn[0]); val ay = abs(nn[1]); val az = abs(nn[2])
                val (a, b) = when { ay >= ax && ay >= az -> p[k][0] to p[k][2]; ax >= az -> p[k][2] to p[k][1]; else -> p[k][0] to p[k][1] }
                uv[i * 6 + k * 2] = (a / ext + 0.5f) * tu
                uv[i * 6 + k * 2 + 1] = (1f - (b / ext + 0.5f)) * tv
            }
        }
        return Geo(pos, nrm, uv)
    }

    /** Матрица поворота Unity (порядок Z, X, Y) → кватернион glTF с отражением оси X. Возвращает x y z w. */
    private fun quat(rx: Double, ry: Double, rz: Double): DoubleArray {
        val a = Math.toRadians(rx); val b = Math.toRadians(ry); val g = Math.toRadians(rz)
        val cx = Math.cos(a); val sx = Math.sin(a); val cy = Math.cos(b); val sy = Math.sin(b); val cz = Math.cos(g); val sz = Math.sin(g)
        // R = Ry * Rx * Rz
        val a00 = cz; val a01 = -sz; val a02 = 0.0
        val a10 = cx * sz; val a11 = cx * cz; val a12 = -sx
        val a20 = sx * sz; val a21 = sx * cz; val a22 = cx
        val r = arrayOf(
            doubleArrayOf(cy * a00 + sy * a20, cy * a01 + sy * a21, cy * a02 + sy * a22),
            doubleArrayOf(a10, a11, a12),
            doubleArrayOf(-sy * a00 + cy * a20, -sy * a01 + cy * a21, -sy * a02 + cy * a22),
        )
        // M R M, M = diag(-1, 1, 1)
        r[0][1] = -r[0][1]; r[0][2] = -r[0][2]; r[1][0] = -r[1][0]; r[2][0] = -r[2][0]
        val tr = r[0][0] + r[1][1] + r[2][2]
        val q = DoubleArray(4)
        if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            q[3] = 0.25 * s; q[0] = (r[2][1] - r[1][2]) / s; q[1] = (r[0][2] - r[2][0]) / s; q[2] = (r[1][0] - r[0][1]) / s
        } else if (r[0][0] > r[1][1] && r[0][0] > r[2][2]) {
            val s = sqrt(1.0 + r[0][0] - r[1][1] - r[2][2]) * 2
            q[3] = (r[2][1] - r[1][2]) / s; q[0] = 0.25 * s; q[1] = (r[0][1] + r[1][0]) / s; q[2] = (r[0][2] + r[2][0]) / s
        } else if (r[1][1] > r[2][2]) {
            val s = sqrt(1.0 + r[1][1] - r[0][0] - r[2][2]) * 2
            q[3] = (r[0][2] - r[2][0]) / s; q[0] = (r[0][1] + r[1][0]) / s; q[1] = 0.25 * s; q[2] = (r[1][2] + r[2][1]) / s
        } else {
            val s = sqrt(1.0 + r[2][2] - r[0][0] - r[1][1]) * 2
            q[3] = (r[1][0] - r[0][1]) / s; q[0] = (r[0][2] + r[2][0]) / s; q[1] = (r[1][2] + r[2][1]) / s; q[2] = 0.25 * s
        }
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return doubleArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l)
    }

    fun glb(map: GameMap, store: MapStore): ByteArray {
        val buf = Buf()
        val s = map.scale
        val nodes = JSONArray(); val meshes = JSONArray(); val materials = JSONArray(); val textures = JSONArray(); val images = JSONArray()
        val animChannels = JSONArray(); val animSamplers = JSONArray()
        val meshOf = HashMap<String, Int>(); val matOf = HashMap<String, Int>(); val texOf = HashMap<String, Int>()
        val used = ArrayList<Int>()
        var animDur = 0.0

        fun texIndex(id: String): Int? {
            if (id.isEmpty()) return null
            texOf[id]?.let { return it }
            val f = store.textureFile(id) ?: return null
            val vi = buf.view(f.readBytes(), null)
            images.put(JSONObject().put("bufferView", vi).put("mimeType", "image/png").put("name", id))
            textures.put(JSONObject().put("source", images.length() - 1))
            texOf[id] = textures.length() - 1
            return texOf[id]
        }

        for (o in map.objects) {
            val tIdx = texIndex(o.tex)
            val mKey = "${o.color}|${if (tIdx != null) o.tex else ""}|${o.shape == "plane"}"
            val mat = matOf.getOrPut(mKey) {
                val pbr = JSONObject()
                    .put("baseColorFactor", JSONArray(listOf(lin((o.color shr 16) and 255), lin((o.color shr 8) and 255), lin(o.color and 255), 1.0)))
                    .put("metallicFactor", 0.0).put("roughnessFactor", 0.9)
                if (tIdx != null) pbr.put("baseColorTexture", JSONObject().put("index", tIdx))
                materials.put(JSONObject().put("name", "m${materials.length()}").put("pbrMetallicRoughness", pbr).put("doubleSided", o.shape == "plane"))
                materials.length() - 1
            }
            val tu = if (tIdx != null) o.tu.toFloat() else 1f; val tv = if (tIdx != null) o.tv.toFloat() else 1f
            val key = "${o.shape}|$tu|$tv|$mat"
            val mesh = meshOf.getOrPut(key) {
                val g = geo(o.shape, tu, tv)
                val pa = buf.floats(g.pos, "VEC3", 3, 34962, true)
                val na = buf.floats(g.nrm, "VEC3", 3, 34962, false)
                val ua = buf.floats(g.uv, "VEC2", 2, 34962, false)
                val prim = JSONObject().put("attributes", JSONObject().put("POSITION", pa).put("NORMAL", na).put("TEXCOORD_0", ua)).put("material", mat).put("mode", 4)
                meshes.put(JSONObject().put("name", o.shape).put("primitives", JSONArray().put(prim)))
                meshes.length() - 1
            }
            val q = quat(o.rx, o.ry, o.rz)
            val node = JSONObject().put("name", o.name.ifBlank { "Объект" }).put("mesh", mesh)
                .put("translation", JSONArray(listOf(-o.x * s, o.y * s, o.z * s)))
                .put("rotation", JSONArray(q.toList()))
                .put("scale", JSONArray(listOf(o.sx * s, o.sy * s, o.sz * s)))
                .put("extras", JSONObject().put("solid", o.solid).put("shape", o.shape).put("bearite_anim", o.an))
            nodes.put(node)
            val ni = nodes.length() - 1
            used.add(ni)

            // анимация: один цикл ключевыми кадрами
            if (o.an.isNotEmpty()) {
                val dur = MapAnim.cycle(o)
                animDur = max(animDur, dur)
                val n = 48
                val times = FloatArray(n + 1) { (dur * it / n).toFloat() }
                val p = DoubleArray(9)
                val tr = FloatArray((n + 1) * 3); val rt = FloatArray((n + 1) * 4); val sc = FloatArray((n + 1) * 3)
                var prev: DoubleArray? = null
                for (k in 0..n) {
                    MapAnim.pose(o, o.ad + dur * k / n, p)
                    tr[k * 3] = (-p[0] * s).toFloat(); tr[k * 3 + 1] = (p[1] * s).toFloat(); tr[k * 3 + 2] = (p[2] * s).toFloat()
                    var qq = quat(p[3], p[4], p[5])
                    val pv = prev
                    if (pv != null && pv[0] * qq[0] + pv[1] * qq[1] + pv[2] * qq[2] + pv[3] * qq[3] < 0) qq = doubleArrayOf(-qq[0], -qq[1], -qq[2], -qq[3])
                    prev = qq
                    for (c in 0 until 4) rt[k * 4 + c] = qq[c].toFloat()
                    sc[k * 3] = (p[6] * s).toFloat(); sc[k * 3 + 1] = (p[7] * s).toFloat(); sc[k * 3 + 2] = (p[8] * s).toFloat()
                }
                val timeAcc = buf.floats(times, "SCALAR", 1, null, true)
                fun channel(path: String, data: FloatArray, type: String, comps: Int) {
                    val out = buf.floats(data, type, comps, null, false)
                    animSamplers.put(JSONObject().put("input", timeAcc).put("output", out).put("interpolation", "LINEAR"))
                    animChannels.put(JSONObject().put("sampler", animSamplers.length() - 1).put("target", JSONObject().put("node", ni).put("path", path)))
                }
                when (o.an) {
                    "move" -> channel("translation", tr, "VEC3", 3)
                    "rotate", "swing" -> channel("rotation", rt, "VEC4", 4)
                    "pulse" -> channel("scale", sc, "VEC3", 3)
                }
            }
        }

        val root = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "Bearite map editor"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("name", map.name).put("nodes", JSONArray(used))))
            .put("nodes", nodes)
        if (meshes.length() > 0) root.put("meshes", meshes)
        if (materials.length() > 0) root.put("materials", materials)
        if (images.length() > 0) root.put("images", images).put("textures", textures)
        if (animChannels.length() > 0) root.put("animations", JSONArray().put(JSONObject().put("name", "Анимация карты").put("samplers", animSamplers).put("channels", animChannels)))
        val bin = buf.out.toByteArray()
        root.put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size)))
        root.put("bufferViews", buf.views).put("accessors", buf.accessors)

        var json = root.toString().toByteArray(Charsets.UTF_8)
        val jPad = (4 - json.size % 4) % 4
        val bPad = (4 - bin.size % 4) % 4
        val total = 12 + 8 + json.size + jPad + 8 + bin.size + bPad
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67).putInt(2).putInt(total)
        bb.putInt(json.size + jPad).putInt(0x4E4F534A); bb.put(json); repeat(jPad) { bb.put(0x20) }
        bb.putInt(bin.size + bPad).putInt(0x004E4942); bb.put(bin); repeat(bPad) { bb.put(0) }
        return bb.array()
    }

    /** Кладёт файл в «Загрузки/Bearite». Возвращает понятный путь для сообщения. */
    fun save(ctx: Context, name: String, data: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "model/gltf-binary")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bearite")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw RuntimeException("не удалось создать файл")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: throw RuntimeException("не удалось записать файл")
            return "Загрузки/Bearite/$name"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "exports").also { it.mkdirs() }
        File(dir, name).writeBytes(data)
        return File(dir, name).path
    }
}
