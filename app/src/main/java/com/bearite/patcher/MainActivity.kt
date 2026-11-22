package com.bearite.patcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.LinearInterpolator
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

/** Три вкладки: Игра (запуск), Моды (управление), Лог. Стиль Material Design 3. */
class MainActivity : AppCompatActivity() {
    private lateinit var mods: ModManager

    private lateinit var pageGame: View
    private lateinit var pageMods: View
    private lateinit var pageLog: View
    private lateinit var pageSettings: View
    private lateinit var pageHelp: View
    private val navIds = intArrayOf(R.id.nav_game, R.id.nav_mods, R.id.nav_log, R.id.nav_settings, R.id.nav_help)
    private var curPage = R.id.nav_game

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installMod(uri)
    }

    private val saves = SaveImport(this)
    private val pickSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) saves.handle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BLog.init(this)
        mods = ModManager(this)
        setContentView(R.layout.activity_main)

        pageGame = findViewById(R.id.page_game)
        pageMods = findViewById(R.id.page_mods)
        pageLog = findViewById(R.id.page_log)
        pageSettings = findViewById(R.id.page_settings)
        pageHelp = findViewById(R.id.page_help)

        for (id in navIds) findViewById<View>(id).setOnClickListener { showPage(id) }
        findViewById<View>(R.id.nav_exit).setOnClickListener { finishAffinity() }
        findViewById<MaterialButton>(R.id.btn_play).setOnClickListener { play() }
        findViewById<MaterialButton>(R.id.btn_load_save).setOnClickListener { pickSave.launch(arrayOf("*/*")) }
        // ник для мультиплеера: тот же, что в чате (prefs «chat», ключ «nick»)
        val nickField = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_nick)
        val chatPrefs = getSharedPreferences("chat", Context.MODE_PRIVATE)
        nickField.setText(chatPrefs.getString("nick", "") ?: "")
        nickField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                chatPrefs.edit().putString("nick", e?.toString()?.trim()?.take(24) ?: "").apply()
            }
        })
        findViewById<ExtendedFloatingActionButton>(R.id.fab_install).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        findViewById<MaterialButton>(R.id.btn_log_refresh).setOnClickListener { refreshLog() }
        findViewById<MaterialButton>(R.id.btn_log_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Bearite log", BLog.tail(1500)))
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_log_clear).setOnClickListener {
            BLog.clear()
            refreshLog()
        }
        findViewById<MaterialButton>(R.id.btn_set_log_clear).setOnClickListener {
            BLog.clear()
            refreshLog()
            Toast.makeText(this, "Лог очищен", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_set_log_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Bearite log", BLog.tail(1500)))
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_help_log).setOnClickListener { showPage(R.id.nav_log) }
        findViewById<MaterialButton>(R.id.btn_help_site).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sp1KLe/Bearite"))) }
            catch (_: Throwable) { Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
        }
        findViewById<TextView>(R.id.help_steps).text =
            "1. Установи Super Bear Adventure из магазина, оригинал мы не трогаем.\n" +
            "2. На вкладке «Моды» поставь нужные моды zip-файлом и включи их.\n" +
            "3. Нажми «Играть с модами». Включение и выключение модов действует после перезапуска игры.\n" +
            "4. Старое сохранение можно перенести кнопкой «Загрузить сохранение»."
        findViewById<TextView>(R.id.help_trouble).text =
            "Если игра вылетела или что-то работает не так, открой вкладку «Лог» и скопируй его. " +
            "Лог пригодится, чтобы найти причину. Мод пишет туда всё, что делает в игре."

        setupHero()

        showPage(savedInstanceState?.getInt("page", R.id.nav_game) ?: R.id.nav_game)
    }

    /** Заголовок в два цвета, вращающиеся орбиты и версия, как в макете. */
    private fun setupHero() {
        val title = SpannableStringBuilder("Твоё новое\nприключение.")
        title.setSpan(
            ForegroundColorSpan(getColor(R.color.bear_tan)), 11, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        findViewById<TextView>(R.id.hero_title).text = title
        val subText = "Запускай Super Bear Adventure с модами и своими картами. Оригинальная игра остаётся нетронутой."
        val sub = SpannableStringBuilder(subText)
        val at = subText.indexOf("Оригинальная игра")
        sub.setSpan(android.text.style.UnderlineSpan(), at, at + "Оригинальная игра".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sub.setSpan(ForegroundColorSpan(getColor(R.color.bear_cream)), at, at + "Оригинальная игра".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sub.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(w: View) { showPage(R.id.nav_help) }
            override fun updateDrawState(ds: android.text.TextPaint) {}
        }, at, at + "Оригинальная игра".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.hero_sub).apply { text = sub; movementMethod = android.text.method.LinkMovementMethod.getInstance(); highlightColor = 0 }
        val info = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Throwable) { null }
        findViewById<TextView>(R.id.header_chip).text = info?.versionName ?: ""

        fun spin(id: Int, ms: Long, reverse: Boolean) {
            ObjectAnimator.ofFloat(findViewById<View>(id), View.ROTATION, if (reverse) 360f else 0f, if (reverse) 0f else 360f).apply {
                duration = ms
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        spin(R.id.hero_orbit1, 30000, false)
        spin(R.id.hero_orbit2, 30000, true)
        ObjectAnimator.ofFloat(findViewById<View>(R.id.hero_icon), View.TRANSLATION_Y, 0f, -10f).apply {
            duration = 2500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        ObjectAnimator.ofFloat(findViewById<View>(R.id.hero_glow), View.ALPHA, 0.6f, 1f).apply {
            duration = 2800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        // вспышки: каждая мерцает со своим темпом и стартует не одновременно с остальными
        fun twinkle(id: Int, ms: Long, delay: Long) {
            val v = findViewById<View>(id) ?: return
            v.alpha = 0f
            v.scaleX = 0.4f
            v.scaleY = 0.4f
            val a = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f)
            val x = ObjectAnimator.ofFloat(v, View.SCALE_X, 0.4f, 1.15f)
            val y = ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.4f, 1.15f)
            val r = ObjectAnimator.ofFloat(v, View.ROTATION, -20f, 20f)
            for (an in listOf(a, x, y, r)) an.apply {
                duration = ms
                startDelay = delay
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
        twinkle(R.id.hero_spark1, 1700, 0)
        twinkle(R.id.hero_spark2, 2100, 500)
        twinkle(R.id.hero_spark3, 1900, 1100)
        twinkle(R.id.hero_spark4, 2400, 300)
        twinkle(R.id.bg_spark1, 2600, 700)
        twinkle(R.id.bg_spark2, 3000, 1500)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", curPage)
    }

    override fun onResume() {
        super.onResume()
        checkPreviousCrash()
        refreshAll()
    }

    /** Если после прошлого запуска остался маркер, игра завершилась аварийно: снимаем logcat. */
    private fun checkPreviousCrash() {
        val marker = File(filesDir, GameActivity.RUNNING_MARKER)
        if (!marker.exists()) return
        marker.delete()
        Thread {
            val lines = LogcatCapture.crashLines()
            if (LogcatCapture.hasCrash(lines)) {
                BLog.e("прошлый запуск игры завершился аварийно")
                BLog.i("--- logcat вокруг аварии (${lines.size} строк) ---\n" + lines.joinToString("\n"))
                // смещения libil2cpp из бэктрейса: мод при следующем запуске назовёт методы игры, где упало
                try {
                    val offs = lines.mapNotNull { Regex("pc ([0-9a-f]{6,8})\\s+\\S*libil2cpp\\.so").find(it)?.groupValues?.get(1) }.distinct().take(40)
                    if (offs.isNotEmpty()) {
                        val dir = File(filesDir, "maps"); dir.mkdirs()
                        File(dir, "_symoffs").writeText(offs.joinToString("\n"))
                    }
                } catch (_: Exception) {}
            } else {
                BLog.i("прошлый запуск закончился без нормального выхода, но аварии в logcat нет: игру, скорее всего, закрыли из списка недавних или она зависла")
                BLog.i("--- значимые строки logcat (${lines.size}) ---\n" + lines.joinToString("\n"))
            }
            runOnUiThread { refreshLog() }
        }.start()
    }

    private fun showPage(id: Int) {
        curPage = id
        pageGame.visibility = if (id == R.id.nav_game) View.VISIBLE else View.GONE
        pageMods.visibility = if (id == R.id.nav_mods) View.VISIBLE else View.GONE
        pageLog.visibility = if (id == R.id.nav_log) View.VISIBLE else View.GONE
        pageSettings.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        pageHelp.visibility = if (id == R.id.nav_help) View.VISIBLE else View.GONE
        for (n in navIds) findViewById<View>(n).isSelected = n == id
        if (id == R.id.nav_log) refreshLog()
        if (id == R.id.nav_settings) refreshSettings()
    }

    private fun refreshSettings() {
        findViewById<TextView>(R.id.settings_info).text =
            "Папка модов:\n${mods.modsDir.absolutePath}\n\nДанные лаунчера:\n${filesDir.absolutePath}"
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
            "Включено ${all.count { it.enabled }} из ${all.size}"
        findViewById<TextView>(R.id.float1_text).text =
            if (game == null) "Не установлена" else "Super Bear Adventure"
    }

    private fun play() {
        val game = Game.find(packageManager) ?: return
        BLog.i("запуск из лаунчера, пакет ${game.pkg}")
        mods.writeConfig()
        // ник с главной страницы: мод читает его из maps/_nick и ставит перед подключением к Photon
        try {
            val nick = getSharedPreferences("chat", Context.MODE_PRIVATE).getString("nick", "")?.trim().orEmpty()
            val dir = File(filesDir, "maps").also { it.mkdirs() }
            if (nick.isEmpty()) File(dir, "_nick").delete() else File(dir, "_nick").writeText(nick)
        } catch (_: Throwable) {}
        startActivity(
            Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_PKG, game.pkg)
        )
    }

    // ----------------------------------------------------------------- моды

    private fun refreshMods() {
        val container = findViewById<LinearLayout>(R.id.mods_list)
        ModListUi.populate(
            activity = this,
            container = container,
            mods = mods,
            allowDelete = true,
            onToggled = { refreshGame() },
            onListChanged = { refreshAll() },
        )
        findViewById<View>(R.id.mods_empty).visibility =
            if (container.childCount == 0) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.mods_path).text = "Папка модов:\n${mods.modsDir.absolutePath}"
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
        val log = BLog.tail(400)
        findViewById<TextView>(R.id.log_text).text = if (log.isEmpty()) "(пусто)" else log
    }
}
