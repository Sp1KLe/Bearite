package com.bearite.patcher

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors

/**
 * Мод-меню поверх игры (как список модов в Geode): открывается кнопкой в GameActivity.
 * Настройки применяются сразу, включение/выключение модов и нативный код — после перезапуска игры.
 */
class ModMenuActivity : AppCompatActivity() {
    private lateinit var mods: ModManager

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        BLog.init(this)
        mods = ModManager(this)
        setContentView(R.layout.activity_mod_menu)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        refresh()
    }

    private fun refresh() {
        val container = findViewById<LinearLayout>(R.id.mods_list)
        ModListUi.populate(this, container, mods, allowDelete = false, onToggled = {}, onListChanged = { refresh() })
        findViewById<TextView>(R.id.menu_empty).visibility =
            if (container.childCount == 0) View.VISIBLE else View.GONE
    }
}
