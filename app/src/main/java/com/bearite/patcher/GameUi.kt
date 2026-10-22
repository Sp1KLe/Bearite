package com.bearite.patcher

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
    private val d = act.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun label(text: String, sizeSp: Float, color: Int = CREAM, bold: Boolean = true) = TextView(act).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun round(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Кнопка-пилюля. primary = светлая (главное действие), иначе тёмная с рамкой. */
    fun pill(
        text: String, primary: Boolean = false, danger: Boolean = false, sizeSp: Float = 14f,
        onClick: () -> Unit
    ): TextView =
        label(text, sizeSp, if (primary) ON_CREAM else CREAM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(40)
            background = when {
                primary -> round(CREAM, 20)
                danger -> round(RUST, 20)
                else -> round(SURFACE3, 20, LINE_TAN)
            }
            setOnClickListener { onClick() }
        }

    /** Переключатель «Вкл / Выкл». */
    fun togglePill(initial: Boolean, widthDp: Int, marginDp: Int = 0, onChange: (Boolean) -> Unit): TextView {
        var state = initial
        val t = label("", 15f).apply { gravity = Gravity.CENTER }
        fun paint() {
            t.text = if (state) "Вкл" else "Выкл"
            t.setTextColor(if (state) ON_CREAM else MUTED)
            t.background = round(if (state) GREEN else SURFACE3, 18)
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
            background = round(SURFACE, 24, LINE_TAN, 1)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        box.addView(label(title, 19f))
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
        const val BG = 0xFF17120F.toInt()
        const val BG_DEEP = 0xFF1B1410.toInt()
        const val SURFACE = 0xFF241A15.toInt()
        const val SURFACE2 = 0xFF30221A.toInt()
        const val SURFACE3 = 0xFF493124.toInt()
        const val ROW = 0xFF271C16.toInt()
        const val CREAM = 0xFFF2DFC5.toInt()
        const val MUTED = 0xFFC3AA91.toInt()
        const val DIM = 0xFF8F7763.toInt()
        const val TAN = 0xFFD6B18D.toInt()
        const val BROWN = 0xFF8D5338.toInt()
        const val RUST = 0xFFB85F54.toInt()
        const val GREEN = 0xFFB9D58A.toInt()
        const val ON_CREAM = 0xFF2B1B12.toInt()
        const val LINE = 0x22FFFFFF
        const val LINE_TAN = 0x55D6B18D

        val TILES = intArrayOf(0xFF8D5338.toInt(), 0xFFB85F54.toInt(), 0xFF7A5C3E.toInt(), 0xFF9C6B4A.toInt(), 0xFF6E4636.toInt())
        fun tileColor(id: String) = TILES[(id.hashCode() and 0x7fffffff) % TILES.size]
    }
}
