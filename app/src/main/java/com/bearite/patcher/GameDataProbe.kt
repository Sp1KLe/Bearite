package com.bearite.patcher

import java.util.zip.ZipFile

/** Диагностика файлов данных игры (Unity): что внутри APK и какая версия сериализации. Результат идёт в общий лог. */
object GameDataProbe {
    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    /** Внутренности data.unity3d: узлы бандла и заголовки сериализованных файлов (версия, type tree, число объектов, меши). */
    private fun probeBundle(apk: String, entry: String) {
        try {
            val u = UnityFs.openInZip(apk, entry)
            if (u == null) { BLog.i("бандл $entry: не открылся (нет записи, сжатие zip или LZMA)"); return }
            val types = u.blocks.groupingBy { it.flags and 0x3F }.eachCount()
            BLog.i("бандл $entry: ревизия ${u.unityRevision}, блоков ${u.blocks.size} (сжатие: $types), узлов ${u.nodes.size}")
            for (n in u.nodes.take(80)) {
                var extra = ""
                if (!n.path.endsWith(".resS") && !n.path.endsWith(".resource") && n.size > 64) {
                    try {
                        val h = u.read(n.offset, 48)
                        val ver = be32(h, 8)
                        extra = " serialized v$ver"
                        if (ver >= 22) {
                            val h2 = u.read(n.offset, 4096)
                            val metaSize = be32(h2, 20); val dataOff = ((h2[28].toLong() and 255) shl 56 or ((h2[29].toLong() and 255) shl 48) or ((h2[30].toLong() and 255) shl 40) or ((h2[31].toLong() and 255) shl 32) or ((h2[32].toLong() and 255) shl 24) or ((h2[33].toLong() and 255) shl 16) or ((h2[34].toLong() and 255) shl 8) or (h2[35].toLong() and 255))
                            var p = 48
                            val sb = StringBuilder(); while (h2[p].toInt() != 0) sb.append((h2[p++].toInt() and 255).toChar()); p++
                            p += 4
                            val tt = h2[p].toInt() != 0
                            extra += ", Unity $sb, typetree=$tt, metadata ${metaSize}Б, данные с $dataOff"
                        }
                    } catch (t: Throwable) { extra = " (заголовок не разобрался: ${t.message})" }
                }
                BLog.i("  узел ${n.path} ${n.size / 1024}K флаги ${n.flags}$extra")
            }
        } catch (t: Throwable) {
            BLog.e("бандл $entry: ошибка", t)
        }
    }

    fun run(apks: List<String>) {
        for (path in apks) {
            if (path.endsWith("base.apk")) probeBundle(path, "assets/bin/Data/data.unity3d")
        }
        for (path in apks) {
            try {
                ZipFile(path).use { z ->
                    val all = z.entries().asSequence().filter { !it.isDirectory }.toList()
                    val data = all.filter { it.name.startsWith("assets/bin/Data/") }
                    val other = all.filter { it.name.startsWith("assets/") && !it.name.startsWith("assets/bin/Data/") }
                    BLog.i("данные игры ${path.substringAfterLast('/')}: файлов ${all.size}, assets/bin/Data ${data.size}, прочих assets ${other.size}")
                    val big = (data + other).sortedByDescending { it.size }.take(70)
                    for (e in big) BLog.i("  ${e.name} ${e.size / 1024}K (в архиве ${e.compressedSize / 1024}K${if (e.method == 0) ", без сжатия" else ""})")
                    val names = data.map { it.name.substringAfterLast('/') }
                    BLog.i("  имена в Data: " + names.take(120).joinToString(" "))
                    for (head in listOf("globalgamemanagers", "data.unity3d", "level0", "sharedassets0.assets", "resources.assets", "unity default resources")) {
                        val e = z.getEntry("assets/bin/Data/$head") ?: continue
                        z.getInputStream(e).use { ins ->
                            val b = ByteArray(160)
                            val n = ins.read(b)
                            if (n <= 0) return@use
                            val hex = b.take(n.coerceAtMost(64)).joinToString("") { "%02x".format(it) }
                            val txt = StringBuilder()
                            for (i in 0 until n) { val c = b[i].toInt() and 255; if (c in 32..126) txt.append(c.toChar()) else if (txt.isNotEmpty() && txt.last() != '|') txt.append('|') }
                            BLog.i("  голова $head: $hex  текст: $txt")
                        }
                    }
                }
            } catch (t: Throwable) {
                BLog.e("данные игры: не вышло прочитать ${path.substringAfterLast('/')}", t)
            }
        }
    }
}
