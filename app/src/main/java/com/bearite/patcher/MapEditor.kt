package com.bearite.patcher

import android.app.Activity
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
    private val tree = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val inspector = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val toolBtns = HashMap<MapViewport.Tool, TextView>()
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var snapBtn: TextView
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
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        vp.gameCam = camInMap()
        vp.texAvg = { store.textureAverage(it) }
        vp.onSelect = { select(it) }
        vp.onEdited = { refreshLive() }
        vp.onCommit = { commit(); refreshTree() }
        dlg.setContentView(buildRoot())
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(GameUi.BG))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            decorView.systemUiVisibility = act.window.decorView.systemUiVisibility
        }
        dlg.setOnDismissListener { onClose() }
        refreshTree()
        showInspector()
        dlg.show()
    }

    // ------------------------------------------------------------------ каркас

    private fun buildRoot(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GameUi.BG) }
        root.addView(toolbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val wide = act.resources.displayMetrics.widthPixels > act.resources.displayMetrics.heightPixels
        if (wide) {
            val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(dock("Сцена", tree, true), LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT))
            body.addView(vp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            body.addView(dock("Инспектор", inspector, false), LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            root.addView(vp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f))
            val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(GameUi.SURFACE) }
            val treeDock = dock("Сцена", tree, true)
            val insDock = dock("Инспектор", inspector, false)
            insDock.visibility = View.GONE
            val t1 = ui.pill("Сцена", primary = true, sizeSp = 13f) {}
            val t2 = ui.pill("Инспектор", sizeSp = 13f) {}
            fun pick(first: Boolean) {
                treeDock.visibility = if (first) View.VISIBLE else View.GONE
                insDock.visibility = if (first) View.GONE else View.VISIBLE
                t1.background = if (first) ui.round(GameUi.CREAM, 20) else ui.round(GameUi.SURFACE3, 20, GameUi.LINE_TAN)
                t2.background = if (!first) ui.round(GameUi.CREAM, 20) else ui.round(GameUi.SURFACE3, 20, GameUi.LINE_TAN)
                t1.setTextColor(if (first) GameUi.ON_CREAM else GameUi.CREAM)
                t2.setTextColor(if (!first) GameUi.ON_CREAM else GameUi.CREAM)
            }
            t1.setOnClickListener { pick(true) }
            t2.setOnClickListener { pick(false) }
            tabs.addView(t1, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(8), dp(4), dp(4), dp(4)) })
            tabs.addView(t2, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), dp(4), dp(8), dp(4)) })
            root.addView(tabs)
            root.addView(treeDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(insDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            vp.onSelect = { select(it); if (it >= 0) pick(false) }
        }
        return root
    }

    private fun dock(title: String, content: LinearLayout, left: Boolean): LinearLayout {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GameUi.SURFACE) }
        if (title == "Сцена") {
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(6), dp(4)) }
            head.addView(ui.label(title, 14f, GameUi.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(mini("＋") { addMenu() })
            head.addView(mini("⧉") { duplicateSelected() })
            head.addView(mini("🗑") { deleteSelected() })
            box.addView(head)
        } else {
            box.addView(ui.label(title, 14f, GameUi.MUTED).apply { setPadding(dp(10), dp(10), dp(10), dp(4)) })
        }
        val sv = ScrollView(act).apply { isFillViewport = false; addView(content) }
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.setPadding(dp(8), dp(2), dp(8), dp(16))
        return box
    }

    private fun mini(t: String, onClick: () -> Unit) = ui.label(t, 17f).apply {
        gravity = Gravity.CENTER
        background = ui.round(GameUi.SURFACE3, 10, GameUi.LINE)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(34)).apply { leftMargin = dp(4) }
    }

    private fun toolbar(): View {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GameUi.SURFACE)
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(ui.pill("‹ Готово", primary = true, sizeSp = 13f) { dlg.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        titleView = ui.label(map.name, 14f, GameUi.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(10), 0, dp(10), 0) }
        val tools = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tools.addView(titleView, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        tools.addView(ui.pill("＋ Фигуры", primary = true, sizeSp = 13f) { addMenu() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) })
        undoBtn = mini("↶") { doUndo() }
        redoBtn = mini("↷") { doRedo() }
        tools.addView(undoBtn); tools.addView(redoBtn)
        tools.addView(View(act), LinearLayout.LayoutParams(dp(10), 1))
        for ((tool, name) in listOf(
            MapViewport.Tool.SELECT to "Выбор", MapViewport.Tool.MOVE to "Двигать",
            MapViewport.Tool.ROTATE to "Вращать", MapViewport.Tool.SCALE to "Размер"
        )) {
            val b = ui.pill(name, sizeSp = 13f) { vp.tool = tool; paintTools() }
            toolBtns[tool] = b
            tools.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(4) })
        }
        snapBtn = ui.pill("Сетка: вкл", sizeSp = 13f) { vp.snap = !vp.snap; paintTools() }
        tools.addView(snapBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(10) })
        tools.addView(ui.pill("Фокус", sizeSp = 13f) { vp.focusSelected() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(4) })
        tools.addView(ui.pill("К камере игры", sizeSp = 13f) { toGameCamera() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(4) })
        bar.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tools) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paintTools()
        return bar
    }

    private fun paintTools() {
        for ((t, b) in toolBtns) {
            val on = vp.tool == t
            b.background = if (on) ui.round(GameUi.CREAM, 20) else ui.round(GameUi.SURFACE3, 20, GameUi.LINE_TAN)
            b.setTextColor(if (on) GameUi.ON_CREAM else GameUi.CREAM)
        }
        snapBtn.text = if (vp.snap) "Сетка: вкл" else "Сетка: выкл"
        snapBtn.alpha = if (vp.snap) 1f else 0.7f
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
        if (map.objects.isEmpty()) tree.addView(ui.label("Пусто. Нажми ＋ и добавь объект.", 12f, GameUi.DIM, bold = false).apply { setPadding(dp(6), dp(12), dp(6), 0) })
    }

    private fun glyph(shape: String) = when (shape) { "sphere" -> "●"; "cylinder" -> "▮"; "capsule" -> "⬮"; "plane" -> "▬"; "portal" -> "◎"; else -> "■" }

    private fun treeRow(g: String, name: String, on: Boolean, color: Int, click: () -> Unit): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.round(if (on) 0x44D6B18D else 0x00000000, 10, if (on) GameUi.LINE_TAN else 0)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { click() }
        }
        r.addView(ui.label(g, 15f, color), LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(ui.label(name, 14f, if (on) GameUi.CREAM else GameUi.MUTED, bold = on).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(2) }
        return r
    }

    /** Окно «Фигуры и шаблоны»: плитки с фигурами и список готовых шаблонов. */
    private fun addMenu() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        col.addView(ui.label("Фигуры", 13f, GameUi.DIM))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (sh in MapStore.SHAPES) {
            val tile = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ui.round(GameUi.SURFACE3, 16, GameUi.LINE_TAN)
                setOnClickListener { dlg?.dismiss(); addObject(sh) }
            }
            tile.addView(ui.label(glyph(sh), 26f, GameUi.TAN).apply { gravity = Gravity.CENTER })
            tile.addView(ui.label(MapStore.SHAPE_NAMES[sh] ?: sh, 11f, GameUi.CREAM).apply { gravity = Gravity.CENTER; maxLines = 1 })
            row.addView(tile, LinearLayout.LayoutParams(0, dp(78), 1f).apply { rightMargin = dp(6) })
        }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(ui.label("Шаблоны", 13f, GameUi.DIM).apply { setPadding(0, dp(16), 0, 0) })
        val hints = listOf("один большой пол", "8 ступеней вверх", "пол и четыре стены")
        MapStore.TEMPLATES.forEachIndexed { i, name ->
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.round(GameUi.SURFACE3, 14, GameUi.LINE_TAN)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    dlg?.dismiss()
                    map.objects.addAll(MapStore.template(i))
                    commit(); select(map.objects.size - 1); vp.frameAll(); vp.invalidate()
                }
            }
            item.addView(ui.label(name, 15f))
            item.addView(ui.label(hints.getOrElse(i) { "" } + " · ${MapStore.template(i).size} объектов", 12f, GameUi.MUTED, bold = false))
            col.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        dlg = ui.dialog("Фигуры и шаблоны", col, listOf(GameUi.Btn("Закрыть")), widthDp = 460)
        dlg.show()
    }

    private fun addObject(shape: String) {
        val o = MapStore.newObj(shape)
        val sp = vp.spawnPoint()
        o.x = sp[0].toDouble(); o.z = sp[2].toDouble()
        o.y = when (shape) { "plane" -> 0.0; "cylinder", "capsule", "portal" -> o.sy; else -> 0.5 * o.sy }
        if (shape == "portal") { o.dx = o.x + 6.0; o.dy = 1.0; o.dz = o.z }
        map.objects.add(o)
        commit()
        select(map.objects.size - 1)
        vp.invalidate()
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
        ui.label(t, 12f, GameUi.DIM).apply { setPadding(dp(4), dp(12), 0, dp(2)) }
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
            setTextColor(GameUi.CREAM)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            background = ui.round(GameUi.BG_DEEP, 10)
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
        inspector.addView(ui.label("Справа: твёрдый — по объекту можно ходить", 11f, GameUi.DIM, bold = false).apply { setPadding(dp(4), dp(2), 0, 0) })

        vec3("Позиция", arrayOf({ o.x }, { o.y }, { o.z }), arrayOf({ v -> o.x = v }, { v -> o.y = v }, { v -> o.z = v }), "pos")
        vec3("Поворот (градусы)", arrayOf({ o.rx }, { o.ry }, { o.rz }), arrayOf({ v -> o.rx = v }, { v -> o.ry = v }, { v -> o.rz = v }), "rot")
        vec3("Размер", arrayOf({ o.sx }, { o.sy }, { o.sz }),
            arrayOf({ v -> o.sx = v.coerceAtLeast(0.01) }, { v -> o.sy = v.coerceAtLeast(0.01) }, { v -> o.sz = v.coerceAtLeast(0.01) }), "size")

        section("Цвет")
        val chips = ArrayList<View>()
        val hex = ui.textInput(String.format(Locale.US, "%06X", o.color), 0, "RRGGBB") {}
        fun paint() = chips.forEachIndexed { k, v ->
            v.background = ui.round(0xFF000000.toInt() or MapStore.PALETTE[k], 8,
                if (MapStore.PALETTE[k] == o.color) GameUi.CREAM else GameUi.LINE, if (MapStore.PALETTE[k] == o.color) 3 else 1)
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

        if (o.shape == "portal") portalSection(o)
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
        inspector.addView(ui.label(hint, 11f, GameUi.DIM, bold = false).apply { setPadding(dp(4), dp(4), 0, 0) })
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
            background = ui.round(GameUi.SURFACE3, 12, if (selected) GameUi.CREAM else GameUi.LINE_TAN, if (selected) 3 else 1)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        if (id == null) {
            box.addView(ui.label("✕", 20f, GameUi.MUTED).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
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
        row.addView(ui.label("＋", 24f, GameUi.CREAM).apply {
            gravity = Gravity.CENTER
            background = ui.round(GameUi.SURFACE3, 12, GameUi.LINE_TAN)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            setOnClickListener { importTexture(o) }
        })
        inspector.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        inspector.addView(ui.label("＋ добавляет картинку из галереи, долгое нажатие на текстуру удаляет её. Цвет объекта работает как оттенок.", 11f, GameUi.DIM, bold = false).apply { setPadding(dp(4), dp(4), 0, 0) })
        if (o.tex.isNotEmpty()) {
            section("Повтор текстуры")
            val r2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val fu = numField(o.tu, "tu") { v -> o.tu = v.coerceIn(0.01, 1000.0) }
            val fv = numField(o.tv, "tv") { v -> o.tv = v.coerceIn(0.01, 1000.0) }
            liveFields.add(fu to { o.tu }); liveFields.add(fv to { o.tv })
            r2.addView(ui.label("X", 12f, GameUi.MUTED), LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT))
            r2.addView(fu, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
            r2.addView(ui.label("Y", 12f, GameUi.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            r2.addView(fv, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
            inspector.addView(r2)
            inspector.addView(ui.pill("По размеру объекта", sizeSp = 12f) {
                o.tu = maxOf(1.0, o.sx)
                o.tv = if (o.sy < maxOf(o.sx, o.sz)) maxOf(1.0, o.sz) else maxOf(1.0, o.sy)
                commit(); showInspector()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(6) })
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
            // Отдельный мир скрывает окружение через рендереры/коллайдеры,
            // поэтому поднимать его на Y=1000 не нужно: это отправляет игрока
            // далеко от игровой сцены и может ломать физику.
            if (on && kotlin.math.abs(map.oy - 1000.0) < 0.01) {
                map.oy = 0.0
                toast("Сброшена старая высота мира Y=1000")
            }
            commit(); vp.frameAll(); showInspector(); vp.invalidate()
        }
        inspector.addView(ui.row("Отдельный мир", worldToggle), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        inspector.addView(ui.label(
            if (map.world) "Остальной мир скрывается, игрок переносится на точку появления, небо заливается цветом ниже. Выключи карту, и всё вернётся."
            else "Выключено: объекты просто добавляются в обычную сцену игры.",
            11f, GameUi.DIM, bold = false
        ).apply { setPadding(dp(4), dp(4), 0, 0) })
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
                    background = ui.round(0xFF000000.toInt() or c, 8, if (c == map.sky) GameUi.CREAM else GameUi.LINE, if (c == map.sky) 3 else 1)
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
        vec3("Сдвиг карты в мире", arrayOf({ map.ox }, { map.oy }, { map.oz }), arrayOf({ v -> map.ox = v }, { v -> map.oy = v }, { v -> map.oz = v }), "off")
        section("Масштаб и слой")
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sc = numField(map.scale, "scale") { map.scale = it.coerceIn(0.01, 100.0) }
        val ly = numField(map.layer.toDouble(), "layer") { map.layer = it.toInt().coerceIn(0, 31) }
        row.addView(ui.label("×", 12f, GameUi.MUTED), LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(sc, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        row.addView(ui.label("слой", 12f, GameUi.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(ly, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(2) })
        inspector.addView(row)
        inspector.addView(ui.label(
            "Как работать: выбери «Двигать», тапни по объекту и тяни цветную стрелку. Один палец по пустому месту вращает обзор, два пальца приближают и сдвигают. «＋» в списке слева добавляет объекты, ↶ отменяет.",
            12f, GameUi.DIM, bold = false
        ).apply { setPadding(dp(4), dp(14), dp(4), 0) })
    }
}
