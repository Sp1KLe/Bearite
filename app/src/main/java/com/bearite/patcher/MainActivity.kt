package com.bearite.patcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var mods: ModManager
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BLog.init(this)
        mods = ModManager(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun gameInstalled(): String? = try {
        packageManager.getPackageInfo(GAME_PKG, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun text(s: String, size: Float = 14f, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun render() {
        root.removeAllViews()
        root.addView(text("Bearite", 28f, bold = true))

        val ver = gameInstalled()
        if (ver == null) {
            root.addView(text("Super Bear Adventure не найдена. Установи игру из магазина: оригинал мы не трогаем."))
        } else {
            root.addView(text("Игра найдена, версия $ver. Оригинал не изменяется."))
        }

        root.addView(Button(this).apply {
            text = "Запустить с модами"
            isEnabled = ver != null
            setOnClickListener {
                BLog.i("запуск из лаунчера")
                mods.writeConfig()
                startActivity(Intent(this@MainActivity, GameActivity::class.java))
            }
        })

        root.addView(text("Моды", 20f, bold = true))
        val list = mods.scan()
        if (list.isEmpty()) {
            root.addView(text("Модов нет. Установи zip с модом кнопкой ниже или положи папку мода сюда:\n${mods.modsDir.absolutePath}"))
        }
        for (m in list) {
            root.addView(Switch(this).apply {
                text = "${m.name}  v${m.version}" +
                    (if (m.author.isNotEmpty()) "  (${m.author})" else "") +
                    "\nбандлов: ${m.bundles.size}"
                isChecked = m.enabled
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, on -> mods.setEnabled(m.id, on) }
            })
        }
        root.addView(Button(this).apply {
            text = "Установить мод из zip"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }
                startActivityForResult(i, REQ_INSTALL)
            }
        })

        root.addView(text("Лог", 20f, bold = true))
        val log = BLog.tail(40)
        root.addView(text(if (log.isEmpty()) "(пусто)" else log, 11f, mono = true))
        root.addView(Button(this).apply {
            text = "Обновить / очистить лог"
            setOnClickListener {
                BLog.clear()
                render()
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQ_INSTALL || resultCode != RESULT_OK) return
        Thread {
            val msg = try {
                "Мод установлен: " + mods.installZip(uri)
            } catch (t: Throwable) {
                BLog.e("установка мода не удалась", t)
                "Ошибка: ${t.message}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    companion object {
        private const val REQ_INSTALL = 1
    }
}
