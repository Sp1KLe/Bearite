package com.bearite.patcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Холст фиксированного «дизайн-размера»: содержимое рисуется в условных dp (высота [DESIGN_H]),
 * а потом целиком масштабируется под экран. Поэтому вид не зависит от dpi и размера экрана,
 * а лишняя ширина просто растягивает раскладку (веса), как в макете.
 */
class DesignFrame @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    private var scale = 1f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        val d = resources.displayMetrics.density
        scale = minOf(h / (DESIGN_H * d), w / (MIN_W * d)).coerceAtLeast(0.1f)
        val cw = (w / scale).toInt()
        val ch = (h / scale).toInt()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            v.pivotX = 0f; v.pivotY = 0f
            v.scaleX = scale; v.scaleY = scale
        }
    }

    // дочерние виды увеличены трансформацией: разрешаем им рисоваться и принимать касания за своими границами
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = true

    companion object {
        const val DESIGN_H = 470f   // высота макета в dp (макет 1672x941 при коэффициенте 0.5)
        const val MIN_W = 700f      // самая узкая ширина, ниже — масштаб уменьшается
    }
}
