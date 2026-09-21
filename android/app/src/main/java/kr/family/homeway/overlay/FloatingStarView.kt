package kr.family.homeway.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.Button
import kotlin.math.cos
import kotlin.math.sin

/** Only the star is painted; its transparent 48 dp window remains a usable touch target. */
internal class FloatingStarView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 169, 48)
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(117, 85, 27)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.85f
        strokeJoin = Paint.Join.ROUND
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 248, 210)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.7f
        strokeJoin = Paint.Join.ROUND
    }
    private val star = Path()

    init {
        contentDescription = "메신저 열기. 끌어서 별 위치를 옮길 수 있어요."
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val metrics = resources.displayMetrics
        val starWidth = OverlayGeometry.starPixels(metrics.xdpi, metrics.density).coerceAtMost(width * 0.86f)
        val starHeight = OverlayGeometry.starPixels(metrics.ydpi, metrics.density).coerceAtMost(height * 0.86f)
        val vertices = (0 until 10).map { index ->
            val angle = -Math.PI / 2.0 + index * Math.PI / 5.0
            val radius = if (index % 2 == 0) 1.0 else 0.46
            cos(angle) * radius to sin(angle) * radius
        }
        val minX = vertices.minOf { it.first }
        val maxX = vertices.maxOf { it.first }
        val minY = vertices.minOf { it.second }
        val maxY = vertices.maxOf { it.second }
        star.reset()
        vertices.forEachIndexed { index, point ->
            val x = (width - starWidth) / 2f + ((point.first - minX) / (maxX - minX) * starWidth).toFloat()
            val y = (height - starHeight) / 2f + ((point.second - minY) / (maxY - minY) * starHeight).toFloat()
            if (index == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        canvas.drawPath(star, fill)
        canvas.drawPath(star, outline)
        canvas.save()
        canvas.scale(0.88f, 0.88f, width / 2f, height / 2f)
        canvas.drawPath(star, highlight)
        canvas.restore()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
