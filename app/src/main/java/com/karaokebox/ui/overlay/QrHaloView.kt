package com.karaokebox.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.karaokebox.ui.dp
import kotlin.math.sin

/**
 * Vòng sáng lan tỏa quanh mã QR khi có điện thoại mới vào phòng. View trong suốt phủ toàn màn hình,
 * nằm NGAY DƯỚI mã QR (QR vẫn rõ, vòng sáng tỏa ra ngoài viền). Chỉ vẽ trong ~2,8 giây sau [pulse];
 * lúc rảnh không tốn tài nguyên. Vị trí đọc trực tiếp từ [target] nên bám theo khi QR đổi từ to giữa màn hình về góc phải.
 */
class QrHaloView(ctx: Context, private val target: View) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var progress = 1f
    private var color = 0xFFFFD56B.toInt()

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2800
        interpolator = LinearInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun pulse(accent: Int) {
        color = accent
        anim.cancel()
        anim.start()
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (!anim.isRunning) return
        if (target.visibility != VISIBLE || target.width == 0) return
        val x = target.x; val y = target.y
        val tw = target.width.toFloat(); val th = target.height.toFloat()

        // quầng sáng đặc phía sau QR, nhạt dần ở cuối
        val steady = if (progress < 0.75f) 1f else 1f - (progress - 0.75f) / 0.25f
        val g = dp(18).toFloat()
        rect.set(x - g, y - g, x + tw + g, y + th + g)
        fill.color = color
        fill.alpha = (steady * (60 + 25 * sin(progress * 36f))).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, dp(24).toFloat(), dp(24).toFloat(), fill)

        // khung sáng sát viền QR, nhấp nháy nhẹ
        val g2 = dp(6).toFloat()
        rect.set(x - g2, y - g2, x + tw + g2, y + th + g2)
        ring.color = color
        ring.strokeWidth = dp(4).toFloat()
        ring.alpha = (steady * (170 + 70 * sin(progress * 36f))).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, dp(14).toFloat(), dp(14).toFloat(), ring)

        // 3 vòng sóng lan ra ngoài rồi mờ dần
        for (i in 0..2) {
            val p = ((progress - i * 0.14f) / 0.62f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val grow = dp(8) + p * dp(96)
            rect.set(x - grow, y - grow, x + tw + grow, y + th + grow)
            ring.strokeWidth = dp(5) * (1f - p) + dp(1)
            ring.color = color
            ring.alpha = ((1f - p) * 230).toInt().coerceIn(0, 255)
            val r = dp(14) + grow * 0.6f
            canvas.drawRoundRect(rect, r, r, ring)
        }
    }
}
