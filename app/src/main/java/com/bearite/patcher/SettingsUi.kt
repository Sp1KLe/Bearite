package com.bearite.patcher

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.ceil
import kotlin.math.floor

/** Строит экран настроек мода из его описания в mod.json. Контекст должен быть с темой Material 3. */
object SettingsUi {
    fun build(ctx: Context, mod: Mod): View {
        val inf = LayoutInflater.from(ctx)
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        if (mod.description.isNotBlank()) box.addView(note(ctx, mod.description, d))
        if (mod.libs.isNotEmpty()) box.addView(note(ctx, "Содержит нативный код (${mod.libs.size} .so).", d))
        if (mod.settings.isEmpty()) {
            box.addView(note(ctx, "У этого мода нет настроек.", d))
        }
        val values = ModSettings.load(mod)
        for (s in mod.settings) {
            val cur = values[s.id] ?: ModSettings.defaultOf(s)
            box.addView(
                when {
                    s.type == "bool" -> boolRow(inf, box, mod, s, cur as Boolean)
                    s.type == "choice" -> choiceRow(ctx, inf, box, mod, s, cur.toString())
                    (s.type == "int" || s.type == "float") && s.min != null && s.max != null && s.min < s.max ->
                        sliderRow(inf, box, mod, s, cur as Number)
                    else -> textRow(inf, box, mod, s, cur.toString())
                }
            )
        }
        return ScrollView(ctx).apply { addView(box) }
    }

    private fun note(ctx: Context, text: String, d: Float) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun describe(t: TextView, text: String) {
        if (text.isBlank()) t.visibility = View.GONE else t.text = text
    }

    private fun boolRow(inf: LayoutInflater, parent: LinearLayout, mod: Mod, s: Setting, cur: Boolean): View {
        val v = inf.inflate(R.layout.setting_bool, parent, false)
        v.findViewById<TextView>(R.id.s_name).text = s.name
        describe(v.findViewById(R.id.s_desc), s.description)
        v.findViewById<MaterialSwitch>(R.id.s_switch).apply {
            isChecked = cur
            setOnCheckedChangeListener { _, on -> ModSettings.set(mod, s.id, on) }
        }
        return v
    }

    private fun sliderRow(inf: LayoutInflater, parent: LinearLayout, mod: Mod, s: Setting, cur: Number): View {
        val v = inf.inflate(R.layout.setting_slider, parent, false)
        val name = v.findViewById<TextView>(R.id.s_name)
        describe(v.findViewById(R.id.s_desc), s.description)
        val slider = v.findViewById<Slider>(R.id.s_slider)
        val isInt = s.type == "int"
        val from = if (isInt) floor(s.min!!).toFloat() else s.min!!.toFloat()
        val to = if (isInt) ceil(s.max!!).toFloat() else s.max!!.toFloat()
        fun fmt(x: Float) = if (isInt) x.toLong().toString() else "%.2f".format(x)
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = if (isInt) 1f else 0f
        slider.value = cur.toFloat().coerceIn(from, to)
        name.text = "${s.name}: ${fmt(slider.value)}"
        slider.addOnChangeListener { _, value, fromUser ->
            name.text = "${s.name}: ${fmt(value)}"
            if (fromUser) ModSettings.set(mod, s.id, if (isInt) value.toLong() else value.toDouble())
        }
        return v
    }

    private fun textRow(inf: LayoutInflater, parent: LinearLayout, mod: Mod, s: Setting, cur: String): View {
        val v = inf.inflate(R.layout.setting_text, parent, false)
        val layout = v.findViewById<TextInputLayout>(R.id.s_layout)
        val edit = v.findViewById<TextInputEditText>(R.id.s_edit)
        layout.hint = s.name
        layout.helperText = s.description.ifBlank { null }
        edit.inputType = when (s.type) {
            "int" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            "float" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_TEXT
        }
        edit.setText(cur)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val text = e?.toString() ?: ""
                val parsed: Any? = when (s.type) {
                    "int" -> text.toLongOrNull()
                    "float" -> text.toDoubleOrNull()
                    else -> text
                }
                if (parsed == null) {
                    layout.error = "Некорректное число"
                } else {
                    layout.error = null
                    ModSettings.set(mod, s.id, parsed)
                }
            }
        })
        return v
    }

    private fun choiceRow(
        ctx: Context, inf: LayoutInflater, parent: LinearLayout, mod: Mod, s: Setting, cur: String,
    ): View {
        val v = inf.inflate(R.layout.setting_choice, parent, false)
        val layout = v.findViewById<TextInputLayout>(R.id.s_layout)
        val dd = v.findViewById<MaterialAutoCompleteTextView>(R.id.s_choice)
        layout.hint = s.name
        layout.helperText = s.description.ifBlank { null }
        dd.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, s.options))
        dd.setText(cur, false)
        dd.setOnItemClickListener { _, _, pos, _ -> ModSettings.set(mod, s.id, s.options[pos]) }
        return v
    }
}
