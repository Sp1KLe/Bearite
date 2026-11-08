package com.bearite.patcher

import java.io.RandomAccessFile

/**
 * Чтение бандлов Unity (UnityFS) прямо из APK игры, без распаковки на диск целиком:
 * блоки (LZ4) разжимаются по требованию. Нужен, чтобы достать геометрию мешей, закрытых от чтения в рантайме.
 */
class UnityFs private constructor(private val f: RandomAccessFile, private val base: Long) {
    class Block(val usize: Int, val csize: Int, val flags: Int)
    class Node(val offset: Long, val size: Long, val flags: Int, val path: String)

    var unityRevision = ""
    val blocks = ArrayList<Block>()
    val nodes = ArrayList<Node>()
    private var dataStart = 0L
    private val blockUStart = ArrayList<Long>()
    private val blockCStart = ArrayList<Long>()
    private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, ByteArray>?) = size > 6
    }

    private fun readAt(pos: Long, len: Int): ByteArray {
        val b = ByteArray(len); f.seek(base + pos); f.readFully(b); return b
    }

    private class Rd(val b: ByteArray, var p: Int = 0) {
        fun u8() = b[p++].toInt() and 255
        fun u16() = (u8() shl 8) or u8()
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun i64() = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL)
        fun cstr(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.UTF_8); p++; return r }
        fun skip(n: Int) { p += n }
    }

    private fun parse(): Boolean {
        val head = readAt(0, 256)
        val r = Rd(head)
        if (String(head, 0, 7) != "UnityFS") return false
        r.p = 8
        val ver = r.i32(); r.cstr(); unityRevision = r.cstr()
        val total = r.i64(); val cInfo = r.i32(); val uInfo = r.i32(); val flags = r.i32()
        var pos = r.p.toLong()
        if (ver >= 7) pos = (pos + 15) and 15L.inv()
        val infoPos: Long
        if (flags and 0x80 != 0) { infoPos = total - cInfo; dataStart = pos } else { infoPos = pos; dataStart = pos + cInfo; if (ver >= 7) dataStart = (dataStart + 15) and 15L.inv() }
        val comp = readAt(infoPos, cInfo)
        val info = when (flags and 0x3F) {
            0 -> comp
            2, 3 -> ByteArray(uInfo).also { lz4(comp, 0, comp.size, it, uInfo) }
            else -> return false   // LZMA в этой игре не встречалась; если встретится, это видно в логе
        }
        val ir = Rd(info)
        ir.skip(16)
        val nb = ir.i32()
        var u = 0L; var c = 0L
        for (i in 0 until nb) {
            val b = Block(ir.i32(), ir.i32(), ir.u16())
            blocks.add(b); blockUStart.add(u); blockCStart.add(c); u += b.usize; c += b.csize
        }
        val nn = ir.i32()
        for (i in 0 until nn) nodes.add(Node(ir.i64(), ir.i64(), ir.i32(), ir.cstr()))
        return true
    }

    private fun block(i: Int): ByteArray {
        cache[i]?.let { return it }
        val b = blocks[i]
        val raw = readAt(dataStart + blockCStart[i], b.csize)
        val out = when (b.flags and 0x3F) {
            0 -> raw
            2, 3 -> ByteArray(b.usize).also { lz4(raw, 0, raw.size, it, b.usize) }
            else -> throw IllegalStateException("сжатие блока ${b.flags and 0x3F} не поддерживается")
        }
        cache[i] = out
        return out
    }

    /** Байты из несжатого потока бандла. */
    fun read(off: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        var pos = off
        // двоичный поиск блока
        var lo = 0; var hi = blocks.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (blockUStart[mid] <= pos) lo = mid else hi = mid - 1 }
        var bi = lo
        while (done < len && bi < blocks.size) {
            val bd = block(bi)
            val inOff = (pos - blockUStart[bi]).toInt()
            val n = minOf(len - done, bd.size - inOff)
            System.arraycopy(bd, inOff, out, done, n)
            done += n; pos += n; bi++
        }
        return out
    }

    fun readNode(n: Node): ByteArray = read(n.offset, n.size.toInt())

    companion object {
        /** Открывает бандл, лежащий в APK/zip несжатым (method=0), по имени записи. null, если записи нет или она сжата. */
        fun openInZip(zipPath: String, entry: String): UnityFs? {
            val raf = RandomAccessFile(zipPath, "r")
            try {
                val base = storedOffset(raf, entry) ?: run { raf.close(); return null }
                val u = UnityFs(raf, base)
                return if (u.parse()) u else { raf.close(); null }
            } catch (t: Throwable) { raf.close(); throw t }
        }

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        private fun le32(b: ByteArray, o: Int) = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)

        /** Смещение данных несжатой записи zip: разбираем центральный каталог сами, ZipFile его не отдаёт. */
        private fun storedOffset(f: RandomAccessFile, name: String): Long? {
            val len = f.length()
            val tailLen = minOf(len, 66000L).toInt()
            val tail = ByteArray(tailLen); f.seek(len - tailLen); f.readFully(tail)
            var e = tailLen - 22
            while (e >= 0 && le32(tail, e) != 0x06054b50L) e--
            if (e < 0) return null
            val cdSize = le32(tail, e + 12); val cdOff = le32(tail, e + 16)
            val cd = ByteArray(cdSize.toInt()); f.seek(cdOff); f.readFully(cd)
            var p = 0
            while (p + 46 <= cd.size && le32(cd, p) == 0x02014b50L) {
                val method = le16(cd, p + 10)
                val nlen = le16(cd, p + 28); val xlen = le16(cd, p + 30); val clen = le16(cd, p + 32)
                val lho = le32(cd, p + 42)
                val nm = String(cd, p + 46, nlen, Charsets.UTF_8)
                if (nm == name) {
                    if (method != 0) return null
                    val lh = ByteArray(30); f.seek(lho); f.readFully(lh)
                    return lho + 30 + le16(lh, 26) + le16(lh, 28)
                }
                p += 46 + nlen + xlen + clen
            }
            return null
        }

        fun lz4(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstSize: Int) {
            var i = off; val end = off + len; var o = 0
            while (i < end && o < dstSize) {
                val tok = src[i++].toInt() and 255
                var lit = tok ushr 4
                if (lit == 15) { while (true) { val v = src[i++].toInt() and 255; lit += v; if (v != 255) break } }
                System.arraycopy(src, i, dst, o, lit); i += lit; o += lit
                if (i >= end || o >= dstSize) break
                val offs = (src[i].toInt() and 255) or ((src[i + 1].toInt() and 255) shl 8); i += 2
                var ml = tok and 15
                if (ml == 15) { while (true) { val v = src[i++].toInt() and 255; ml += v; if (v != 255) break } }
                ml += 4
                var s = o - offs
                for (k in 0 until ml) dst[o++] = dst[s++]
            }
        }
    }
}
