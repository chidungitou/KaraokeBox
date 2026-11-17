package com.karaokebox.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.karaokebox.game.GameRound
import com.karaokebox.game.nowMs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vòng xoay may mắn cuối bài, phủ toàn màn hình TV (không nhận phím / chạm; PlayerActivity bắt phím OK/Back để bỏ qua).
 * Vẽ hoàn toàn bằng Canvas, một ValueAnimator duy nhất nên rất nhẹ cho box yếu. Kết quả do máy chủ ([com.karaokebox.game.GameEngine])
 * quyết định sẵn trong [GameRound.resultIndex]; view chỉ quay tới đúng ô đó.
 *
 * TV tạo lại màn hình giữa vòng: [show] nhận lại cùng vòng, tính phần đã quay từ [GameRound.startedAt] rồi quay tiếp, không quay lại từ đầu.
 */
class GameView(ctx: Context) : View(ctx) {

    private companion object {
        const val BG = 0xE6100C18.toInt()
        const val GOLD = 0xFFFFC857.toInt()
        const val MUTED = 0xFFA398B4.toInt()
        const val TURNS = 5
        const val TICK_MIN_GAP_MS = 60L
        val PALETTE = intArrayOf(
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFF9A825.toInt(),
            0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt()
        )
        const val REMINDER_TEXT = "Uống có trách nhiệm · Đã uống rượu bia thì không lái xe"
    }

