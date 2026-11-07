package com.bearite.patcher

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Общие элементы оформления для интерфейса поверх игры (меню модов, редактор карт, кнопки на экране).
 * Палитра та же, что в лаунчере: кофейный фон, сливочный текст, карамель и терракота.
 */
class GameUi(val act: Activity) {
    private val d = UiScale.density(act)
    fun dp(v: Int) = (v * d).toInt()

    fun label(text: String, sizeSp: Float, color: Int = CREAM, bold: Boolean = true) = TextView(act).apply {
        this.text = text
        textSize = sizeSp * UiScale.factor(act)
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun round(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Фон панелей и окон — картинка пользователя (res/drawable-nodpi/panel_bg.png), растянутая по девяти частям. */
    fun panelBg(cornerDp: Int = 34): Drawable {
        var b = panelBmp
        if (b == null) {
            val o = android.graphics.BitmapFactory.Options().apply { inScaled = false }
            b = android.graphics.BitmapFactory.decodeResource(act.resources, R.drawable.panel_bg, o)
            panelBmp = b
        }
        return if (b != null) NineSliceBg(b, 90, dp(cornerDp).toFloat()) else round(SURFACE, 28, OUTLINE, 4)
    }

    /** Анимация нажатия: кнопка проседает и пружинит обратно. Нажатие не перехватывает. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressAnim(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** Объёмный фон кнопки игры: тёмная обводка, градиент, глянцевый блик. */
    fun chunky(base: Int, radiusDp: Int, strokeDp: Int = 3): Drawable =
        GameBtnBg(base, dp(radiusDp).toFloat(), dp(strokeDp).toFloat())

    /** Белый текст с толстой тёмной окантовкой, как в игре. */
    fun outlined(text: String, sizeSp: Float, color: Int = 0xFFFFFFFF.toInt()): OutlinedText = OutlinedText(act).apply {
        this.text = text
        textSize = sizeSp * UiScale.factor(act)
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        outlineWidthPx = dp(1) * 2f + sizeSp * 0.12f * d
    }

    /** Кнопка игры: белый текст с окантовкой на цветной глянцевой плашке. */
    fun gameBtn(text: String, base: Int, sizeSp: Float = 18f, radiusDp: Int = 16, onClick: () -> Unit): TextView =
        outlined(text, sizeSp).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = chunky(base, radiusDp, 3)
            setOnClickListener { onClick() }
            pressAnim(this)
        }

    /** Окно «Скоро…» для разделов, которых ещё нет. */
    fun soon(title: String = "Скоро…") {
        dialog(title, label("скоро...", 22f).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)) },
            listOf(Btn("Понятно", primary = true)), widthDp = 320).show()
    }

    /** Кнопка-пилюля. primary = жёлтая (главное действие), danger = красная, иначе синяя. */
    fun pill(
        text: String, primary: Boolean = false, danger: Boolean = false, sizeSp: Float = 14f,
        onClick: () -> Unit
    ): TextView =
        outlined(text, sizeSp).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(40)
            background = when {
                primary -> chunky(YELLOW, 18, 2)
                danger -> chunky(RUST, 18, 2)
                else -> chunky(SURFACE3, 18, 2)
            }
            setOnClickListener { onClick() }
            pressAnim(this)
        }

    /** Переключатель «Вкл / Выкл». */
    fun togglePill(initial: Boolean, widthDp: Int, marginDp: Int = 0, onChange: (Boolean) -> Unit): TextView {
        var state = initial
        val t = label("", 15f).apply { gravity = Gravity.CENTER }
        fun paint() {
            t.text = if (state) "Вкл" else "Выкл"
            t.setTextColor(0xFFFFFFFF.toInt())
            t.background = chunky(if (state) GREEN else SURFACE3, 16, 2)
        }
        paint()
        t.setOnClickListener {
            state = !state
            paint()
            onChange(state)
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(38)).apply { leftMargin = dp(marginDp) }
        return t
    }

    fun fmt(x: Double, decimals: Int): String =
        if (decimals <= 0) Math.round(x).toString()
        else String.format(Locale.US, "%." + decimals + "f", x).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun styleBar(bar: SeekBar) {
        bar.progressTintList = ColorStateList.valueOf(TAN)
        bar.progressBackgroundTintList = ColorStateList.valueOf(0x55F2DFC5)
        bar.thumbTintList = ColorStateList.valueOf(CREAM)
    }

    /**
     * Ползунок с числом справа (для настроек модов). Значение отдаётся во время движения не чаще раза в 60 мс
     * и ещё раз при отпускании.
     */
    fun slider(lo: Double, hi: Double, cur: Double, decimals: Int, widthDp: Int, onChange: (Double) -> Unit): View {
        val isInt = decimals <= 0
        val steps = if (isInt) (hi - lo).toInt().coerceAtLeast(1) else 1000
        var v = cur.coerceIn(lo, hi)
        val value = label(fmt(v, decimals), 15f).apply { gravity = Gravity.CENTER }
        val bar = SeekBar(act).apply {
            max = steps
            progress = Math.round((v - lo) / (hi - lo) * steps).toInt()
            styleBar(this)
        }
        var lastSave = 0L
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                var x = lo + p.toDouble() / steps * (hi - lo)
                x = if (isInt) Math.round(x).toDouble() else Math.round(x * 1000.0) / 1000.0
                v = x
                value.text = fmt(v, decimals)
                if (fromUser) {
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 60) {
                        lastSave = now
                        onChange(v)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = onChange(v)
        })
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar, LinearLayout.LayoutParams(dp(widthDp), dp(38)))
            addView(value, LinearLayout.LayoutParams(dp(64), dp(38)))
        }
    }

    /**
     * Строка редактора: название, «−», ползунок, «+», поле для точного ввода.
     * Значение можно набрать руками: оно может выходить за пределы ползунка.
     */
    fun numberRow(
        name: String, cur: Double, lo: Double, hi: Double, step: Double, decimals: Int,
        onChange: (Double) -> Unit
    ): View {
        var v = cur
        val steps = ((hi - lo) / step).toInt().coerceAtLeast(1)
        val bar = SeekBar(act).apply { max = steps; styleBar(this) }
        val field = EditText(act).apply {
            setTextColor(CREAM)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            background = round(BG_DEEP, 12)
            setPadding(dp(4), 0, dp(4), 0)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        var guard = false
        fun show() {
            guard = true
            bar.progress = Math.round((v.coerceIn(lo, hi) - lo) / step).toInt().coerceIn(0, steps)
            if (!field.hasFocus()) field.setText(fmt(v, decimals))
            guard = false
        }
        var last = 0L
        fun set(nv: Double, force: Boolean) {
            v = Math.round(nv / step) * step
            v = Math.round(v * 10000.0) / 10000.0
            show()
            val now = System.currentTimeMillis()
            if (force || now - last > 70) {
                last = now
                onChange(v)
            }
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && !guard) set(lo + p * step, false)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = set(lo + sb.progress * step, true)
        })
        fun commitField() {
            val x = field.text.toString().replace(',', '.').toDoubleOrNull() ?: return
            v = x
            onChange(v)
            guard = true
            bar.progress = Math.round((v.coerceIn(lo, hi) - lo) / step).toInt().coerceIn(0, steps)
            guard = false
        }
        field.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                commitField()
                field.clearFocus()
            }
            false
        }
        field.setOnFocusChangeListener { _, has -> if (!has) { commitField(); field.setText(fmt(v, decimals)) } }
        fun stepBtn(text: String, sign: Int) = label(text, 20f).apply {
            gravity = Gravity.CENTER
            background = round(SURFACE3, 12)
            setOnClickListener { field.clearFocus(); set(v + sign * step, true) }
        }
        show()
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(6), dp(3))
            background = round(ROW, 12)
            addView(label(name, 14f, MUTED), LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stepBtn("−", -1), LinearLayout.LayoutParams(dp(36), dp(34)))
            addView(bar, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(stepBtn("+", 1), LinearLayout.LayoutParams(dp(36), dp(34)))
            addView(field, LinearLayout.LayoutParams(dp(72), dp(34)).apply { leftMargin = dp(6) })
        }
    }

    fun textInput(cur: String, widthDp: Int, hint: String = "", onChange: (String) -> Unit): EditText {
        val e = EditText(act).apply {
            setText(cur)
            this.hint = hint
            setTextColor(CREAM)
            setHintTextColor(DIM)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            background = round(BG_DEEP, 14)
            setPadding(dp(12), 0, dp(12), 0)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                if (widthDp > 0) dp(widthDp) else 0, dp(40), if (widthDp > 0) 0f else 1f
            )
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(x: Editable?) = onChange(x?.toString().orEmpty())
        })
        return e
    }

    /** Строка «название слева, элемент справа» внутри раскрытой карточки. */
    fun row(name: String, control: View): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = round(ROW, 12)
        setPadding(dp(14), dp(5), dp(8), dp(5))
        addView(label(name, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(control)
    }

    fun tile(letter: String, color: Int, sizeDp: Int = 52): View =
        label(letter, 22f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = round(color, 14)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }


    // ------------------------------------------------------------ окна в стиле лаунчера

    /** Окно в оформлении Bearite: тёмная карточка с рамкой, заголовок, содержимое и ряд кнопок. */
    class Btn(val text: String, val primary: Boolean = false, val danger: Boolean = false, val onClick: () -> Unit = {})

    fun dialog(title: String, content: View?, buttons: List<Btn>, onCancel: (() -> Unit)? = null, widthDp: Int = 440): Dialog {
        val dlg = Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(26), dp(22), dp(26), dp(22))
        }
        box.addView(outlined(title, 20f))
        if (content != null) box.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            for (b in buttons) row.addView(
                pill(b.text, b.primary, b.danger, 14f) { dlg.dismiss(); b.onClick() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) }
            )
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        val sv = android.widget.ScrollView(act).apply { addView(box) }
        dlg.setContentView(sv)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val dm = act.resources.displayMetrics
            setLayout(minOf(dp(widthDp), (dm.widthPixels * 0.92f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        if (onCancel != null) dlg.setOnCancelListener { onCancel() }
        // окно выпрыгивает с пружинкой
        dlg.setOnShowListener {
            box.alpha = 0f; box.scaleX = 0.8f; box.scaleY = 0.8f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
        return dlg
    }

    fun confirm(title: String, message: String, okText: String, danger: Boolean = false, onOk: () -> Unit) {
        dialog(title, label(message, 14f, MUTED, bold = false), listOf(Btn("Отмена"), Btn(okText, primary = !danger, danger = danger, onClick = onOk))).show()
    }

    /** Список вариантов; tap выбирает и закрывает окно. */
    fun choose(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dlg: Dialog? = null
        items.forEachIndexed { i, t ->
            col.addView(
                label(t, 16f, if (i == selected) ON_CREAM else CREAM).apply {
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = round(if (i == selected) CREAM else SURFACE3, 14, if (i == selected) 0 else LINE_TAN)
                    setOnClickListener { dlg?.dismiss(); onPick(i) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            )
        }
        dlg = dialog(title, col, listOf(Btn("Закрыть")))
        dlg.show()
    }

    companion object {
        // палитра в стиле Super Bear Adventure: тёмно-синий фон, жёлтые и зелёные «леденцовые» кнопки, тёмная обводка
        const val BG = 0xFF4B4EA3.toInt()
        const val BG_DEEP = 0xFF3E4194.toInt()
        const val SURFACE = 0xFF5A5DB8.toInt()
        const val SURFACE2 = 0xFF6A6DC8.toInt()
        const val SURFACE3 = 0xFF4F52AA.toInt()
        const val ROW = 0xFF6366C2.toInt()
        const val CREAM = 0xFFF6F8FF.toInt()
        const val MUTED = 0xFFE4E6FF.toInt()
        const val DIM = 0xFFBFC2F2.toInt()
        const val TAN = 0xFFFFD23F.toInt()
        const val BROWN = 0xFFFFA726.toInt()
        const val RUST = 0xFFE5483D.toInt()
        const val GREEN = 0xFF63D32B.toInt()
        private var panelBmp: android.graphics.Bitmap? = null
        const val YELLOW = 0xFFFFC61A.toInt()
        const val ON_CREAM = 0xFF3A2400.toInt()
        const val OUTLINE = 0xFF1A1206.toInt()
        const val LINE = 0x33FFFFFF
        const val LINE_TAN = 0xFF0B1220.toInt()

        fun lighten(c: Int, k: Float = 0.28f): Int {
            fun ch(v: Int) = (v + (255 - v) * k).toInt().coerceIn(0, 255)
            return (c and 0xFF000000.toInt()) or (ch((c shr 16) and 255) shl 16) or (ch((c shr 8) and 255) shl 8) or ch(c and 255)
        }

        val TILES = intArrayOf(0xFFE5483D.toInt(), 0xFFFF9F1C.toInt(), 0xFF2BA84A.toInt(), 0xFF2E86DE.toInt(), 0xFF9B59B6.toInt())
        fun tileColor(id: String) = TILES[(id.hashCode() and 0x7fffffff) % TILES.size]
    }
}
