package com.bearite.patcher

import java.util.zip.ZipFile

/** Диагностика файлов данных игры (Unity): что внутри APK и какая версия сериализации. Результат идёт в общий лог. */
object GameDataProbe {
    fun run(apks: List<String>) {
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
