package com.bearite.patcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Заготовки сеток для примитивов Unity (размеры как у GameObject.CreatePrimitive). */
object PrimMeshes {
    class Mesh(val v: FloatArray, val t: IntArray, val sign: FloatArray, val doubleSided: Boolean)

    private val cache = HashMap<String, Mesh>()

    fun get(shape: String): Mesh = cache.getOrPut(if (shape == "portal") "cylinder" else shape) {
        when (if (shape == "portal") "cylinder" else shape) {
            "sphere" -> {
                val l = ArrayList<Pair<Float, Float>>()
                for (i in 0..8) { val a = Math.PI / 2 - Math.PI * i / 8; l.add((0.5 * sin(a)).toFloat() to (0.5 * cos(a)).toFloat()) }
                rings(l, 14)
            }
            "cylinder" -> rings(listOf(1f to 0f, 1f to 0.5f, -1f to 0.5f, -1f to 0f), 18)
            "capsule" -> {
                val l = ArrayList<Pair<Float, Float>>()
                for (i in 0..4) { val a = Math.PI / 2 * (1 - i / 4.0); l.add((0.5 + 0.5 * sin(a)).toFloat() to (0.5 * cos(a)).toFloat()) }
                for (i in 0..4) { val a = -Math.PI / 2 * (i / 4.0); l.add((-0.5 + 0.5 * sin(a)).toFloat() to (0.5 * cos(a)).toFloat()) }
                rings(l, 14)
            }
            "plane" -> build(floatArrayOf(-5f, 0f, -5f, 5f, 0f, -5f, 5f, 0f, 5f, -5f, 0f, 5f), intArrayOf(0, 1, 2, 0, 2, 3), true)
            else -> build(
                floatArrayOf(
                    -.5f, -.5f, -.5f, .5f, -.5f, -.5f, .5f, .5f, -.5f, -.5f, .5f, -.5f,
                    -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f, .5f, -.5f, .5f, .5f
                ),
                intArrayOf(
                    0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
                    3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2
                ), false
            )
        }
    }

    /** Тело вращения: кольца (y, радиус) сверху вниз. */
    private fun rings(r: List<Pair<Float, Float>>, seg: Int): Mesh {
        val v = FloatArray(r.size * seg * 3)
        for ((i, p) in r.withIndex()) for (k in 0 until seg) {
            val a = 2 * Math.PI * k / seg
            val o = (i * seg + k) * 3
            v[o] = (p.second * cos(a)).toFloat(); v[o + 1] = p.first; v[o + 2] = (p.second * sin(a)).toFloat()
        }
        val t = ArrayList<Int>()
        for (i in 0 until r.size - 1) for (k in 0 until seg) {
            val a = i * seg + k; val b = i * seg + (k + 1) % seg
            val c = (i + 1) * seg + k; val d = (i + 1) * seg + (k + 1) % seg
            if (r[i].second > 1e-4f) { t.add(a); t.add(b); t.add(c) }
            if (r[i + 1].second > 1e-4f) { t.add(b); t.add(d); t.add(c) }
        }
        return build(v, t.toIntArray(), false)
    }

    /** Знак нормали каждого треугольника: наружу от центра (все тела выпуклые и стоят в нуле). */
    private fun build(v: FloatArray, t: IntArray, dbl: Boolean): Mesh {
        val s = FloatArray(t.size / 3)
        if (!dbl) for (i in s.indices) {
            val a = t[i * 3] * 3; val b = t[i * 3 + 1] * 3; val c = t[i * 3 + 2] * 3
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val cx = (v[a] + v[b] + v[c]) / 3; val cy = (v[a + 1] + v[b + 1] + v[c + 1]) / 3; val cz = (v[a + 2] + v[b + 2] + v[c + 2]) / 3
            s[i] = if (nx * cx + ny * cy + nz * cz >= 0) 1f else -1f
        }
        return Mesh(v, t, s, dbl)
    }
}

/**
 * Окно просмотра редактора карт: сетка, объекты с освещением, орбитальная камера и манипуляторы
 * (двигать, вращать, масштабировать) как в Godot. Все координаты в системе карты (как в файле .map).
 */
class MapViewport(ctx: Context, private val map: GameMap) : View(ctx) {
    enum class Tool { SELECT, MOVE, ROTATE, SCALE }

    var tool = Tool.MOVE
        set(v) { field = v; invalidate() }
    var selected = -1
        set(v) { field = v; invalidate() }
    var snap = true
    var onSelect: ((Int) -> Unit)? = null
    /** Объект изменён перетаскиванием (идёт процесс). */
    var onEdited: (() -> Unit)? = null
    /** Перетаскивание закончено: можно сохранять и запоминать для отмены. */
    var onCommit: (() -> Unit)? = null
    var texAvg: ((String) -> Int)? = null  // средний цвет текстуры для предпросмотра
    var gameCam: DoubleArray? = null  // позиция камеры игры в координатах карты (x y z fx fy fz)
    /** Файл картинки текстуры: по нему рисуем настоящую текстуру, а не средний цвет. */
    var texFile: ((String) -> java.io.File?)? = null
    /** Геометрия сцены игры (мировые координаты, по 10 чисел: центр, полуразмеры, цвет, номер текстуры): рисуется закрашенными блоками под объектами карты. */
    var scene: FloatArray? = null
    var sceneMeshes: List<MapStore.SceneMesh>? = null
    var sceneInst: Map<Int, MapStore.SceneInst>? = null
    /** Текстуры материалов мира игры по номеру (maps/scene_tex/N.png). */
    var sceneTexFile: ((Int) -> java.io.File?)? = null
    var showScene = true

    // камера
    private var yaw = 0.75f
    private var pitch = 0.5f
    private var dist = 14f
    private var tx = 0f; private var ty = 0f; private var tz = 0f

    private val d = resources.displayMetrics.density
    private var focal = 1f
    private var ex = 0f; private var ey = 0f; private var ez = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 1f
    private var rx = 1f; private var ry = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 1f; private var uz = 0f

