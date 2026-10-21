package com.bearite.patcher

import java.io.File
import java.util.Locale

/** Один объект карты. Координаты заданы относительно сдвига карты, размеры умножаются на масштаб карты. */
data class MapObj(
    var name: String = "Блок",
    var shape: String = "cube",  // cube, sphere, cylinder, capsule, plane
    var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0,
    var sx: Double = 1.0, var sy: Double = 1.0, var sz: Double = 1.0,
    var rx: Double = 0.0, var ry: Double = 0.0, var rz: Double = 0.0,
    var color: Int = 0x8D5338,  // RGB
    var solid: Boolean = true,
)

data class GameMap(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var scene: String = "",  // пусто = в любой сцене
    var ox: Double = 0.0, var oy: Double = 0.0, var oz: Double = 0.0,
    var scale: Double = 1.0,
    var layer: Int = 0,
    val objects: MutableList<MapObj> = ArrayList(),
)

/** Состояние, которое движок карт пишет из игры (maps/_state.txt). */
data class EngineState(
    val status: String = "",   // ok, error, unknown
    val message: String = "",
    val scene: String = "",
    val cam: DoubleArray? = null,  // x y z fx fy fz
    val objects: Int = 0,
    val fresh: Boolean = false,    // файл обновлялся недавно: движок работает
)

/**
 * Карты хранятся в filesDir/maps/<id>.map обычным текстом. Тот же файл читает нативная часть Bearite
 * (встроенный мод) и строит объекты в игре, поэтому формат менять нужно в обоих местах.
 */
class MapStore(private val dir: File) {
    constructor(act: android.content.Context) : this(File(act.filesDir, "maps"))

    init { dir.mkdirs() }

