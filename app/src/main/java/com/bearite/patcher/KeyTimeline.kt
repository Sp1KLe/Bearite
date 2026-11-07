package com.bearite.patcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Шкала времени для своих анимаций: ромбики — ключевые кадры, жёлтая линия — текущее время.
 * Тап по шкале переносит время, перетаскивание ромбика меняет время ключа.
 */
class KeyTimeline(ctx: Context, private val dens: Float) : View(ctx) {
    var duration = 2.0
    var keys: List<MapAnim.Key> = emptyList()
    var playhead = 0.0
    var selected = -1
    /** Время изменилось тапом/перетаскиванием по пустому месту. */
    var onScrub: ((Double) -> Unit)? = null
    /** Ключ выбран (индекс в keys) или сдвинут (finished = true, когда палец отпущен). */
    var onKey: ((Int, Boolean) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = 18f * dens
    private var dragKey = -1
    private val rect = RectF()
    private val path = Path()

    private fun xOf(t: Double) = pad + ((t / max(0.01, duration)).coerceIn(0.0, 1.0) * (width - 2 * pad)).toFloat()
    private fun tOf(x: Float) = (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * duration)

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val mid = h * 0.55f
        p.style = Paint.Style.FILL; p.color = 0xFF2A2C66.toInt()
        rect.set(pad - 6 * dens, mid - 12 * dens, width - pad + 6 * dens, mid + 12 * dens)
        c.drawRoundRect(rect, 10 * dens, 10 * dens, p)
        // отметки по секундам
        p.color = 0x88FFFFFF.toInt(); p.strokeWidth = 1.5f * dens; p.textSize = 9.5f * dens; p.textAlign = Paint.Align.CENTER
        val step = when { duration <= 6 -> 1.0; duration <= 20 -> 2.0; duration <= 60 -> 5.0; else -> 10.0 }
        var t = 0.0
        while (t <= duration + 1e-6) {
            val x = xOf(t)
            c.drawLine(x, mid - 12 * dens, x, mid - 6 * dens, p)
            c.drawText(if (t == Math.floor(t)) t.toInt().toString() else String.format("%.1f", t), x, 11 * dens, p)
            t += step
        }
        // ключи
        for ((i, k) in keys.withIndex()) {
            val x = xOf(k.t); val r = 9 * dens
            path.reset(); path.moveTo(x, mid - r); path.lineTo(x + r, mid); path.lineTo(x, mid + r); path.lineTo(x - r, mid); path.close()
            p.style = Paint.Style.FILL; p.color = if (i == selected) 0xFFFFC61A.toInt() else 0xFFFFFFFF.toInt()
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dens; p.color = 0xFF1A1206.toInt()
            c.drawPath(path, p)
        }
        // текущее время
        val px = xOf(playhead)
        p.style = Paint.Style.FILL; p.color = 0xFFFF5A4D.toInt()
        c.drawRect(px - 1.5f * dens, mid - 18 * dens, px + 1.5f * dens, mid + 18 * dens, p)
        path.reset(); path.moveTo(px - 6 * dens, mid - 18 * dens); path.lineTo(px + 6 * dens, mid - 18 * dens); path.lineTo(px, mid - 10 * dens); path.close()
        c.drawPath(path, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = -1; var bd = 22 * dens
                for ((i, k) in keys.withIndex()) { val d = abs(xOf(k.t) - e.x); if (d < bd) { bd = d; best = i } }
                dragKey = best
                if (best >= 0) { selected = best; playhead = keys[best].t; onKey?.invoke(best, false) }
                else { playhead = tOf(e.x); selected = -1; onScrub?.invoke(playhead) }
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val t = Math.round(tOf(e.x) * 20) / 20.0   // шаг 0.05 с
                if (dragKey >= 0) { keys[dragKey].t = t; playhead = t; onKey?.invoke(dragKey, false) }
                else { playhead = t; onScrub?.invoke(t) }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragKey >= 0) onKey?.invoke(dragKey, true)
                dragKey = -1
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (64 * dens).toInt())
}
