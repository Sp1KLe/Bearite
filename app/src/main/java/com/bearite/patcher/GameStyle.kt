package com.bearite.patcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * Фон кнопки «как в Super Bear Adventure»: тёмная обводка, градиент от светлого верха к насыщенному низу
 * и белый глянцевый блик на верхней половине. При нажатии слегка темнеет.
 */
class GameBtnBg(private val base: Int, private val radius: Float, private val stroke: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fill: Shader? = null
    private var gloss: Shader? = null
    private var pressed = false

    override fun onBoundsChange(b: android.graphics.Rect) {
        fill = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), GameUi.lighten(base, 0.38f), base, Shader.TileMode.CLAMP)
        gloss = LinearGradient(0f, b.top.toFloat(), 0f, b.centerY().toFloat(), 0x99FFFFFF.toInt(), 0x11FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = GameUi.OUTLINE
        c.drawRoundRect(rect, radius, radius, p)
        rect.inset(stroke, stroke)
        val r = (radius - stroke).coerceAtLeast(0f)
        p.shader = fill
        c.drawRoundRect(rect, r, r, p)
        // глянцевый блик
        val gl = RectF(rect.left + rect.width() * 0.05f, rect.top + stroke * 0.5f, rect.right - rect.width() * 0.05f, rect.centerY())
        p.shader = gloss
        c.drawRoundRect(gl, r * 0.8f, r * 0.8f, p)
        p.shader = null
        if (pressed) {
            p.color = 0x44000000
            c.drawRoundRect(rect, r, r, p)
        }
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Текст с толстой тёмной окантовкой, как надписи в игре. */
class OutlinedText(ctx: Context) : TextView(ctx) {
    var outlineColor = GameUi.OUTLINE
    var outlineWidthPx = 0f
    private var busy = false

    override fun invalidate() { if (!busy) super.invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidthPx <= 0f) { super.onDraw(canvas); return }
        val saved = currentTextColor
        busy = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidthPx
        paint.strokeJoin = Paint.Join.ROUND
        setTextColor(outlineColor)
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        setTextColor(saved)
        busy = false
        super.onDraw(canvas)
    }
}
