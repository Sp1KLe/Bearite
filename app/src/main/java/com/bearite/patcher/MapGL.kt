package com.bearite.patcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30 as G
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Мир игры в редакторе: настоящие модели с UV, текстурами, тайлингом и прозрачностью на OpenGL ES 3.
 * Игра только отдаёт данные сцены (меши, матрицы, материалы), рисует их сам редактор, поэтому качество
 * одинаковое в покое и при движении камеры. Объекты карты, сетка и манипуляторы рисует поверх MapViewport.
 */
class MapGL(ctx: Context, private val texFile: (Int) -> File?) : GLSurfaceView(ctx), GLSurfaceView.Renderer {

    /** ex ey ez, r(3), u(3), f(3), focal, w, h, ox oy oz scale */
    @Volatile private var cam: FloatArray? = null
    @Volatile private var meshes: List<MapStore.SceneMesh>? = null
    @Volatile private var insts: Map<Int, MapStore.SceneInst>? = null
    @Volatile private var dataVer = 0
    private var seenVer = -1
    @Volatile var sky = intArrayOf(0xFF6FA8DC.toInt(), 0xFFCFE6F7.toInt())  // верх, низ

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setData(m: List<MapStore.SceneMesh>?, i: Map<Int, MapStore.SceneInst>?) {
        meshes = m; insts = i; dataVer++; requestRender()
    }

    fun setCamera(c: FloatArray) { cam = c; requestRender() }

    // ------------------------------------------------------------------ GL-ресурсы (только в потоке GL)

    private class GpuMesh(val vbo: Int, val ibo: Int, val cx: Float, val cy: Float, val cz: Float, val r: Float)

    private var prog = 0
    private var skyProg = 0
    private var uMvp = 0; private var uMv = 0; private var uCol = 0; private var uSt = 0; private var uUseTex = 0; private var uTex = 0
    private var skyTop = 0; private var skyBot = 0
    private val gpu = HashMap<Int, GpuMesh?>()
    private val texIds = HashMap<Int, Int>()   // номер текстуры -> id GL (0 — не вышло)
    private var quadBuf = 0
    private var pendingTex = false

