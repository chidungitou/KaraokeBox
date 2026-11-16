package com.karaokebox.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.karaokebox.data.repo.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cài bản app mới theo lệnh từ điện thoại (nút "Tải và cài bản mới" trong Cài đặt của web remote).
 * Box tải APK về ở nền, rồi mở trình cài đặt của Android NGAY TRÊN TV: Android không cho cài ngầm nên người dùng
 * vẫn phải bấm "Cài đặt" một lần trên TV. Tiến độ phát cho điện thoại qua [state] (ServiceLocator đẩy lên WebSocket).
 */
object AppInstaller {
    enum class Phase { IDLE, DOWNLOADING, INSTALLING, NEED_PERMISSION, ERROR }

    data class State(val phase: Phase = Phase.IDLE, val percent: Int = 0, val message: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Intent cần mở trên TV (trình cài đặt hoặc màn hình cấp quyền cài). PlayerActivity nhận và mở để biết đây là
     * chuyển màn hình trong app (không coi là thoát app); không có ai nhận thì [start] tự mở bằng context của app.
     */
    val launches = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    val isDownloading: Boolean get() = _state.value.phase == Phase.DOWNLOADING

    /** Báo lỗi cho điện thoại (vd PlayerActivity không mở được trình cài đặt). */
    fun fail(message: String) { _state.value = State(Phase.ERROR, 0, message) }

    /** false nếu đang tải dở (không bắt đầu lần nữa). */
    @Synchronized
    fun start(scope: CoroutineScope, ctx: Context, s: SettingsStore, rel: AppUpdater.Release): Boolean {
        if (isDownloading) return false

        // Android 8+: phải cho phép app này cài ứng dụng, và chỉ làm được trên màn hình TV
        if (AppUpdater.needsInstallPermission(ctx)) {
            _state.value = State(
                Phase.NEED_PERMISSION, 0,
                "Box chưa cho phép cài ứng dụng. Nhìn lên TV, bật \"Cho phép từ nguồn này\" cho Karaoke Box, bấm Back rồi bấm lại nút cài ở đây."
            )
            if (!open(ctx, AppUpdater.permissionIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                fail("Box không có màn hình cấp quyền cài ứng dụng. Cài tay bằng adb (xem UPDATE_YOUTUBE.md).")
            }
            return true
        }

        _state.value = State(Phase.DOWNLOADING, 0, "Đang tải ${rel.label}…")
        scope.launch {
            try {
                val apk = AppUpdater.download(ctx, rel) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    _state.value = State(
                        Phase.DOWNLOADING, pct,
                        if (total > 0) "Đang tải... $pct% (${done / 1024 / 1024} / ${total / 1024 / 1024} MB)"
                        else "Đang tải... ${done / 1024 / 1024} MB"
                    )
                }
                val chk = withContext(Dispatchers.IO) { AppUpdater.inspect(ctx, apk) }
                if (!chk.validPackage) {
                    fail("File tải về không phải APK của Karaoke Box (có thể bị lỗi mạng). Bấm cài lại.")
                    return@launch
                }
                if (chk.sameSigner == false) {
                    fail("Bản mới ký bằng khoá khác bản đang chạy nên Android sẽ từ chối cài đè. Cần gỡ app rồi cài lại (mất hàng chờ và cài đặt), xem UPDATE_YOUTUBE.md.")
                    return@launch
                }
                s.relaunchAfterUpdate = true   // cài xong thì BootReceiver tự mở lại app
                _state.value = State(
                    Phase.INSTALLING, 100,
                    "Đã tải xong. Nhìn lên TV và bấm \"Cài đặt\". Cài xong box tự mở lại app, điện thoại hãy tải lại trang."
                )
                if (!open(ctx, AppUpdater.installIntent(ctx, apk))) {
                    s.relaunchAfterUpdate = false
                    fail("Box không có trình cài đặt ứng dụng. Cài tay bằng adb: adb install -r ${apk.absolutePath}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Tải thất bại: ${e.message ?: e.javaClass.simpleName}. Kiểm tra Internet của box rồi thử lại.")
            }
        }
        return true
    }

    private fun open(ctx: Context, intent: Intent): Boolean {
        if (launches.subscriptionCount.value > 0) return launches.tryEmit(intent)
        return try { ctx.startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
    }
}
