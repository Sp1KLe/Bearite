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
    var tex: String = "",        // id своей текстуры (maps/textures/<id>.png), пусто = без текстуры
    var tu: Double = 1.0, var tv: Double = 1.0,  // повторы текстуры по осям
    var dest: String = "",       // круг телепорта: "" = точка этой карты, "@exit" = выход из мира, иначе id карты
    var dx: Double = 0.0, var dy: Double = 1.0, var dz: Double = 0.0,  // точка назначения в этой карте
    var clone: Boolean = false,  // с bind: поставить копию платформы на месте круга, а не занимать оригинал
    var bind: String = "",       // имя родного объекта игры (платформа запуска уровня), который круг «занимает»; пусто = свой круг
)

data class GameMap(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var scene: String = "",  // пусто = в любой сцене
    var ox: Double = 0.0, var oy: Double = 0.0, var oz: Double = 0.0,
    var scale: Double = 1.0,
    var layer: Int = 0,
    var world: Boolean = false,       // отдельный мир: остальной мир скрывается, игрок появляется на карте
    var sky: Int = 0x87B8E8,          // цвет неба в отдельном мире
    var spx: Double = 0.0, var spy: Double = 2.0, var spz: Double = 0.0,  // точка появления (в координатах карты)
    val objects: MutableList<MapObj> = ArrayList(),
)

/** Состояние, которое движок карт пишет из игры (maps/_state.txt). */
data class EngineState(
    val status: String = "",   // ok, error, unknown
    val message: String = "",
    val scene: String = "",
    val cam: DoubleArray? = null,  // x y z fx fy fz
    val objects: Int = 0,
    val world: String = "",        // id карты-мира, в котором сейчас игрок
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


    // ------------------------------------------------------------------ текстуры

    private val texDir get() = File(dir, "textures").also { it.mkdirs() }

    /** Id всех импортированных текстур по порядку добавления. */
    fun textures(): List<String> =
        (texDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
            .map { it.name.removeSuffix(".png") }
            .sortedWith(compareBy({ it.length }, { it }))

    fun texturePng(id: String) = File(texDir, "$id.png")

    /**
     * Добавляет картинку из галереи: уменьшает до 512 px, сохраняет PNG для превью и сырой RGBA (.tex) для игры.
     * Игре отдаём сырые пиксели, чтобы не зависеть от того, оставила ли игра в сборке декодер картинок.
     */
    fun importTexture(ctx: android.content.Context, uri: android.net.Uri): String? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        var bm = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("не удалось прочитать картинку")
        val k = 512f / maxOf(bm.width, bm.height)
        if (k < 1f) bm = android.graphics.Bitmap.createScaledBitmap(bm, maxOf(1, (bm.width * k).toInt()), maxOf(1, (bm.height * k).toInt()), true)
        val id = "t" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        File(texDir, "$id.png").outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val w = bm.width; val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val buf = java.nio.ByteBuffer.allocate(8 + w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(w).putInt(h)
        // строки у Unity идут снизу вверх
        for (y in h - 1 downTo 0) for (x in 0 until w) {
            val c = px[y * w + x]
            buf.put(((c shr 16) and 255).toByte()).put(((c shr 8) and 255).toByte()).put((c and 255).toByte()).put(((c ushr 24) and 255).toByte())
        }
        File(texDir, "$id.tex").writeBytes(buf.array())
        id
    } catch (_: Throwable) {
        null
    }

    fun deleteTexture(id: String) { File(texDir, "$id.png").delete(); File(texDir, "$id.tex").delete() }

    private val avgCache = HashMap<String, Int>()

    /** Средний цвет текстуры: для предпросмотра в редакторе. */
    fun textureAverage(id: String): Int = avgCache.getOrPut(id) {
        try {
            val bm = android.graphics.BitmapFactory.decodeFile(texturePng(id).path) ?: return@getOrPut 0xFF888888.toInt()
            val s = android.graphics.Bitmap.createScaledBitmap(bm, 1, 1, true)
            s.getPixel(0, 0) or 0xFF000000.toInt()
        } catch (_: Throwable) { 0xFF888888.toInt() }
    }

    fun clearTextureCache(id: String) { avgCache.remove(id) }

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
            world = kv["world"] ?: "",
            fresh = System.currentTimeMillis() - f.lastModified() < 5000,
        )
    }

    companion object {
        val SHAPES = listOf("cube", "sphere", "cylinder", "capsule", "plane", "portal")
        val SHAPE_NAMES = mapOf("cube" to "Куб", "sphere" to "Шар", "cylinder" to "Цилиндр", "capsule" to "Капсула",
            "plane" to "Плоскость", "portal" to "Круг телепорта")

        /** Новый объект с подходящими размерами и цветом для формы. */
        fun newObj(shape: String): MapObj {
            val o = MapObj(SHAPE_NAMES[shape] ?: "Блок", shape)
            when (shape) {
                "plane" -> { o.sx = 2.0; o.sz = 2.0 }
                "portal" -> { o.sx = 3.0; o.sy = 0.1; o.sz = 3.0; o.color = 0x6FE0D0; o.solid = false }
            }
            return o
        }

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
            if (m.world) {
                append("world=1\n")
                append("sky=").append(String.format("%06X", m.sky and 0xFFFFFF)).append('\n')
                append("spx=").append(f(m.spx)).append('\n')
                append("spy=").append(f(m.spy)).append('\n')
                append("spz=").append(f(m.spz)).append('\n')
            }
            for (o in m.objects) {
                append("O\t").append(clean(o.name)).append('\t').append(o.shape)
                for (v in doubleArrayOf(o.x, o.y, o.z, o.sx, o.sy, o.sz, o.rx, o.ry, o.rz)) append('\t').append(f(v))
                append('\t').append(String.format("%06X", o.color and 0xFFFFFF))
                append('\t').append(if (o.solid) 1 else 0)
                append('\t').append(clean(o.tex)).append('\t').append(f(o.tu)).append('\t').append(f(o.tv))
                append('\t').append(clean(o.dest)).append('\t').append(f(o.dx)).append('\t').append(f(o.dy)).append('\t').append(f(o.dz)).append('\t').append(clean(o.bind)).append('\t').append(if (o.clone) 1 else 0).append('\n')
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
                            tex = p.getOrNull(14) ?: "",
                            tu = p.getOrNull(15)?.toDoubleOrNull() ?: 1.0, tv = p.getOrNull(16)?.toDoubleOrNull() ?: 1.0,
                            dest = p.getOrNull(17) ?: "",
                            dx = p.getOrNull(18)?.toDoubleOrNull() ?: 0.0, dy = p.getOrNull(19)?.toDoubleOrNull() ?: 1.0,
                            dz = p.getOrNull(20)?.toDoubleOrNull() ?: 0.0,
                            bind = p.getOrNull(21) ?: "",
                            clone = p.getOrNull(22) == "1",
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
                    "world" -> m.world = v == "1" || v == "true"
                    "sky" -> m.sky = v.trim().toIntOrNull(16) ?: 0x87B8E8
                    "spx" -> m.spx = v.toDoubleOrNull() ?: 0.0
                    "spy" -> m.spy = v.toDoubleOrNull() ?: 2.0
                    "spz" -> m.spz = v.toDoubleOrNull() ?: 0.0
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