    fun list(): List<GameMap> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".map") } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { f -> try { parse(f.name.removeSuffix(".map"), f.readText()) } catch (_: Throwable) { null } }

    fun save(m: GameMap) {
        val tmp = File(dir, ".${m.id}.tmp")
        tmp.writeText(serialize(m))
        if (!tmp.renameTo(File(dir, m.id + ".map"))) {
            File(dir, m.id + ".map").writeText(serialize(m))
            tmp.delete()
        }
    }

    fun hasGuard() = File(dir, "_guard").exists()
    fun guardInfo(): String = try { File(dir, "_guard").readText().take(200) } catch (_: Throwable) { "" }
    fun clearGuard() { File(dir, "_guard").delete() }

    fun delete(m: GameMap) { File(dir, m.id + ".map").delete() }

    fun newId(): String {
        var i = 1
        while (File(dir, "map$i.map").exists()) i++
        return "map$i"
    }

    fun create(name: String): GameMap = GameMap(newId(), name).also { save(it) }

    fun duplicate(m: GameMap): GameMap {
        val copy = parse(newId(), serialize(m))
        copy.name = m.name + " (копия)"
        save(copy)
        return copy
    }

    fun readState(): EngineState {
        val f = File(dir, "_state.txt")
        if (!f.isFile) return EngineState()
        val kv = HashMap<String, String>()
        try {
            f.readLines().forEach { l -> l.indexOf('=').let { i -> if (i > 0) kv[l.substring(0, i)] = l.substring(i + 1) } }
        } catch (_: Throwable) {
            return EngineState()
        }
        val cam = kv["cam"]?.trim()?.split(' ')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 6 }?.toDoubleArray()
        return EngineState(
            status = kv["status"] ?: "unknown",
            message = kv["msg"] ?: "",
            scene = kv["scene"] ?: "",
            cam = cam,
            objects = kv["objects"]?.toIntOrNull() ?: 0,
            fresh = System.currentTimeMillis() - f.lastModified() < 5000,
        )
    }

    companion object {
        val SHAPES = listOf("cube", "sphere", "cylinder", "capsule", "plane")
        val SHAPE_NAMES = mapOf("cube" to "Куб", "sphere" to "Шар", "cylinder" to "Цилиндр", "capsule" to "Капсула", "plane" to "Плоскость")

        /** Цвета на выбор в редакторе (RGB). */
        val PALETTE = intArrayOf(
            0x8D5338, 0xD6B18D, 0xF2DFC5, 0xB85F54, 0xB9D58A, 0x5E8C61,
            0x4F8FBF, 0x8E6AD8, 0xE0A030, 0x3A3A3A, 0xFFFFFF, 0x1B1410,
        )

        private fun f(x: Double): String = String.format(Locale.US, "%.4f", x).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        fun serialize(m: GameMap): String = buildString {
            append("name=").append(clean(m.name)).append('\n')
            append("enabled=").append(if (m.enabled) 1 else 0).append('\n')
            append("scene=").append(clean(m.scene)).append('\n')
            append("ox=").append(f(m.ox)).append('\n')
            append("oy=").append(f(m.oy)).append('\n')
            append("oz=").append(f(m.oz)).append('\n')
            append("scale=").append(f(m.scale)).append('\n')
            append("layer=").append(m.layer).append('\n')
            for (o in m.objects) {
                append("O\t").append(clean(o.name)).append('\t').append(o.shape)
                for (v in doubleArrayOf(o.x, o.y, o.z, o.sx, o.sy, o.sz, o.rx, o.ry, o.rz)) append('\t').append(f(v))
                append('\t').append(String.format("%06X", o.color and 0xFFFFFF))
                append('\t').append(if (o.solid) 1 else 0).append('\n')
            }
        }

        fun parse(id: String, text: String): GameMap {
            val m = GameMap(id, id)
            for (raw in text.lines()) {
                val line = raw.trimEnd('\r')
                if (line.isEmpty()) continue
                if (line.startsWith("O\t")) {
                    val p = line.split('\t')
                    if (p.size < 14) continue
                    val n = DoubleArray(9) { p[3 + it].toDoubleOrNull() ?: 0.0 }
                    m.objects.add(
                        MapObj(
                            name = p[1], shape = if (p[2] in SHAPES) p[2] else "cube",
                            x = n[0], y = n[1], z = n[2], sx = n[3], sy = n[4], sz = n[5],
                            rx = n[6], ry = n[7], rz = n[8],
                            color = p[12].toIntOrNull(16) ?: 0x8D5338, solid = p[13] == "1",
                        )
                    )
                    continue
                }
                val i = line.indexOf('=')
                if (i <= 0) continue
                val k = line.substring(0, i)
                val v = line.substring(i + 1)
                when (k) {
                    "name" -> m.name = v.ifBlank { id }
                    "enabled" -> m.enabled = v == "1" || v == "true"
                    "scene" -> m.scene = v
                    "ox" -> m.ox = v.toDoubleOrNull() ?: 0.0
                    "oy" -> m.oy = v.toDoubleOrNull() ?: 0.0
                    "oz" -> m.oz = v.toDoubleOrNull() ?: 0.0
                    "scale" -> m.scale = (v.toDoubleOrNull() ?: 1.0).coerceIn(0.01, 100.0)
                    "layer" -> m.layer = (v.toIntOrNull() ?: 0).coerceIn(0, 31)
                }
            }
            return m
        }

        // ------------------------------------------------------------ шаблоны

        val TEMPLATES = listOf("Пол 20×20", "Лестница", "Арена")

        fun template(index: Int): List<MapObj> = when (index) {
            0 -> listOf(MapObj("Пол", "cube", 0.0, -0.5, 0.0, 20.0, 1.0, 20.0, color = 0x8D5338))
            1 -> (0 until 8).map {
                MapObj("Ступень ${it + 1}", "cube", 0.0, it * 0.6 + 0.3, it * 1.6, 4.0, 0.6, 1.6, color = if (it % 2 == 0) 0xD6B18D else 0x8D5338)
            }
            else -> listOf(
                MapObj("Пол", "cube", 0.0, -0.5, 0.0, 24.0, 1.0, 24.0, color = 0x8D5338),
                MapObj("Стена 1", "cube", 0.0, 1.5, 12.0, 24.0, 3.0, 1.0, color = 0xB85F54),
                MapObj("Стена 2", "cube", 0.0, 1.5, -12.0, 24.0, 3.0, 1.0, color = 0xB85F54),
                MapObj("Стена 3", "cube", 12.0, 1.5, 0.0, 1.0, 3.0, 24.0, color = 0xB85F54),
                MapObj("Стена 4", "cube", -12.0, 1.5, 0.0, 1.0, 3.0, 24.0, color = 0xB85F54),
            )
        }
    }
}
