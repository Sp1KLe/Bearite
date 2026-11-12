package com.bearite.patcher

import android.graphics.BitmapFactory
import kotlin.math.abs
import android.app.Activity
import java.io.File
import android.app.Dialog
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Редактор карты в духе Godot: окно просмотра с манипуляторами, дерево сцены слева, инспектор справа,
 * панель инструментов сверху, отмена и повтор. Правки сразу пишутся в файл карты.
 */
class MapEditor(
    private val act: Activity,
    private val ui: GameUi,
    private val store: MapStore,
    private val map: GameMap,
    private val onClose: () -> Unit,
) {
    private val dlg = Dialog(act, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    private val vp = MapViewport(act, map)
    private val vpHost = android.widget.FrameLayout(act).apply {
        addView(vp, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val tree = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val inspector = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val toolBtns = HashMap<MapViewport.Tool, TextView>()
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var animBtn: TextView
    private lateinit var snapBtn: TextView
    private lateinit var sceneBtn: TextView
    private lateinit var realBtn: TextView
    private var rootLayout: LinearLayout? = null
    private lateinit var gameBtn: TextView
    private var sceneAskedAt = 0L
    private lateinit var titleView: TextView

    private val undo = ArrayList<String>()
    private val redo = ArrayList<String>()
    private var lastSnap = MapStore.serialize(map)
    private var lastKey: String? = null
    private var lastKeyAt = 0L

    /** Поля инспектора, которые обновляются, пока тянут манипулятор. */
    private val liveFields = ArrayList<Pair<EditText, () -> Double>>()
    private var updating = false

    private fun dp(v: Int) = ui.dp(v)

    fun show() {
        ui.flat = true   // кнопки и переключатели в стиле Material Design 3, пока открыт редактор
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        vp.gameCam = camInMap()
        vp.texAvg = { store.textureAverage(it) }
        vp.texFile = { store.textureFile(it) }
        vp.sceneTexFile = { store.sceneTexFile(it) }
        vp.onSelect = { select(it) }
        vp.onEdited = { refreshLive() }
        vp.onCommit = { commit(); refreshTree() }
        dlg.setContentView(buildRoot())
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(M3.SURFACE))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            decorView.systemUiVisibility = act.window.decorView.systemUiVisibility
        }
        dlg.setOnDismissListener { ui.flat = false; sceneAskedAt = -1L; vp.onCamera = null; store.clearEdCam(); onClose() }
        vp.propBox = { o -> propBoxOf(o) }
        vp.showScene = false
        setGameView(false)
        loadScene(auto = true)   // мир рисовать не нужно, данные нужны только для каталога моделей
        refreshTree()
        showInspector()
        dlg.show()
    }

    // ------------------------------------------------------------------ каркас

    private lateinit var treeDock: LinearLayout
    private lateinit var insDock: LinearLayout

    /** Экран редактора: на весь экран вид сцены, сверху одна узкая панель с иконками; списки и свойства выезжают поверх по кнопкам ☰ и ⚙. */
    private fun buildRoot(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(M3.SURFACE) }
        rootLayout = root
        root.addView(toolbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val dm = act.resources.displayMetrics
        val stage = android.widget.FrameLayout(act)
        stage.addView(vpHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        treeDock = dock("Сцена", tree, true).apply { visibility = View.GONE; alpha = 0.97f }
        insDock = dock("Инспектор", inspector, false).apply { visibility = View.GONE; alpha = 0.97f }
        val wTree = minOf(dp(230), (dm.widthPixels * 0.78f).toInt())
        val wIns = minOf(dp(300), (dm.widthPixels * 0.88f).toInt())
        stage.addView(treeDock, android.widget.FrameLayout.LayoutParams(wTree, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        stage.addView(insDock, android.widget.FrameLayout.LayoutParams(wIns, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun togglePanel(tree: Boolean) {
        val narrow = act.resources.displayMetrics.widthPixels < dp(700)
        val target = if (tree) treeDock else insDock
        val other = if (tree) insDock else treeDock
        val show = target.visibility != View.VISIBLE
        target.visibility = if (show) View.VISIBLE else View.GONE
        if (show && narrow) other.visibility = View.GONE
        panelBtns[0]?.alpha = if (treeDock.visibility == View.VISIBLE) 1f else 0.75f
        panelBtns[1]?.alpha = if (insDock.visibility == View.VISIBLE) 1f else 0.75f
    }

    private val panelBtns = arrayOfNulls<TextView>(2)

    private fun dock(title: String, content: LinearLayout, left: Boolean): LinearLayout {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(M3.CONTAINER) }
        if (title == "Сцена") {
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(6), dp(4)) }
            head.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            head.addView(mini("＋") { addMenu() })
            head.addView(mini("⧉") { duplicateSelected() })
            head.addView(mini("🗑") { deleteSelected() })
            box.addView(head)
        }
        val sv = ScrollView(act).apply { isFillViewport = false; addView(content) }
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.setPadding(dp(8), dp(6), dp(8), dp(16))
        return box
    }

    private fun mini(t: String, onClick: () -> Unit) = ui.label(t, 18f).apply {
        gravity = Gravity.CENTER
        background = ui.round(M3.TONAL, 22)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) }
    }

    /** Дополнительные действия: всё редкое спрятано в одно меню. */
    private fun moreMenu() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var d: android.app.Dialog? = null
        fun item(t: String, run: () -> Unit) = col.addView(ui.pill(t, sizeSp = 14f) { d?.dismiss(); run() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        item(if (vp.snap) "Сетка: вкл" else "Сетка: выкл") { vp.snap = !vp.snap; paintTools() }
        item(if (vp.animate) "■ Остановить анимацию" else "▶ Показать анимацию") { vp.animate = !vp.animate; paintTools() }
        item("Фокус на объекте") { vp.focusSelected() }
        item("К камере игры") { toGameCamera() }
        item("Экспорт .glb") { exportGlb() }
        item("Обновить каталог моделей") { loadScene() }
        d = ui.dialog("Ещё", col, listOf(GameUi.Btn("Закрыть")), widthDp = 320)
        d.show()
    }

    private fun toolbar(): View {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(M3.CONTAINER)
            setPadding(dp(6), 0, dp(6), 0)
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(mini("‹") { dlg.dismiss() }.apply { background = ui.round(M3.PRIMARY, 22); setTextColor(M3.ON_PRIMARY) })
        val tb = mini("☰") { togglePanel(true) }; panelBtns[0] = tb; tb.alpha = 0.75f
        row.addView(tb)
        row.addView(mini("＋") { addMenu() }.apply { background = ui.round(M3.PRIMARY, 22); setTextColor(M3.ON_PRIMARY) })
        undoBtn = mini("↶") { doUndo() }
        redoBtn = mini("↷") { doRedo() }
        row.addView(undoBtn); row.addView(redoBtn)
        row.addView(View(act), LinearLayout.LayoutParams(dp(8), 1))
        for ((tool, name) in listOf(
            MapViewport.Tool.SELECT to "☝", MapViewport.Tool.MOVE to "✥",
            MapViewport.Tool.ROTATE to "⟳", MapViewport.Tool.SCALE to "⤢"
        )) {
            val b = mini(name) { vp.tool = tool; paintTools() }
            toolBtns[tool] = b
            row.addView(b)
        }
        val ib = mini("⚙") { togglePanel(false) }; panelBtns[1] = ib; ib.alpha = 0.75f
        row.addView(View(act), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(ib)
        row.addView(mini("⋯") { moreMenu() })
        // прежние кнопки живут скрытыми: на них опираются paintTools и старые обработчики
        snapBtn = ui.pill("", sizeSp = 13f) {}; animBtn = ui.pill("", sizeSp = 13f) {}; sceneBtn = ui.pill("", sizeSp = 13f) {}
        gameBtn = ui.pill("", sizeSp = 13f) {}; realBtn = ui.pill("", sizeSp = 13f) {}
        titleView = ui.label(map.name, 12f)
        bar.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paintTools()
        return bar
    }

    private var viewStamp = 0L

    /** Вид мира: под прозрачным видом лежит слой OpenGL с настоящими моделями и текстурами игры. */
    private fun setGameView(on: Boolean) {
        if (on && vp.realView) toggleRealView()
        vp.gameView = on
        if (::gameBtn.isInitialized) {
            gameBtn.alpha = if (on) 1f else 0.7f
            gameBtn.text = if (on) "■ Модели" else "Модели"
        }
        vp.invalidate()
    }

    /** Реальная картинка игры из камеры редактора: мод рендерит её по запросу, редактор подхватывает файл. */
    private fun toggleRealView() {
        val on = !vp.realView
        if (on && vp.gameView) setGameView(false)
        vp.realView = on
        realBtn.alpha = if (on) 1f else 0.7f
        realBtn.text = if (on) "■ Реальный вид" else "Реальный вид"
        if (!on) return
        viewStamp = store.viewFile().let { if (it.isFile) it.lastModified() else 0L }
        vp.onViewNeeded = { p -> store.requestView(p[0], p[1], p[2], p[3], p[4], p[5], p[6].toInt(), p[7].toInt()) }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var waited = 0
        h.post(object : Runnable {
            override fun run() {
                if (!vp.realView || !dlg.isShowing) return
                val f = store.viewFile()
                if (f.isFile && f.lastModified() != viewStamp) {
                    viewStamp = f.lastModified()
                    try { BitmapFactory.decodeFile(f.path)?.let { vp.viewImage = it; vp.invalidate() } } catch (_: Throwable) {}
                    waited = 0
                } else if (vp.viewImage == null && ++waited == 40) toast("Игра не отдаёт картинку. Мир остаётся блоками; пришли лог, если так и должно работать")
                h.postDelayed(this, 120)
            }
        })
        vp.invalidate()
    }

    private fun paintTools() {
        for ((t, b) in toolBtns) {
            val on = vp.tool == t
            b.background = if (on) ui.round(M3.PRIMARY, 20) else ui.round(M3.HIGH, 20, M3.OUTLINE_V)
            b.setTextColor(if (on) M3.ON_PRIMARY else M3.ON_SURF)
        }
        snapBtn.text = if (vp.snap) "Сетка: вкл" else "Сетка: выкл"
        animBtn.text = if (vp.animate) "■ Анимация" else "▶ Анимация"
        snapBtn.alpha = if (vp.snap) 1f else 0.7f
        sceneBtn.text = if (vp.showScene) "Мир игры: вкл" else "Мир игры: выкл"
        sceneBtn.alpha = if (vp.showScene) 1f else 0.7f
        undoBtn.alpha = if (undo.isEmpty()) 0.35f else 1f
        redoBtn.alpha = if (redo.isEmpty()) 0.35f else 1f
    }

    // --------------------------------------------------------- история и сохранение

    /** Запоминает состояние для отмены и пишет карту в файл. key: подряд идущие правки одного поля склеиваются. */
    private fun commit(key: String? = null) {
        val now = MapStore.serialize(map)
        if (now == lastSnap) return
        val t = System.currentTimeMillis()
        val merge = key != null && key == lastKey && t - lastKeyAt < 1200
        if (!merge) { undo.add(lastSnap); if (undo.size > 80) undo.removeAt(0) }
        lastKey = key; lastKeyAt = t
        lastSnap = now
        redo.clear()
        store.save(map)
        paintTools()
    }

    private fun restore(snap: String) {
        val m = MapStore.parse(map.id, snap)
        map.name = m.name; map.enabled = m.enabled; map.scene = m.scene
        map.ox = m.ox; map.oy = m.oy; map.oz = m.oz; map.scale = m.scale; map.layer = m.layer
        map.world = m.world; map.sky = m.sky; map.spx = m.spx; map.spy = m.spy; map.spz = m.spz
        map.goal = m.goal; map.timer = m.timer; map.winMsg = m.winMsg; map.loseMsg = m.loseMsg
        map.objects.clear(); map.objects.addAll(m.objects)
        lastSnap = snap; lastKey = null
        store.save(map)
        if (vp.selected >= map.objects.size) vp.selected = map.objects.size - 1
        refreshTree(); showInspector(); vp.invalidate(); paintTools()
        titleView.text = map.name
    }

    private fun doUndo() {
        if (undo.isEmpty()) return
        redo.add(lastSnap)
        restore(undo.removeAt(undo.size - 1))
    }

    private fun doRedo() {
        if (redo.isEmpty()) return
        undo.add(lastSnap)
        restore(redo.removeAt(redo.size - 1))
    }

    // ------------------------------------------------------------------ сцена

    private fun select(i: Int) {
        vp.selected = i
        refreshTree()
        showInspector()
    }

    private fun refreshTree() {
        tree.removeAllViews()
        tree.addView(treeRow("▣", "Карта: " + map.name, vp.selected < 0, 0xFFD6B18D.toInt()) { select(-1) })
        map.objects.forEachIndexed { i, o ->
            tree.addView(treeRow(glyph(o.shape), o.name.ifBlank { "объект" }, vp.selected == i, 0xFF000000.toInt() or o.color) { select(i) })
        }
        if (map.objects.isEmpty()) tree.addView(ui.label("＋", 22f, M3.VARIANT, bold = false).apply { setPadding(dp(6), dp(12), dp(6), 0) })
    }

    private fun glyph(shape: String) = when (if (shape.startsWith("prop:")) "prop" else shape) { "prop" -> "◆";  "sphere" -> "●"; "cylinder" -> "▮"; "capsule" -> "⬮"; "plane" -> "▬"; "portal" -> "◎"; "coin" -> "◉"; "finish" -> "⚑"; else -> "■" }

    private fun treeRow(g: String, name: String, on: Boolean, color: Int, click: () -> Unit): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.round(if (on) 0x33B9C3FF else 0x00000000, 10, if (on) M3.OUTLINE_V else 0)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { click() }
        }
        r.addView(ui.label(g, 15f, color), LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(ui.label(name, 14f, if (on) M3.ON_SURF else M3.VARIANT, bold = on).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(2) }
        return r
    }

    /** Окно «Фигуры и шаблоны»: плитки с фигурами и список готовых шаблонов. */
    // ------------------------------------------------------------------- модели игры как объекты

    private var sceneNames: Map<Int, String> = emptyMap()
    private var propIdx: Map<String, Int>? = null
    private var allProps: List<String> = emptyList()
    private val propBoxes = HashMap<String, FloatArray?>()

    /** Имя модели → номер объекта сцены, у которого есть читаемый меш (для каждого имени берём первый). */
    private fun propIndex(): Map<String, Int> {
        propIdx?.let { return it }
        val ms = vp.sceneMeshes; val ins = vp.sceneInst
        val out = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        if (ms != null && ins != null) for ((j, nm) in sceneNames.toSortedMap()) {
            val inst = ins[j] ?: continue
            if (ms.getOrNull(inst.mesh)?.v?.isNotEmpty() != true) continue
            if (nm.isNotBlank() && !out.containsKey(nm)) out[nm] = j
        }
        propIdx = out
        return out
    }

    /** S0 (родной размер, 3), центр коробки меша в его осях (3), её размер (3), номер объекта сцены. Масштаб карты учтён в S0. */
    private fun propBoxOf(o: MapObj): FloatArray? {
        val name = o.shape.removePrefix("prop:")
        val j = propIndex()[name] ?: return null
        val raw = propBoxes.getOrPut(name) {
            val inst = vp.sceneInst?.get(j) ?: return@getOrPut null
            val me = vp.sceneMeshes?.getOrNull(inst.mesh) ?: return@getOrPut null
            if (me.v.isEmpty()) return@getOrPut null
            var x0 = 1e9f; var y0 = 1e9f; var z0 = 1e9f; var x1 = -1e9f; var y1 = -1e9f; var z1 = -1e9f
            var i = 0
            while (i < me.v.size) {
                x0 = minOf(x0, me.v[i]); x1 = maxOf(x1, me.v[i]); y0 = minOf(y0, me.v[i + 1]); y1 = maxOf(y1, me.v[i + 1]); z0 = minOf(z0, me.v[i + 2]); z1 = maxOf(z1, me.v[i + 2]); i += 3
            }
            val m = inst.m
            fun n(c: Int) = Math.sqrt((m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2]).toDouble()).toFloat().coerceAtLeast(1e-4f)
            floatArrayOf(n(0), n(1), n(2), (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, maxOf(x1 - x0, 0.02f), maxOf(y1 - y0, 0.02f), maxOf(z1 - z0, 0.02f), j.toFloat())
        } ?: return null
        val sc = if (map.scale == 0.0) 1f else map.scale.toFloat()
        val r = raw.copyOf(); r[0] /= sc; r[1] /= sc; r[2] /= sc
        return r
    }

    /** Ставит модель игры в карту. pos/rot — в координатах карты; без них — перед камерой редактора. */
    private fun achieve(key: String) {
        try { AchievementStore(act).unlockKey(key)?.let { toast("★ Достижение: $it") } } catch (_: Throwable) {}
    }

    private fun addProp(name: String, pos: DoubleArray? = null, rot: DoubleArray? = null) {
        val o = MapObj(name, "prop:$name")
        o.solid = true
        val sp = vp.spawnPoint()
        o.x = pos?.get(0) ?: sp[0].toDouble(); o.y = pos?.get(1) ?: 0.0; o.z = pos?.get(2) ?: sp[2].toDouble()
        if (rot != null) { o.rx = rot[0]; o.ry = rot[1]; o.rz = rot[2] }
        map.objects.add(o)
        commit()
        select(map.objects.size - 1)
        vp.invalidate()
        achieve("bearite:modeler")
    }

    private val shop = PropShop(File(store.dirPath()), store)
    private var pickTab = 0   // 0 фигуры, 1 игра, 2 сообщество

    /** Окно выбора объектов: широкое, плиточное. Вкладки: фигуры, модели игры, пропы сообщества (с магазином). */
    private fun addMenu() {
        PrimMeshes.loader = { id -> shop.loadMesh(id) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val cols = 5
        var query = ""
        lateinit var refill: () -> Unit
        fun tile(glyph: String, title: String, click: () -> Unit, long: (() -> Unit)? = null): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ui.round(M3.HIGH, 16, M3.OUTLINE_V)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { click() }
            if (long != null) setOnLongClickListener { long(); true }
            addView(ui.label(glyph, 26f, M3.PRIMARY).apply { gravity = Gravity.CENTER })
            addView(ui.label(title, 11f, M3.ON_SURF, bold = false).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        fun note(t: String) = grid.addView(ui.label(t, 12f, M3.VARIANT, bold = false).apply { setPadding(0, dp(10), 0, dp(2)) })
        class T(val g: String, val t: String, val c: () -> Unit, val l: (() -> Unit)? = null)
        fun addTiles(items: List<T>) {
            var i = 0
            while (i < items.size) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0 until cols) {
                    val it = items.getOrNull(i + k)
                    if (it != null) row.addView(tile(it.g, it.t, it.c, it.l), LinearLayout.LayoutParams(0, dp(84), 1f).apply { rightMargin = dp(6); topMargin = dp(6) })
                    else row.addView(View(act), LinearLayout.LayoutParams(0, dp(84), 1f).apply { rightMargin = dp(6) })
                }
                grid.addView(row)
                i += cols
            }
        }
        fun fill() {
            grid.removeAllViews()
            val q = query
            when (pickTab) {
                0 -> {
                    val shapes = MapStore.SHAPES.filter { q.isBlank() || (MapStore.SHAPE_NAMES[it] ?: it).contains(q, true) }
                    if (shapes.isEmpty()) note("Ничего не найдено")
                    addTiles(shapes.map { sh -> T(glyph(sh), MapStore.SHAPE_NAMES[sh] ?: sh, { dlg?.dismiss(); addObject(sh) }) })
                }
                1 -> {
                    val names = (propIndex().keys + allProps).toSortedSet(String.CASE_INSENSITIVE_ORDER).filter { q.isBlank() || it.contains(q, true) }
                    if (names.isEmpty()) note(if (q.isBlank()) "Каталог пуст: нажми «↻ Каталог» в панели и открой окно снова (нужен свежий мод)" else "Ничего не найдено")
                    else note("Моделей игры: ${names.size}")
                    addTiles(names.take(150).map { nm -> T("◆", nm, { dlg?.dismiss(); addProp(nm) }) })
                    if (names.size > 150) note("…ещё ${names.size - 150}: уточни поиск")
                }
                else -> {
                    val br = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    br.addView(ui.pill("Магазин пропов", primary = true, sizeSp = 13f) { dlg?.dismiss(); shopDialog() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
                    br.addView(ui.pill("Импорт .zip / .glb", sizeSp = 13f) { importPropFile { refill() } }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    grid.addView(br, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                    val list = shop.installed().filter { q.isBlank() || it.second.contains(q, true) }
                    if (list.isEmpty()) note(if (q.isBlank()) "Пока нет скачанных пропов: загляни в магазин" else "Ничего не найдено")
                    else note("Скачано: ${list.size} · долгое нажатие — удалить")
                    addTiles(list.map { (id, nm) -> T("★", nm, { dlg?.dismiss(); addCommunityProp(id, nm) }, {
                        ui.dialog("Удалить «$nm»?", null, listOf(GameUi.Btn("Отмена"), GameUi.Btn("Удалить", danger = true) { shop.remove(id); PrimMeshes.forget("cprop:$id"); refill() })).show()
                    }) })
                }
            }
        }
        refill = { fill() }
        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val tabViews = ArrayList<TextView>()
        listOf("Фигуры и предметы", "Игра", "Сообщество").forEachIndexed { i, nm ->
            val t = ui.pill(nm, primary = i == pickTab, sizeSp = 13f) { pickTab = i; tabViews.forEachIndexed { k, v -> v.alpha = if (k == i) 1f else 0.6f }; fill() }
            t.alpha = if (i == pickTab) 1f else 0.6f
            tabViews.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        }
        col.addView(tabs)
        col.addView(ui.textInput("", 700, "Поиск по названию") { query = it; fill() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        col.addView(ScrollView(act).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)).apply { topMargin = dp(4) })
        fill()
        dlg = ui.dialog("Добавить объект", col, listOf(GameUi.Btn("Закрыть")), widthDp = 760)
        dlg.show()
    }

    /** Пропы из файла: .zip (index.json + модели .glb) или одиночный .glb. */
    private fun importPropFile(done: () -> Unit) {
        val g = act as? GameActivity ?: return toast("Импорт доступен только из игры")
        g.pickFile("*/*") { uri ->
            if (uri == null) return@pickFile
            Thread {
                val res: Pair<Int, String?> = try {
                    val name = act.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "prop"
                    val bytes = act.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("файл не прочитался")
                    if (bytes.size > 60_000_000) 0 to "файл слишком большой" else shop.installFile(bytes, name)
                } catch (t: Throwable) { 0 to (t.message ?: "ошибка") }
                act.runOnUiThread {
                    toast(if (res.second == null) "Установлено пропов: ${res.first}" else "Не вышло: ${res.second}")
                    done()
                }
            }.start()
        }
    }

    /** Магазин: список из релиза «props» проекта, скачивание в одно касание. */
    private fun shopDialog() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val status = ui.label("Загружаю список…", 13f, M3.VARIANT, bold = false)
        list.addView(status)
        col.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        val d = ui.dialog("Магазин пропов", col, listOf(GameUi.Btn("Закрыть")) , onCancel = null, widthDp = 640)
        d.setOnDismissListener { addMenu() }
        d.show()
        shop.fetchIndex { items, err ->
            list.removeAllViews()
            if (items == null) { list.addView(ui.label("Не удалось загрузить: ${err ?: "нет связи"}", 13f, M3.VARIANT, bold = false)); return@fetchIndex }
            if (items.isEmpty()) { list.addView(ui.label("В магазине пока пусто", 13f, M3.VARIANT, bold = false)); return@fetchIndex }
            for (e in items) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = ui.round(M3.HIGH, 14, M3.OUTLINE_V); setPadding(dp(14), dp(8), dp(10), dp(8))
                }
                val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(ui.label(e.name, 15f))
                val sub = (if (e.author.isNotEmpty()) e.author + " · " else "") + (if (e.size > 0) "%.1f МБ".format(e.size / 1048576.0) else "")
                if (sub.isNotEmpty()) tx.addView(ui.label(sub, 12f, M3.VARIANT, bold = false))
                row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val btn = ui.pill(if (shop.isInstalled(e.id)) "Скачано" else "Скачать", sizeSp = 13f) {}
                btn.setOnClickListener {
                    btn.text = "…"
                    shop.download(e) { er -> if (er == null) btn.text = "Скачано" else { btn.text = "Скачать"; toast("Не вышло: $er") } }
                }
                row.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }
    }

    private fun addCommunityProp(id: String, name: String) {
        val o = MapObj(name, "cprop:$id")
        o.solid = true
        if (store.textureFile("cp_$id") != null) o.tex = "cp_$id"
        val sp = vp.spawnPoint()
        o.x = sp[0].toDouble(); o.z = sp[2].toDouble()
        val mesh = PrimMeshes.get(o.shape)
        var lo = 1e9f; var i = 1
        while (i < mesh.v.size) { lo = minOf(lo, mesh.v[i]); i += 3 }
        o.y = if (lo < 1e8f) -lo.toDouble() else 0.0   // модель стоит на земле
        shop.loadAnim(id)?.let { a ->   // анимация из .glb: те же ключевые кадры, что в редакторе (по кругу)
            val base = poseOf(o)
            o.kf = MapAnim.fmtKeys(a.map { r -> MapAnim.Key(r[0], doubleArrayOf(base[0] + r[1], base[1] + r[2], base[2] + r[3], base[3] + r[4], base[4] + r[5], base[5] + r[6], base[6] * r[7], base[7] * r[8], base[8] * r[9]), 1) })
            o.an = "keys"; o.ap = maxOf(a.last()[0], 0.2); o.km = 0
        }
        map.objects.add(o)
        commit()
        select(map.objects.size - 1)
        vp.invalidate()
    }

    private fun addObject(shape: String) {
        val o = MapStore.newObj(shape)
        val sp = vp.spawnPoint()
        o.x = sp[0].toDouble(); o.z = sp[2].toDouble()
        o.y = when (shape) { "plane" -> 0.0; "coin" -> 1.0; "finish" -> o.sy; "cylinder", "capsule", "portal" -> o.sy; else -> 0.5 * o.sy }
        if (shape == "portal") { o.dx = o.x + 6.0; o.dy = 1.0; o.dz = o.z }
        map.objects.add(o)
        commit()
        select(map.objects.size - 1)
        vp.invalidate()
        achieve("bearite:builder")
    }

    private fun duplicateSelected() {
        val o = map.objects.getOrNull(vp.selected) ?: return toast("Сначала выбери объект")
        val c = o.copy(x = o.x + 1.0)
        map.objects.add(vp.selected + 1, c)
        commit()
        select(vp.selected + 1)
        vp.invalidate()
    }

    private fun deleteSelected() {
        val i = vp.selected
        if (i !in map.objects.indices) return toast("Сначала выбери объект")
        map.objects.removeAt(i)
        commit()
        select(if (map.objects.isEmpty()) -1 else minOf(i, map.objects.size - 1))
        vp.invalidate()
    }

    // ------------------------------------------------------------------- камера игры

    /** Камера игры в координатах карты. */
    private fun camInMap(): DoubleArray? {
        val c = store.readState().cam ?: return null
        val s = if (map.scale == 0.0) 1.0 else map.scale
        return doubleArrayOf((c[0] - map.ox) / s, (c[1] - map.oy) / s, (c[2] - map.oz) / s, c[3], c[4], c[5])
    }

    /** Ставит выбранный объект (или новый куб) на 6 единиц перед камерой игры. */
    private fun toGameCamera() {
        val c = camInMap() ?: return toast("Позиция камеры игры пока неизвестна: закрой меню и подожди секунду")
        val s = if (map.scale == 0.0) 1.0 else map.scale
        var o = map.objects.getOrNull(vp.selected)
        if (o == null) { o = MapObj("Блок", "cube"); map.objects.add(o); vp.selected = map.objects.size - 1 }
        o.x = r2(c[0] + c[3] * 6 / s); o.y = r2(c[1] + c[4] * 6 / s); o.z = r2(c[2] + c[5] * 6 / s)
        vp.gameCam = c
        commit()
        vp.focusSelected()
        select(vp.selected)
    }

    /** Просит мод записать геометрию сцены игры и, как только файл появился, показывает её в редакторе закрашенными блоками с текстурами. */
    private fun loadScene(auto: Boolean = false) {
        val asked = System.currentTimeMillis()
        sceneAskedAt = asked
        store.requestScene()
        var tries = 0
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                if (sceneAskedAt != asked) return
                if (store.sceneStamp() >= asked - 1000) {
                    val sc = store.readScene()
                    Thread {
                        var me = store.readMeshes(); val ins = store.readInstances(); val nm = store.readSceneNames(); val extra = store.readAllProps()
                        try {
                            val mf = File(store.dirPath(), "_meshmiss")
                            val gp = (act as? GameActivity)?.gameApkPaths ?: ""
                            if (me != null && mf.isFile && gp.isNotEmpty()) {
                                val r = MeshDb.fill(gp, File(store.dirPath()), me, mf.readLines())
                                me = r.first
                                BLog.i("меши из файлов игры: " + r.second)
                            }
                        } catch (t: Throwable) { BLog.e("меши из файлов игры не вышли", t) }
                        act.runOnUiThread { vp.sceneMeshes = me; vp.sceneInst = ins; sceneNames = nm; allProps = extra; propIdx = null; propBoxes.clear(); vp.invalidate() }
                    }.start()
                    if (!auto) toast(if (sc == null || sc.isEmpty()) "В сцене игры нет объектов" else "Карта игры загружена: ${sc.size / 10} объектов")
                    return
                }
                if (++tries < 12) h.postDelayed(this, 500)
                else if (!auto) toast("Игра не ответила. Подожди секунду и нажми «↻ Каталог»")
            }
        })
    }

    private fun r2(x: Double) = Math.round(x * 100.0) / 100.0
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- инспектор

    private fun refreshLive() {
        updating = true
        for ((f, get) in liveFields) if (!f.hasFocus()) f.setText(ui.fmt(get(), 2))
        updating = false
    }

    private fun showInspector() {
        inspector.removeAllViews()
        liveFields.clear()
        val o = map.objects.getOrNull(vp.selected)
        if (o == null) mapInspector() else objectInspector(o)
    }

    private fun section(t: String) = inspector.addView(
        ui.label(t, 12f, M3.VARIANT).apply { setPadding(dp(4), dp(12), 0, dp(2)) }
    )

    /** Три числа X Y Z с цветными подписями осей, как в Godot. */
    private fun vec3(title: String, get: Array<() -> Double>, set: Array<(Double) -> Unit>, key: String) {
        section(title)
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cols = intArrayOf(0xFFEB4C5F.toInt(), 0xFF7EC14A.toInt(), 0xFF4C86EB.toInt())
        for (k in 0 until 3) {
            row.addView(ui.label("XYZ"[k].toString(), 12f, cols[k]).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT))
            val f = numField(get[k](), key + k) { v -> set[k](v) }
            liveFields.add(f to get[k])
            row.addView(f, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        }
        inspector.addView(row)
    }

    private fun numField(cur: Double, key: String, onValue: (Double) -> Unit): EditText {
        val e = EditText(act).apply {
            setText(ui.fmt(cur, 2))
            setTextColor(M3.ON_SURF)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            background = ui.round(M3.CONTAINER, 10)
            setPadding(dp(6), 0, dp(4), 0)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(x: Editable?) {
                if (updating) return
                val v = x?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: return
                onValue(v)
                commit(key)
                vp.invalidate()
            }
        })
        return e
    }

    private fun objectInspector(o: MapObj) {
        section("Объект")
        inspector.addView(ui.textInput(o.name, 0) { o.name = it; commit("name"); refreshTree() }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        })
        val shapeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val shape = ui.pill(MapStore.SHAPE_NAMES[o.shape] ?: o.shape, sizeSp = 13f) {}
        shape.setOnClickListener {
            o.shape = MapStore.SHAPES[(MapStore.SHAPES.indexOf(o.shape) + 1) % MapStore.SHAPES.size]
            shape.text = MapStore.SHAPE_NAMES[o.shape]
            commit(); refreshTree(); vp.invalidate()
        }
        shapeRow.addView(shape, LinearLayout.LayoutParams(0, dp(36), 1f).apply { topMargin = dp(6) })
        shapeRow.addView(ui.togglePill(o.solid, 96, 6) { o.solid = it; commit() }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
        inspector.addView(shapeRow)
        inspector.addView(ui.textInput(o.tag, 40, "Метка для мода (start, finish…)") { o.tag = it.trim().take(40); commit("tag") }.apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(6) }
        })

        vec3("Позиция", arrayOf({ o.x }, { o.y }, { o.z }), arrayOf({ v -> o.x = v }, { v -> o.y = v }, { v -> o.z = v }), "pos")
        vec3("Поворот (градусы)", arrayOf({ o.rx }, { o.ry }, { o.rz }), arrayOf({ v -> o.rx = v }, { v -> o.ry = v }, { v -> o.rz = v }), "rot")
        vec3("Размер", arrayOf({ o.sx }, { o.sy }, { o.sz }),
            arrayOf({ v -> o.sx = v.coerceAtLeast(0.01) }, { v -> o.sy = v.coerceAtLeast(0.01) }, { v -> o.sz = v.coerceAtLeast(0.01) }), "size")

        section("Цвет")
        val chips = ArrayList<View>()
        val hex = ui.textInput(String.format(Locale.US, "%06X", o.color), 0, "RRGGBB") {}
        fun paint() = chips.forEachIndexed { k, v ->
            v.background = ui.round(0xFF000000.toInt() or MapStore.PALETTE[k], 8,
                if (MapStore.PALETTE[k] == o.color) M3.ON_SURF else M3.OUTLINE_V, if (MapStore.PALETTE[k] == o.color) 3 else 1)
        }
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var rowL: LinearLayout? = null
        MapStore.PALETTE.forEachIndexed { k, c ->
            if (k % 6 == 0) { rowL = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(rowL, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(4) }) }
            val v = View(act)
            v.setOnClickListener {
                o.color = c; paint(); hex.setText(String.format(Locale.US, "%06X", c)); commit(); refreshTree(); vp.invalidate()
            }
            chips.add(v)
            rowL!!.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(4) })
        }
        paint()
        inspector.addView(grid)
        hex.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(x: Editable?) {
                x?.toString()?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let {
                    o.color = it; paint(); commit("color"); refreshTree(); vp.invalidate()
                }
            }
        })
        inspector.addView(hex)

        if (o.shape == "portal") { portalSection(o); nativeSection(o) }
        textureSection(o)

        inspector.addView(ui.pill("К камере игры", sizeSp = 13f) { toGameCamera() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(12) })
        inspector.addView(ui.pill("Удалить объект", danger = true, sizeSp = 13f) { deleteSelected() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(6) })
    }

    // ------------------------------------------------------------- круг телепорта

    private fun destTitle(o: MapObj): String = when (o.dest) {
        "" -> "В точку на этой карте"
        "@exit" -> "Выход в обычный мир"
        else -> store.list().firstOrNull { it.id == o.dest }?.let { "На карту «${it.name}»" } ?: "Карта не найдена"
    }

    private fun nativeSection(o: MapObj) {
        section("Круг как в Черепахограде")
        inspector.addView(ui.pill(
            when { o.bind.isEmpty() -> "Свой простой круг"; o.clone -> "Копия платформы (на месте круга)"; else -> "Занять родную платформу" }, sizeSp = 13f
        ) {
            ui.choose("Какой круг", listOf("Свой простой круг", "Копия платформы Черепахограда (на месте круга)", "Занять родную платформу Черепахограда"),
                when { o.bind.isEmpty() -> 0; o.clone -> 1; else -> 2 }) { i ->
                when (i) {
                    0 -> { o.bind = ""; o.clone = false }
                    1 -> { o.bind = "Launch Platform (Turtletown)"; o.clone = true }
                    else -> { o.bind = "Launch Platform (Turtletown)"; o.clone = false }
                }
                commit(); showInspector(); vp.invalidate()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) })
        if (o.bind.isNotEmpty()) {
            inspector.addView(ui.label("Имя объекта в игре", 12f, M3.VARIANT).apply { setPadding(dp(4), dp(8), 0, dp(2)) })
            inspector.addView(ui.textInput(o.bind, 260, "имя объекта в игре") { v -> if (v.trim() != o.bind && v.isNotBlank()) { o.bind = v.trim(); commit("bind") } }
                .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)) })
        }
    }

    private fun portalSection(o: MapObj) {
        section("Телепорт")
        inspector.addView(ui.pill(destTitle(o), sizeSp = 13f) {
            val others = store.list().filter { it.id != map.id }
            val items = listOf("В точку на этой карте", "Выход в обычный мир") +
                others.map { "На карту «${it.name}»" + if (it.world) " (мир)" else "" }
            ui.choose("Куда ведёт круг", items, when (o.dest) { "" -> 0; "@exit" -> 1; else -> others.indexOfFirst { it.id == o.dest }.let { i -> if (i < 0) -1 else i + 2 } }) { i ->
                o.dest = when (i) { 0 -> ""; 1 -> "@exit"; else -> others[i - 2].id }
                commit(); showInspector(); vp.invalidate()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) })
        val hint = when (o.dest) {
            "" -> "Игрок встаёт в круг и переносится в точку ниже. Круг срабатывает, когда в него заходят."
            "@exit" -> "Выход из отдельного мира: игрок возвращается туда, где вошёл в мир."
            else -> "Игрок переносится на точку появления выбранной карты. Если карта это отдельный мир, мир включается сам."
        }
        if (o.dest.isEmpty()) {
            vec3("Точка назначения", arrayOf({ o.dx }, { o.dy }, { o.dz }), arrayOf({ v -> o.dx = v }, { v -> o.dy = v }, { v -> o.dz = v }), "dest")
            inspector.addView(ui.pill("Точка в центр обзора", sizeSp = 12f) {
                val sp = vp.spawnPoint()
                o.dx = sp[0].toDouble(); o.dz = sp[2].toDouble(); o.dy = 1.0
                commit(); showInspector(); vp.invalidate()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(6) })
        }
    }

    // ------------------------------------------------------------------- текстуры

    private fun thumb(id: String?, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val size = dp(58)
        val box = FrameLayout(act).apply {
            background = ui.round(M3.HIGH, 12, if (selected) M3.ON_SURF else M3.OUTLINE_V, if (selected) 3 else 1)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        if (id == null) {
            box.addView(ui.label("✕", 20f, M3.VARIANT).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val iv = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
            try {
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(store.texturePng(id).path, o))
            } catch (_: Throwable) {}
            box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        }
        box.layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(6) }
        return box
    }

    private fun textureSection(o: MapObj) {
        animationSection(o)
        section("Текстура")
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(thumb(null, o.tex.isEmpty(), {
            if (o.tex.isNotEmpty()) { o.tex = ""; commit(); showInspector(); vp.invalidate() }
        }))
        for (id in store.textures()) {
            row.addView(thumb(id, o.tex == id, {
                if (o.tex.isEmpty() && o.color != 0xFFFFFF) o.color = 0xFFFFFF  // цвет работает как оттенок: белый = текстура как есть
                o.tex = id; commit(); refreshTree(); showInspector(); vp.invalidate()
            }, {
                ui.confirm("Удалить текстуру?", "Она пропадёт из всех карт, где использована.", "Удалить", danger = true) {
                    store.deleteTexture(id)
                    store.clearTextureCache(id)
                    map.objects.forEach { if (it.tex == id) it.tex = "" }
                    commit(); showInspector(); vp.invalidate()
                }
            }))
        }
        row.addView(ui.label("＋", 24f, M3.ON_SURF).apply {
            gravity = Gravity.CENTER
            background = ui.round(M3.HIGH, 12, M3.OUTLINE_V)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            setOnClickListener { importTexture(o) }
        })
        inspector.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        if (o.tex.isNotEmpty()) {
            section("Повтор текстуры")
            val r2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val fu = numField(o.tu, "tu") { v -> o.tu = v.coerceIn(0.01, 1000.0) }
            val fv = numField(o.tv, "tv") { v -> o.tv = v.coerceIn(0.01, 1000.0) }
            liveFields.add(fu to { o.tu }); liveFields.add(fv to { o.tv })
            r2.addView(ui.label("X", 12f, M3.VARIANT), LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT))
            r2.addView(fu, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
            r2.addView(ui.label("Y", 12f, M3.VARIANT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            r2.addView(fv, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
            inspector.addView(r2)
            inspector.addView(ui.pill("По размеру объекта", sizeSp = 12f) {
                o.tu = maxOf(1.0, o.sx)
                o.tv = if (o.sy < maxOf(o.sx, o.sz)) maxOf(1.0, o.sz) else maxOf(1.0, o.sy)
                commit(); showInspector()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(6) })
        }
    }

    /** Анимация объекта: вид, параметры по осям, период и задержка. Проигрывается в игре у всех игроков одинаково. */
    private fun animationSection(o: MapObj) {
        section("Анимация")
        val kind = ui.pill(MapAnim.NAMES[o.an] ?: "Нет", sizeSp = 13f) {}
        kind.setOnClickListener {
            o.an = MapAnim.KINDS[(MapAnim.KINDS.indexOf(o.an) + 1) % MapAnim.KINDS.size]
            if (o.an == "move" && o.ax == 0.0 && o.ay == 0.0 && o.az == 0.0) o.ay = 3.0
            if (o.an == "rotate" && o.ax == 0.0 && o.ay == 0.0 && o.az == 0.0) o.ay = 90.0
            if (o.an == "swing" && o.ax == 0.0 && o.ay == 0.0 && o.az == 0.0) o.az = 20.0
            if (o.an == "pulse" && o.ax == 0.0) o.ax = 0.3
            if (o.an == "keys" && o.kf.isEmpty()) { o.kf = MapAnim.fmtKeys(listOf(MapAnim.Key(0.0, poseOf(o), 1))); o.ap = maxOf(o.ap, 2.0) }
            commit(); showInspector(); vp.invalidate()
        }
        inspector.addView(kind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(4) })
        if (o.an.isEmpty()) {
            return
        }
        if (o.an == "keys") { keysSection(o); return }
        vec3("Параметры", arrayOf({ o.ax }, { o.ay }, { o.az }), arrayOf({ v -> o.ax = v }, { v -> o.ay = v }, { v -> o.az = v }), "anim")
        val r2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fp = numField(o.ap, "ap") { v -> o.ap = v.coerceIn(0.2, 3600.0) }
        val fd = numField(o.ad, "ad") { v -> o.ad = v }
        liveFields.add(fp to { o.ap }); liveFields.add(fd to { o.ad })
        if (o.an != "rotate") {
            r2.addView(ui.label("Период, с", 12f, M3.VARIANT), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            r2.addView(fp, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(6) })
        }
        r2.addView(ui.label("Задержка, с", 12f, M3.VARIANT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        r2.addView(fd, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(6) })
        inspector.addView(r2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun poseOf(o: MapObj) = doubleArrayOf(o.x, o.y, o.z, o.rx, o.ry, o.rz, o.sx, o.sy, o.sz)

    private fun applyPose(o: MapObj, v: DoubleArray) {
        o.x = v[0]; o.y = v[1]; o.z = v[2]; o.rx = v[3]; o.ry = v[4]; o.rz = v[5]; o.sx = v[6]; o.sy = v[7]; o.sz = v[8]
        refreshLive(); commit("pose"); vp.invalidate()
    }

    private var kfSel = -1
    private var kfHead = 0.0

    /** Свои анимации: шкала времени с ключевыми кадрами. Ключ хранит полную позу объекта (позиция, поворот, размер). */
    private fun keysSection(o: MapObj) {
        val keys = MapAnim.parseKeys(o.kf).map { MapAnim.Key(it.t, it.v.copyOf(), it.ease) }.toMutableList()
        val dur = maxOf(0.2, o.ap)
        kfHead = kfHead.coerceIn(0.0, dur)
        if (kfSel !in keys.indices) kfSel = -1
        fun save() { o.kf = MapAnim.fmtKeys(keys); commit("kf") }
        val info = ui.label("", 12f, M3.VARIANT, bold = false).apply { setPadding(dp(4), dp(6), 0, 0) }
        fun paintInfo() {
            info.text = "Время: " + ui.fmt(kfHead, 2) + " с · ключей: " + keys.size + if (kfSel >= 0) " · выбран ключ " + (kfSel + 1) + " (" + MapAnim.EASES[keys[kfSel].ease] + ")" else ""
        }
        val tl = KeyTimeline(act, dp(1).toFloat())
        tl.duration = dur; tl.keys = keys; tl.playhead = kfHead; tl.selected = kfSel
        tl.onScrub = { t -> kfHead = t; kfSel = -1; paintInfo() }
        tl.onKey = { i, done ->
            kfSel = i; kfHead = keys[i].t; paintInfo()
            if (done) {
                val k = keys[i]; keys.sortBy { it.t }; kfSel = keys.indexOf(k); tl.selected = kfSel
                save(); paintInfo(); tl.invalidate()
            }
        }
        inspector.addView(tl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(6) })
        inspector.addView(info)
        paintInfo()
        fun row(vararg v: View) = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, x) in v.withIndex()) addView(x, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }.also { inspector.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }) }
        row(
            ui.pill("+ Ключ", primary = true, sizeSp = 13f) {
                val near = keys.indexOfFirst { abs(it.t - kfHead) < 0.04 }
                if (near >= 0) { poseOf(o).copyInto(keys[near].v); kfSel = near }
                else { keys.add(MapAnim.Key(kfHead, poseOf(o), keys.lastOrNull { it.t < kfHead }?.ease ?: 1)); keys.sortBy { it.t }; kfSel = keys.indexOfFirst { abs(it.t - kfHead) < 0.04 } }
                save(); showInspector()
            },
            ui.pill("Удалить ключ", danger = true, sizeSp = 13f) {
                if (kfSel in keys.indices) { keys.removeAt(kfSel); kfSel = -1; save(); showInspector() } else toast("Выбери ключ на шкале")
            },
        )
        row(
            ui.pill("Поза на этом времени", sizeSp = 12f) {
                val v = DoubleArray(9); poseOf(o).copyInto(v)
                MapAnim.evalKeys(keys.sortedBy { it.t }, kfHead, v)
                if (keys.isNotEmpty()) applyPose(o, v)
            },
            ui.pill("Плавность: " + (if (kfSel >= 0) MapAnim.EASES[keys[kfSel].ease] else "—"), sizeSp = 12f) {
                if (kfSel in keys.indices) { keys[kfSel].ease = (keys[kfSel].ease + 1) % MapAnim.EASES.size; save(); showInspector() } else toast("Выбери ключ на шкале")
            },
        )
        row(
            ui.pill(MapAnim.MODES[o.km], sizeSp = 12f) { o.km = (o.km + 1) % MapAnim.MODES.size; commit(); showInspector() },
            ui.pill("Время ключа ± 0.5", sizeSp = 12f) {
                if (kfSel in keys.indices) { val k = keys[kfSel]; k.t = (k.t + 0.5).coerceAtMost(dur); keys.sortBy { it.t }; kfSel = keys.indexOf(k); kfHead = k.t; save(); showInspector() } else toast("Выбери ключ на шкале")
            },
        )
        val r2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fp = numField(o.ap, "ap") { v -> o.ap = v.coerceIn(0.2, 3600.0); tl.duration = o.ap; tl.invalidate() }
        val fd = numField(o.ad, "ad") { v -> o.ad = v }
        liveFields.add(fp to { o.ap }); liveFields.add(fd to { o.ad })
        r2.addView(ui.label("Длина, с", 12f, M3.VARIANT), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r2.addView(fp, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(6) })
        r2.addView(ui.label("Задержка, с", 12f, M3.VARIANT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        r2.addView(fd, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(6) })
        inspector.addView(r2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    /** Экспорт карты в .glb (glTF 2.0) с цветами, текстурами и анимациями; файл кладётся в «Загрузки». */
    private fun exportGlb() {
        try {
            val bytes = MapExport.glb(map, store)
            val name = map.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "map" } + ".glb"
            val where = MapExport.save(act, name, bytes)
            toast("Сохранено: $where")
        } catch (t: Throwable) {
            BLog.e("экспорт glb не удался", t)
            toast("Экспорт не удался: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun importTexture(o: MapObj) {
        val g = act as? GameActivity ?: return toast("Выбор картинки доступен только в игре")
        g.pickImage { uri ->
            if (uri == null) return@pickImage
            val id = store.importTexture(act, uri)
            if (id == null) { toast("Не удалось прочитать картинку"); return@pickImage }
            if (o.color != 0xFFFFFF) o.color = 0xFFFFFF
            o.tex = id
            commit(); refreshTree(); showInspector(); vp.invalidate()
            toast("Текстура добавлена")
        }
    }

    private fun mapInspector() {
        section("Карта")
        inspector.addView(ui.textInput(map.name, 0) { map.name = it.ifBlank { map.id }; titleView.text = map.name; commit("mapname"); refreshTree() }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        })
        section("Режим")
        val worldToggle = ui.togglePill(map.world, 84) { on ->
            map.world = on
            if (on && map.oy < 500) {
                map.ox = 0.0; map.oy = 1000.0; map.oz = 0.0
                toast("Мир поднят на высоту 1000, чтобы не пересекаться с обычным")
            }
            commit(); vp.frameAll(); showInspector(); vp.invalidate()
        }
        inspector.addView(ui.row("Отдельный мир", worldToggle), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        if (!map.world) {
            val shareToggle = ui.togglePill(map.shared, 84) { on -> map.shared = on; commit("shared") }
            inspector.addView(ui.row("Делиться с игроками", shareToggle), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        if (map.world) {
            vec3("Точка появления игрока", arrayOf({ map.spx }, { map.spy }, { map.spz }),
                arrayOf({ v -> map.spx = v }, { v -> map.spy = v }, { v -> map.spz = v }), "spawn")
            inspector.addView(ui.pill("Точка в центр обзора", sizeSp = 12f) {
                val sp = vp.spawnPoint()
                map.spx = sp[0].toDouble(); map.spz = sp[2].toDouble(); map.spy = maxOf(map.spy, 1.5)
                commit(); showInspector(); vp.invalidate()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(6) })
            section("Цвет неба")
            val skyRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val skies = intArrayOf(0x87B8E8, 0xF2DFC5, 0xE8A07A, 0x2B2A4C, 0x1B1410, 0x5E8C61)
            for (c in skies) {
                val v = View(act).apply {
                    background = ui.round(0xFF000000.toInt() or c, 8, if (c == map.sky) M3.ON_SURF else M3.OUTLINE_V, if (c == map.sky) 3 else 1)
                    setOnClickListener { map.sky = c; commit(); showInspector(); vp.invalidate() }
                }
                skyRow.addView(v, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
            }
            inspector.addView(skyRow)
        } else {
            val sceneBtn = ui.pill(if (map.scene.isEmpty()) "Сцена: любая" else "Сцена: ${map.scene}", sizeSp = 13f) {}
            sceneBtn.setOnClickListener {
                val cur = store.readState().scene
                map.scene = if (map.scene.isEmpty()) cur else ""
                sceneBtn.text = if (map.scene.isEmpty()) "Сцена: любая" else "Сцена: ${map.scene}"
                commit()
                if (map.scene.isEmpty() && cur.isEmpty()) toast("Сцена игры пока неизвестна")
            }
            inspector.addView(sceneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(8) })
        }
        section("Мини-игра")
        val goals = listOf("", "coins", "finish", "both")
        val goalNames = listOf("Нет (просто карта)", "Собрать все монеты", "Дойти до финиша", "Монеты, потом финиш")
        val goalBtn = ui.pill(goalNames[goals.indexOf(map.goal).coerceAtLeast(0)], sizeSp = 13f) {}
        goalBtn.setOnClickListener {
            ui.choose("Цель мини-игры", goalNames, goals.indexOf(map.goal).coerceAtLeast(0)) { i -> map.goal = goals[i]; commit(); showInspector() }
        }
        inspector.addView(goalBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) })
        if (map.goal.isNotEmpty()) {
            val trow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            trow.addView(ui.label("время, сек (0 — без лимита)", 12f, M3.VARIANT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trow.addView(numField(map.timer.toDouble(), "mgtimer") { map.timer = it.toInt().coerceIn(0, 3600) }, LinearLayout.LayoutParams(dp(80), dp(36)))
            inspector.addView(trow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            inspector.addView(ui.textInput(map.winMsg, 0, "Текст при победе") { map.winMsg = it; commit("mgwin") }.apply { textSize = 13f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) })
            inspector.addView(ui.textInput(map.loseMsg, 0, "Текст при поражении") { map.loseMsg = it; commit("mglose") }.apply { textSize = 13f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(4) })
        }
        if (!map.world && map.goal.isNotEmpty()) {
            vec3("Старт мини-игры (куда вернуть игрока)", arrayOf({ map.spx }, { map.spy }, { map.spz }),
                arrayOf({ v -> map.spx = v }, { v -> map.spy = v }, { v -> map.spz = v }), "spawn")
        }
        vec3("Сдвиг карты в мире", arrayOf({ map.ox }, { map.oy }, { map.oz }), arrayOf({ v -> map.ox = v }, { v -> map.oy = v }, { v -> map.oz = v }), "off")
        section("Масштаб и слой")
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sc = numField(map.scale, "scale") { map.scale = it.coerceIn(0.01, 100.0) }
        val ly = numField(map.layer.toDouble(), "layer") { map.layer = it.toInt().coerceIn(0, 31) }
        row.addView(ui.label("×", 12f, M3.VARIANT), LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(sc, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        row.addView(ui.label("слой", 12f, M3.VARIANT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(ly, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        inspector.addView(row)
    }
}
