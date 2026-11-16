package com.karaokebox.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.karaokebox.data.scan.FileNameParser
import com.karaokebox.ui.dp
import java.util.ArrayDeque
import kotlin.math.ceil

/**
 * Chữ chạy ngang ở TRÊN CÙNG màn hình, nằm NGAY DƯỚI banner "ƯU TIÊN / TIẾP THEO":
 *  - "♫ VỪA CHỌN": có người vừa thêm một bài vào danh sách chờ.
 *  - "! LỖI" / "THÔNG BÁO": các thông báo khác.
 * Khung bo tròn, viền phát sáng nhấp nháy theo màu huy hiệu, trượt vào từ trên rồi mờ dần khi xong.
 */
class MarqueeToastView(ctx: Context) : LinearLayout(ctx) {

    enum class Kind(val label: String, val accent: Int) {
        ADDED("♫ VỪA CHỌN", 0xFF3DDCFF.toInt()),    // xanh ngọc (khác vàng/hồng của banner phía trên)
        ERROR("! LỖI", 0xFFFF6B6B.toInt()),
        INFO("THÔNG BÁO", 0xFFB39DFF.toInt())
    }

    private class Item(val kind: Kind, val lead: String?, val title: String, val artist: String?)

    private val badge = TextView(ctx)
    private val clip = FrameLayout(ctx)
    private val tv = TextView(ctx)
    private val bg = GradientDrawable()
    private val badgeBg = GradientDrawable()

    private val pending = ArrayDeque<Item>()
    private var running = false
    private var gen = 0
    private var accent = Kind.ADDED.accent

    private val speedPxPerMs = dp(150) / 1000f   // ~150dp mỗi giây

    // viền sáng lên - dịu xuống liên tục trong lúc chữ chạy
    private val glow = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val f = it.animatedValue as Float
            bg.setStroke(dp(2), blend(accent, Color.WHITE, f * 0.55f))
            badge.alpha = 0.82f + 0.18f * f
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(18), dp(7))

        bg.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        bg.colors = intArrayOf(0xF2101C26.toInt(), 0xF2183040.toInt())
        bg.cornerRadius = dp(16).toFloat()
        bg.setStroke(dp(2), accent)
        background = bg

        badge.textSize = 16f
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.letterSpacing = 0.06f
        badge.setTextColor(0xFF08202A.toInt())
        badge.gravity = Gravity.CENTER
        badge.setPadding(dp(14), dp(7), dp(14), dp(7))
        badgeBg.cornerRadius = dp(11).toFloat()
        badge.background = badgeBg
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        tv.textSize = 24f
        tv.setTextColor(Color.WHITE)
        tv.setSingleLine()
        tv.setHorizontallyScrolling(true)
        tv.includeFontPadding = false
        clip.clipChildren = true
        clip.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(clip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })

        visibility = View.GONE
        alpha = 0f
    }

    /** Thông báo chữ thường (lỗi, hướng dẫn...). */
    fun post(message: String, kind: Kind = Kind.INFO) = post(kind, null, message, null)

    fun post(kind: Kind, lead: String?, title: String, artist: String?) {
        val a = artist?.trim()?.takeIf { it.isNotEmpty() && it != FileNameParser.UNKNOWN_ARTIST }
        if (pending.size >= 4) pending.poll()   // tránh dồn ứ khi nhiều người bấm liên tục
        pending.add(Item(kind, lead?.trim()?.takeIf { it.isNotEmpty() }, title, a))
        if (!running) next()
    }

    private fun next() {
        val item = pending.poll()
        if (item == null) {
            running = false
            glow.cancel()
            return
        }
        running = true

        accent = item.kind.accent
        badge.text = item.kind.label
        badgeBg.setColor(accent)
        bg.setStroke(dp(2), accent)
        tv.text = buildText(item)

        val textW = ceil(Layout.getDesiredWidth(tv.text, tv.paint)).toInt() + dp(12)
        tv.layoutParams = (tv.layoutParams as FrameLayout.LayoutParams).apply { width = textW }
        tv.translationX = 100_000f   // giấu ngoài khung cho tới khi bắt đầu chạy

        val g = gen
        animate().cancel()
        visibility = View.VISIBLE
        alpha = 0f
        translationY = -dp(18).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(350).start()
        if (!glow.isStarted) glow.start()

        clip.doOnLayout { if (g == gen) scroll(textW, g) }
    }

    private fun buildText(item: Item): CharSequence {
        val sb = SpannableStringBuilder()
        item.lead?.let {
            val s = sb.length
            sb.append(it).append("  ")
            sb.setSpan(ForegroundColorSpan(0xFFB8CBD6.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var s = sb.length
        sb.append(item.title)
        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.WHITE), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        item.artist?.let {
            sb.append("   –   ")
            s = sb.length
            sb.append(it)
            sb.setSpan(ForegroundColorSpan(item.kind.accent), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun scroll(textW: Int, g: Int) {
        val clipW = clip.width.toFloat().coerceAtLeast(1f)
        tv.translationX = clipW
        val dist = clipW + textW
        tv.animate().cancel()
        tv.animate()
            .translationX(-textW.toFloat())
            .setDuration((dist / speedPxPerMs).toLong().coerceAtLeast(4000L))
            .setInterpolator(LinearInterpolator())
            .withEndAction { if (g == gen) finish(g) }
            .start()
    }

    private fun finish(g: Int) {
        animate().cancel()
        animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(300)
            .withEndAction {
                if (g != gen) return@withEndAction
                visibility = View.GONE
                next()
            }
            .start()
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) * (1 - f) + Color.red(b) * f).toInt()
        val g = (Color.green(a) * (1 - f) + Color.green(b) * f).toInt()
        val bl = (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    override fun onDetachedFromWindow() {
        gen++
        running = false
        pending.clear()
        glow.cancel()
        animate().cancel()
        tv.animate().cancel()
        super.onDetachedFromWindow()
    }
}
