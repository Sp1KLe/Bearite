package com.bearite.patcher

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Список модов с карточками: общий для вкладки «Моды» и для меню внутри игры. */
object ModListUi {
    /**
     * @param onToggled вызывается при включении/выключении мода
     * @param onListChanged вызывается, когда список изменился (удаление мода)
     */
    fun populate(
        activity: Activity,
        container: LinearLayout,
        mods: ModManager,
        allowDelete: Boolean,
        onToggled: () -> Unit,
        onListChanged: () -> Unit,
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (m in mods.scan()) {
            val row = inflater.inflate(R.layout.item_mod, container, false)
            row.findViewById<TextView>(R.id.mod_name).text = m.name
            row.findViewById<TextView>(R.id.mod_meta).text = buildString {
                append("v").append(m.version)
                if (m.author.isNotEmpty()) append(" · ").append(m.author)
                if (m.bundles.isNotEmpty()) append(" · бандлов: ").append(m.bundles.size)
                if (m.libs.isNotEmpty()) append(" · нативных: ").append(m.libs.size)
                if (m.settings.isNotEmpty()) append(" · настроек: ").append(m.settings.size)
            }

            val iv = row.findViewById<ImageView>(R.id.mod_icon)
            val bmp = m.icon?.let { BitmapFactory.decodeFile(it.path) }
            if (bmp != null) {
                val dp = row.resources.displayMetrics.density
                iv.setImageBitmap(bmp)
                iv.background = GradientDrawable().apply {
                    setColor(0x33888888)
                    cornerRadius = 10 * dp
                }
                iv.clipToOutline = true
                iv.outlineProvider = ViewOutlineProvider.BACKGROUND
                iv.visibility = View.VISIBLE
            }

            val sw = row.findViewById<MaterialSwitch>(R.id.mod_switch)
            var guard = false
            fun setSilently(on: Boolean) {
                guard = true
                sw.isChecked = on
                guard = false
            }
            sw.isChecked = m.enabled
            sw.setOnCheckedChangeListener { _, on ->
                if (guard) return@setOnCheckedChangeListener
                if (on && m.libs.isNotEmpty() && !mods.isNativeAccepted(m.id)) {
                    setSilently(false)
                    confirmNative(activity, m) {
                        mods.acceptNative(m.id)
                        mods.setEnabled(m.id, true)
                        setSilently(true)
                        onToggled()
                    }
                } else {
                    mods.setEnabled(m.id, on)
                    onToggled()
                }
            }

            val del = row.findViewById<View>(R.id.mod_delete)
            if (allowDelete) del.setOnClickListener { confirmDelete(activity, mods, m, onListChanged) }
            else del.visibility = View.GONE

            row.isClickable = true
            row.setOnClickListener { showSettings(activity, m) }
            container.addView(row)
        }
    }

    fun showSettings(activity: Activity, m: Mod) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(m.name)
            .setView(SettingsUi.build(activity, m))
            .setPositiveButton("Готово", null)
            .show()
    }

    private fun confirmNative(activity: Activity, m: Mod, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Нативный код")
            .setMessage(
                "Мод «${m.name}» содержит нативные библиотеки (.so). Они выполняются внутри игры " +
                    "и имеют полный доступ к её процессу и данным Bearite. " +
                    "Включай только моды, которым доверяешь."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Включить") { _, _ -> onOk() }
            .show()
    }

    private fun confirmDelete(activity: Activity, mods: ModManager, m: Mod, onDone: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Удалить мод?")
            .setMessage("«${m.name}» будет удалён вместе с файлами и настройками.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                mods.delete(m)
                onDone()
            }
            .show()
    }
}
