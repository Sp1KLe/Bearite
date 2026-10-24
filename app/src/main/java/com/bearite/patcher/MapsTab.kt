package com.bearite.patcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Locale

/**
 * Вкладка «Карты» меню Bearite: список карт, создание, редактирование объектов прямо в игре.
 * Изменения сразу пишутся в файл карты; встроенный мод Bearite подхватывает файл и обновляет объекты в сцене.
 */
class MapsTab(private val act: Activity, private val ui: GameUi, private val store: MapStore) {
    private val expandedMaps = HashSet<String>()
    private val expandedObjs = HashSet<String>()
    private var rebuild: (() -> Unit)? = null

    fun render(list: LinearLayout, onChanged: () -> Unit) {
        rebuild = onChanged
        list.removeAllViews()
        val st = store.readState()
        list.addView(statusCard(st), cardLp())
        if (store.hasGuard()) {
            val info = store.guardInfo()
            if (info.isNotBlank() && info != "sync") list.addView(
                ui.label("Игра упала на вызове: $info", 12f, GameUi.RUST, bold = false).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) }
            )
            list.addView(ui.pill("Сбросить защиту после падения", danger = true) {
                store.clearGuard()
                toast("Готово: при следующем запуске игры карты снова включатся")
                onChanged()
            }, cardLp())
        }

        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(ui.pill("＋ Новая карта", primary = true) { newMapDialog(onChanged) })
        bar.addView(ui.pill("Из буфера") { importFromClipboard() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) })
        list.addView(bar, cardLp())

        val maps = store.list()
        if (maps.isEmpty()) {
            list.addView(ui.label(
                "Карт пока нет. Нажми «Новая карта»: блоки появятся в игре перед камерой, а двигать и красить их можно здесь же.",
                15f, GameUi.MUTED, bold = false
            ).apply { gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(30), ui.dp(12), ui.dp(30)) })
            return
        }
        for (m in maps) list.addView(mapCard(m, st), cardLp())
    }

    // -------------------------------------------------------------- состояние движка

    private fun statusCard(st: EngineState): View {
        val (text, color) = when {
            !st.fresh -> "Движок карт не отвечает: запусти игру через Bearite (встроенный мод должен быть включён). Карты можно править и сейчас, они применятся при запуске." to GameUi.MUTED
            st.status == "ok" && st.world.isNotEmpty() -> "Ты в отдельном мире «${store.list().firstOrNull { it.id == st.world }?.name ?: st.world}» · объектов: ${st.objects}" to GameUi.GREEN
            st.status == "ok" -> "Движок карт работает · сцена: ${st.scene.ifEmpty { "?" }} · объектов в сцене: ${st.objects}" to GameUi.GREEN
            else -> "Движок карт: ${st.message.ifEmpty { "ошибка" }}" to GameUi.RUST
        }
        return ui.label(text, 13f, color, bold = false).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = ui.round(GameUi.BG_DEEP, 14, GameUi.LINE)
        }
    }

    // ------------------------------------------------------------------ карта

    private fun mapCard(m: GameMap, st: EngineState): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(GameUi.SURFACE2, 18, GameUi.LINE)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.tile(m.name.take(1).uppercase(Locale.ROOT).ifEmpty { "К" }, GameUi.tileColor(m.id)))
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label(m.name, 18f))
        texts.addView(ui.label(
            "объектов: ${m.objects.size} · " + when {
                m.world -> "отдельный мир"
                m.scene.isEmpty() -> "любая сцена"
                else -> "сцена ${m.scene}"
            },
            12f, GameUi.MUTED, bold = false
        ))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(10) })
        head.addView(ui.togglePill(m.enabled, 92, 6) { on ->
            m.enabled = on
            store.save(m)
            if (on && m.world) {  // одновременно может быть включён только один отдельный мир
                store.list().filter { it.world && it.enabled && it.id != m.id }.forEach { it.enabled = false; store.save(it) }
                rebuild?.invoke()
            }
        })
        val open = m.id in expandedMaps
        val chevron = ui.label("›", 26f).apply { gravity = Gravity.CENTER; rotation = if (open) 90f else 0f }
        head.addView(chevron, LinearLayout.LayoutParams(ui.dp(30), ui.dp(38)))
        box.addView(head)
        val details = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0)
            visibility = if (open) View.VISIBLE else View.GONE
        }
        var built = false
        fun build() { if (!built) { built = true; buildMapDetails(details, m, st) } }
        if (open) build()
        head.isClickable = true
        head.setOnClickListener {
            val opening = details.visibility != View.VISIBLE
            if (opening) { build(); expandedMaps.add(m.id) } else expandedMaps.remove(m.id)
            chevron.animate().rotation(if (opening) 90f else 0f).setDuration(200).start()
            details.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) { details.alpha = 0f; details.animate().alpha(1f).setDuration(200).start() }
        }
        box.addView(details, ui.matchWrap())
        return box
    }

    private fun buildMapDetails(d: LinearLayout, m: GameMap, st: EngineState) {
        fun add(v: View) = d.addView(v, rowLp())
        fun save() = store.save(m)

        add(ui.pill("Открыть редактор карты", primary = true) { MapEditor(act, ui, store, m) { rebuild?.invoke() }.show() })
        add(ui.row("Название", ui.textInput(m.name, 230) { m.name = it.ifBlank { m.id }; save() }))

        // сцена: карта появляется только в выбранной сцене (по умолчанию в любой)
        val sceneBtn = ui.pill(sceneText(m)) {}
        sceneBtn.setOnClickListener {
            val cur = store.readState().scene
            val ids = listOf("", "level_1", "hub", "backrooms", cur)
            val items = if (m.world) listOf(
                "Везде: мир включается сразу (круг телепорта или переключатель)",
                "Заменяет уровень Черепахограда: открывается, когда игра сама загрузит level_1 (родная платформа, окно «Вперёд!»)",
                "Только в хабе", "Только в backrooms", "Текущая сцена: ${cur.ifEmpty { "неизвестна" }}",
            ) else listOf("Любая сцена", "level_1", "hub", "backrooms", "Текущая сцена: ${cur.ifEmpty { "неизвестна" }}")
            ui.choose("Где появляется карта", items, ids.indexOf(m.scene).let { if (it < 0) 4 else it }) { i ->
                val pick = ids[i]
                if (i == 4 && pick.isEmpty()) {
                    Toast.makeText(act, "Текущая сцена пока неизвестна: запусти игру", Toast.LENGTH_SHORT).show()
                } else {
                    m.scene = pick
                    save()
                    sceneBtn.text = sceneText(m)
                }
            }
        }
        add(ui.row("Сцена", sceneBtn))
        if (m.world) add(ui.label("Отдельный мир со сценой level_1 заменяет уровень Черепахограда: встаёшь на родную платформу, жмёшь «Вперёд!», игра грузит уровень сама, а вместо него появляется твой мир. Выход: меню паузы.", 11f, GameUi.DIM, bold = false).apply { setPadding(ui.dp(4), ui.dp(2), 0, ui.dp(4)) })

        add(ui.numberRow("Сдвиг X", m.ox, -1000.0, 1000.0, 0.5, 2) { m.ox = it; save() })
        add(ui.numberRow("Сдвиг Y", m.oy, -1000.0, 1000.0, 0.5, 2) { m.oy = it; save() })
        add(ui.numberRow("Сдвиг Z", m.oz, -1000.0, 1000.0, 0.5, 2) { m.oz = it; save() })
        add(ui.numberRow("Масштаб", m.scale, 0.1, 10.0, 0.1, 2) { m.scale = it.coerceIn(0.01, 100.0); save() })
        add(ui.numberRow("Слой", m.layer.toDouble(), 0.0, 31.0, 1.0, 0) { m.layer = it.toInt().coerceIn(0, 31); save() })

        val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ui.pill("Сдвиг → к камере") {
            val c = store.readState().cam
            if (c == null) toast("Позиция камеры пока неизвестна: закрой меню и подожди секунду")
            else { m.ox = c[0] + c[3] * 6; m.oy = c[1] + c[4] * 6; m.oz = c[2] + c[5] * 6; save(); rebuild?.invoke() }
        })
        actions.addView(ui.pill("Шаблон…") { pickTemplate(m) }, gap())
        add(actions)

        d.addView(ui.label("Добавить объект перед камерой", 13f, GameUi.MUTED, bold = false).apply { setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(2)) })
        val shapes = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, s) in MapStore.SHAPES.withIndex()) {
            shapes.addView(ui.pill("＋ " + MapStore.SHAPE_NAMES[s], sizeSp = 13f) {
                val o = MapStore.newObj(s)
                putAtCamera(m, o)
                m.objects.add(o)
                save()
                expandedObjs.add(objKey(m, m.objects.size - 1))
                rebuild?.invoke()
            }, if (i == 0) wrapLp() else gap())
        }
        d.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(shapes) }, rowLp())

        // объекты
        m.objects.forEachIndexed { i, o -> d.addView(objCard(m, i, o), rowLp()) }

        val tail = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        tail.addView(ui.pill("Поделиться (в буфер)") {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Bearite map", "BEARITE-MAP\n" + MapStore.serialize(m)))
            toast("Карта скопирована в буфер: отправь текст другу, он нажмёт «Из буфера»")
        })
        tail.addView(ui.pill("Дублировать") { val c = store.duplicate(m); expandedMaps.add(c.id); rebuild?.invoke() }, gap())
        tail.addView(ui.pill("Удалить карту", danger = true) {
            ui.confirm("Удалить карту?", "«${m.name}» и все её объекты будут удалены.", "Удалить", danger = true) { store.delete(m); rebuild?.invoke() }
        }, gap())
        d.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tail) }, rowLp().apply { topMargin = ui.dp(12) })
    }

    private fun sceneText(m: GameMap) = if (m.scene.isEmpty()) "Любая" else m.scene

    // ------------------------------------------------------------- новая карта

    private fun newMapDialog(onChanged: () -> Unit) {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        fun option(title: String, text: String, world: Boolean) {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.round(GameUi.SURFACE3, 16, GameUi.LINE_TAN)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                setOnClickListener { dlg?.dismiss(); createMap(world); onChanged() }
            }
            box.addView(ui.label(title, 16f))
            box.addView(ui.label(text, 12f, GameUi.MUTED, bold = false))
            col.addView(box, ui.matchWrap().apply { topMargin = ui.dp(8) })
        }
        option("Отдельный мир", "Своя пустота: обычный мир скрывается, игрок переносится на карту, небо своего цвета. Так делают свои уровни.", true)
        option("Дополнение к миру", "Объекты добавляются в обычную сцену игры перед камерой: можно достроить существующее место.", false)
        dlg = ui.dialog("Новая карта", col, listOf(GameUi.Btn("Отмена")))
        dlg.show()
    }

    private fun createMap(world: Boolean) {
        val m = store.create("Карта ${store.list().size + 1}")
        if (world) {
            m.world = true
            m.ox = 0.0; m.oy = 1000.0; m.oz = 0.0
            m.objects.add(MapObj("Пол", "cube", 0.0, -0.5, 0.0, 24.0, 1.0, 24.0, color = 0x8D5338))
            m.enabled = false  // сначала настрой мир, включишь переключателем
        } else placeAtCamera(m)
        store.save(m)
        expandedMaps.add(m.id)
    }

    // ----------------------------------------------------------------- объект

    private fun objKey(m: GameMap, i: Int) = m.id + "#" + i

    private fun objCard(m: GameMap, i: Int, o: MapObj): View {
        val key = objKey(m, i)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(GameUi.BG_DEEP, 14, GameUi.LINE)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val swatch = View(act).apply { background = ui.round(0xFF000000.toInt() or o.color, 8, GameUi.LINE) }
        head.addView(swatch, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
        val title = ui.label("", 15f)
        fun summary() { title.text = o.name + "  ·  " + (MapStore.SHAPE_NAMES[o.shape] ?: o.shape) }
        summary()
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(10) })
        val open = key in expandedObjs
        val chevron = ui.label("›", 22f).apply { gravity = Gravity.CENTER; rotation = if (open) 90f else 0f }
        head.addView(chevron, LinearLayout.LayoutParams(ui.dp(28), ui.dp(34)))
        box.addView(head)
        val d = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            visibility = if (open) View.VISIBLE else View.GONE
        }
        var built = false
        fun save() = store.save(m)
        fun build() {
            if (built) return
            built = true
            fun add(v: View) = d.addView(v, rowLp())
            add(ui.row("Название", ui.textInput(o.name, 230) { o.name = it; summary(); save() }))
            var si = MapStore.SHAPES.indexOf(o.shape).coerceAtLeast(0)
            val shape = ui.pill(MapStore.SHAPE_NAMES[o.shape] ?: o.shape) {}
            shape.setOnClickListener {
                si = (si + 1) % MapStore.SHAPES.size
                o.shape = MapStore.SHAPES[si]
                shape.text = MapStore.SHAPE_NAMES[o.shape]
                summary(); save()
            }
            add(ui.row("Форма", shape))
            val solid = ui.togglePill(o.solid, 92) { o.solid = it; save() }
            add(ui.row("Твёрдый (по нему можно ходить)", solid))
            add(ui.numberRow("Позиция X", o.x, -100.0, 100.0, 0.1, 2) { o.x = it; save() })
            add(ui.numberRow("Позиция Y", o.y, -100.0, 100.0, 0.1, 2) { o.y = it; save() })
            add(ui.numberRow("Позиция Z", o.z, -100.0, 100.0, 0.1, 2) { o.z = it; save() })
            add(ui.numberRow("Размер X", o.sx, 0.1, 50.0, 0.1, 2) { o.sx = it.coerceAtLeast(0.01); save() })
            add(ui.numberRow("Размер Y", o.sy, 0.1, 50.0, 0.1, 2) { o.sy = it.coerceAtLeast(0.01); save() })
            add(ui.numberRow("Размер Z", o.sz, 0.1, 50.0, 0.1, 2) { o.sz = it.coerceAtLeast(0.01); save() })
            add(ui.numberRow("Поворот X", o.rx, -180.0, 180.0, 1.0, 0) { o.rx = it; save() })
            add(ui.numberRow("Поворот Y", o.ry, -180.0, 180.0, 1.0, 0) { o.ry = it; save() })
            add(ui.numberRow("Поворот Z", o.rz, -180.0, 180.0, 1.0, 0) { o.rz = it; save() })

            val colors = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val chips = ArrayList<View>()
            fun paintChips() = chips.forEachIndexed { k, v ->
                v.background = ui.round(0xFF000000.toInt() or MapStore.PALETTE[k], 10,
                    if (MapStore.PALETTE[k] == o.color) GameUi.CREAM else GameUi.LINE, if (MapStore.PALETTE[k] == o.color) 3 else 1)
            }
            MapStore.PALETTE.forEachIndexed { k, c ->
                val v = View(act)
                v.setOnClickListener { o.color = c; swatch.background = ui.round(0xFF000000.toInt() or c, 8, GameUi.LINE); paintChips(); save() }
                chips.add(v)
                colors.addView(v, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { rightMargin = ui.dp(6) })
            }
            paintChips()
            val hex = ui.textInput(String.format("%06X", o.color), 100, "RRGGBB") { s ->
                s.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let {
                    o.color = it
                    swatch.background = ui.round(0xFF000000.toInt() or it, 8, GameUi.LINE)
                    paintChips(); save()
                }
            }
            colors.addView(hex)
            add(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(colors) })

            val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(ui.pill("К камере") {
                if (putAtCamera(m, o)) { save(); rebuild?.invoke() } else toast("Позиция камеры пока неизвестна")
            })
            acts.addView(ui.pill("Копия") {
                m.objects.add(i + 1, o.copy(name = o.name, x = o.x + o.sx, )); save()
                expandedObjs.add(objKey(m, i + 1)); rebuild?.invoke()
            }, gap())
            acts.addView(ui.pill("Удалить", danger = true) {
                m.objects.removeAt(i); save(); expandedObjs.removeAll { it.startsWith(m.id + "#") }; rebuild?.invoke()
            }, gap())
            d.addView(acts, rowLp())
        }
        if (open) build()
        head.isClickable = true
        head.setOnClickListener {
            val opening = d.visibility != View.VISIBLE
            if (opening) { build(); expandedObjs.add(key) } else expandedObjs.remove(key)
            chevron.animate().rotation(if (opening) 90f else 0f).setDuration(200).start()
            d.visibility = if (opening) View.VISIBLE else View.GONE
        }
        box.addView(d, ui.matchWrap())
        return box
    }

    // ----------------------------------------------------------- камера и шаблоны

    /** Ставит объект на 6 единиц перед камерой (в координатах карты). false, если камера неизвестна. */
    private fun putAtCamera(m: GameMap, o: MapObj): Boolean {
        val c = store.readState().cam ?: return false
        val s = if (m.scale == 0.0) 1.0 else m.scale
        o.x = round2((c[0] + c[3] * 6 - m.ox) / s)
        o.y = round2((c[1] + c[4] * 6 - m.oy) / s)
        o.z = round2((c[2] + c[5] * 6 - m.oz) / s)
        return true
    }

    /** Новая карта: начало координат в точке перед камерой. */
    private fun placeAtCamera(m: GameMap) {
        val c = store.readState().cam ?: return
        m.ox = round2(c[0] + c[3] * 8); m.oy = round2(c[1] + c[4] * 8); m.oz = round2(c[2] + c[5] * 8)
        m.objects.add(MapObj("Блок", "cube", 0.0, 0.0, 0.0, 3.0, 1.0, 3.0))
    }

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0

    private fun pickTemplate(m: GameMap) {
        ui.choose("Добавить шаблон", MapStore.TEMPLATES, -1) { which ->
            m.objects.addAll(MapStore.template(which))
            store.save(m)
            rebuild?.invoke()
        }
    }

    private fun importFromClipboard() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString().orEmpty()
        if (!text.startsWith("BEARITE-MAP")) { toast("В буфере нет карты Bearite"); return }
        try {
            val m = MapStore.parse(store.newId(), text.removePrefix("BEARITE-MAP").trimStart())
            if (m.objects.isEmpty()) { toast("В карте нет объектов"); return }
            store.save(m)
            expandedMaps.add(m.id)
            toast("Карта «${m.name}» добавлена")
            rebuild?.invoke()
        } catch (t: Throwable) {
            toast("Не получилось прочитать карту")
        }
    }

    // ------------------------------------------------------------------ мелочи

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    private fun rowLp() = ui.matchWrap().apply { topMargin = ui.dp(6) }
    private fun cardLp() = ui.matchWrap().apply { bottomMargin = ui.dp(10) }
    private fun wrapLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun gap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = ui.dp(8) }
}
