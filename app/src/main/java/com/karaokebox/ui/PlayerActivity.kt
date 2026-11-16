package com.karaokebox.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.karaokebox.ServiceLocator
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.server.ApiException
import com.karaokebox.server.ToastEvent
import com.karaokebox.service.KaraokeService
import com.karaokebox.ui.overlay.MarqueeToastView
import com.karaokebox.ui.overlay.QrHaloView
import com.karaokebox.server.Reactions
import com.karaokebox.ui.overlay.QrOverlayView
import com.karaokebox.ui.overlay.ReactionView
import com.karaokebox.ui.overlay.StageTheme
import com.karaokebox.ui.overlay.QueuePanelView
import com.karaokebox.ui.overlay.SettingsPanelView
import com.karaokebox.ui.overlay.TopBannerView
import com.karaokebox.ui.overlay.WaitingView
import com.karaokebox.ui.settings.SettingsActivity
import com.karaokebox.data.update.AppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private val sl = ServiceLocator
    private lateinit var playerView: PlayerView
    private lateinit var qr: QrOverlayView        // CHỈ 1 mã: góc trên PHẢI (hoặc to giữa màn hình khi rảnh)
    private lateinit var reactions: ReactionView   // tim / pháo / vỗ tay / quẩy từ điện thoại bay lên màn hình
    private lateinit var halo: QrHaloView         // vòng sáng quanh QR khi có điện thoại mới vào phòng
    private lateinit var topBanner: TopBannerView
    private lateinit var marquee: MarqueeToastView
    private lateinit var panel: QueuePanelView
    private lateinit var settingsPanel: SettingsPanelView   // cài đặt nhanh ngay trên màn hình đang hát (nút Vàng / Settings)
    private lateinit var idleHint: TextView
    private lateinit var waiting: WaitingView   // màn hình chờ chọn bài (sau khi có điện thoại quét QR)

    private val handler = Handler(Looper.getMainLooper())
    // Mờ lại sau khi chọn bài; không mờ khi đang rảnh hoặc khi ai đó đang giữ nút "Hiện QR" trên điện thoại
    private val dimQr = Runnable {
        if (idle || sl.qrHold.value || noPhone) return@Runnable
        qrViews().forEach { it.animate().alpha(0.35f).setDuration(600).start() }
    }
    // Phòng khi lệnh "thả tay" bị mất (điện thoại mất sóng...): tự nhả sau 20 giây
    private val releaseHold = Runnable { sl.qrHold.value = false }
    private var lastBack = 0L
    private var idle = true
    private var queueCount = 0     // số bài đang chờ (có thể được khôi phục sau khi mở máy)
    private var connected = false   // có ít nhất 1 điện thoại đang kết nối (đã quét QR)
    // Ở màn hình chờ: sau 3 giây không còn điện thoại nào kết nối thì QR sáng rõ lên để quét lại
    private var noPhone = false
    // (đang hát cũng vậy: không còn ai kết nối thì QR rõ lên, có người vào lại thì mờ dần như cũ)
    private val markNoPhone = Runnable {
        noPhone = true
        if (idle) applyQrAlpha() else brightenQr(permanent = true)
    }
    private var internalNav = false   // đang mở màn hình Cài đặt của chính app (không phải thoát app)
    private var leftApp = false       // đã dừng hát + tắt dịch vụ vì người dùng rời app (Home / Back / chuyển app)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.startForegroundService(this, Intent(this, KaraokeService::class.java))
        buildUi()
        observe()
        watchForAppUpdate()
        // Mặc định luôn vào màn hình QR (không tự mở Cài đặt nữa; mở Cài đặt bằng nút Vàng/Settings hoặc icon bánh răng)
    }

    /** Sau khi mở app 45 giây, rồi mỗi 6 giờ: có bản app mới thì nhắc bằng dòng chữ chạy (mỗi bản chỉ nhắc một lần). */
    private fun watchForAppUpdate() {
        lifecycleScope.launch {
            delay(45_000)
            var notified = ""
            while (true) {
                if (sl.settings.autoUpdateCheck) {
                    val rel = AppUpdater.check(sl.settings, 6 * 3_600_000L).release
                    if (rel != null && AppUpdater.isNewer(rel) && rel.tag != notified) {
                        notified = rel.tag
                        marquee.post("Có bản Karaoke Box mới ${rel.label}. Vào Cài đặt > Cập nhật app để cài.", MarqueeToastView.Kind.INFO)
                    }
                }
                delay(6 * 3_600_000L)
            }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        waiting = WaitingView(this) { openSettings() }
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))

        idleHint = TextView(this).apply {
            text = "Quét mã QR bằng điện thoại để chọn bài hát\nMENU: danh sách chờ   Nút Vàng / Settings: cài đặt"
            textSize = 22f
            setLineSpacing(dp(10).toFloat(), 1f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(idleHint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(36) })

        qr = QrOverlayView(this)
        // Vòng sáng nằm ngay DƯỚI QR (QR vẫn rõ), phủ toàn màn hình, không nhận phím/chạm
        halo = QrHaloView(this, qr)
        root.addView(halo, FrameLayout.LayoutParams(-1, -1))
        // Bắt đầu ở dạng nhỏ, góc TRÊN PHẢI; khi rảnh, observe() sẽ gọi setLarge(true) để đưa ra giữa
        root.addView(qr, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = dp(24); rightMargin = dp(32)
        })

        // Hiệu ứng cảm xúc phủ trên video, dưới chữ chạy và các bảng; không nhận phím/chạm
        reactions = ReactionView(this)
        root.addView(reactions, FrameLayout.LayoutParams(-1, -1))

        // Cột chữ chạy ở trên cùng (chừa chỗ cho QR góc phải): banner ƯU TIÊN / TIẾP THEO ở trên,
        // dòng "vừa chọn" nằm NGAY DƯỚI nó. Banner ẩn thì dòng "vừa chọn" tự dồn lên.
        topBanner = TopBannerView(this)
        marquee = MarqueeToastView(this)
        val topStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBanner, LinearLayout.LayoutParams(-1, -2))
            addView(marquee, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        root.addView(topStack, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = dp(24); leftMargin = dp(48); rightMargin = dp(190)
        })

        panel = QueuePanelView(this)
        root.addView(panel, FrameLayout.LayoutParams(dp(640), -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(48) })

        // Dòng cuối của bảng nhanh mở Cài đặt đầy đủ (thư mục nhạc, quét lại...): đó là Activity riêng nên video tạm ẩn
        settingsPanel = SettingsPanelView(this) { settingsPanel.hide(); openSettings() }
        root.addView(settingsPanel, FrameLayout.LayoutParams(dp(640), -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(48) })

        setContentView(root)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sl.controller.snapshot.collect { s ->
                        idle = s.status == "IDLE"
                        refreshMode()
                        if (!idle && s.status == "LOADING") brightenQr(false)
                    }
                }
                launch {
                    // Quét QR = điện thoại mở trang web → WebSocket kết nối. Đã vào màn hình chờ chọn bài thì giữ nguyên
                    // (không quay lại QR to giữa màn hình, vì màn hình chờ đã có QR ở góc trên phải).
                    sl.hub.clientCount.collect { n ->
                        handler.removeCallbacks(markNoPhone)
                        if (n > 0) {
                            val wasNoPhone = noPhone
                            noPhone = false
                            refreshMode()
                            if (wasNoPhone && !idle) brightenQr(false)   // có người vào lại: sáng thêm 8 giây rồi mờ
                        } else {
                            handler.postDelayed(markNoPhone, 3_000)    // 3 giây không còn điện thoại → QR rõ lên
                        }
                    }
                }
                launch {
                    sl.queueRepo.state.collect { q ->
                        panel.update(q.items)
                        queueCount = q.items.size
                        waiting.setQueueCount(queueCount)
                        updateIdleHint()
                        if (!idle) brightenQr(false)
                    }
                }
                launch { sl.controller.toasts.collect { onToast(it) } }
                launch {
                    // Cài đặt đổi từ điện thoại / bảng nhanh: cập nhật bảng đang mở, đổi giao diện chờ ngay
                    sl.settings.changes.collect { key ->
                        settingsPanel.refresh()
                        if (key == SettingsStore.K_STAGE_THEME) waiting.refreshTheme()
                    }
                }
                launch { sl.hub.joins.collect { onJoin(it) } }   // điện thoại mới vào phòng
                launch { Reactions.flow.collect { reactions.spawn(it) } }
                launch {
                    // Có chủ phòng (người đầu tiên quét QR to) → sang màn hình chờ chọn bài. Đặt lại / thoát app → không còn chủ → QR to
                    sl.room.state.collect { st ->
                        connected = st.ownerId != null
                        settingsPanel.refresh()
                        refreshMode()
                    }
                }
                launch {
                    // Nút "Hiện QR" trên điện thoại: giữ = QR rõ, thả = mờ lại ngay
                    sl.qrHold.collect { hold ->
                        handler.removeCallbacks(releaseHold)
                        if (hold) {
                            brightenQr(permanent = true)
                            handler.postDelayed(releaseHold, 20_000)
                        } else if (!idle) {
                            handler.removeCallbacks(dimQr); handler.post(dimQr)
                        }
                        refreshMode()
                    }
                }
                launch { sl.serverInfo.collect { info -> info?.let { i -> qrViews().forEach { it.setInfo(i) } } } }
            }
        }
    }

    private fun qrViews() = listOf(qr)

    /**
     * Có điện thoại mới vào phòng: vòng sáng quanh QR + lời chào "<tên> vừa vào phòng hát".
     * Đang ở màn hình chờ → huy hiệu lớn trên sân khấu; đang hát → chữ chạy nhỏ ở trên cùng (không che lời bài hát).
     */
    private fun onJoin(name: String) {
        noPhone = false
        refreshMode()
        halo.pulse(StageTheme.resolve(sl.settings.stageThemeMode).accent)
        if (idle) waiting.showWelcome(name) else marquee.post("$name vừa vào phòng hát")
    }

    private fun updateIdleHint() {
        val sb = SpannableStringBuilder()
        if (queueCount > 0) {
            // dòng nhắc hàng chờ nổi bật màu vàng
            sb.append("Có $queueCount bài đang chờ – nhấn OK trên remote để bắt đầu hát\n")
            sb.setSpan(ForegroundColorSpan(0xFFFFB02E.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("Quét mã QR bằng điện thoại để chọn bài hát\nMENU: danh sách chờ   Nút Vàng / Settings: cài đặt")
        idleHint.text = sb
    }

    /**
     * Rảnh + chưa ai quét  → màn hình QR to giữa màn hình (rõ).
     * Rảnh + đã có người quét → màn hình chờ "Vui lòng chọn bài hát" (3D) + bánh răng cài đặt + QR nhỏ ở góc trên phải
     *   (mờ khi còn điện thoại kết nối; rõ lên khi 3 giây không còn điện thoại nào kết nối).
     * Đang hát → video + QR nhỏ ở góc trên phải (mờ; giữ nút "Hiện QR" trên điện thoại để QR rõ lên).
     */
    private fun refreshMode() {
        val showWaiting = idle && connected
        qrViews().forEach { it.setLarge(idle && !connected) }
        qr.visibility = View.VISIBLE
        idleHint.visibility = if (idle && !connected) View.VISIBLE else View.GONE
        if (showWaiting) waiting.show() else waiting.hide()
        if (idle) applyQrAlpha()
    }

    /** Độ rõ của QR khi đang rảnh (khi đang hát dùng bộ hẹn giờ 8 giây ở brightenQr/dimQr). */
    private fun applyQrAlpha() {
        val waitingScreen = idle && connected
        if (sl.qrHold.value || !waitingScreen || noPhone) {
            brightenQr(permanent = true)
        } else {
            handler.removeCallbacks(dimQr)
            qrViews().forEach { it.animate().alpha(0.35f).setDuration(600).start() }
        }
    }

    /** "Ưu tiên" / "Tiếp theo" chạy ở trên cùng; "vừa chọn" và các thông báo khác chạy ngay bên dưới banner đó. */
    private fun onToast(t: ToastEvent) {
        val song = t.song
        when {
            t.kind == "next_up" && song != null ->
                topBanner.post(TopBannerView.Kind.NEXT_UP, null, song.title, song.artist)
            t.kind == "song_priority" && song != null ->
                topBanner.post(TopBannerView.Kind.PRIORITY, "${t.byName ?: "Khách"} đưa lên hát tiếp:", song.title, song.artist)
            t.kind == "song_added" && song != null ->
                marquee.post(MarqueeToastView.Kind.ADDED, "${t.byName ?: "Khách"} vừa chọn:", song.title, song.artist)
            t.kind == "error" -> marquee.post(t.message, MarqueeToastView.Kind.ERROR)
            else -> marquee.post(t.message)
        }
    }

    private fun brightenQr(permanent: Boolean) {
        handler.removeCallbacks(dimQr)
        qrViews().forEach { it.animate().cancel(); it.alpha = 1f }
        if (!permanent) handler.postDelayed(dimQr, 8000)
    }

    override fun onStart() {
        super.onStart()
        internalNav = false
        if (leftApp) {   // quay lại app sau khi bấm Home: bật lại máy chủ (bài hát và hàng chờ vẫn còn)
            leftApp = false
            ContextCompat.startForegroundService(this, Intent(this, KaraokeService::class.java))
        }
        waiting.refreshTheme()   // vừa đổi giao diện trong Cài đặt thì áp dụng ngay khi quay lại
        playerView.player = sl.controller.player.exo
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sl.qrHold.value = false
        super.onDestroy()
    }

    override fun onStop() {
        playerView.player = null
        val leaving = !internalNav && !isChangingConfigurations
        super.onStop()
        // Bấm Home / chuyển sang app khác / thoát: DỪNG hát và tắt dịch vụ. Trước đây bộ phát vẫn chạy ngầm
        // (tiếng vẫn ra, vẫn giải mã video không có màn hình → máy giật, lag ở màn hình chính).
        if (leaving) leaveApp()
    }

    private fun leaveApp() {
        if (leftApp) return
        leftApp = true
        handler.removeCallbacksAndMessages(null)
        sl.qrHold.value = false
        sl.controller.stopAll()
        sl.room.reset()   // thoát app = hết buổi hát: lần mở sau, người quét QR đầu tiên là chủ phòng
        stopService(Intent(this, KaraokeService::class.java))
    }

    private fun launchSafe(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try { block() } catch (e: ApiException) { marquee.post(e.message ?: "") }
        }
    }

    /**
     * Khi bảng cài đặt nhanh đang mở, nó nhận các phím điều hướng TRƯỚC mọi view khác (vd nút bánh răng của
     * màn hình chờ đang giữ focus sẽ nuốt phím OK). Các phím còn lại (âm lượng...) đi đường bình thường.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (settingsPanel.isShowing) {
            val k = event.keyCode
            val mine = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
                k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
                k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
                k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU ||
                k == KeyEvent.KEYCODE_PROG_YELLOW || k == KeyEvent.KEYCODE_SETTINGS
            if (mine) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onSettingsPanelKey(k)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onSettingsPanelKey(k: Int) {
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> settingsPanel.move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> settingsPanel.move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> settingsPanel.change(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> settingsPanel.change(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> settingsPanel.activate()
            KeyEvent.KEYCODE_MENU -> { settingsPanel.hide(); panel.show() }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_SETTINGS -> settingsPanel.hide()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = sl.controller

        if (panel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> panel.move(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> panel.move(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    panel.selectedId()?.let { id -> launchSafe { sl.controller.moveToTop(id, "Remote TV") } }
                KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_BLUE,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->
                    panel.selectedId()?.let { id -> launchSafe { sl.queueRepo.remove(id) } }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_SETTINGS -> {
                    panel.hide(); settingsPanel.show()
                }
                KeyEvent.KEYCODE_BACK -> panel.hide()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                launchSafe { c.toggle() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> launchSafe { c.next() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> launchSafe { c.replay() }
            KeyEvent.KEYCODE_DPAD_UP -> launchSafe { c.setVolume(null, +6) }
            KeyEvent.KEYCODE_DPAD_DOWN -> launchSafe { c.setVolume(null, -6) }
            KeyEvent.KEYCODE_MENU -> panel.show()
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_SETTINGS -> settingsPanel.show()
            KeyEvent.KEYCODE_PROG_GREEN -> launchSafe { c.setVocal(null) }
            KeyEvent.KEYCODE_PROG_RED -> launchSafe { c.setKey(-1, null) }    // giảm tone
            KeyEvent.KEYCODE_PROG_BLUE -> launchSafe { c.setKey(+1, null) }   // tăng tone
            KeyEvent.KEYCODE_BACK -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) exitApp() else {
                    lastBack = now
                    marquee.post("Nhấn Back lần nữa để thoát")
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun openSettings() {
        internalNav = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun exitApp() {
        leaveApp()
        finishAndRemoveTask()
    }
}