    private fun compile(type: Int, src: String): Int {
        val s = G.glCreateShader(type)
        G.glShaderSource(s, src); G.glCompileShader(s)
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = G.glCreateProgram()
        G.glAttachShader(p, compile(G.GL_VERTEX_SHADER, vs))
        G.glAttachShader(p, compile(G.GL_FRAGMENT_SHADER, fs))
        G.glLinkProgram(p)
        return p
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = link(
            """#version 300 es
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec2 aUv;
            uniform mat4 uMvp; uniform mat4 uMv; uniform vec4 uSt;
            out vec2 vUv; out vec3 vView;
            void main() {
                vec4 p = vec4(aPos, 1.0);
                gl_Position = uMvp * p;
                vView = (uMv * p).xyz;
                vUv = aUv * uSt.xy + uSt.zw;
            }""",
            """#version 300 es
            precision mediump float;
            in vec2 vUv; in vec3 vView;
            uniform sampler2D uTex; uniform vec4 uCol; uniform float uUseTex;
            out vec4 o;
            void main() {
                vec4 c = uCol;
                if (uUseTex > 0.5) { vec4 t = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)); c.rgb *= t.rgb; if (uCol.a < 0.999) c.a *= t.a; }
                vec3 n = normalize(cross(dFdx(vView), dFdy(vView)));
                float l = abs(dot(n, normalize(vec3(0.35, 0.8, -0.45))));
                c.rgb *= 0.62 + 0.38 * l;
                if (c.a < 0.02) discard;
                o = c;
            }"""
        )
        uMvp = G.glGetUniformLocation(prog, "uMvp"); uMv = G.glGetUniformLocation(prog, "uMv")
        uCol = G.glGetUniformLocation(prog, "uCol"); uSt = G.glGetUniformLocation(prog, "uSt")
        uUseTex = G.glGetUniformLocation(prog, "uUseTex"); uTex = G.glGetUniformLocation(prog, "uTex")
        skyProg = link(
            """#version 300 es
            layout(location=0) in vec2 aP; out float vY;
            void main() { vY = aP.y * 0.5 + 0.5; gl_Position = vec4(aP, 0.999, 1.0); }""",
            """#version 300 es
            precision mediump float; in float vY; uniform vec3 uTop; uniform vec3 uBot; out vec4 o;
            void main() { o = vec4(mix(uBot, uTop, clamp(vY, 0.0, 1.0)), 1.0); }"""
        )
        skyTop = G.glGetUniformLocation(skyProg, "uTop"); skyBot = G.glGetUniformLocation(skyProg, "uBot")
        val q = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val ids = IntArray(1); G.glGenBuffers(1, ids, 0); quadBuf = ids[0]
        G.glBindBuffer(G.GL_ARRAY_BUFFER, quadBuf)
        G.glBufferData(G.GL_ARRAY_BUFFER, q.size * 4, ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(q).also { it.position(0) }, G.GL_STATIC_DRAW)
        // контекст пересоздан: все старые ресурсы недействительны
        gpu.clear(); texIds.clear(); seenVer = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { G.glViewport(0, 0, width, height) }

    private fun upload(idx: Int, m: MapStore.SceneMesh): GpuMesh? {
        val nv = m.v.size / 3
        if (nv == 0 || nv > 65000 || m.idx.isEmpty()) return null
        val vb = ByteBuffer.allocateDirect(nv * 20).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var minX = 1e9f; var minY = 1e9f; var minZ = 1e9f; var maxX = -1e9f; var maxY = -1e9f; var maxZ = -1e9f
        val hasUv = m.uv.size >= nv * 2
        for (i in 0 until nv) {
            val x = m.v[i * 3]; val y = m.v[i * 3 + 1]; val z = m.v[i * 3 + 2]
            vb.put(x).put(y).put(z).put(if (hasUv) m.uv[i * 2] else 0f).put(if (hasUv) m.uv[i * 2 + 1] else 0f)
            minX = minOf(minX, x); maxX = max(maxX, x); minY = minOf(minY, y); maxY = max(maxY, y); minZ = minOf(minZ, z); maxZ = max(maxZ, z)
        }
        vb.position(0)
        val ib = ByteBuffer.allocateDirect(m.idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (v in m.idx) ib.put(v.toShort())
        ib.position(0)
        val ids = IntArray(2); G.glGenBuffers(2, ids, 0)
        G.glBindBuffer(G.GL_ARRAY_BUFFER, ids[0]); G.glBufferData(G.GL_ARRAY_BUFFER, nv * 20, vb, G.GL_STATIC_DRAW)
        G.glBindBuffer(G.GL_ELEMENT_ARRAY_BUFFER, ids[1]); G.glBufferData(G.GL_ELEMENT_ARRAY_BUFFER, m.idx.size * 2, ib, G.GL_STATIC_DRAW)
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        return GpuMesh(ids[0], ids[1], cx, cy, cz, sqrt(dx * dx + dy * dy + dz * dz) / 2f)
    }

    private fun texture(i: Int): Int {
        texIds[i]?.let { return it }
        if (loadedThisFrame >= 4) { pendingTex = true; return 0 }
        ++loadedThisFrame
        var id = 0
        try {
            val f = texFile(i)
            if (f != null) {
                val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(f.path, o)
                var ss = 1
                while (o.outWidth / ss > 1024 || o.outHeight / ss > 1024) ss *= 2
                val o2 = BitmapFactory.Options(); o2.inSampleSize = ss
                val bmp: Bitmap? = BitmapFactory.decodeFile(f.path, o2)
                if (bmp != null) {
                    val t = IntArray(1); G.glGenTextures(1, t, 0); id = t[0]
                    G.glBindTexture(G.GL_TEXTURE_2D, id)
                    GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, bmp, 0)
                    G.glGenerateMipmap(G.GL_TEXTURE_2D)
                    G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_MIN_FILTER, G.GL_LINEAR_MIPMAP_LINEAR)
                    G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_MAG_FILTER, G.GL_LINEAR)
                    G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_WRAP_S, G.GL_REPEAT)
                    G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_WRAP_T, G.GL_REPEAT)
                    bmp.recycle()
                }
            }
        } catch (_: Throwable) { id = 0 }
        texIds[i] = id
        return id
    }

    private var loadedThisFrame = 0
    private val mvp = FloatArray(16)
    private val mv = FloatArray(16)
    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val tmp = FloatArray(16)

    private fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
    }

    private class Item(val ins: MapStore.SceneInst, val mesh: MapStore.SceneMesh, val g: GpuMesh, val mvp: FloatArray, val mv: FloatArray, val depth: Float)
    private class Part(val item: Item, val sub: Int, val depth: Float)

    override fun onDrawFrame(gl: GL10?) {
        loadedThisFrame = 0
        val c = cam
        val ms = meshes; val ins = insts
        if (seenVer != dataVer) {
            seenVer = dataVer
            for (g in gpu.values) if (g != null) G.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0)
            gpu.clear()
        }
        G.glDisable(G.GL_SCISSOR_TEST)
        G.glClearColor(0.43f, 0.66f, 0.86f, 1f)
        G.glClear(G.GL_COLOR_BUFFER_BIT or G.GL_DEPTH_BUFFER_BIT)
        // небо: вертикальный градиент
        G.glDisable(G.GL_DEPTH_TEST)
        G.glUseProgram(skyProg)
        fun rgb(v: Int, loc: Int) = G.glUniform3f(loc, ((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
        rgb(sky[0], skyTop); rgb(sky[1], skyBot)
        G.glBindBuffer(G.GL_ARRAY_BUFFER, quadBuf)
        G.glEnableVertexAttribArray(0); G.glVertexAttribPointer(0, 2, G.GL_FLOAT, false, 8, 0)
        G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
        G.glDisableVertexAttribArray(0)
        if (c == null || ms == null || ins == null || ins.isEmpty()) return

        val ex = c[0]; val ey = c[1]; val ez = c[2]
        val focal = c[12]; val w = c[13]; val h = c[14]
        val ox = c[15]; val oy = c[16]; val oz = c[17]; val s = c[18]
        val n = 0.1f; val f = 6000f
        java.util.Arrays.fill(view, 0f)
        view[0] = c[3]; view[4] = c[4]; view[8] = c[5]; view[12] = -(c[3] * ex + c[4] * ey + c[5] * ez)
        view[1] = c[6]; view[5] = c[7]; view[9] = c[8]; view[13] = -(c[6] * ex + c[7] * ey + c[8] * ez)
        view[2] = c[9]; view[6] = c[10]; view[10] = c[11]; view[14] = -(c[9] * ex + c[10] * ey + c[11] * ez)
        view[15] = 1f
        java.util.Arrays.fill(proj, 0f)
        proj[0] = 2f * focal / w; proj[5] = 2f * focal / h
        proj[10] = (f + n) / (f - n); proj[14] = -2f * f * n / (f - n); proj[11] = 1f
        val halfW = w / (2f * focal); val halfH = h / (2f * focal)

        val opaque = ArrayList<Item>()
        val blend = ArrayList<Part>()
        for ((j, inst) in ins) {
            val mesh = ms.getOrNull(inst.mesh) ?: continue
            val g = gpu.getOrPut(inst.mesh) { upload(inst.mesh, mesh) } ?: continue
            val m = inst.m
            // модель в координатах редактора: (мир − смещение карты) / масштаб
            for (k in 0 until 16) model[k] = m[k]
            for (k in 0 until 12) model[k] = m[k] / s
            model[12] = (m[12] - ox) / s; model[13] = (m[13] - oy) / s; model[14] = (m[14] - oz) / s; model[15] = 1f
            mul(view, model, mv)
            // отсечение по сфере модели
            val cvx = mv[0] * g.cx + mv[4] * g.cy + mv[8] * g.cz + mv[12]
            val cvy = mv[1] * g.cx + mv[5] * g.cy + mv[9] * g.cz + mv[13]
            val cvz = mv[2] * g.cx + mv[6] * g.cy + mv[10] * g.cz + mv[14]
            val sc = max(sqrt(mv[0] * mv[0] + mv[1] * mv[1] + mv[2] * mv[2]), max(sqrt(mv[4] * mv[4] + mv[5] * mv[5] + mv[6] * mv[6]), sqrt(mv[8] * mv[8] + mv[9] * mv[9] + mv[10] * mv[10])))
            val rr = g.r * sc
            if (cvz + rr < n || cvz - rr > f) continue
            if (abs(cvx) > cvz * halfW + rr * 1.5f || abs(cvy) > cvz * halfH + rr * 1.5f) continue
            mul(proj, mv, mvp)
            val it = Item(inst, mesh, g, mvp.copyOf(), mv.copyOf(), cvz)
            val nsub = if (mesh.subs.isEmpty()) 1 else mesh.subs.size / 2
            var anyBlend = false
            for (sub in 0 until nsub) {
                val ti = if (inst.mats.size >= sub * 8 + 8) inst.mats[sub * 8 + 3].toInt() else 0
                if (ti / 1000 > 0) { blend.add(Part(it, sub, cvz)); anyBlend = true }
            }
            if (!anyBlend || nsub > 1) opaque.add(it)
            @Suppress("UNUSED_VARIABLE") val unused = j
        }

        G.glEnable(G.GL_DEPTH_TEST)
        G.glDepthFunc(G.GL_LEQUAL)
        G.glDisable(G.GL_CULL_FACE)
        G.glUseProgram(prog)
        G.glActiveTexture(G.GL_TEXTURE0); G.glUniform1i(uTex, 0)
        G.glEnableVertexAttribArray(0); G.glEnableVertexAttribArray(1)
        G.glDisable(G.GL_BLEND)
        G.glDepthMask(true)
        for (it in opaque) drawItem(it, false)
        // прозрачные подмеши: от дальних к ближним, без записи глубины
        G.glEnable(G.GL_BLEND)
        G.glBlendFunc(G.GL_SRC_ALPHA, G.GL_ONE_MINUS_SRC_ALPHA)
        G.glDepthMask(false)
        blend.sortByDescending { it.depth }
        var lastItem: Item? = null
        for (p in blend) {
            if (p.item !== lastItem) { bind(p.item); lastItem = p.item }
            drawSub(p.item, p.sub)
        }
        G.glDepthMask(true)
        G.glDisable(G.GL_BLEND)
        G.glDisableVertexAttribArray(0); G.glDisableVertexAttribArray(1)
        if (pendingTex) { pendingTex = false; requestRender() }
    }

    private fun bind(it: Item) {
        G.glUniformMatrix4fv(uMvp, 1, false, it.mvp, 0)
        G.glUniformMatrix4fv(uMv, 1, false, it.mv, 0)
        G.glBindBuffer(G.GL_ARRAY_BUFFER, it.g.vbo)
        G.glBindBuffer(G.GL_ELEMENT_ARRAY_BUFFER, it.g.ibo)
        G.glVertexAttribPointer(0, 3, G.GL_FLOAT, false, 20, 0)
        G.glVertexAttribPointer(1, 2, G.GL_FLOAT, false, 20, 12)
    }

    private fun drawItem(it: Item, onlyBlend: Boolean) {
        bind(it)
        val nsub = if (it.mesh.subs.isEmpty()) 1 else it.mesh.subs.size / 2
        for (sub in 0 until nsub) {
            val ti = if (it.ins.mats.size >= sub * 8 + 8) it.ins.mats[sub * 8 + 3].toInt() else 0
            if (ti / 1000 > 0) continue   // прозрачный подмеш рисуется во втором проходе
            drawSub(it, sub)
        }
    }

    private fun drawSub(it: Item, sub: Int) {
        val mesh = it.mesh
        val from = if (mesh.subs.isEmpty()) 0 else mesh.subs[sub * 2]
        val cnt = if (mesh.subs.isEmpty()) mesh.idx.size else mesh.subs[sub * 2 + 1]
        if (cnt <= 0 || from + cnt > mesh.idx.size) return
        var r = 0.72f; var g = 0.7f; var b = 0.66f; var ti = 0
        var st0 = 1f; var st1 = 1f; var st2 = 0f; var st3 = 0f
        if (it.ins.mats.size >= sub * 8 + 8) {
            val mm = it.ins.mats
            r = mm[sub * 8]; g = mm[sub * 8 + 1]; b = mm[sub * 8 + 2]; ti = mm[sub * 8 + 3].toInt()
            st0 = mm[sub * 8 + 4]; st1 = mm[sub * 8 + 5]; st2 = mm[sub * 8 + 6]; st3 = mm[sub * 8 + 7]
        }
        val alpha = 1f - (ti / 1000) / 100f
        ti %= 1000
        val tid = if (ti > 0) texture(ti) else 0
        if (tid != 0) { G.glBindTexture(G.GL_TEXTURE_2D, tid); G.glUniform1f(uUseTex, 1f) } else G.glUniform1f(uUseTex, 0f)
        if (tid == 0 && r + g + b < 0.3f) { r = 0.6f; g = 0.6f; b = 0.6f }
        if (tid != 0) { r = 1f; g = 1f; b = 1f }   // с текстурой цвет материала почти всегда белый; оттенок убирает «грязь» на светлых текстурах
        G.glUniform4f(uCol, r, g, b, alpha)
        G.glUniform4f(uSt, st0, st1, st2, st3)
        G.glDrawElements(G.GL_TRIANGLES, cnt, G.GL_UNSIGNED_SHORT, from * 2)
    }
}