    private val fill = Paint().apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.2f; isAntiAlias = false; strokeJoin = Paint.Join.ROUND }
    private val line = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private val text = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textSize = 11f * d }
    private val path = Path()
    private val texPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false; isFilterBitmap = true }
    private val texMatrix = Matrix()
    private val texSrc = FloatArray(6)
    private val texDst = FloatArray(6)
    private class Tex(val bmp: Bitmap, val shader: BitmapShader) {
        /** Средний цвет текстуры — для блоков, у которых настоящих UV нет. */
        val avg: Int = run {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val w = bmp.width; val h = bmp.height
            for (y in 0 until h step max(1, h / 8)) for (x in 0 until w step max(1, w / 8)) { val c = bmp.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
            if (n == 0) 0xFF888888.toInt() else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
    }
    private val texCache = HashMap<String, Tex?>()

    /** Текстура для предпросмотра: уменьшаем до 256 px по большей стороне, держим в памяти (и неудачу тоже, чтобы не читать файл на каждый треугольник). */
    private fun tex(id: String): Tex? {
        if (texCache.containsKey(id)) return texCache[id]
        val t: Tex? = try {
            val f = texFile?.invoke(id)
            if (f == null) null else {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, b)
                var ss = 1
                while (max(b.outWidth, b.outHeight) / ss > 256) ss *= 2
                val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })
                if (bmp == null) null else Tex(bmp, BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
            }
        } catch (_: Throwable) { null }
        texCache[id] = t
        return t
    }

    /** Координаты текстуры вершины в локальных осях фигуры: куб и плоскость — по грани, тела вращения — вокруг оси Y. */
    private fun uv(shape: String, mesh: PrimMeshes.Mesh, i: Int, nax: Int, out: FloatArray, o: Int) {
        val x = mesh.v[i * 3]; val y = mesh.v[i * 3 + 1]; val z = mesh.v[i * 3 + 2]
        when (shape) {
            "sphere", "cylinder", "capsule", "portal" -> {
                out[o] = (Math.atan2(z.toDouble(), x.toDouble()) / (2 * Math.PI)).toFloat() + 0.5f
                out[o + 1] = if (shape == "sphere") y + 0.5f else if (shape == "capsule") y / 2f + 0.5f else y / 2f + 0.5f
            }
            "plane" -> { out[o] = x / 10f + 0.5f; out[o + 1] = z / 10f + 0.5f }
            else -> when (nax) {
                0 -> { out[o] = z + 0.5f; out[o + 1] = y + 0.5f }
                1 -> { out[o] = x + 0.5f; out[o + 1] = z + 0.5f }
                else -> { out[o] = x + 0.5f; out[o + 1] = y + 0.5f }
            }
        }
    }

    // кадр: треугольники для сортировки и выбора
    private var triScr = FloatArray(0)
    private var triObj = IntArray(0)
    private var triCol = IntArray(0)
    private var triUv = FloatArray(0)
    private var triTex = ByteArray(0)
    private var order = LongArray(0)
    private var triCount = 0
    private var sortedIdx = IntArray(0)
    private var vtmp = FloatArray(0)
    private val sp = FloatArray(3)
    private val sq = FloatArray(3)

    init {
        isFocusable = true
        frameAll()
    }

    // ------------------------------------------------------------------ камера

    fun frameAll() {
        if (map.objects.isEmpty()) { tx = 0f; ty = 0.5f; tz = 0f; dist = 14f; return }
        var mnx = 1e9; var mny = 1e9; var mnz = 1e9; var mxx = -1e9; var mxy = -1e9; var mxz = -1e9
        for (o in map.objects) {
            mnx = min(mnx, o.x); mxx = max(mxx, o.x); mny = min(mny, o.y); mxy = max(mxy, o.y); mnz = min(mnz, o.z); mxz = max(mxz, o.z)
        }
        tx = ((mnx + mxx) / 2).toFloat(); ty = ((mny + mxy) / 2).toFloat(); tz = ((mnz + mxz) / 2).toFloat()
        val r = hypot(hypot(mxx - mnx, mxy - mny), mxz - mnz).toFloat() / 2
        dist = max(12f, r * 2.4f + 6f)
    }

    fun focusSelected() {
        val o = map.objects.getOrNull(selected) ?: return frameAll().also { invalidate() }
        tx = o.x.toFloat(); ty = o.y.toFloat(); tz = o.z.toFloat()
        dist = max(5f, (max(o.sx, max(o.sy, o.sz)) * 3).toFloat())
        invalidate()
    }

    /** Точка, куда ставить новые объекты: центр обзора, на земле. */
    fun spawnPoint(): FloatArray = floatArrayOf(snapV(tx.toDouble(), 0.5).toFloat(), 0f, snapV(tz.toDouble(), 0.5).toFloat())

    private fun updateBasis() {
        val cp = cos(pitch); val sp = sin(pitch)
        ex = tx + dist * cp * sin(yaw); ey = ty + dist * sp; ez = tz + dist * cp * cos(yaw)
        var fxx = tx - ex; var fyy = ty - ey; var fzz = tz - ez
        val fl = sqrt(fxx * fxx + fyy * fyy + fzz * fzz)
        fxx /= fl; fyy /= fl; fzz /= fl
        fx = fxx; fy = fyy; fz = fzz
        // right = cross(up(0,1,0), forward) (левосторонняя система Unity)
        var rxx = fzz; var rzz = -fxx
        val rl = sqrt(rxx * rxx + rzz * rzz)
        rxx /= rl; rzz /= rl
        rx = rxx; ry = 0f; rz = rzz
        ux = fy * rz - fz * ry; uy = fz * rx - fx * rz; uz = fx * ry - fy * rx
        if (gameView && rootView.height > 0) {
            // настоящая камера игры смотрит на весь экран, а вьюпорт — только его часть: берём центр и фокус всего экрана
            val loc = IntArray(2); getLocationInWindow(loc)
            focal = 0.866f * rootView.height
            pcx = rootView.width / 2f - loc[0]; pcy = rootView.height / 2f - loc[1]
        } else { focal = 0.866f * height; pcx = width / 2f; pcy = height / 2f }
    }

    // игровая камера: редактор отдаёт позу, мод ставит её настоящей камере игры, мир рисует сама игра
    var gameView = false
        set(v) { field = v; lastCamSent = 0L; invalidate() }
    var onCamera: ((FloatArray) -> Unit)? = null
    private var pcx = 0f
    private var pcy = 0f
    private var lastCamSent = 0L
    private var lastCamSig = ""

    private fun sendCam() {
        val now = System.currentTimeMillis()
        val sig = "%.3f %.3f %.3f %.4f %.4f %.4f".format(ex, ey, ez, fx, fy, fz)
        if (sig == lastCamSig && now - lastCamSent < 500) { postInvalidateDelayed(520); return }
        lastCamSig = sig; lastCamSent = now
        val sc = if (map.scale == 0.0) 1f else map.scale.toFloat()
        val yaw = Math.toDegrees(Math.atan2(fx.toDouble(), fz.toDouble())).toFloat()
        val pitch = -Math.toDegrees(Math.asin(fy.toDouble().coerceIn(-1.0, 1.0))).toFloat()
        onCamera?.invoke(floatArrayOf(ex * sc + map.ox.toFloat(), ey * sc + map.oy.toFloat(), ez * sc + map.oz.toFloat(), yaw, pitch, 60f))
        postInvalidateDelayed(520)  // сердцебиение: пока редактор открыт, мод держит камеру
    }

    /** Мировая точка → экран. out: x, y, глубина. false, если точка за камерой. */
    private fun proj(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val vx = x - ex; val vy = y - ey; val vz = z - ez
        val cz = vx * fx + vy * fy + vz * fz
        if (cz < 0.05f) return false
        val cx = vx * rx + vy * ry + vz * rz
        val cy = vx * ux + vy * uy + vz * uz
        out[0] = pcx + cx / cz * focal
        out[1] = pcy - cy / cz * focal
        out[2] = cz
        return true
    }

    /** Отрезок с обрезкой по ближней плоскости. */
    private fun seg(c: Canvas, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
        var za = (ax - ex) * fx + (ay - ey) * fy + (az - ez) * fz
        var zb = (bx - ex) * fx + (by - ey) * fy + (bz - ez) * fz
        var x1 = ax; var y1 = ay; var z1 = az; var x2 = bx; var y2 = by; var z2 = bz
        if (za < 0.05f && zb < 0.05f) return
        if (za < 0.05f) { val t = (0.05f - za) / (zb - za); x1 = ax + (bx - ax) * t; y1 = ay + (by - ay) * t; z1 = az + (bz - az) * t }
        else if (zb < 0.05f) { val t = (0.05f - zb) / (za - zb); x2 = bx + (ax - bx) * t; y2 = by + (ay - by) * t; z2 = bz + (az - bz) * t }
        if (proj(x1, y1, z1, sp) && proj(x2, y2, z2, sq)) c.drawLine(sp[0], sp[1], sq[0], sq[1], line)
    }

    // ---------------------------------------------------------------- отрисовка

    private fun rot(o: MapObj, out: FloatArray) = rotXYZ(o.rx, o.ry, o.rz, out)

    private fun rotXYZ(rx: Double, ry: Double, rz: Double, out: FloatArray) {
        val a = Math.toRadians(rx); val b = Math.toRadians(ry); val g = Math.toRadians(rz)
        val cx = cos(a).toFloat(); val sx = sin(a).toFloat()
        val cy = cos(b).toFloat(); val sy = sin(b).toFloat()
        val cz = cos(g).toFloat(); val sz = sin(g).toFloat()
        // R = Ry * Rx * Rz (порядок Unity Euler)
        // Rz
        val z00 = cz; val z01 = -sz; val z10 = sz; val z11 = cz
        // Rx * Rz
        val a00 = z00; val a01 = z01; val a02 = 0f
        val a10 = cx * z10; val a11 = cx * z11; val a12 = -sx
        val a20 = sx * z10; val a21 = sx * z11; val a22 = cx
        // Ry * (Rx*Rz)
        out[0] = cy * a00 + sy * a20; out[1] = cy * a01 + sy * a21; out[2] = cy * a02 + sy * a22
        out[3] = a10; out[4] = a11; out[5] = a12
        out[6] = -sy * a00 + cy * a20; out[7] = -sy * a01 + cy * a21; out[8] = -sy * a02 + cy * a22
    }

    // «Реальный вид»: настоящая картинка мира из игры вместо блоков
    var realView = false
        set(v) { field = v; lastReqAt = 0L; lastSig = ""; invalidate() }
    var viewImage: Bitmap? = null
    /** Мировые параметры камеры: x y z, pitch, yaw (градусы Unity), fov, ширина, высота картинки. */
    var onViewNeeded: ((FloatArray) -> Unit)? = null
    private var lastReqAt = 0L
    private var lastSig = ""
    private val viewSrc = android.graphics.Rect()
    private val viewDst = android.graphics.RectF()
    private val viewPaint = Paint().apply { isFilterBitmap = true }

    private fun requestViewIfMoved() {
        val sig = "%.2f %.2f %.2f %.3f %.3f %.3f %d".format(ex, ey, ez, fx, fy, fz, width)
        val now = System.currentTimeMillis()
        if (sig == lastSig || now - lastReqAt < 300) { if (sig != lastSig) postInvalidateDelayed(320); return }
        lastSig = sig; lastReqAt = now
        val s = if (map.scale == 0.0) 1f else map.scale.toFloat()
        val k = 640f / max(width, height)
        val yaw = Math.toDegrees(Math.atan2(fx.toDouble(), fz.toDouble())).toFloat()
        val pitch = Math.toDegrees(Math.asin(fy.toDouble().coerceIn(-1.0, 1.0))).toFloat() * -1f
        onViewNeeded?.invoke(floatArrayOf(ex * s + map.ox.toFloat(), ey * s + map.oy.toFloat(), ez * s + map.oz.toFloat(), pitch, yaw, 60f, (width * k).toInt().toFloat(), (height * k).toInt().toFloat()))
    }

    private val m9 = FloatArray(9)
    private var bgShader: Shader? = null
    private var bgH = 0
    private val bgPaint = Paint()

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        if (animate) postInvalidateOnAnimation()
        updateBasis()
        if (gameView) {
            sendCam()   // фон не рисуем: сквозь прозрачное окно виден настоящий мир игры
        } else {
            if (map.world) {
                val sk = map.sky
                c.drawColor(Color.rgb((0x1E + (Color.red(sk) - 0x1E) / 2), (0x19 + (Color.green(sk) - 0x19) / 2), (0x14 + (Color.blue(sk) - 0x14) / 2)))
            } else {
                if (bgShader == null || bgH != height) {
                    bgShader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF3B3E8C.toInt(), 0xFF1F2152.toInt(), Shader.TileMode.CLAMP); bgH = height
                }
                bgPaint.shader = bgShader
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            }
            val vi = viewImage
            if (realView && vi != null) {
                viewSrc.set(0, 0, vi.width, vi.height); viewDst.set(0f, 0f, width.toFloat(), height.toFloat())
                c.drawBitmap(vi, viewSrc, viewDst, viewPaint)
            }
            if (realView) requestViewIfMoved()
            drawGrid(c)
            if (!(realView && vi != null)) drawWorld(c)
        }
        drawObjects(c)
        drawCameraMarker(c)
        drawSpawn(c)
        drawPortalLink(c)
        drawGizmo(c)
        drawHud(c)
    }

    private fun drawGrid(c: Canvas) {
        val n = 30
        line.strokeWidth = 1f
        for (i in -n..n) {
            val major = i % 5 == 0
            line.color = if (major) 0x55FFFFFF else 0x22FFFFFF
            if (i != 0) {
                seg(c, i.toFloat(), 0f, -n.toFloat(), i.toFloat(), 0f, n.toFloat())
                seg(c, -n.toFloat(), 0f, i.toFloat(), n.toFloat(), 0f, i.toFloat())
            }
        }
        line.strokeWidth = 1.6f * d
        line.color = 0xCCEB4C5F.toInt(); seg(c, -n.toFloat(), 0f, 0f, n.toFloat(), 0f, 0f)
        line.color = 0xCC4C86EB.toInt(); seg(c, 0f, 0f, -n.toFloat(), 0f, 0f, n.toFloat())
        line.color = 0xCC7EC14A.toInt(); seg(c, 0f, 0f, 0f, 0f, 6f, 0f)
    }

    private val sceneTexCache = HashMap<Int, Tex?>()
    private val sceneFilters = HashMap<Int, LightingColorFilter>()
    private val sq4 = FloatArray(8)
    private val sd4 = FloatArray(8)
    private val sp4 = Array(4) { FloatArray(3) }
    private val qpath = Path()

    private fun sceneTex(i: Int, fine: Boolean = false): Tex? {
        val ck = if (fine) i + 1_000_000 else i
        val lim = if (fine) 128 else 48
        if (sceneTexCache.containsKey(ck)) return sceneTexCache[ck]
        val t: Tex? = try {
            val f = sceneTexFile?.invoke(i)
            var bmp = if (f == null) null else BitmapFactory.decodeFile(f.path)
            // уменьшаем «как мип-карты»: без этого мелкие текстуры (трава, кирпич) на расстоянии рябят шумом
            while (bmp != null && (bmp.width > lim || bmp.height > lim) && bmp.width > 2 && bmp.height > 2)
                bmp = Bitmap.createScaledBitmap(bmp, maxOf(2, bmp.width / 2), maxOf(2, bmp.height / 2), true)
            if (bmp == null) null else Tex(bmp, BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
        } catch (_: Throwable) { null }
        sceneTexCache[ck] = t
        return t
    }

    /** Мир игры: закрашенные блоки (границы рендереров) с цветом и текстурой материала, от дальних к ближним. */
    private fun drawScene(c: Canvas, fast: Boolean = false) {
        val sc = scene ?: return
        if (!showScene || sc.size < 10) return
        val s = if (map.scale == 0.0) 1f else map.scale.toFloat()
        val ox = map.ox.toFloat(); val oy = map.oy.toFloat(); val oz = map.oz.toFloat()
        val n = sc.size / 10
        val dd = FloatArray(n) { j ->
            val dx = (sc[j * 10] - ox) / s - tx; val dy = (sc[j * 10 + 1] - oy) / s - ty; val dz = (sc[j * 10 + 2] - oz) / s - tz
            dx * dx + dy * dy + dz * dz
        }
        var idx = (0 until n).toList()
        val maxObj = if (fast) 350 else 900
        if (n > maxObj) idx = idx.sortedBy { dd[it] }.take(maxObj)
        // порядок: дальние (и крупные, вроде земли) первыми
        val key = FloatArray(n)
        for (j in idx) {
            val cx = (sc[j * 10] - ox) / s; val cy = (sc[j * 10 + 1] - oy) / s; val cz = (sc[j * 10 + 2] - oz) / s
            val big = max(sc[j * 10 + 3], max(sc[j * 10 + 4], sc[j * 10 + 5])) / s
            val dx = cx - ex; val dy = cy - ey; val dz = cz - ez
            key[j] = sqrt(dx * dx + dy * dy + dz * dz) + 2f * big
        }
        val order = idx.sortedByDescending { key[it] }
        // настоящие модели рисуем у самых близких объектов (бюджет треугольников), остальные — блоками
        val meshes = sceneMeshes; val insts = sceneInst
        val meshSet = HashSet<Int>()
        if (meshes != null && insts != null && insts.isNotEmpty()) {
            var budget = if (fast) 4500 else 9000
            for (j in idx.sortedBy { dd[it] }) {
                val ins = insts[j] ?: continue
                val me = meshes.getOrNull(ins.mesh) ?: continue
                val cost = me.idx.size / 3
                if (cost == 0 || cost > budget) continue
                budget -= cost; meshSet.add(j)
                if (budget < 60) break
            }
        }
        for (j in order) {
            if (j in meshSet) {
                val ins = insts!![j]!!
                drawMeshInst(c, meshes!![ins.mesh], ins, sc[j * 10 + 6], sc[j * 10 + 7], sc[j * 10 + 8], sc[j * 10 + 9].toInt(), ox, oy, oz, s, fast)
                continue
            }
            val cx = (sc[j * 10] - ox) / s; val cy = (sc[j * 10 + 1] - oy) / s; val cz = (sc[j * 10 + 2] - oz) / s
            val hx = sc[j * 10 + 3] / s; val hy = sc[j * 10 + 4] / s; val hz = sc[j * 10 + 5] / s
            var cr = sc[j * 10 + 6]; var cg = sc[j * 10 + 7]; var cb = sc[j * 10 + 8]
            if (cr + cg + cb < 0.3f) { cr = 0.55f; cg = 0.55f; cb = 0.55f }
            val tv = sc[j * 10 + 9].toInt()
            val alpha255 = ((1f - (tv / 1000) / 100f) * 255f).toInt().coerceIn(0, 255)
            val avgTex0 = sceneTex(tv % 1000)
            val tex: Tex? = avgTex0.takeIf { false }
            val avgTex = avgTex0
            if (avgTex != null) { cr *= Color.red(avgTex.avg) / 255f; cg *= Color.green(avgTex.avg) / 255f; cb *= Color.blue(avgTex.avg) / 255f }
            val sx = if (ex > cx) 1f else -1f; val sy = if (ey > cy) 1f else -1f; val sz = if (ez > cz) 1f else -1f
            for (face in 0 until 3) {
                // углы грани: a и b — две оси грани, w — размер вдоль них (для числа повторов текстуры)
                val (k, wa, wb) = when (face) { 0 -> Triple(0.85f, 2 * hz, 2 * hy); 1 -> Triple(1.0f, 2 * hx, 2 * hz); else -> Triple(0.7f, 2 * hx, 2 * hy) }
                for (q in 0 until 4) {
                    val a = if (q == 0 || q == 3) -1f else 1f
                    val b = if (q < 2) 1f else -1f   // порядок: (a-,b+) (a+,b+) (a+,b-) (a-,b-)
                    val px: Float; val py: Float; val pz: Float
                    when (face) {
                        0 -> { px = cx + sx * hx; py = cy + b * hy; pz = cz + a * hz }
                        1 -> { px = cx + a * hx; py = cy + sy * hy; pz = cz + b * hz }
                        else -> { px = cx + a * hx; py = cy + b * hy; pz = cz + sz * hz }
                    }
                    if (!proj(px, py, pz, sp4[q])) { sp4[q][2] = -1f }
                }
                if (sp4.any { it[2] < 0f }) continue
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (q in 0 until 4) { minX = min(minX, sp4[q][0]); maxX = max(maxX, sp4[q][0]); minY = min(minY, sp4[q][1]); maxY = max(maxY, sp4[q][1]) }
                if (maxX < 0 || minX > width || maxY < 0 || minY > height || (maxX - minX < 1.5f && maxY - minY < 1.5f)) continue
                qpath.rewind()
                qpath.moveTo(sp4[0][0], sp4[0][1]); qpath.lineTo(sp4[1][0], sp4[1][1]); qpath.lineTo(sp4[2][0], sp4[2][1]); qpath.lineTo(sp4[3][0], sp4[3][1]); qpath.close()
                val mul = Color.argb(alpha255, min(255, (cr * k * 255).toInt()), min(255, (cg * k * 255).toInt()), min(255, (cb * k * 255).toInt()))
                if (tex != null) {
                    val tu = max(1f, min(8f, Math.round(wa).toFloat())); val tv = max(1f, min(8f, Math.round(wb).toFloat()))
                    val bw = tex.bmp.width * tu; val bh = tex.bmp.height * tv
                    sq4[0] = 0f; sq4[1] = 0f; sq4[2] = bw; sq4[3] = 0f; sq4[4] = bw; sq4[5] = bh; sq4[6] = 0f; sq4[7] = bh
                    for (q in 0 until 4) { sd4[q * 2] = sp4[q][0]; sd4[q * 2 + 1] = sp4[q][1] }
                    if (texMatrix.setPolyToPoly(sq4, 0, sd4, 0, 4)) {
                        tex.shader.setLocalMatrix(texMatrix)
                        texPaint.shader = tex.shader
                        texPaint.colorFilter = sceneFilters.getOrPut(mul) { LightingColorFilter(mul, 0) }
                        c.drawPath(qpath, texPaint)
                        continue
                    }
                }
                fill.color = mul
                c.drawPath(qpath, fill)
            }
        }
        texPaint.shader = null
        texPaint.colorFilter = null
    }

    private var mbuf = FloatArray(0)
    private val mpath = Path()
    private val msrc = FloatArray(6)
    private val mdst = FloatArray(6)

    /** Настоящая модель объекта мира: треугольники с текстурой по UV, плоская подсветка, отсечение задних граней. */
    private fun drawMeshInst(c: Canvas, me: MapStore.SceneMesh, ins: MapStore.SceneInst, cr0: Float, cg0: Float, cb0: Float, texIdx0: Int, ox: Float, oy: Float, oz: Float, s: Float, fast: Boolean) {
        val m = ins.m
        val nv = me.v.size / 3
        if (nv < 3) return
        if (mbuf.size < nv * 6) mbuf = FloatArray(nv * 6)
        for (i in 0 until nv) {
            val x = me.v[i * 3]; val y = me.v[i * 3 + 1]; val z = me.v[i * 3 + 2]
            val wx = (m[0] * x + m[4] * y + m[8] * z + m[12] - ox) / s
            val wy = (m[1] * x + m[5] * y + m[9] * z + m[13] - oy) / s
            val wz = (m[2] * x + m[6] * y + m[10] * z + m[14] - oz) / s
            mbuf[i * 6] = wx; mbuf[i * 6 + 1] = wy; mbuf[i * 6 + 2] = wz
            if (proj(wx, wy, wz, sp)) { mbuf[i * 6 + 3] = sp[0]; mbuf[i * 6 + 4] = sp[1]; mbuf[i * 6 + 5] = sp[2] } else mbuf[i * 6 + 5] = -1f
        }
        val det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
        val flip = det < 0f
        val lx = 0.45f; val ly = 0.8f; val lz = -0.4f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val ix = me.idx
        val nsub = if (me.subs.isEmpty()) 1 else me.subs.size / 2
        for (sub in 0 until nsub) {
            val from = if (me.subs.isEmpty()) 0 else me.subs[sub * 2]
            val to = if (me.subs.isEmpty()) ix.size else from + me.subs[sub * 2 + 1]
            // материал этого подмеша
            var cr = cr0; var cg = cg0; var cb = cb0; var ti = texIdx0
            if (ins.mats.size >= sub * 4 + 4) { cr = ins.mats[sub * 4]; cg = ins.mats[sub * 4 + 1]; cb = ins.mats[sub * 4 + 2]; ti = ins.mats[sub * 4 + 3].toInt() }
            if (cr + cg + cb < 0.3f) { cr = 0.6f; cg = 0.6f; cb = 0.6f }
            val alpha255 = ((1f - (ti / 1000) / 100f) * 255f).toInt().coerceIn(0, 255)
            ti %= 1000
            val tex = sceneTex(ti, true)
            val tc = if (fast && tex != null) triColors(ins.mesh, sub, ti, me, from, to, tex) else null
            var t = from
            while (t + 2 < to) {
                val a = ix[t]; val b = if (flip) ix[t + 2] else ix[t + 1]; val cc = if (flip) ix[t + 1] else ix[t + 2]
                t += 3
                if (mbuf[a * 6 + 5] < 0f || mbuf[b * 6 + 5] < 0f || mbuf[cc * 6 + 5] < 0f) continue
                val ax = mbuf[a * 6 + 3]; val ay = mbuf[a * 6 + 4]; val bx = mbuf[b * 6 + 3]; val by = mbuf[b * 6 + 4]; val cx = mbuf[cc * 6 + 3]; val cy = mbuf[cc * 6 + 4]
                val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                if (abs(area) < 1.2f) continue
                if (max(max(ax, bx), cx) < 0 || min(min(ax, bx), cx) > width || max(max(ay, by), cy) < 0 || min(min(ay, by), cy) > height) continue
                val ux = mbuf[b * 6] - mbuf[a * 6]; val uy = mbuf[b * 6 + 1] - mbuf[a * 6 + 1]; val uz = mbuf[b * 6 + 2] - mbuf[a * 6 + 2]
                val vx = mbuf[cc * 6] - mbuf[a * 6]; val vy = mbuf[cc * 6 + 1] - mbuf[a * 6 + 1]; val vz = mbuf[cc * 6 + 2] - mbuf[a * 6 + 2]
                var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                if (nl < 1e-9f) continue
                nx /= nl; ny /= nl; nz /= nl
                val facing = nx * (ex - mbuf[a * 6]) + ny * (ey - mbuf[a * 6 + 1]) + nz * (ez - mbuf[a * 6 + 2])
                if (facing < 0f) continue
                val k = 0.5f + 0.5f * max(0f, (nx * lx + ny * ly + nz * lz) / ll)
                val qk = (Math.round(k * 8f) / 8f)
                mpath.rewind(); mpath.moveTo(ax, ay); mpath.lineTo(bx, by); mpath.lineTo(cx, cy); mpath.close()
                if (tc != null) {
                    // быстрый режим: цвет треугольника берём из текстуры один раз (кэш), без шейдера
                    val tcol = tc[(t - 3 - from) / 3]
                    val ta = Color.alpha(tcol)
                    if (ta < 100) continue
                    fill.color = Color.argb(alpha255 * ta / 255, min(255, (Color.red(tcol) * cr * qk).toInt()), min(255, (Color.green(tcol) * cg * qk).toInt()), min(255, (Color.blue(tcol) * cb * qk).toInt()))
                    c.drawPath(mpath, fill)
                    continue
                }
                val mul = Color.argb(alpha255, min(255, (cr * qk * 255).toInt()), min(255, (cg * qk * 255).toInt()), min(255, (cb * qk * 255).toInt()))
                if (tex != null) {
                    val tw = tex.bmp.width.toFloat(); val th = tex.bmp.height.toFloat()
                    msrc[0] = me.uv[a * 2] * tw; msrc[1] = (1f - me.uv[a * 2 + 1]) * th
                    msrc[2] = me.uv[b * 2] * tw; msrc[3] = (1f - me.uv[b * 2 + 1]) * th
                    msrc[4] = me.uv[cc * 2] * tw; msrc[5] = (1f - me.uv[cc * 2 + 1]) * th
                    mdst[0] = ax; mdst[1] = ay; mdst[2] = bx; mdst[3] = by; mdst[4] = cx; mdst[5] = cy
                    if (texMatrix.setPolyToPoly(msrc, 0, mdst, 0, 3)) {
                        tex.shader.setLocalMatrix(texMatrix)
                        texPaint.shader = tex.shader
                        texPaint.colorFilter = sceneFilters.getOrPut(mul or 0xFF000000.toInt()) { LightingColorFilter(mul or 0xFF000000.toInt(), 0) }
                        texPaint.alpha = alpha255
                        c.drawPath(mpath, texPaint)
                        continue
                    }
                }
                fill.color = mul
                c.drawPath(mpath, fill)
            }
        }
        texPaint.shader = null
        texPaint.colorFilter = null
        texPaint.alpha = 255
    }

    private val triColorCache = HashMap<Long, IntArray>()

    /** Цвет каждого треугольника подмеша: текстура в центре его UV (один раз и в кэш). Нужен для быстрого рисования при движении камеры. */
    private fun triColors(meshIdx: Int, sub: Int, ti: Int, me: MapStore.SceneMesh, from: Int, to: Int, tex: Tex): IntArray {
        val key = meshIdx * 1_000_000L + sub * 10_000L + ti
        triColorCache[key]?.let { return it }
        val n = (to - from) / 3
        val out = IntArray(max(n, 0))
        val bw = tex.bmp.width; val bh = tex.bmp.height
        for (q in 0 until n) {
            val a = me.idx[from + q * 3]; val b = me.idx[from + q * 3 + 1]; val cc = me.idx[from + q * 3 + 2]
            var u = (me.uv[a * 2] + me.uv[b * 2] + me.uv[cc * 2]) / 3f
            var v = (me.uv[a * 2 + 1] + me.uv[b * 2 + 1] + me.uv[cc * 2 + 1]) / 3f
            u -= Math.floor(u.toDouble()).toFloat(); v = 1f - (v - Math.floor(v.toDouble()).toFloat())
            out[q] = tex.bmp.getPixel(min(bw - 1, (u * bw).toInt()), min(bh - 1, (v * bh).toInt()))
        }
        if (triColorCache.size > 400) triColorCache.clear()
        triColorCache[key] = out
        return out
    }

    // кэш мира: во время жестов рисуем упрощённо, когда камера замерла — полную модель один раз в картинку
    private var touching = false
    private var worldBmp: Bitmap? = null
    private var worldSig = ""

    private fun drawWorld(c: Canvas) {
        if (touching || animate) { drawScene(c, fast = true); return }
        val sig = "%.3f %.3f %.3f %.4f %.4f %.4f %d %d %d %d".format(ex, ey, ez, fx, fy, fz, width, height, System.identityHashCode(scene), System.identityHashCode(sceneInst)) + (if (showScene) 1 else 0) + map.scale + map.ox + map.oy + map.oz
        var b = worldBmp
        if (b == null || b.width != width || b.height != height) { b?.recycle(); b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); worldBmp = b; worldSig = "" }
        if (sig != worldSig) {
            b.eraseColor(Color.TRANSPARENT)
            drawScene(Canvas(b), fast = false)
            worldSig = sig
        }
        c.drawBitmap(b, 0f, 0f, null)
    }

    /** Предпросмотр анимаций объектов (кнопка «▶ Анимация»): перерисовываем каждый кадр, пока включено. */
    var animate = false
        set(v) { field = v; animT0 = System.nanoTime(); invalidate() }
    private var animT0 = System.nanoTime()
    private val ps = DoubleArray(9)

    private fun drawObjects(c: Canvas) {
        var maxTris = 0; var maxVerts = 0
        for (o in map.objects) { val m = PrimMeshes.get(o.shape); maxTris += m.t.size / 3; maxVerts = max(maxVerts, m.v.size / 3) }
        if (triScr.size < maxTris * 6) { triScr = FloatArray(maxTris * 6); triObj = IntArray(maxTris); triCol = IntArray(maxTris); order = LongArray(maxTris); sortedIdx = IntArray(maxTris); triUv = FloatArray(maxTris * 6); triTex = ByteArray(maxTris) }
        if (vtmp.size < maxVerts * 6) vtmp = FloatArray(maxVerts * 6)
        triCount = 0
        val lx = 0.45f; val ly = 0.8f; val lz = -0.4f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val p = FloatArray(3)
        for ((oi, o) in map.objects.withIndex()) {
            if (gameView && oi != selected) continue   // остальные объекты уже нарисовала сама игра
            val mesh = PrimMeshes.get(o.shape)
            // предпросмотр анимации: положение объекта в текущий момент
            val anim = animate && o.an.isNotEmpty()
            if (anim) MapAnim.pose(o, (System.nanoTime() - animT0) / 1e9, ps)
            if (anim) rotXYZ(ps[3], ps[4], ps[5], m9) else rot(o, m9)
            val sxx = (if (anim) ps[6] else o.sx).toFloat(); val syy = (if (anim) ps[7] else o.sy).toFloat(); val szz = (if (anim) ps[8] else o.sz).toFloat()
            val opx = (if (anim) ps[0] else o.x).toFloat(); val opy = (if (anim) ps[1] else o.y).toFloat(); val opz = (if (anim) ps[2] else o.z).toFloat()
            val nv = mesh.v.size / 3
            // мир (vtmp[i*6..2]) и экран (vtmp[i*6+3..5]; глубина < 0 → не виден)
            for (i in 0 until nv) {
                val vx = mesh.v[i * 3] * sxx; val vy = mesh.v[i * 3 + 1] * syy; val vz = mesh.v[i * 3 + 2] * szz
                val wx = m9[0] * vx + m9[1] * vy + m9[2] * vz + opx
                val wy = m9[3] * vx + m9[4] * vy + m9[5] * vz + opy
                val wz = m9[6] * vx + m9[7] * vy + m9[8] * vz + opz
                vtmp[i * 6] = wx; vtmp[i * 6 + 1] = wy; vtmp[i * 6 + 2] = wz
                if (proj(wx, wy, wz, p)) { vtmp[i * 6 + 3] = p[0]; vtmp[i * 6 + 4] = p[1]; vtmp[i * 6 + 5] = p[2] }
                else vtmp[i * 6 + 5] = -1f
            }
            var r = Color.red(o.color); var g = Color.green(o.color); var b = Color.blue(o.color)
            val tx0 = if (o.tex.isNotEmpty()) tex(o.tex) else null
            if (o.tex.isNotEmpty() && tx0 == null) {   // картинка не прочиталась: хотя бы средний цвет
                val avg = texAvg?.invoke(o.tex) ?: 0xFF888888.toInt()
                r = r * Color.red(avg) / 255; g = g * Color.green(avg) / 255; b = b * Color.blue(avg) / 255
            }
            for (t in 0 until mesh.t.size / 3) {
                val a = mesh.t[t * 3]; val bb = mesh.t[t * 3 + 1]; val cc = mesh.t[t * 3 + 2]
                if (vtmp[a * 6 + 5] < 0 || vtmp[bb * 6 + 5] < 0 || vtmp[cc * 6 + 5] < 0) continue
                val ax = vtmp[a * 6]; val ay = vtmp[a * 6 + 1]; val az = vtmp[a * 6 + 2]
                val ux2 = vtmp[bb * 6] - ax; val uy2 = vtmp[bb * 6 + 1] - ay; val uz2 = vtmp[bb * 6 + 2] - az
                val wx2 = vtmp[cc * 6] - ax; val wy2 = vtmp[cc * 6 + 1] - ay; val wz2 = vtmp[cc * 6 + 2] - az
                var nx = uy2 * wz2 - uz2 * wy2; var ny = uz2 * wx2 - ux2 * wz2; var nz = ux2 * wy2 - uy2 * wx2
                var s = mesh.sign[t]
                val toEye = nx * (ex - ax) + ny * (ey - ay) + nz * (ez - az)
                if (mesh.doubleSided) s = if (toEye >= 0) 1f else -1f
                else if (toEye * s <= 0) continue
                nx *= s; ny *= s; nz *= s
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                if (nl < 1e-9f) continue
                val lit = max(0f, (nx * lx + ny * ly + nz * lz) / (nl * ll))
                val k = 0.42f + 0.58f * lit
                val i = triCount++
                triScr[i * 6] = vtmp[a * 6 + 3]; triScr[i * 6 + 1] = vtmp[a * 6 + 4]
                triScr[i * 6 + 2] = vtmp[bb * 6 + 3]; triScr[i * 6 + 3] = vtmp[bb * 6 + 4]
                triScr[i * 6 + 4] = vtmp[cc * 6 + 3]; triScr[i * 6 + 5] = vtmp[cc * 6 + 4]
                triObj[i] = oi
                triCol[i] = Color.rgb(min(255, (r * k).toInt()), min(255, (g * k).toInt()), min(255, (b * k).toInt()))
                triTex[i] = 0
                if (tx0 != null) {
                    // грань куба определяем по нормали в осях фигуры (вершины заданы без поворота и размера)
                    val lax = mesh.v[a * 3]; val lay = mesh.v[a * 3 + 1]; val laz = mesh.v[a * 3 + 2]
                    val lux = mesh.v[bb * 3] - lax; val luy = mesh.v[bb * 3 + 1] - lay; val luz = mesh.v[bb * 3 + 2] - laz
                    val lwx = mesh.v[cc * 3] - lax; val lwy = mesh.v[cc * 3 + 1] - lay; val lwz = mesh.v[cc * 3 + 2] - laz
                    val lnx = abs(luy * lwz - luz * lwy); val lny = abs(luz * lwx - lux * lwz); val lnz = abs(lux * lwy - luy * lwx)
                    val nax = if (lnx >= lny && lnx >= lnz) 0 else if (lny >= lnz) 1 else 2
                    uv(o.shape, mesh, a, nax, triUv, i * 6); uv(o.shape, mesh, bb, nax, triUv, i * 6 + 2); uv(o.shape, mesh, cc, nax, triUv, i * 6 + 4)
                    // шов тела вращения: тянем координаты u в одну сторону
                    if (o.shape != "cube" && o.shape != "plane") {
                        val u0 = triUv[i * 6]; val u1 = triUv[i * 6 + 2]; val u2 = triUv[i * 6 + 4]
                        if (max(u0, max(u1, u2)) - min(u0, min(u1, u2)) > 0.5f) {
                            if (u0 < 0.5f) triUv[i * 6] = u0 + 1f
                            if (u1 < 0.5f) triUv[i * 6 + 2] = u1 + 1f
                            if (u2 < 0.5f) triUv[i * 6 + 4] = u2 + 1f
                        }
                    }
                    // цвет объекта работает как оттенок: белый = текстура как есть
                    triCol[i] = Color.rgb(min(255, (Color.red(o.color) * k).toInt()), min(255, (Color.green(o.color) * k).toInt()), min(255, (Color.blue(o.color) * k).toInt()))
                    triTex[i] = 1
                }
                if (gameView) { triCol[i] = (triCol[i] and 0x00FFFFFF) or 0x77000000; triTex[i] = 0 }
                val depth = (vtmp[a * 6 + 5] + vtmp[bb * 6 + 5] + vtmp[cc * 6 + 5]) / 3f
                // ключ: глубина (положительные float сортируются как целые): ближние первыми
                order[i] = (java.lang.Float.floatToIntBits(depth).toLong() shl 32) or i.toLong()
            }
        }
        java.util.Arrays.sort(order, 0, triCount)
        for (k in 0 until triCount) sortedIdx[k] = (order[k] and 0xFFFFFFFFL).toInt()
        // рисуем от дальних к ближним
        for (k in triCount - 1 downTo 0) {
            val i = sortedIdx[k]
            path.rewind()
            path.moveTo(triScr[i * 6], triScr[i * 6 + 1])
            path.lineTo(triScr[i * 6 + 2], triScr[i * 6 + 3])
            path.lineTo(triScr[i * 6 + 4], triScr[i * 6 + 5])
            path.close()
            if (triTex[i].toInt() == 1) {
                val tx = tex(map.objects[triObj[i]].tex)
                if (tx != null) {
                    val bw = tx.bmp.width.toFloat(); val bh = tx.bmp.height.toFloat()
                    for (q in 0 until 3) { texSrc[q * 2] = triUv[i * 6 + q * 2] * bw; texSrc[q * 2 + 1] = (1f - triUv[i * 6 + q * 2 + 1]) * bh; texDst[q * 2] = triScr[i * 6 + q * 2]; texDst[q * 2 + 1] = triScr[i * 6 + q * 2 + 1] }
                    if (texMatrix.setPolyToPoly(texSrc, 0, texDst, 0, 3)) {
                        tx.shader.setLocalMatrix(texMatrix)
                        texPaint.shader = tx.shader
                        texPaint.colorFilter = LightingColorFilter(triCol[i], 0)
                        c.drawPath(path, texPaint)
                        continue
                    }
                }
            }
            fill.color = triCol[i]
            c.drawPath(path, fill)
        }
        // контур выбранного объекта
        val o = map.objects.getOrNull(selected) ?: return
        rot(o, m9)
        line.color = 0xFFFFA52F.toInt(); line.strokeWidth = 2f * d
        val hx = if (o.shape == "plane") 5f * o.sx.toFloat() else 0.5f * o.sx.toFloat()
        val hz = if (o.shape == "plane") 5f * o.sz.toFloat() else 0.5f * o.sz.toFloat()
        val hy = when (o.shape) { "plane" -> 0f; "cylinder", "capsule", "portal" -> 1f * o.sy.toFloat(); else -> 0.5f * o.sy.toFloat() }
        val cs = Array(8) { i ->
            val vx = (if (i and 1 == 0) -hx else hx); val vy = (if (i and 2 == 0) -hy else hy); val vz = (if (i and 4 == 0) -hz else hz)
            floatArrayOf(
                m9[0] * vx + m9[1] * vy + m9[2] * vz + o.x.toFloat(),
                m9[3] * vx + m9[4] * vy + m9[5] * vz + o.y.toFloat(),
                m9[6] * vx + m9[7] * vy + m9[8] * vz + o.z.toFloat()
            )
        }
        for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
            if (i and bit == 0) { val j = i or bit; seg(c, cs[i][0], cs[i][1], cs[i][2], cs[j][0], cs[j][1], cs[j][2]) }
        }
    }

    /** У выбранного круга телепорта рисуем линию к точке назначения. */
    private fun drawPortalLink(c: Canvas) {
        val o = map.objects.getOrNull(selected) ?: return
        if (o.shape != "portal") return
        val p = FloatArray(3)
        when (o.dest) {
            "" -> {
                line.color = 0xFF6FE0D0.toInt(); line.strokeWidth = 2f * d
                seg(c, o.x.toFloat(), o.y.toFloat(), o.z.toFloat(), o.dx.toFloat(), o.dy.toFloat(), o.dz.toFloat())
                if (proj(o.dx.toFloat(), o.dy.toFloat(), o.dz.toFloat(), p)) {
                    fill.color = 0xFF6FE0D0.toInt(); c.drawCircle(p[0], p[1], 6f * d, fill)
                    text.color = 0xFF6FE0D0.toInt(); c.drawText("куда", p[0] + 9f * d, p[1] + 4f * d, text)
                }
            }
            else -> if (proj(o.x.toFloat(), o.y.toFloat(), o.z.toFloat(), p)) {
                text.color = 0xFF6FE0D0.toInt()
                c.drawText(if (o.dest == "@exit") "→ в обычный мир" else "→ другая карта", p[0] + 12f * d, p[1] - 12f * d, text)
            }
        }
    }

    private fun drawSpawn(c: Canvas) {
        if (!map.world) return
        val x = map.spx.toFloat(); val y = map.spy.toFloat(); val z = map.spz.toFloat()
        val p = FloatArray(3)
        if (!proj(x, y, z, p)) return
        line.color = 0xFF6FE0D0.toInt(); line.strokeWidth = 2f * d
        seg(c, x, y, z, x, 0f, z)
        fill.color = 0xFF6FE0D0.toInt()
        c.drawCircle(p[0], p[1], 7f * d, fill)
        text.color = 0xFF6FE0D0.toInt()
        c.drawText("появление", p[0] + 10f * d, p[1] + 4f * d, text)
    }

    private fun drawCameraMarker(c: Canvas) {
        val g = gameCam ?: return
        val p = FloatArray(3)
        if (!proj(g[0].toFloat(), g[1].toFloat(), g[2].toFloat(), p)) return
        line.color = 0xFFD6B18D.toInt(); line.strokeWidth = 2f * d
        seg(c, g[0].toFloat(), g[1].toFloat(), g[2].toFloat(), (g[0] + g[3] * 2).toFloat(), (g[1] + g[4] * 2).toFloat(), (g[2] + g[5] * 2).toFloat())
        fill.color = 0xFFD6B18D.toInt()
        c.drawCircle(p[0], p[1], 6f * d, fill)
        text.color = 0xFFD6B18D.toInt()
        c.drawText("камера игры", p[0] + 9f * d, p[1] + 4f * d, text)
    }

    // ---------------------------------------------------------------- манипуляторы

    private val AX = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
    private val AXC = intArrayOf(0xFFEB4C5F.toInt(), 0xFF7EC14A.toInt(), 0xFF4C86EB.toInt())

    /** Оси манипулятора: мировые для «Двигать/Вращать», локальные (повёрнутые) для «Размер». */
    private fun gizmoAxes(o: MapObj): Array<FloatArray> {
        if (tool != Tool.SCALE) return AX
        rot(o, m9)
        return Array(3) { k -> floatArrayOf(m9[k], m9[3 + k], m9[6 + k]) }
    }

    private fun gizmoLen(o: MapObj): Float {
        val p = FloatArray(3)
        if (!proj(o.x.toFloat(), o.y.toFloat(), o.z.toFloat(), p)) return 0f
        return 90f * d * p[2] / focal
    }

    private fun drawGizmo(c: Canvas) {
        if (tool == Tool.SELECT) return
        val o = map.objects.getOrNull(selected) ?: return
        val len = gizmoLen(o)
        if (len <= 0f) return
        val cx = o.x.toFloat(); val cy = o.y.toFloat(); val cz = o.z.toFloat()
        val ax = gizmoAxes(o)
        val p0 = FloatArray(3); val p1 = FloatArray(3)
        proj(cx, cy, cz, p0)
        for (k in 0 until 3) {
            val hot = dragMode == 1 && dragAxis == k
            line.color = if (hot) 0xFFFFE066.toInt() else AXC[k]
            fill.color = line.color
            when (tool) {
                Tool.MOVE, Tool.SCALE -> {
                    line.strokeWidth = 3f * d
                    val ex2 = cx + ax[k][0] * len; val ey2 = cy + ax[k][1] * len; val ez2 = cz + ax[k][2] * len
                    seg(c, cx, cy, cz, ex2, ey2, ez2)
                    if (proj(ex2, ey2, ez2, p1)) {
                        if (tool == Tool.SCALE) {
                            c.drawRect(p1[0] - 7f * d, p1[1] - 7f * d, p1[0] + 7f * d, p1[1] + 7f * d, fill)
                        } else {
                            val dx = p1[0] - p0[0]; val dy = p1[1] - p0[1]
                            val l = hypot(dx, dy).coerceAtLeast(1f)
                            val nx = dx / l; val ny = dy / l
                            path.rewind()
                            path.moveTo(p1[0] + nx * 14f * d, p1[1] + ny * 14f * d)
                            path.lineTo(p1[0] - ny * 7f * d, p1[1] + nx * 7f * d)
                            path.lineTo(p1[0] + ny * 7f * d, p1[1] - nx * 7f * d)
                            path.close()
                            c.drawPath(path, fill)
                        }
                    }
                }
                Tool.ROTATE -> {
                    line.strokeWidth = 2.5f * d
                    ring(k, cx, cy, cz, len * 0.8f) { a, b, cc, e, f, g -> seg(c, a, b, cc, e, f, g) }
                }
                else -> {}
            }
        }
        // центр: свободное перемещение / общий масштаб
        if (tool == Tool.MOVE || tool == Tool.SCALE) {
            fill.color = if (dragMode == 2) 0xFFFFE066.toInt() else 0xFFF2DFC5.toInt()
            c.drawCircle(p0[0], p0[1], 8f * d, fill)
        }
    }

    private fun ring(k: Int, cx: Float, cy: Float, cz: Float, r: Float, f: (Float, Float, Float, Float, Float, Float) -> Unit) {
        val seg = 40
        var px = 0f; var py = 0f; var pz = 0f
        for (i in 0..seg) {
            val a = (2 * Math.PI * i / seg).toFloat()
            val u = cos(a) * r; val v = sin(a) * r
            val x = cx + if (k == 0) 0f else u
            val y = cy + when (k) { 0 -> u; 1 -> 0f; else -> v }
            val z = cz + when (k) { 0 -> v; 1 -> v; else -> 0f }
            if (i > 0) f(px, py, pz, x, y, z)
            px = x; py = y; pz = z
        }
    }

    private fun drawHud(c: Canvas) {
        text.color = 0x99F2DFC5.toInt()
        val hint = when (tool) {
            Tool.SELECT -> "Тап: выбрать · палец: вращать · два пальца: приблизить и сдвинуть"
            Tool.MOVE -> "Тяни стрелку или центр · палец по пустому: вращать"
            Tool.ROTATE -> "Тяни кольцо · палец по пустому: вращать"
            Tool.SCALE -> "Тяни квадрат (центр — весь размер)"
        }
        c.drawText(hint, 10f * d, height - 10f * d, text)
    }

    // ----------------------------------------------------------------- касания

    private var dragMode = 0   // 0 нет, 1 ось, 2 центр
    private var dragAxis = 0
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var panning = false
    private var lastDist2 = 0f
    private var lastMidX = 0f; private var lastMidY = 0f
    private var startObj: MapObj? = null
    private var axScreenX = 0f; private var axScreenY = 0f; private var axLen = 1f
    private var ringTX = 0f; private var ringTY = 0f; private var ringSign = 1f
    private var hit0 = FloatArray(3)

    private fun snapV(v: Double, step: Double) = if (snap) (v / step).roundToInt() * step else Math.round(v * 100.0) / 100.0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false; panning = false
                dragMode = 0
                if (!tryStartGizmo(e.x, e.y)) dragMode = 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // второй палец: отменяем перетаскивание манипулятора и переходим к камере
                if (dragMode != 0) { dragMode = 0; startObj = null; onCommit?.invoke() }
                panning = true
                lastDist2 = pinchDist(e); lastMidX = (e.getX(0) + e.getX(1)) / 2; lastMidY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (panning && e.pointerCount >= 2) {
                    val dd = pinchDist(e)
                    if (lastDist2 > 0f && dd > 0f) dist = (dist * lastDist2 / dd).coerceIn(1.5f, 250f)
                    lastDist2 = dd
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val k = dist / focal
                    tx -= (rx * (mx - lastMidX) - ux * (my - lastMidY)) * k
                    ty -= (ry * (mx - lastMidX) - uy * (my - lastMidY)) * k
                    tz -= (rz * (mx - lastMidX) - uz * (my - lastMidY)) * k
                    lastMidX = mx; lastMidY = my
                    invalidate()
                } else if (e.pointerCount == 1) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > 10f * d) moved = true
                    if (dragMode != 0) { if (moved) dragGizmo(e.x, e.y) }
                    else if (moved) {
                        yaw += (e.x - lastX) * 0.008f
                        pitch = (pitch + (e.y - lastY) * 0.008f).coerceIn(-1.5f, 1.5f)
                        invalidate()
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                touching = false; postInvalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragMode != 0) { if (moved) onCommit?.invoke() }
                else if (!moved && !panning) {
                    val hit = pick(e.x, e.y)
                    selected = hit
                    onSelect?.invoke(hit)
                }
                dragMode = 0; startObj = null; panning = false
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.getX(1 - e.actionIndex); lastY = e.getY(1 - e.actionIndex); panning = false; moved = true }
            MotionEvent.ACTION_CANCEL -> { dragMode = 0; startObj = null; panning = false; touching = false; postInvalidate() }
        }
        return true
    }

    private fun pinchDist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    private fun tryStartGizmo(x: Float, y: Float): Boolean {
        if (tool == Tool.SELECT) return false
        val o = map.objects.getOrNull(selected) ?: return false
        val len = gizmoLen(o)
        if (len <= 0f) return false
        val c0 = FloatArray(3)
        proj(o.x.toFloat(), o.y.toFloat(), o.z.toFloat(), c0)
        val reach = 24f * d
        val ax = gizmoAxes(o)
        var best = -1; var bestD = reach
        if (tool == Tool.ROTATE) {
            val pa = FloatArray(3); val pb = FloatArray(3)
            for (k in 0 until 3) {
                var prev: FloatArray? = null
                var dmin = 1e9f
                ring(k, o.x.toFloat(), o.y.toFloat(), o.z.toFloat(), len * 0.8f) { a, b, cc, e, f, g ->
                    if (proj(a, b, cc, pa) && proj(e, f, g, pb)) dmin = min(dmin, distToSeg(x, y, pa[0], pa[1], pb[0], pb[1]))
                }
                if (dmin < bestD) { bestD = dmin; best = k }
            }
            if (best < 0) return false
            dragMode = 1; dragAxis = best; startObj = o.copy()
            ringTX = x - c0[0]; ringTY = y - c0[1]
            val l = hypot(ringTX, ringTY).coerceAtLeast(1f)
            ringTX /= l; ringTY /= l
            val toward = ax[best][0] * fx + ax[best][1] * fy + ax[best][2] * fz
            ringSign = if (toward < 0) 1f else -1f
            return true
        }
        // центр
        if (hypot(x - c0[0], y - c0[1]) < 16f * d) {
            dragMode = 2; startObj = o.copy()
            if (tool == Tool.MOVE) groundHit(x, y, o.y.toFloat())?.let { hit0 = it } ?: run { dragMode = 0; return false }
            return true
        }
        val p1 = FloatArray(3)
        for (k in 0 until 3) {
            if (!proj(o.x.toFloat() + ax[k][0] * len, o.y.toFloat() + ax[k][1] * len, o.z.toFloat() + ax[k][2] * len, p1)) continue
            val dd = distToSeg(x, y, c0[0], c0[1], p1[0], p1[1])
            if (dd < bestD) { bestD = dd; best = k }
        }
        if (best < 0) return false
        val p2 = FloatArray(3)
        proj(o.x.toFloat() + ax[best][0] * len, o.y.toFloat() + ax[best][1] * len, o.z.toFloat() + ax[best][2] * len, p2)
        axScreenX = p2[0] - c0[0]; axScreenY = p2[1] - c0[1]
        axLen = max(1f, axScreenX * axScreenX + axScreenY * axScreenY)
        dragMode = 1; dragAxis = best; startObj = o.copy()
        axDirLen = len
        return true
    }

    private var axDirLen = 1f

    private fun groundHit(x: Float, y: Float, planeY: Float): FloatArray? {
        val sx = (x - pcx) / focal; val sy = -(y - pcy) / focal
        val dx = fx + rx * sx + ux * sy; val dy = fy + ry * sx + uy * sy; val dz = fz + rz * sx + uz * sy
        if (abs(dy) < 1e-4f) return null
        val t = (planeY - ey) / dy
        if (t <= 0f) return null
        return floatArrayOf(ex + dx * t, planeY, ez + dz * t)
    }

    private fun dragGizmo(x: Float, y: Float) {
        val o = map.objects.getOrNull(selected) ?: return
        val s = startObj ?: return
        val ddx = x - downX; val ddy = y - downY
        when (tool) {
            Tool.MOVE -> if (dragMode == 1) {
                val t = (ddx * axScreenX + ddy * axScreenY) / axLen * axDirLen
                when (dragAxis) {
                    0 -> o.x = snapV(s.x + t, 0.5)
                    1 -> o.y = snapV(s.y + t, 0.5)
                    else -> o.z = snapV(s.z + t, 0.5)
                }
            } else {
                val h = groundHit(x, y, s.y.toFloat()) ?: return
                o.x = snapV(s.x + (h[0] - hit0[0]), 0.5)
                o.z = snapV(s.z + (h[2] - hit0[2]), 0.5)
            }
            Tool.ROTATE -> {
                val deg = (ddx * -ringTY + ddy * ringTX) / d * 0.8f * ringSign
                fun a(v: Double) = norm(if (snap) Math.round((v + deg) / 15.0) * 15.0 else v + deg)
                when (dragAxis) { 0 -> o.rx = a(s.rx); 1 -> o.ry = a(s.ry); else -> o.rz = a(s.rz) }
            }
            Tool.SCALE -> if (dragMode == 1) {
                val t = (ddx * axScreenX + ddy * axScreenY) / axLen * axDirLen
                fun sv(v: Double) = max(0.05, snapV(v + t, 0.25))
                when (dragAxis) { 0 -> o.sx = sv(s.sx); 1 -> o.sy = sv(s.sy); else -> o.sz = sv(s.sz) }
            } else {
                val k = (1.0 - ddy / (150f * d)).coerceIn(0.05, 20.0)
                o.sx = max(0.05, snapV(s.sx * k, 0.25)); o.sy = max(0.05, snapV(s.sy * k, 0.25)); o.sz = max(0.05, snapV(s.sz * k, 0.25))
            }
            else -> {}
        }
        onEdited?.invoke()
        invalidate()
    }

    private fun norm(a: Double): Double { var v = a % 360.0; if (v > 180) v -= 360; if (v < -180) v += 360; return Math.round(v * 100.0) / 100.0 }

    /** Ближайший к камере объект под пальцем. */
    private fun pick(x: Float, y: Float): Int {
        for (k in 0 until triCount) {
            val i = sortedIdx[k]
            val ax = triScr[i * 6]; val ay = triScr[i * 6 + 1]; val bx = triScr[i * 6 + 2]; val by = triScr[i * 6 + 3]; val cx = triScr[i * 6 + 4]; val cy = triScr[i * 6 + 5]
            val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
            val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
            val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
            val neg = d1 < 0 || d2 < 0 || d3 < 0
            val pos = d1 > 0 || d2 > 0 || d3 > 0
            if (!(neg && pos)) return triObj[i]
        }
        return -1
    }
}