    private var round: GameRound? = null
    private var roundId = -1L
    private var angle = 0f            // góc quay hiện tại của vòng (độ, theo chiều kim đồng hồ)
    private var finalAngle = 0f
    private var inResult = false
    private var anim: ValueAnimator? = null
    private var lastTickIdx = -1
    private var lastTickAt = 0L
    private var tone: ToneGenerator? = null
    private var toneFailed = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFD54F.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xB3100C18.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.RIGHT
        setShadowLayer(4f, 0f, 2f, 0xCC000000.toInt())
    }
    private val rect = RectF()
    private val path = Path()

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Nhận trạng thái vòng từ máy chủ; null = không còn vòng nào (ẩn ngay). */
    fun show(r: GameRound?) {
        if (r == null) { hideNow(); return }
        if (r.id != roundId) {   // vòng mới
            stopAnim()
            roundId = r.id
            val n = max(1, r.labels.size)
            val sa = 360f / n
            val jitter = (Random.nextFloat() - 0.5f) * sa * 0.6f   // dừng lệch tâm ô một chút cho tự nhiên (vẫn nằm trong ô)
            finalAngle = 360f * TURNS + (360f - (r.resultIndex * sa + sa / 2f)) + jitter
            angle = 0f
            inResult = false
            lastTickIdx = -1
            visibility = VISIBLE
        }
        round = r
        if (r.phase == "RESULT") {
            if (!inResult) {
                stopAnim()
                angle = finalAngle
                inResult = true
                playResultSound(r)
            }
        } else if (anim == null && !inResult) {
            startSpin(r)
        }
        postInvalidateOnAnimation()
    }

    private fun startSpin(r: GameRound) {
        val elapsed = (nowMs() - r.startedAt).coerceIn(0L, r.spinMs)
        val a = ValueAnimator.ofFloat(0f, finalAngle)
        a.duration = r.spinMs
        a.interpolator = DecelerateInterpolator(2.4f)
        a.addUpdateListener {
            angle = it.animatedValue as Float
            tickIfNeeded()
            invalidate()
        }
        anim = a
        a.start()
        if (elapsed > 0L) a.currentPlayTime = elapsed
    }

    private fun stopAnim() {
        anim?.cancel()
        anim = null
    }

    private fun hideNow() {
        stopAnim()
        round = null
        roundId = -1L
        inResult = false
        angle = 0f
        visibility = GONE
    }

    // ------------------------------------------------------------------ âm thanh

    private fun tone(): ToneGenerator? {
        if (tone == null && !toneFailed) {
            tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: RuntimeException) { toneFailed = true; null }
        }
        return tone
    }

    /** "Tích" mỗi khi kim đi qua ranh giới giữa hai ô; thưa dần theo tốc độ vòng xoay vì chỉ phát khi đổi ô. */
    private fun tickIfNeeded() {
        val r = round ?: return
        val n = max(1, r.labels.size)
        val sa = 360f / n
        val under = ((((-angle) % 360f) + 360f) % 360f / sa).toInt() % n
        if (under == lastTickIdx) return
        lastTickIdx = under
        if (!r.sound) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < TICK_MIN_GAP_MS) return
        lastTickAt = now
        try { tone()?.startTone(ToneGenerator.TONE_PROP_BEEP, 35) } catch (_: RuntimeException) { }
    }

    private fun playResultSound(r: GameRound) {
        if (!r.sound) return
        try { tone()?.startTone(ToneGenerator.TONE_PROP_ACK, 450) } catch (_: RuntimeException) { }
    }

    // ------------------------------------------------------------------ vẽ

    /** Co chữ lại cho vừa [maxW] (không phóng to quá [size]). */
    private fun fit(p: Paint, s: String, maxW: Float, size: Float) {
        p.textSize = size
        val m = p.measureText(s)
        if (m > maxW && m > 0f) p.textSize = size * maxW / m
    }

    override fun onDraw(canvas: Canvas) {
        val r = round ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(BG)

        // Tiêu đề + gợi ý
        text.textAlign = Paint.Align.CENTER
        text.color = GOLD
        text.setShadowLayer(0f, 0f, 0f, 0)
        text.textSize = h * 0.055f
        canvas.drawText(if (r.test) "VÒNG XOAY MAY MẮN · QUAY THỬ" else "VÒNG XOAY MAY MẮN", w / 2f, h * 0.085f, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = MUTED
        text.textSize = h * 0.026f
        canvas.drawText("OK / Back: bỏ qua", w - h * 0.04f, h * 0.045f, text)

        // Vòng xoay
        val n = max(1, r.labels.size)
        val sa = 360f / n
        val cx = w / 2f
        val cy = h * 0.45f
        val rad = h * 0.30f
        rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        line.strokeWidth = max(2f, rad * 0.012f)
        for (i in 0 until n) {
            fill.color = PALETTE[i % PALETTE.size]
            if (n == 1) canvas.drawCircle(cx, cy, rad, fill) else canvas.drawArc(rect, -90f + i * sa + angle, sa, true, fill)
        }
        if (n > 1) {
            for (i in 0 until n) {
                val a = Math.toRadians((-90f + i * sa + angle).toDouble())
                canvas.drawLine(cx, cy, cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat(), line)
            }
        }
        // Chữ trên ô: chạy từ tâm ra rìa
        for (i in 0 until n) {
            canvas.save()
            canvas.rotate(angle + i * sa + sa / 2f - 90f, cx, cy)
            fit(label, r.labels[i], rad * 0.62f, rad * 0.115f)
            canvas.drawText(r.labels[i], cx + rad * 0.92f, cy + label.textSize * 0.35f, label)
            canvas.restore()
        }
        // Có kết quả: làm tối các ô còn lại, ô trúng sáng nổi bật
        if (inResult && n > 1) {
            for (i in 0 until n) {
                if (i != r.resultIndex) canvas.drawArc(rect, -90f + i * sa + angle, sa, true, dim)
            }
        }
        // Viền + đèn nhấp nháy quanh vòng
        ring.strokeWidth = rad * 0.05f
        canvas.drawCircle(cx, cy, rad + ring.strokeWidth / 2f, ring)
        val blink = ((SystemClock.uptimeMillis() / (if (inResult) 300L else 120L)) % 2L).toInt()
        val dotR = rad * 0.018f
        val lights = 24
        for (j in 0 until lights) {
            val a = Math.toRadians(j * 360.0 / lights)
            dot.color = if ((j + blink) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF7A5B00.toInt()
            canvas.drawCircle(cx + (rad + ring.strokeWidth / 2f) * cos(a).toFloat(), cy + (rad + ring.strokeWidth / 2f) * sin(a).toFloat(), dotR, dot)
        }
        // Tâm
        fill.color = 0xFF1B1626.toInt()
        canvas.drawCircle(cx, cy, rad * 0.1f, fill)
        ring.strokeWidth = rad * 0.025f
        canvas.drawCircle(cx, cy, rad * 0.1f, ring)
        // Kim chỉ (cố định ở trên)
        path.reset()
        path.moveTo(cx, cy - rad + rad * 0.07f)
        path.lineTo(cx - rad * 0.075f, cy - rad - rad * 0.11f)
        path.lineTo(cx + rad * 0.075f, cy - rad - rad * 0.11f)
        path.close()
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, fill)
        line.strokeWidth = max(2f, rad * 0.012f)
        line.color = 0xFF1B1626.toInt()
        canvas.drawPath(path, line)
        line.color = 0x66000000

        // Kết quả
        text.textAlign = Paint.Align.CENTER
        if (inResult) {
            text.color = 0xFFFFFFFF.toInt()
            fit(text, r.title, w * 0.92f, h * 0.05f)
            canvas.drawText(r.title, w / 2f, h * 0.835f, text)
            text.color = when (r.level) {
                "SIP" -> 0xFF69F0AE.toInt()
                "HALF" -> 0xFFFFB02E.toInt()
                else -> 0xFFFF5252.toInt()
            }
            text.setShadowLayer(h * 0.01f, 0f, h * 0.004f, 0xCC000000.toInt())
            fit(text, r.action, w * 0.92f, h * 0.085f)
            canvas.drawText(r.action, w / 2f, h * 0.93f, text)
            text.setShadowLayer(0f, 0f, 0f, 0)
        } else {
            text.color = MUTED
            text.textSize = h * 0.05f
            canvas.drawText("ĐANG QUAY…", w / 2f, h * 0.86f, text)
        }
        if (r.reminder) {
            text.color = 0xFF8F86A0.toInt()
            fit(text, REMINDER_TEXT, w * 0.92f, h * 0.026f)
            canvas.drawText(REMINDER_TEXT, w / 2f, h * 0.98f, text)
        }

        // Đèn nhấp nháy cần vẽ lại đều; dừng hẳn khi view đã ẩn
        if (visibility == VISIBLE) postInvalidateDelayed(if (anim != null) 16L else 150L)
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        try { tone?.release() } catch (_: RuntimeException) { }
        tone = null
        super.onDetachedFromWindow()
    }
}
