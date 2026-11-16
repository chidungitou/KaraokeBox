package com.karaokebox.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.karaokebox.core.qr.QrGenerator
import com.karaokebox.server.ServerInfo
import com.karaokebox.ui.dp

/**
 * Mã QR duy nhất: khi đang hát thì hiện nhỏ ở góc trên bên PHẢI; khi rảnh thì hiện to giữa màn hình.
 */
class QrOverlayView(ctx: Context) : LinearLayout(ctx) {
    private val image = ImageView(ctx)
    private val caption = TextView(ctx)
    private var url: String? = null
    private var large = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(10), dp(10), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
        caption.setTextColor(Color.BLACK)
        caption.gravity = Gravity.CENTER
        addView(image)
        addView(caption)
        applySize()
    }

    fun setInfo(info: ServerInfo) {
        url = info.url
        caption.text = "Quét mã để chọn bài - ${info.ip}:${info.port}"
        render()
    }

    fun setLarge(value: Boolean) {
        if (large == value) return
        large = value
        applySize()
        render()
    }

    /** QR to: tối đa 280dp nhưng không quá ~42% chiều cao màn hình, để chừa chỗ cho dòng hướng dẫn bên dưới. */
    private fun sizePx(): Int =
        if (large) minOf(dp(280), (resources.displayMetrics.heightPixels * 0.42f).toInt()) else dp(120)

    private fun applySize() {
        val size = sizePx()
        image.layoutParams = LayoutParams(size, size)
        caption.textSize = if (large) 20f else 11f
        caption.maxWidth = size   // chú thích xuống dòng, không làm khung QR rộng ra
        (layoutParams as? FrameLayout.LayoutParams)?.let {
            if (large) {
                // QR to nằm phía TRÊN, giữa màn hình (không đè lên chữ hướng dẫn ở phía dưới)
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                it.setMargins(0, (resources.displayMetrics.heightPixels * 0.05f).toInt(), 0, 0)
            } else {
                it.gravity = Gravity.TOP or Gravity.RIGHT    // RIGHT tuyệt đối: không bị đảo theo layout direction
                it.setMargins(0, dp(24), dp(32), 0)
            }
            layoutParams = it
        }
    }

    private fun render() {
        val u = url ?: return
        val px = sizePx()
        image.setImageBitmap(QrGenerator.create(u, px))
    }
}
