package com.bearite.patcher

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Связь лаунчера с нативным реплеем (порт идеи Replay Mod): команды моду, его состояние, файлы записей
 * (maps/replays/<имя>.rpl) и ключевые кадры камеры (<имя>.cam: «мс x y z yaw pitch»).
 * Команды идут через maps/_cmd строками «rp\t<команда>\t<аргумент>», состояние мод пишет в maps/_replay.
 */
class ReplayLink(ctx: Context) {
    val dir = File(ctx.filesDir, "maps")
    val rdir = File(dir, "replays")

    class Status(
        val rec: Boolean, val sess: Boolean, val paused: Boolean, val frames: Int, val durMs: Long, val tMs: Long,
        val speed: Double, val cam: Int, val keys: Int,
        val cx: Float, val cy: Float, val cz: Float, val cyaw: Float, val cpitch: Float,
        val scene: String, val msg: String,
    )

    /** Состояние от мода или null, если файла нет или он старый (мод перезаписывает его раз в несколько секунд). */
    fun status(): Status? = try {
        val f = File(dir, "_replay")
        if (System.currentTimeMillis() - f.lastModified() > 9000) null else {
            val p = f.readText().trimEnd('\n').split('\t')
            Status(
                p[0] == "1", p[1] == "1", p[2] == "1", p[3].toInt(), p[4].toLong(), p[5].toLong(), p[6].toDouble(), p[7].toInt(), p[8].toInt(),
                p[9].toFloat(), p[10].toFloat(), p[11].toFloat(), p[12].toFloat(), p[13].toFloat(), p.getOrElse(14) { "" }, p.getOrElse(15) { "" },
            )
        }
    } catch (_: Throwable) { null }

    /** Одна или несколько команд подряд: «start», «save\tимя», «seek\t1500» … Если прошлые ещё не прочитаны, дописываем в конец. */
    fun cmds(vararg lines: String) {
        val text = lines.joinToString("") { "rp\t$it\n" }
        BLog.i("реплей: команды " + lines.joinToString(" | ").replace('\t', ' '))
        try {
            dir.mkdirs()
            val f = File(dir, "_cmd")
            if (f.exists()) f.appendText(text) else { val t = File(dir, "_cmd.tmp"); t.writeText(text); t.renameTo(f) }
        } catch (_: Throwable) {}
    }

    class Item(val name: String, val durMs: Long, val frames: Int, val scene: String, val modified: Long)

    fun list(): List<Item> {
        val files = rdir.listFiles { f -> f.isFile && f.name.endsWith(".rpl") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val h = f.bufferedReader().use { it.readLine() }.orEmpty().split('\t')
                if (h.firstOrNull() != "BRP1") null
                else Item(f.name.removeSuffix(".rpl"), h.getOrElse(3) { "0" }.toLongOrNull() ?: 0L, h.getOrElse(2) { "0" }.toIntOrNull() ?: 0, h.getOrElse(1) { "" }, f.lastModified())
            } catch (_: Throwable) { null }
        }.sortedByDescending { it.modified }
    }

    /** Имя файла без путей и точек: так же чистит его нативная часть. */
    fun safeName(s: String): String {
        val o = s.map { c -> if (c == '/' || c == '\\' || c == '.' || c == ':' || c.code < 32) '_' else c }.joinToString("").trim().take(40)
        return o.ifBlank { "replay" }
    }

    fun rename(old: String, new: String): Boolean {
        val n = safeName(new)
        if (n == old) return true
        val dst = File(rdir, "$n.rpl")
        if (dst.exists()) return false
        val ok = File(rdir, "$old.rpl").renameTo(dst)
        if (ok) File(rdir, "$old.cam").let { if (it.isFile) it.renameTo(File(rdir, "$n.cam")) }
        return ok
    }

    fun delete(name: String) {
        File(rdir, "$name.rpl").delete()
        File(rdir, "$name.cam").delete()
    }

    class Key(val t: Long, val x: Float, val y: Float, val z: Float, val yaw: Float, val pitch: Float)

    fun readKeys(name: String): List<Key> = try {
        File(rdir, "$name.cam").readLines().mapNotNull { l ->
            val p = l.trim().split(' ')
            if (p.size != 6) null else Key(p[0].toLong(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat())
        }
    } catch (_: Throwable) { emptyList() }

    fun writeKeys(name: String, keys: List<Key>) {
        try {
            rdir.mkdirs()
            val f = File(rdir, "$name.cam")
            if (keys.isEmpty()) f.delete()
            else f.writeText(keys.joinToString("") { String.format(Locale.US, "%d %.3f %.3f %.3f %.2f %.2f\n", it.t, it.x, it.y, it.z, it.yaw, it.pitch) })
        } catch (_: Throwable) {}
    }

    /** Поза свободной камеры для мода: «x y z yaw pitch» (pitch вниз — плюс, как в Unity). */
    fun writeFreeCam(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        try {
            val t = File(dir, "_rpcam.tmp")
            t.writeText(String.format(Locale.US, "%.3f %.3f %.3f %.2f %.2f\n", x, y, z, yaw, pitch))
            t.renameTo(File(dir, "_rpcam"))
        } catch (_: Throwable) {}
    }

    companion object {
        /** Имя, под которым хранятся ключи камеры для просматриваемой записи («_current» — ещё не сохранённая). */
        @Volatile var keyName = "_current"

        fun fmtTime(ms: Long): String = String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)
    }
}
