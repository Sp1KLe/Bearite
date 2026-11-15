package com.bearite.patcher

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/** Какие аксессуары надеты: filesDir/wardrobe.cfg (читает нативный мод) и wardrobe.sel (id надетых). */
class WardrobeStore(private val ctx: android.content.Context, private val shop: PropShop) {
    private val sel get() = File(ctx.filesDir, "wardrobe.sel")
    private val cfg get() = File(ctx.filesDir, "wardrobe.cfg")

    fun worn(): List<String> = try { if (sel.isFile) sel.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList() } catch (_: Throwable) { emptyList() }

    /** Надевает (в слоте остаётся один аксессуар) или снимает; перезаписывает конфиг для мода. */
    fun toggle(id: String) {
        val cur = worn().toMutableList()
        if (!cur.remove(id)) {
            val a = shop.accOf(id) ?: return
            cur.removeAll { shop.accOf(it)?.slot == a.slot }
            cur.add(id)
        }
        write(cur)
    }

    fun clear() = write(emptyList())

    private fun write(ids: List<String>) {
        try {
            sel.writeText(ids.joinToString("\n") + "\n")
            val sb = StringBuilder()
            for (id in ids) {
                val a = shop.accOf(id) ?: continue
                sb.append(listOf(id, id, a.slot, a.off[0], a.off[1], a.off[2], a.scale, "%06X".format(a.color and 0xFFFFFF), a.rot[0], a.rot[1], a.rot[2]).joinToString("\t")).append('\n')
            }
            cfg.writeText(sb.toString())
        } catch (_: Throwable) {}
    }
}

/** Каталог аксессуаров: модели из магазина «props» со слотом (голова, лицо, спина, руки, ноги); бесплатно, «Надеть» — и они на медведе. */
class WardrobeWindow(private val act: Activity, private val ui: GameUi) {
    private val store = MapStore(act)
    private val shop = PropShop(File(store.dirPath()), store)
    private val ward = WardrobeStore(act, shop)
    private var remote: List<PropShop.Entry>? = null
    private var err = ""
    private var busy = false
    private val downloading = HashSet<String>()
    private var filter = 0   // 0 — все, иначе индекс в SLOT_TABS
    private val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    private val slotTabs = listOf("Все" to "", "Голова" to "head", "Лицо" to "face", "Спина" to "back", "Руки" to "hand", "Ноги" to "foot")

    /** side = true: панель справа (поверх синего поля гардероба), медведь слева остаётся виден. */
    fun show(side: Boolean = false) {
        val dlg = ui.dialog("Каталог аксессуаров", ScrollView(act).apply { addView(box) },
            listOf(GameUi.Btn("Снять всё", danger = true) { ward.clear() }, GameUi.Btn("Закрыть")), widthDp = if (side) 420 else 560)
        if (side) dlg.window?.apply {
            val dm = act.resources.displayMetrics
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            setLayout((dm.widthPixels * 0.40f).toInt(), (dm.heightPixels * 0.96f).toInt())
            setDimAmount(0f)
        }
        dlg.show()
        draw()
        load()
    }

    private fun load() {
        busy = true; err = ""
        shop.fetchIndex(PropShop.ACC_BASE) { list, e -> busy = false; remote = list?.filter { it.acc != null }; err = e ?: ""; draw() }
    }

    private fun matches(slot: String): Boolean { val f = slotTabs[filter].second; return f.isEmpty() || slot.startsWith(f) }

    private fun slotName(slot: String) = when {
        slot == "head" -> "голова"; slot == "face" -> "лицо"; slot == "back" -> "спина"
        slot.startsWith("hand") -> "рука"; slot.startsWith("foot") -> "нога"; else -> slot
    }

    private fun draw() {
        box.removeAllViews()
        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        slotTabs.forEachIndexed { i, t ->
            tabs.addView(ui.pill(t.first, primary = i == filter, sizeSp = 13f) { filter = i; draw() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)).apply { rightMargin = ui.dp(6) })
        }
        box.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        // установленные + доступные в магазине (без дублей)
        val installed = shop.installed().filter { shop.accOf(it.first) != null }
        val items = ArrayList<Triple<String, String, PropShop.Acc>>()
        for ((id, name) in installed) items.add(Triple(id, name, shop.accOf(id)!!))
        remote?.forEach { e -> if (items.none { it.first == e.id }) items.add(Triple(e.id, e.name, e.acc!!)) }
        val shown = items.filter { matches(it.third.slot) }.sortedBy { it.second.lowercase() }
        val worn = ward.worn().toSet()
        if (busy && remote == null) box.addView(ui.label("Загружаю список…", 14f, M3.VARIANT, bold = false).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8)) })
        if (err.isNotEmpty()) box.addView(ui.label("Магазин недоступен: $err", 13f, M3.VARIANT, bold = false).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        if (shown.isEmpty() && !busy) box.addView(ui.label("Здесь пока пусто. Аксессуары берутся из релиза «accessories» репозитория Bearite.", 13f, M3.VARIANT, bold = false).apply { setPadding(0, ui.dp(16), 0, ui.dp(8)) })
        for ((id, name, acc) in shown) {
            val have = shop.isInstalled(id)
            val on = id in worn
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ui.round(M3.HIGH, 14, if (on) M3.PRIMARY else M3.OUTLINE_V); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            }
            val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(ui.label(name, 15f))
            tx.addView(ui.label("Слот: ${slotName(acc.slot)} · бесплатно", 12f, M3.VARIANT, bold = false))
            row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val btn = when {
                id in downloading -> ui.pill("…") {}
                !have -> ui.pill("Скачать", primary = true) { download(id) }
                on -> ui.pill("Снять", danger = true) { ward.toggle(id); draw() }
                else -> ui.pill("Надеть", primary = true) { ward.toggle(id); draw() }
            }
            row.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
        }
    }

    private fun download(id: String) {
        val e = remote?.firstOrNull { it.id == id } ?: return
        downloading.add(id); draw()
        shop.download(e, PropShop.ACC_BASE) { r -> downloading.remove(id); if (r != null) err = "$id: $r"; draw() }
    }
}
