package com.bearite.patcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Три вкладки: Игра (запуск), Моды (управление), Лог. Стиль Material Design 3. */
class MainActivity : AppCompatActivity() {
    private lateinit var mods: ModManager

    private lateinit var pageGame: View
    private lateinit var pageMods: View
    private lateinit var pageLog: View
    private lateinit var nav: BottomNavigationView

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installMod(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // на Android 12+ берём цвета обоев пользователя, иначе работает наша палитра
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        BLog.init(this)
        mods = ModManager(this)
        setContentView(R.layout.activity_main)

        pageGame = findViewById(R.id.page_game)
        pageMods = findViewById(R.id.page_mods)
        pageLog = findViewById(R.id.page_log)
        nav = findViewById(R.id.nav)

        nav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        findViewById<MaterialButton>(R.id.btn_play).setOnClickListener { play() }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_install).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        findViewById<MaterialButton>(R.id.btn_log_refresh).setOnClickListener { refreshLog() }
        findViewById<MaterialButton>(R.id.btn_log_clear).setOnClickListener {
            BLog.clear()
            refreshLog()
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_game  // сработает и слушатель -> showPage
        } else {
            showPage(nav.selectedItemId)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun showPage(id: Int) {
        pageGame.visibility = if (id == R.id.nav_game) View.VISIBLE else View.GONE
        pageMods.visibility = if (id == R.id.nav_mods) View.VISIBLE else View.GONE
        pageLog.visibility = if (id == R.id.nav_log) View.VISIBLE else View.GONE
    }

    private fun refreshAll() {
        refreshGame()
        refreshMods()
        refreshLog()
    }

    // ----------------------------------------------------------------- игра

    private fun refreshGame() {
        val game = Game.find(packageManager)
        val icon = findViewById<ImageView>(R.id.status_icon)
        val title = findViewById<TextView>(R.id.status_title)
        val sub = findViewById<TextView>(R.id.status_sub)
        val play = findViewById<MaterialButton>(R.id.btn_play)

        if (game == null) {
            icon.setImageResource(R.drawable.ic_error)
            title.text = "Игра не найдена"
            sub.text = "Установи Super Bear Adventure из магазина. Оригинал мы не трогаем."
        } else {
            icon.setImageResource(R.drawable.ic_ok)
            title.text = "Super Bear Adventure найдена"
            sub.text = "Версия ${game.version} · ${game.pkg}"
        }
        play.isEnabled = game != null

        val all = mods.scan()
        findViewById<TextView>(R.id.game_mods_info).text =
            "Включено модов: ${all.count { it.enabled }} из ${all.size}"
    }

    private fun play() {
        val game = Game.find(packageManager) ?: return
        BLog.i("запуск из лаунчера, пакет ${game.pkg}")
        mods.writeConfig()
        startActivity(
            Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_PKG, game.pkg)
        )
    }

    // ----------------------------------------------------------------- моды

    private fun refreshMods() {
        val list = mods.scan()
        val container = findViewById<LinearLayout>(R.id.mods_list)
        container.removeAllViews()
        findViewById<View>(R.id.mods_empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.mods_path).text = "Папка модов:\n${mods.modsDir.absolutePath}"

        for (m in list) {
            val row = layoutInflater.inflate(R.layout.item_mod, container, false)
            row.findViewById<TextView>(R.id.mod_name).text = m.name
            row.findViewById<TextView>(R.id.mod_meta).text = buildString {
                append("v").append(m.version)
                if (m.author.isNotEmpty()) append(" · ").append(m.author)
                append(" · бандлов: ").append(m.bundles.size)
            }
            row.findViewById<MaterialSwitch>(R.id.mod_switch).apply {
                isChecked = m.enabled
                setOnCheckedChangeListener { _, on ->
                    mods.setEnabled(m.id, on)
                    refreshGame()
                }
            }
            row.findViewById<View>(R.id.mod_delete).setOnClickListener { confirmDelete(m) }
            container.addView(row)
        }
    }

    private fun confirmDelete(m: Mod) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить мод?")
            .setMessage("«${m.name}» будет удалён вместе с файлами.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                mods.delete(m)
                refreshAll()
            }
            .show()
    }

    private fun installMod(uri: Uri) {
        Thread {
            val msg = try {
                "Мод установлен: " + mods.installZip(uri)
            } catch (t: Throwable) {
                BLog.e("установка мода не удалась", t)
                "Ошибка: ${t.message}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshAll()
            }
        }.start()
    }

    // ------------------------------------------------------------------ лог

    private fun refreshLog() {
        val log = BLog.tail(200)
        findViewById<TextView>(R.id.log_text).text = if (log.isEmpty()) "(пусто)" else log
    }
}
