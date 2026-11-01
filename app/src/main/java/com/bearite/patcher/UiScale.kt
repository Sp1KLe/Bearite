package com.bearite.patcher

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Размер интерфейса мода: множитель к плотности экрана для кнопок, панелей и текста поверх игры.
 * Значение берётся из настройки «ui_scale» встроенного мода Bearite (файл builtin/bearite/settings.json).
 * Если настройки нет или файла нет, возвращается 1 (обычный размер).
 */
object UiScale {
    private const val KEY = "ui_scale"

    fun factor(ctx: Context): Float = try {
        val f = File(File(ctx.filesDir, "builtin/${ModManager.BUILTIN_ID}"), "settings.json")
        JSONObject(f.readText()).optDouble(KEY, 1.0).coerceIn(0.5, 2.0).toFloat()
    } catch (_: Throwable) {
        1f
    }

    /** Плотность экрана, умноженная на размер интерфейса мода. Вместо displayMetrics.density для всех элементов мода. */
    fun density(ctx: Context): Float = ctx.resources.displayMetrics.density * factor(ctx)
}
