package com.karaokebox.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.karaokebox.ServiceLocator
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.player.audio.MicEngine
import com.karaokebox.player.audio.StudioPreset
import com.karaokebox.ui.dp

/**
 * Bảng cài đặt nhanh nổi ngay trên màn hình đang hát (nút Vàng / Settings), giống bảng danh sách chờ.
 * Chỉ chứa các mục chỉnh được lúc đang hát; cài đặt quản trị (thư mục nhạc, quét lại...) nằm ở dòng cuối.
 * Phím được PlayerActivity chuyển vào: Lên/Xuống = chọn dòng, Trái/Phải = đổi giá trị, OK = đổi / mở.
 * Mọi thay đổi ghi thẳng vào SettingsStore nên điện thoại và bài đang hát cập nhật theo qua SettingsStore.changes.
 */
class SettingsPanelView(ctx: Context, private val onOpenFull: () -> Unit) : LinearLayout(ctx) {

    // step phải ở CUỐI để các dòng cũ viết Row(nhãn, giá trị) { đổi giá trị } vẫn gán khối lệnh cuối vào step
    private class Row(
        val label: String,
        val value: (() -> String)?,
        val action: (() -> Unit)? = null,
        val step: ((Int) -> Unit)? = null
    )

    /** "Đặt lại chủ phòng" cần bấm OK hai lần để tránh bấm nhầm giữa buổi hát. */
    private var confirmReset = false

    private val settings get() = ServiceLocator.settings

    private val rows: List<Row> = listOf(
        Row("Kênh ca sĩ (file 1 track stereo)", { if (settings.leftIsVocal) "TRÁI" else "PHẢI" }) {
            settings.leftIsVocal = !settings.leftIsVocal
        },
        Row("Hiệu ứng âm thanh phòng thu", { StudioPreset.fromKey(settings.audioFx).label }) { d ->
            val list = StudioPreset.ALL
            val cur = list.indexOfFirst { it.key == settings.audioFx }.coerceAtLeast(0)
            settings.audioFx = list[(cur + d + list.size) % list.size].key
        },
        Row("Cường độ hiệu ứng", { "${settings.audioFxLevel}%" }) { d ->
            settings.audioFxLevel = (settings.audioFxLevel + d * 10).coerceIn(0, 100)
        },
        Row("Micro cắm USB", {
            if (!settings.micEnabled) "TẮT"
            else when (ServiceLocator.mic.status.value) {
                MicEngine.NO_PERMISSION -> "BẬT (chưa cấp quyền micro)"
                MicEngine.NO_DEVICE -> "BẬT (không thấy micro)"
                MicEngine.ERROR -> "BẬT (lỗi thu âm)"
                else -> "BẬT"
            }
        }) { settings.micEnabled = !settings.micEnabled },
        Row("Âm lượng micro", { "${settings.micVolume}%" }) { d ->
            settings.micVolume = (settings.micVolume + d * 5).coerceIn(0, 100)
        },
        Row("Vang micro", { "${settings.micEcho}%" }) { d ->
            settings.micEcho = (settings.micEcho + d * 5).coerceIn(0, 100)
        },
        Row("Xếp hàng công bằng", { if (settings.fairQueue) "BẬT" else "TẮT" }) {
            settings.fairQueue = !settings.fairQueue
        },
        Row("Khách được qua bài của người khác", { if (settings.guestCanSkip) "BẬT" else "TẮT" }) {
            settings.guestCanSkip = !settings.guestCanSkip
        },
        Row("Chủ phòng (OK: đặt lại)", {
            if (confirmReset) "BẤM OK LẦN NỮA ĐỂ XÁC NHẬN"
            else ServiceLocator.room.state.value.let { st -> if (st.ownerId == null) "chưa có" else st.ownerName.ifBlank { "Khách" } }
        }, action = {
            if (!confirmReset) confirmReset = true
            else {
                confirmReset = false
                // Các máy đang trong phòng không tự nhận lại; người MỚI quét QR sẽ thành chủ phòng
                ServiceLocator.room.reset(ServiceLocator.hub.presentIds())
            }
        }),
        Row("Chất lượng YouTube (từ bài sau)", { "${settings.youtubeMaxHeight}p" }) { d ->
            val list = SettingsStore.YT_HEIGHTS
            val cur = list.indexOf(settings.youtubeMaxHeight).takeIf { it >= 0 } ?: list.indexOf(720)
            settings.youtubeMaxHeight = list[(cur + d + list.size) % list.size]
        },
        Row("Giao diện màn hình chờ", { StageTheme.describe(settings.stageThemeMode) }) { d ->
            val modes = StageTheme.MODES
            val cur = modes.indexOfFirst { it.first == settings.stageThemeMode }.coerceAtLeast(0)
            settings.stageThemeMode = modes[(cur + d + modes.size) % modes.size].first
        },
        // Dòng cuối: mở màn hình Cài đặt đầy đủ (thư mục nhạc, quét lại, cập nhật YouTube...)
        Row("Cài đặt đầy đủ: thư mục nhạc, quét lại, cập nhật YouTube...", null)
    )

    private var sel = 0
    private val title = TextView(ctx)
    private val tvs = rows.map { TextView(ctx) }
    private val foot = TextView(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(0xE6140F1C.toInt())
        setPadding(dp(20), dp(16), dp(20), dp(16))
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.text = "Cài đặt nhanh"
        addView(title)
        tvs.forEach {
            it.textSize = 19f
            it.maxLines = 2
            it.ellipsize = TextUtils.TruncateAt.END
            it.setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(it)
        }
        foot.textSize = 14f
        foot.setTextColor(0xFFA398B4.toInt())
        foot.setPadding(0, dp(12), 0, 0)
        foot.text = "Lên/Xuống: chọn   Trái/Phải/OK: đổi   MENU: danh sách chờ   BACK / Vàng: đóng"
        addView(foot)
        visibility = View.GONE
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    fun show() { sel = 0; visibility = View.VISIBLE; render() }
    fun hide() { confirmReset = false; visibility = View.GONE }

    /** Giá trị đổi từ nơi khác (điện thoại...) trong lúc bảng đang mở. */
    fun refresh() { if (isShowing) render() }

    fun move(d: Int) {
        confirmReset = false
        sel = (sel + d).coerceIn(0, rows.size - 1)
        render()
    }

    /** Trái / Phải: đổi giá trị dòng đang chọn (dòng "Cài đặt đầy đủ" không có giá trị). */
    fun change(d: Int) {
        confirmReset = false
        rows[sel].step?.invoke(d)
        render()
    }

    /** OK: đổi giá trị sang tiếp theo; riêng dòng cuối thì mở Cài đặt đầy đủ. */
    fun activate() {
        val row = rows[sel]
        when {
            row.action != null -> { row.action.invoke(); render() }
            row.step == null -> onOpenFull()
            else -> change(+1)
        }
    }

    private fun render() {
        for (i in rows.indices) {
            val on = i == sel
            val tv = tvs[i]
            tv.text = rowText(rows[i])
            tv.setBackgroundColor(if (on) 0xFFFFB02E.toInt() else Color.TRANSPARENT)
            tv.setTextColor(if (on) 0xFF2B1A00.toInt() else Color.WHITE)
        }
    }

    private fun rowText(r: Row): CharSequence {
        val v = r.value ?: return r.label
        val sb = SpannableStringBuilder("${r.label}:   ‹ ")
        val s0 = sb.length
        sb.append(v())
        sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" ›")
        return sb
    }
}
