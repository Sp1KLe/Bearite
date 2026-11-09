package com.bearite.patcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Геометрия мешей, закрытых от чтения в рантайме, берётся прямо из файлов игры (data.unity3d в APK).
 * Мод присылает имя и число вершин такого меша, здесь он находится среди объектов Mesh (class 43) сериализованных файлов.
 * Формат сериализации Unity 6 без type tree, поэтому раскладка Mesh описана вручную.
 */
object MeshDb {
    private class Obj(val node: Int, val abs: Long, val size: Int, val name: String)

    private var apkKey = ""
    private var bundle: UnityFs? = null
    private var index: List<Obj>? = null
    private val byName = HashMap<String, ArrayList<Obj>>()

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    // ---------------------------------------------------------------- сериализованный файл

    /** Объекты class 43 одного файла: абсолютное смещение в потоке бандла и размер. */
    private fun meshObjects(u: UnityFs, nodeIdx: Int): List<Pair<Long, Int>> {
        val n = u.nodes[nodeIdx]
        val h = u.read(n.offset, 48)
        val hb = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        val version = hb.getInt(8)
        if (version < 22) return emptyList()
        val littleMeta = h[16].toInt() == 0
        val metaSize = hb.getInt(20)
        val dataOffset = hb.getLong(32)
        if (metaSize <= 0 || metaSize > 64 * 1024 * 1024) return emptyList()
        val md = u.read(n.offset + 48, minOf(metaSize.toLong() + 64, n.size - 48).toInt())
        val b = ByteBuffer.wrap(md).order(if (littleMeta) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        // версия Unity, платформа, type tree
        while (b.get().toInt() != 0) { }
        b.position(b.position() + 4)
        if (b.get().toInt() != 0) return emptyList()   // с type tree формат другой, у этой игры его нет
        val typeCount = b.getInt()
        val classes = IntArray(typeCount)
        for (i in 0 until typeCount) {
            val cid = b.getInt(); classes[i] = cid
            b.get()          // isStripped
            b.getShort()     // scriptTypeIndex
            if (cid == 114) b.position(b.position() + 16)
            b.position(b.position() + 16)   // oldTypeHash
        }
        val objCount = b.getInt()
        val out = ArrayList<Pair<Long, Int>>()
        for (i in 0 until objCount) {
            while (b.position() % 4 != 0) b.get()
            b.getLong()                    // pathID
            val start = b.getLong(); val size = b.getInt(); val ti = b.getInt()
            if (ti in classes.indices && classes[ti] == 43) out.add((n.offset + dataOffset + start) to size)
        }
        return out
    }

    private fun alignedName(b: ByteBuffer): String {
        val len = b.getInt()
        if (len < 0 || len > 512) throw IllegalStateException("имя")
        val s = ByteArray(len); b.get(s)
        while (b.position() % 4 != 0) b.get()
        return String(s, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- Mesh

    class Parsed(val name: String, val vcount: Int, val mesh: MapStore.SceneMesh)

    private fun fmtSize(f: Int) = when (f) { 0, 10, 11 -> 4; 1, 4, 5, 8, 9 -> 2; else -> 1 }

    private fun half(h: Int): Float {
        val e = (h shr 10) and 31; val m = h and 1023
        val v = if (e == 0) m * 5.9604645e-8f else if (e == 31) (if (m == 0) Float.POSITIVE_INFINITY else Float.NaN) else Math.pow(2.0, (e - 15).toDouble()).toFloat() * (1f + m / 1024f)
        return if (h and 0x8000 != 0) -v else v
    }

    private fun comp(b: ByteBuffer, pos: Int, f: Int): Float = when (f) {
        0 -> b.getFloat(pos)
        1 -> half(b.getShort(pos).toInt() and 0xFFFF)
        2 -> (b.get(pos).toInt() and 255) / 255f
        3 -> maxOf(b.get(pos) / 127f, -1f)
        4 -> (b.getShort(pos).toInt() and 0xFFFF) / 65535f
        5 -> maxOf(b.getShort(pos) / 32767f, -1f)
        else -> 0f
    }

    var lastFail = ""
    private fun fail(tag: String, b: ByteBuffer): Parsed? { lastFail = tag + " поз=" + b.position() + " из " + b.limit(); return null }

    private fun parseMesh(raw: ByteArray, nameOnly: Boolean = false, res: ((String, Long, Int) -> ByteArray?)? = null): Parsed? {
        val b = le(raw)
        val name = alignedName(b)
        if (nameOnly) return Parsed(name, -1, MapStore.SceneMesh(FloatArray(0), FloatArray(0), IntArray(0)))
        val nsm = b.getInt()
        if (nsm < 0 || nsm > 64) return fail("#1", b)
        val sFirst = IntArray(nsm); val sCount = IntArray(nsm); val sTop = IntArray(nsm); val sBase = IntArray(nsm)
        for (i in 0 until nsm) {
            sFirst[i] = b.getInt(); sCount[i] = b.getInt(); sTop[i] = b.getInt(); sBase[i] = b.getInt()
            b.getInt(); b.getInt()                     // firstVertex, vertexCount
            b.position(b.position() + 24)              // localAABB
        }
        // BlendShapeData
        var n = b.getInt(); b.position(b.position() + n * 40)
        n = b.getInt()
        for (i in 0 until n) { b.getInt(); b.getInt(); b.get(); b.get(); while (b.position() % 4 != 0) b.get() }
        n = b.getInt()
        for (i in 0 until n) { alignedName(b); b.getInt(); b.getInt(); b.getInt() }
        n = b.getInt(); b.position(b.position() + n * 4)
        n = b.getInt(); b.position(b.position() + n * 64)      // bindPose
        n = b.getInt(); b.position(b.position() + n * 4)       // boneNameHashes
        b.getInt()                                              // rootBoneNameHash
        n = b.getInt(); b.position(b.position() + n * 24)      // bonesAABB
        n = b.getInt(); b.position(b.position() + n * 4)       // variableBoneCountWeights
        val compression = b.get().toInt()
        b.get(); b.get(); b.get()
        while (b.position() % 4 != 0) b.get()
        if (compression != 0) return fail("#2", b)
        val indexFormat = b.getInt()
        val ibLen = b.getInt()
        if (ibLen < 0 || ibLen > b.remaining()) return fail("#3", b)
        val ibPos = b.position(); b.position(ibPos + ibLen)
        while (b.position() % 4 != 0) b.get()
        val vcount = b.getInt()
        val nch = b.getInt()
        if (nch < 0 || nch > 32) return fail("#4", b)
        val chStream = IntArray(nch); val chOff = IntArray(nch); val chFmt = IntArray(nch); val chDim = IntArray(nch)
        for (i in 0 until nch) { chStream[i] = b.get().toInt() and 255; chOff[i] = b.get().toInt() and 255; chFmt[i] = b.get().toInt() and 255; chDim[i] = b.get().toInt() and 15 }
        var dLen = b.getInt()
        if (dLen < 0 || dLen > b.remaining()) return fail("#5", b)
        val dPos = b.position()
        if (vcount < 3 || vcount > 65000) return fail("#6", b)
        // у закрытых мешей вершины лежат не в самом объекте, а в файле .resS (m_StreamData в конце объекта)
        var dBytes: ByteArray
        if (dLen > 0) dBytes = raw.copyOfRange(dPos, dPos + dLen)
        else {
            // конец объекта: offset(8) size(4) длина_пути(4) путь_к_.resS (имя файла или archive:/…)
            val key = ".resS".toByteArray()
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            var pathAt = -1; var plen = 0
            var q = raw.size - key.size
            while (q >= 20 && pathAt < 0) {
                var ok = true; for (k in key.indices) if (raw[q + k] != key[k]) { ok = false; break }
                if (ok) {
                    val e = q + key.size
                    for (len in key.size..120) { val st = e - len; if (st >= 16 && bb.getInt(st - 4) == len) { pathAt = st; plen = len; break } }
                }
                --q
            }
            if (pathAt < 0 || res == null) { lastFail = "#12 хвост=" + raw.copyOfRange(maxOf(0, raw.size - 72), raw.size).joinToString("") { "%02x".format(it) }; return null }
            val size = bb.getInt(pathAt - 8); val off = bb.getLong(pathAt - 16)
            if (plen <= 0 || plen > 256 || size <= 0) return fail("#13", b)
            dBytes = res(String(raw, pathAt, plen, Charsets.UTF_8), off, size) ?: return fail("#14", b)
            dLen = dBytes.size
        }
        // шаг и начало каждого потока
        val streams = (chStream.maxOrNull() ?: 0) + 1
        val stride = IntArray(streams)
        for (i in 0 until nch) if (chDim[i] > 0) stride[chStream[i]] = maxOf(stride[chStream[i]], chOff[i] + chDim[i] * fmtSize(chFmt[i]))
        val sStart = LongArray(streams)
        var cur = 0L
        for (s in 0 until streams) { sStart[s] = cur; cur += stride[s].toLong() * vcount; cur = (cur + 15) and 15L.inv() }
        if (nch < 1 || chDim[0] < 3 || stride[chStream[0]] == 0) return fail("#7", b)
        val data = ByteBuffer.wrap(dBytes).order(ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(vcount * 3)
        val uv = FloatArray(vcount * 2)
        val ps = chStream[0]; val pst = stride[ps]
        for (i in 0 until vcount) {
            val p = (sStart[ps] + i.toLong() * pst + chOff[0]).toInt()
            if (p + 12 > dLen) { lastFail = "#8 i=$i/$vcount dLen=$dLen ibLen=$ibLen idxFmt=$indexFormat nsm=$nsm каналы=" + (0 until nch).joinToString(" ") { "${chStream[it]}/${chOff[it]}/${chFmt[it]}/${chDim[it]}" } + " шаги=" + stride.joinToString(","); return null }
            for (k in 0 until 3) v[i * 3 + k] = comp(data, p + k * fmtSize(chFmt[0]), chFmt[0])
        }
        if (nch > 4 && chDim[4] >= 2 && stride[chStream[4]] > 0) {
            val us = chStream[4]; val ust = stride[us]
            for (i in 0 until vcount) {
                val p = (sStart[us] + i.toLong() * ust + chOff[4]).toInt()
                if (p + 4 > dLen) break
                uv[i * 2] = comp(data, p, chFmt[4]); uv[i * 2 + 1] = comp(data, p + fmtSize(chFmt[4]), chFmt[4])
            }
        }
        // индексы подмешей подряд
        val idx = ArrayList<Int>()
        val subs = ArrayList<Int>()
        val ibuf = ByteBuffer.wrap(raw, ibPos, ibLen).slice().order(ByteOrder.LITTLE_ENDIAN)
        val isz = if (indexFormat == 0) 2 else 4
        for (i in 0 until nsm) {
            val start = idx.size
            if (sTop[i] == 0 && sCount[i] > 0) {
                for (k in 0 until sCount[i]) {
                    val po = sFirst[i] + k * isz
                    if (po + isz > ibLen) return fail("#9", b)
                    val ix = (if (isz == 2) (ibuf.getShort(po).toInt() and 0xFFFF) else ibuf.getInt(po)) + sBase[i]
                    if (ix < 0 || ix >= vcount) return fail("#10", b)
                    idx.add(ix)
                }
            }
            subs.add(start); subs.add(idx.size - start)
        }
        if (idx.size < 3 || idx.size > 200000) return fail("#11", b)
        return Parsed(name, vcount, MapStore.SceneMesh(v, uv, idx.toIntArray(), subs.toIntArray()))
    }

    // ---------------------------------------------------------------- индекс и подстановка

    private fun open(apk: String): UnityFs? {
        if (apkKey == apk && bundle != null) return bundle
        bundle = UnityFs.openInZip(apk, "assets/bin/Data/data.unity3d")
        apkKey = apk; index = null; byName.clear()
        return bundle
    }

    private fun buildIndex(u: UnityFs, cacheFile: File, key: String) {
        if (index != null) return
        // кеш: имя, узел, смещение, размер
        try {
            if (cacheFile.isFile) {
                val lines = cacheFile.readLines()
                if (lines.firstOrNull() == key) {
                    val list = ArrayList<Obj>()
                    for (i in 1 until lines.size) { val p = lines[i].split('\t'); if (p.size == 4) list.add(Obj(p[1].toInt(), p[2].toLong(), p[3].toInt(), p[0])) }
                    index = list; for (o in list) byName.getOrPut(o.name) { ArrayList() }.add(o)
                    return
                }
            }
        } catch (_: Throwable) { }
        val all = ArrayList<Obj>()
        for ((ni, n) in u.nodes.withIndex()) {
            if (n.path.endsWith(".resS") || n.path.endsWith(".resource") || n.size < 1024) continue
            val list = try { meshObjects(u, ni) } catch (_: Throwable) { emptyList() }
            for ((abs, size) in list.sortedBy { it.first }) {
                val nm = try { parseMesh(u.read(abs, minOf(size, 300)), nameOnly = true)?.name ?: "" } catch (_: Throwable) { "" }
                if (nm.isNotEmpty()) all.add(Obj(ni, abs, size, nm))
            }
        }
        index = all
        for (o in all) byName.getOrPut(o.name) { ArrayList() }.add(o)
        try { cacheFile.writeText(key + "\n" + all.joinToString("\n") { "${it.name}\t${it.node}\t${it.abs}\t${it.size}" }) } catch (_: Throwable) { }
    }

    /** miss: строки _meshmiss "номер\tвершин\tподмешей\tимя". Возвращает список мешей, где найденные заменены настоящей геометрией. */
    fun fill(apk: String, dir: File, meshes: List<MapStore.SceneMesh>, miss: List<String>): Pair<List<MapStore.SceneMesh>, String> {
        val u = open(apk) ?: return meshes to "бандл игры не открылся"
        val keyF = File(apk)
        buildIndex(u, File(dir, "_meshindex"), "${keyF.length()}-${keyF.lastModified()}")
        val out = ArrayList(meshes)
        val resolver = { path: String, off: Long, size: Int ->
            val fn = path.substringAfterLast('/')
            val nd = u.nodes.firstOrNull { it.path == fn || it.path.endsWith("/$fn") }
            if (nd == null || off < 0 || off + size > nd.size) null else u.read(nd.offset + off, size)
        }
        var found = 0; var absent = 0; var bad = 0; var logged = 0
        for (l in miss) {
            val p = l.split('\t')
            if (p.size < 4) continue
            val blob = p[0].toIntOrNull() ?: continue; val vc = p[1].toIntOrNull() ?: -1; val nsub = p[2].toIntOrNull() ?: 0
            val cands = byName[p[3]]
            if (cands == null || blob !in out.indices) { ++absent; continue }
            var done = false
            for (o in cands) {
                lastFail = ""
                val parsed = try { parseMesh(u.read(o.abs, o.size), false, resolver) } catch (t: Throwable) { lastFail = "исключение " + t.message; null }
                if (parsed == null) { if (logged++ < 8) BLog.i("  меш ${p[3]}: не разобрался ($lastFail), размер ${o.size}, нужно вершин $vc"); continue }
                if (vc > 0 && parsed.vcount != vc) { if (logged++ < 8) BLog.i("  меш ${p[3]}: вершин ${parsed.vcount}, нужно $vc"); continue }
                out[blob] = parsed.mesh; done = true; break
            }
            if (done) ++found else ++bad
        }
        return out to "нечитаемых мешей ${miss.size}: найдено в файлах игры $found, нет по имени $absent, не совпали/не разобрались $bad"
    }
}
