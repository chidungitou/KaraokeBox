package com.karaokebox.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.karaokebox.ui.dp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hiệu ứng cảm xúc bay từ dưới lên (tim, pháo, vỗ tay, quẩy) phủ toàn màn hình, không nhận phím / chạm.
 * Nhẹ cho box yếu: tối đa [MAX] hiệu ứng cùng lúc (đầy thì bỏ cái cũ nhất), mỗi loại chỉ vẽ emoji vào bitmap MỘT lần
 * rồi dùng lại, vẽ khoảng 30 khung hình/giây và dừng hẳn khi không còn hiệu ứng nào.
 */
class ReactionView(ctx: Context) : View(ctx) {

    private class P(val kind: String, val x0: Float, val born: Long, val durMs: Long, val sway: Float, val phase: Float, val scale: Float)

    private companion object {
        const val MAX = 10
        const val FRAME_MS = 33L
        val EMOJI = mapOf(
            "heart" to "\u2764\uFE0F",
            "firework" to "\uD83C\uDF86",
            "clap" to "\uD83D\uDC4F",
            "party" to "\uD83C\uDF89"
        )
    }

    private val parts = ArrayList<P>(MAX)
    private val bitmaps = HashMap<String, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun spawn(kind: String) {
        if (kind !in EMOJI || width == 0 || height == 0) return
        if (parts.size >= MAX) parts.removeAt(0)
        val r = Random
        parts.add(
            P(
                kind = kind,
                x0 = width * (0.55f + r.nextFloat() * 0.40f),     // bay lên ở nửa bên phải, tránh che lời bài hát ở giữa
                born = SystemClock.uptimeMillis(),
                durMs = 2800L + r.nextLong(1200L),
                sway = dp(14) + r.nextFloat() * dp(26),
                phase = r.nextFloat() * 6.28f,
                scale = 0.9f + r.nextFloat() * 0.4f
            )
        )
        postInvalidateOnAnimation()
    }

    private fun bitmapFor(kind: String): Bitmap = bitmaps.getOrPut(kind) {
        val size = dp(80)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(60).toFloat() }
        val text = EMOJI.getValue(kind)
        val fm = p.fontMetrics
        Canvas(bmp).drawText(text, (size - p.measureText(text)) / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        bmp
    }

    override fun onDraw(canvas: Canvas) {
        if (parts.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val t = (now - p.born).toFloat() / p.durMs
            if (t >= 1f) { it.remove(); continue }
            val bmp = bitmapFor(p.kind)
            val size = bmp.width * p.scale * (0.7f + 0.3f * min(t * 4f, 1f))     // nở ra nhanh lúc mới xuất hiện
            val ease = 1f - (1f - t) * (1f - t)                                   // bay nhanh rồi chậm dần
            val y = height * 0.92f - height * 0.78f * ease
            val x = p.x0 + sin(t * 6f + p.phase) * p.sway
            paint.alpha = when {
                t < 0.08f -> (t / 0.08f * 255).toInt()
                t > 0.7f -> ((1f - t) / 0.3f * 255).toInt()
                else -> 255
            }.coerceIn(0, 255)
            dst.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
            canvas.drawBitmap(bmp, null, dst, paint)
        }
        if (parts.isNotEmpty()) postInvalidateDelayed(FRAME_MS)
    }

    override fun onDetachedFromWindow() {
        parts.clear()
        super.onDetachedFromWindow()
    }
}
