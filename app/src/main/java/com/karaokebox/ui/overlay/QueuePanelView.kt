package com.karaokebox.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.karaokebox.data.repo.QueueEntry
import com.karaokebox.data.scan.FileNameParser
import com.karaokebox.data.yt.isYoutube
import com.karaokebox.ui.dp

/** Danh sách chờ điều khiển bằng D-pad (Menu mở, OK = đẩy lên đầu, Đỏ/Del = xóa). */
class QueuePanelView(ctx: Context) : LinearLayout(ctx) {
    private var items: List<QueueEntry> = emptyList()
    private var sel = 0
    private val rowCount = 5   // mỗi dòng cao hơn (tên bài xuống dòng + ca sĩ) nên hiện ít dòng hơn mà vẫn đủ chữ
    private val title = TextView(ctx)
    private val rows = (0 until rowCount).map { TextView(ctx) }
    private val foot = TextView(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(0xE6140F1C.toInt())
        setPadding(dp(20), dp(16), dp(20), dp(16))
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        addView(title)
        rows.forEach {
            it.textSize = 19f
            it.maxLines = 3                       // tên bài tối đa 2 dòng + 1 dòng ca sĩ
            it.ellipsize = TextUtils.TruncateAt.END
            it.setPadding(dp(10), dp(7), dp(10), dp(7))
            addView(it)
        }
        foot.textSize = 14f
        foot.setTextColor(0xFFA398B4.toInt())
        foot.setPadding(0, dp(12), 0, 0)
        foot.text = "OK: đẩy lên đầu   Đỏ / Xóa: bỏ bài   MENU: cài đặt   BACK: đóng"
        addView(foot)
        visibility = View.GONE
    }

    fun update(list: List<QueueEntry>) {
        items = list
        sel = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        render()
    }

    fun show() { visibility = View.VISIBLE; render() }
    fun hide() { visibility = View.GONE }

    fun move(d: Int) {
        if (items.isEmpty()) return
        sel = (sel + d).coerceIn(0, items.size - 1)
        render()
    }

    fun selectedId(): Long? = items.getOrNull(sel)?.itemId

    private fun render() {
        title.text = "Danh sách chờ (${items.size})"
        val start = (sel - rowCount / 2).coerceIn(0, (items.size - rowCount).coerceAtLeast(0))
        for (i in 0 until rowCount) {
            val idx = start + i
            val tv = rows[i]
            val e = items.getOrNull(idx)
            if (e == null) {
                tv.text = if (items.isEmpty() && i == 0) "Chưa có bài nào. Quét mã QR để chọn bài." else ""
                tv.setBackgroundColor(Color.TRANSPARENT)
                tv.setTextColor(0xFFA398B4.toInt())
            } else {
                val on = idx == sel
                tv.text = rowText(idx, e, on)
                tv.setBackgroundColor(if (on) 0xFFFFB02E.toInt() else Color.TRANSPARENT)
                tv.setTextColor(if (on) 0xFF2B1A00.toInt() else Color.WHITE)
            }
        }
    }

    /** Dòng 1: số thứ tự + tên bài đầy đủ (xuống dòng nếu dài). Dòng 2: ca sĩ (nếu có) · người thêm. */
    private fun rowText(idx: Int, e: QueueEntry, selected: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append("${idx + 1}. ")
        if (e.song.isYoutube) sb.append("[YT] ")
        val t0 = sb.length
        sb.append(e.song.title)
        sb.setSpan(StyleSpan(Typeface.BOLD), t0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val artist = e.song.artist.trim().takeIf { it.isNotEmpty() && it != FileNameParser.UNKNOWN_ARTIST }
        val sub = listOfNotNull(artist, e.addedBy.takeIf { it.isNotBlank() }?.let { "thêm bởi $it" }).joinToString("  ·  ")
        if (sub.isNotEmpty()) {
            sb.append("\n")
            val s0 = sb.length
            sb.append(sub)
            sb.setSpan(AbsoluteSizeSpan(15, true), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(if (selected) 0xFF5A3A00.toInt() else 0xFFFFC866.toInt()), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
