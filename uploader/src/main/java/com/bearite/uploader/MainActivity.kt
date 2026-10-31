package com.bearite.uploader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Загрузчик модов Bearite: выбираешь .zip в проводнике, и он появляется во вкладке «Все моды» лаунчера.
 * Моды лежат в релизе «user-mods» репозитория на GitHub (архив мода и index.json), для записи нужен токен GitHub с правом Contents: write.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var pick: Button
    private val prefs by lazy { getSharedPreferences("up", Context.MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20)); setBackgroundColor(0xFF17120F.toInt()) }
        fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun field(hint: String, value: String, pass: Boolean, onChange: (String) -> Unit) = EditText(this).apply {
            this.hint = hint; setText(value); setTextColor(0xFFF2DFC5.toInt()); setHintTextColor(0xFF8F7763.toInt()); textSize = 14f; isSingleLine = true
            inputType = if (pass) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0xFF271C16.toInt()); cornerRadius = dp(12).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) = onChange(e?.toString().orEmpty().trim())
            })
        }
        col.addView(label("Bearite · загрузка модов", 22f, 0xFFF2DFC5.toInt(), true))
        col.addView(label("Выбери .zip мода, он появится во вкладке «Все моды» в игре без переустановки.", 13f, 0xFFC3AA91.toInt()).apply { setPadding(0, dp(6), 0, dp(14)) })
        col.addView(label("Токен GitHub", 12f, 0xFFC3AA91.toInt()))
        col.addView(field("github_pat_…", prefs.getString("token", "") ?: "", true) { prefs.edit().putString("token", it).apply() }, lp().apply { bottomMargin = dp(10) })
        col.addView(label("Репозиторий", 12f, 0xFFC3AA91.toInt()))
        col.addView(field("владелец/репозиторий", prefs.getString("repo", "Sp1KLe/Bearite") ?: "Sp1KLe/Bearite", false) { prefs.edit().putString("repo", it).apply() }, lp().apply { bottomMargin = dp(14) })
        pick = Button(this).apply {
            text = "Отправить мод (.zip)"; textSize = 16f; isAllCaps = false; setTextColor(0xFF2B1B10.toInt())
            background = GradientDrawable().apply { setColor(0xFFF2DFC5.toInt()); cornerRadius = dp(28).toFloat() }
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7) }
        }
        col.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        status = label("", 13f, 0xFFF2DFC5.toInt()).apply { setPadding(0, dp(14), 0, 0) }
        scroll = ScrollView(this).apply { addView(status) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(label("Как получить токен: github.com → Settings → Developer settings → Fine-grained tokens → Generate. Репозиторий выбери свой, право Contents: Read and write. Вставь его выше один раз.", 11f, 0xFF8F7763.toInt()).apply { gravity = Gravity.START; setPadding(0, dp(10), 0, 0) })
        setContentView(col)
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun say(t: String) = main.post { status.append(t + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val token = prefs.getString("token", "") ?: ""
        val repo = prefs.getString("repo", "") ?: ""
        if (token.isEmpty() || !repo.contains('/')) { status.text = ""; say("Сначала впиши токен GitHub и репозиторий."); return }
        pick.isEnabled = false; status.text = ""
        Thread {
            try { upload(uri, token, repo) } catch (t: Throwable) { say("Ошибка: ${t.message ?: t.javaClass.simpleName}") }
            main.post { pick.isEnabled = true }
        }.start()
    }

    // ---------------------------------------------------------------- GitHub

    private class Resp(val code: Int, val text: String)

    private fun http(method: String, url: String, token: String, body: ByteArray? = null, file: File? = null, type: String = "application/json"): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "Bearite-uploader")
        if (body != null || file != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", type)
            if (file != null) { c.setFixedLengthStreamingMode(file.length()); file.inputStream().use { i -> c.outputStream.use { o -> i.copyTo(o) } } }
            else c.outputStream.use { it.write(body!!) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return Resp(code, text)
    }

    private fun check(r: Resp, what: String) { if (r.code !in 200..299) throw RuntimeException("$what: код ${r.code} ${r.text.take(160)}") }

    private fun displayName(uri: Uri): String {
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" } } catch (_: Throwable) {}
        return "mod.zip"
    }

    private fun upload(uri: Uri, token: String, repo: String) {
        say("Читаю файл…")
        val tmp = File(cacheDir, "upload.zip")
        contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (tmp.length() == 0L) throw RuntimeException("файл пустой")
        val mj = try { ZipFile(tmp).use { z ->
            val e = java.util.Collections.list(z.entries()).firstOrNull { it.name == "mod.json" || it.name.endsWith("/mod.json") } ?: throw RuntimeException("в архиве нет mod.json")
            JSONObject(z.getInputStream(e).bufferedReader().readText())
        } } catch (t: java.util.zip.ZipException) { throw RuntimeException("это не zip-архив") }
        val id = mj.optString("id")
        if (!Regex("[A-Za-z0-9._-]{1,64}").matches(id)) throw RuntimeException("в mod.json нет корректного id")
        val zipName = "$id.zip"
        say("Мод «${mj.optString("name", id)}» v${mj.optString("version", "?")}, ${tmp.length() / 1024} КБ")

        val api = "https://api.github.com/repos/$repo"
        say("Ищу релиз user-mods…")
        var rel = http("GET", "$api/releases/tags/user-mods", token)
        if (rel.code == 404) {
            say("Создаю релиз user-mods…")
            rel = http("POST", "$api/releases", token, JSONObject().put("tag_name", "user-mods").put("name", "Моды пользователей").put("body", "Моды, загруженные через приложение Bearite Моды.").put("prerelease", false).put("make_latest", "false").toString().toByteArray())
        }
        check(rel, "релиз")
        val relJ = JSONObject(rel.text)
        val relId = relJ.getLong("id")
        var assets = relJ.optJSONArray("assets") ?: JSONArray()

        fun findAsset(name: String): JSONObject? { for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.optString("name") == name) return a }; return null }
        fun deleteAsset(name: String) { findAsset(name)?.let { check(http("DELETE", "$api/releases/assets/${it.getLong("id")}", token), "удаление старого $name") } }
        fun put(name: String, file: File, type: String) {
            deleteAsset(name)
            check(http("POST", "https://uploads.github.com/repos/$repo/releases/$relId/assets?name=${java.net.URLEncoder.encode(name, "UTF-8")}", token, file = file, type = type), "загрузка $name")
        }

        // текущий index.json
        var index = JSONArray()
        findAsset("index.json")?.let { a ->
            try {
                val c = URL(a.getString("browser_download_url")).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "Bearite-uploader")
                val o = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
                index = o.optJSONArray("mods") ?: JSONArray()
            } catch (t: Throwable) { say("Старый index.json не прочитался, создаю заново") }
        }

        say("Отправляю архив $zipName…")
        put(zipName, tmp, "application/zip")
        val entry = JSONObject().put("id", id).put("name", mj.optString("name", id)).put("version", mj.optString("version", "?"))
            .put("author", mj.optString("author")).put("description", mj.optString("description"))
            .put("url", "https://github.com/$repo/releases/download/user-mods/$zipName").put("size", tmp.length())
        val fresh = JSONArray()
        for (i in 0 until index.length()) { val o = index.getJSONObject(i); if (o.optString("id") != id) fresh.put(o) }
        fresh.put(entry)
        say("Обновляю список модов…")
        val idx = File(cacheDir, "index.json"); idx.writeText(JSONObject().put("mods", fresh).toString())
        // после загрузки архива список ассетов мог измениться, перечитываем
        val again = http("GET", "$api/releases/$relId", token); check(again, "релиз")
        assets = JSONObject(again.text).optJSONArray("assets") ?: JSONArray()
        put("index.json", idx, "application/json")
        tmp.delete(); idx.delete()
        say("Готово! «$id» появится во вкладке «Все моды» лаунчера (потяни «Обновить»).")
    }
}
