package com.karaokebox.ui.overlay

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
 * Banner chạy chữ ở TRÊN CÙNG màn hình, nằm giữa hai mã QR hai góc:
 *  - "TIẾP THEO": báo bài kế tiếp khi bài đang hát chạy được 50%.
 *  - "ƯU TIÊN": có người vừa đưa một bài lên đầu hàng chờ.
 * Huy hiệu màu cố định bên trái, tên bài chạy từ phải sang trái trong khung bên phải.
 */
class TopBannerView(ctx: Context) : LinearLayout(ctx) {

    enum class Kind(val label: String, val accent: Int) {
        NEXT_UP("♪ TIẾP THEO", 0xFFFFB02E.toInt()),   // vàng hổ phách (cùng màu web remote)
        PRIORITY("ƯU TIÊN", 0xFFFF5C8A.toInt())        // hồng (cùng màu nút "Ưu tiên")
    }

    private class Item(val kind: Kind, val lead: String?, val title: String, val artist: String?)

    private val badge = TextView(ctx)
    private val clip = FrameLayout(ctx)
    private val tv = TextView(ctx)
    private val bg = GradientDrawable()
    private val badgeBg = GradientDrawable()

    private val pending = ArrayDeque<Item>()
    private var running = false
    private var gen = 0   // tăng mỗi lần bị ngắt: mọi animation cũ thấy số khác sẽ tự bỏ

    private val passes = 2                       // mỗi thông báo chạy 2 lượt cho dễ đọc
    private val speedPxPerMs = dp(130) / 1000f   // ~130dp mỗi giây

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(18), dp(8))

        bg.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        bg.colors = intArrayOf(0xF2140F1C.toInt(), 0xF2231B30.toInt())
        bg.cornerRadius = dp(16).toFloat()
        bg.setStroke(dp(2), Kind.NEXT_UP.accent)
        background = bg

        badge.textSize = 17f
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.letterSpacing = 0.06f
        badge.setTextColor(0xFF2B1A00.toInt())
        badge.gravity = Gravity.CENTER
        badge.setPadding(dp(16), dp(8), dp(16), dp(8))
        badgeBg.cornerRadius = dp(11).toFloat()
        badge.background = badgeBg
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        tv.textSize = 25f
        tv.setTextColor(Color.WHITE)
        tv.setSingleLine()
        tv.setHorizontallyScrolling(true)
        tv.includeFontPadding = false
        clip.clipChildren = true
        clip.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(clip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16) })

        visibility = View.GONE
        alpha = 0f
    }

    /** Thêm một thông báo vào hàng; các thông báo được chạy lần lượt. */
    fun post(kind: Kind, lead: String?, title: String, artist: String?) {
        val a = artist?.trim()?.takeIf { it.isNotEmpty() && it != FileNameParser.UNKNOWN_ARTIST }
        val item = Item(kind, lead?.trim()?.takeIf { it.isNotEmpty() }, title, a)
        if (kind == Kind.PRIORITY) {
            // Có bài ưu tiên mới: tắt ngay dòng đang chạy (và các dòng đang chờ, vì đã cũ), chạy lại bài mới nhất
            pending.clear()
            pending.add(item)
            if (running) interrupt() else next()
            return
        }
        if (pending.size >= 4) pending.poll()   // tránh dồn ứ khi nhiều người bấm liên tục
        pending.add(item)
        if (!running) next()
    }

    private fun interrupt() {
        gen++
        animate().cancel()
        tv.animate().cancel()
        next()
    }

    private fun next() {
        val item = pending.poll()
        if (item == null) {
            running = false
            return
        }
        running = true

        badge.text = item.kind.label
        badgeBg.setColor(item.kind.accent)
        bg.setStroke(dp(2), item.kind.accent)
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

        clip.doOnLayout { if (g == gen) scroll(textW, passes, g) }
    }

    private fun buildText(item: Item): CharSequence {
        val sb = SpannableStringBuilder()
        item.lead?.let {
            val s = sb.length
            sb.append(it).append("  ")
            sb.setSpan(ForegroundColorSpan(0xFFC9BFDA.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    private fun scroll(textW: Int, passesLeft: Int, g: Int) {
        val clipW = clip.width.toFloat().coerceAtLeast(1f)
        tv.translationX = clipW
        val dist = clipW + textW
        tv.animate().cancel()
        tv.animate()
            .translationX(-textW.toFloat())
            .setDuration((dist / speedPxPerMs).toLong().coerceAtLeast(4000L))
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                if (g != gen) return@withEndAction
                if (passesLeft > 1) scroll(textW, passesLeft - 1, g) else finish(g)
            }
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

    override fun onDetachedFromWindow() {
        gen++
        running = false
        pending.clear()
        animate().cancel()
        tv.animate().cancel()
        super.onDetachedFromWindow()
    }
}
