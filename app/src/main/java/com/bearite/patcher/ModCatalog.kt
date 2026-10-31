package com.bearite.patcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Каталог модов на сервере (релиз «mods» репозитория Bearite на GitHub): index.json со списком и zip-архивы модов.
 * Каталог собирает CI из sdk/mods; здесь он только читается, а мод ставится тем же ModManager.installZip.
 */
object ModCatalog {
    /** Встроенные моды (их публикует сборка) и моды, загруженные через приложение «Bearite Моды»; одинаковые id берутся из второго списка. */
    private val URLS = listOf(
        "https://github.com/Sp1KLe/Bearite/releases/download/mods/index.json",
        "https://github.com/Sp1KLe/Bearite/releases/download/user-mods/index.json",
    )

    class Item(val id: String, val name: String, val version: String, val author: String, val description: String, val url: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Bearite-launcher")
        return c
    }

    /** Один index.json: null — нет связи, пустой список — файла нет (404). */
    private fun fetchOne(u: String): List<Item> {
        val c = open(u)
        val code = c.responseCode
        if (code == 404) { c.disconnect(); return emptyList() }
        if (code !in 200..299) throw RuntimeException("код $code")
        val text = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val arr = JSONObject(text).optJSONArray("mods")
        val out = ArrayList<Item>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id"); val url = o.optString("url")
            if (id.isEmpty() || url.isEmpty()) continue
            out.add(Item(id, o.optString("name", id), o.optString("version", "?"), o.optString("author"), o.optString("description"), url, o.optLong("size", 0)))
        }
        return out
    }

    fun fetch(cb: (List<Item>?, String) -> Unit) {
        Thread {
            try {
                val byId = LinkedHashMap<String, Item>()
                var failed: Throwable? = null
                var ok = 0
                for (u in URLS) {
                    try { for (it in fetchOne(u)) byId[it.id] = it; ok++ } catch (t: Throwable) { failed = t }
                }
                if (ok == 0 && failed != null) throw failed
                BLog.i("каталог модов: ${byId.size}")
                main.post { cb(byId.values.toList(), "") }
            } catch (t: Throwable) {
                BLog.i("каталог модов: ошибка ${t.javaClass.simpleName} ${t.message}")
                main.post { cb(null, "Не удалось загрузить каталог (${t.message ?: t.javaClass.simpleName})") }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Скачивает архив мода и ставит его. Колбэк: пустая строка — успех, иначе текст ошибки. */
    fun install(ctx: Context, mods: ModManager, item: Item, cb: (String) -> Unit) {
        Thread {
            val f = File(ctx.cacheDir, "catalog_${item.id}.zip")
            try {
                val c = open(item.url)
                if (c.responseCode !in 200..299) throw RuntimeException("код ${c.responseCode}")
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                c.disconnect()
                val id = mods.installZip(Uri.fromFile(f))
                BLog.i("мод из каталога установлен: $id")
                main.post { cb("") }
            } catch (t: Throwable) {
                BLog.e("мод из каталога не установился: ${item.id}", t)
                main.post { cb(t.message ?: t.javaClass.simpleName) }
            } finally { f.delete() }
        }.apply { isDaemon = true }.start()
    }
}
