package com.karaokebox.ui.settings

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karaokebox.BuildConfig
import com.karaokebox.ServiceLocator
import com.karaokebox.data.update.AppUpdater
import com.karaokebox.ui.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cài đặt > Cập nhật app: cho biết bản đang chạy, bản mới nhất trên GitHub; bấm một nút để tải rồi cài.
 * Điều khiển được hoàn toàn bằng remote hồng ngoại (D-pad).
 */
class AppUpdateActivity : AppCompatActivity() {
    private val sl = ServiceLocator
    private lateinit var verdict: TextView
    private lateinit var info: TextView
    private lateinit var progress: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnInstall: Button
    private var lastError: String? = null
    private var busy = false
    private var downloaded: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        fun tv(size: Float, color: Int = Color.WHITE) = TextView(this).apply { textSize = size; setTextColor(color) }

        col.addView(tv(28f).apply { text = "Cập nhật Karaoke Box" })
        col.addView(tv(16f, MUTED).apply {
            text = "App tự lấy bản mới từ GitHub. Bấm \"Tải và cài\", đợi trình cài đặt của Android hiện ra rồi chọn Cài đặt. Hàng chờ, yêu thích và thư viện nhạc được giữ nguyên."
            setPadding(0, dp(8), 0, dp(16))
        })
        verdict = tv(22f).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(verdict)
        info = tv(18f).apply { setLineSpacing(0f, 1.25f) }
        col.addView(info)
        progress = tv(18f, ORANGE).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(progress)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(16)) }
        btnInstall = Button(this).apply { setOnClickListener { downloadAndInstall() } }
        btnCheck = Button(this).apply {
            text = LABEL_CHECK
            setOnClickListener { runCheck(force = true) }
        }
        row.addView(btnInstall); row.addView(btnCheck)
        col.addView(row)

        col.addView(CheckBox(this).apply {
            text = "Tự kiểm tra bản mới khi mở app và nhắc trên màn hình TV"
            textSize = 18f; setTextColor(Color.WHITE); isChecked = sl.settings.autoUpdateCheck
            setOnCheckedChangeListener { _, v -> sl.settings.autoUpdateCheck = v }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF14111B.toInt())
            isFillViewport = true
            addView(col)
        })
        btnInstall.requestFocus()
        render()
        runCheck(force = false)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun runCheck(force: Boolean) {
        if (busy) return
        busy = true
        render()
        lifecycleScope.launch {
            try {
                val r = AppUpdater.check(sl.settings, if (force) 0L else 6 * 3_600_000L)
                lastError = r.error
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun downloadAndInstall() {
        if (busy) return
        val rel = AppUpdater.known(sl.settings)
        if (rel == null) { runCheck(force = true); return }

        // Android 8+: phải cho phép app này cài ứng dụng; mở sẵn màn hình cài đặt tương ứng
        if (AppUpdater.needsInstallPermission(this)) {
            progress.text = "Hãy bật \"Cho phép từ nguồn này\" cho Karaoke Box, bấm Back rồi bấm lại nút cài."
            try { startActivity(AppUpdater.permissionIntent(this)) } catch (e: ActivityNotFoundException) {
                progress.text = "Box không có màn hình cấp quyền cài ứng dụng. Cài tay bằng adb (xem hướng dẫn UPDATE_YOUTUBE.md)."
            }
            return
        }

        busy = true
        render()
        lifecycleScope.launch {
            try {
                val apk = AppUpdater.download(this@AppUpdateActivity, rel) { done, total ->
                    runOnUiThread {
                        progress.text = if (total > 0) "Đang tải... ${done * 100 / total}%  (${done / 1024 / 1024} / ${total / 1024 / 1024} MB)"
                        else "Đang tải... ${done / 1024 / 1024} MB"
                    }
                }
                downloaded = apk
                val chk = AppUpdater.inspect(this@AppUpdateActivity, apk)
                if (!chk.validPackage) {
                    progress.text = "File tải về không phải APK của Karaoke Box (có thể bị lỗi mạng). Thử lại."
                    return@launch
                }
                progress.text = if (chk.sameSigner == false)
                    "Cảnh báo: bản mới ký bằng khoá khác bản đang chạy nên Android sẽ từ chối cài đè. Cần gỡ app rồi cài lại (mất hàng chờ và cài đặt). Trước khi gỡ, bấm Sao lưu trong Cài đặt trên điện thoại để giữ bài Yêu thích."
                else "Đã tải xong, đang mở trình cài đặt..."
                sl.settings.relaunchAfterUpdate = true
                try {
                    startActivity(AppUpdater.installIntent(this@AppUpdateActivity, apk))
                } catch (e: ActivityNotFoundException) {
                    sl.settings.relaunchAfterUpdate = false
                    progress.text = "Box không có trình cài đặt ứng dụng. Cài tay bằng adb: adb install -r ${apk.absolutePath}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.text = "Tải thất bại: ${e.message ?: e.javaClass.simpleName}. Kiểm tra Internet của box rồi thử lại."
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun render() {
        val rel = AppUpdater.known(sl.settings)
        val newer = rel != null && AppUpdater.isNewer(rel)
        val (text, color) = when {
            newer -> "Đã có bản mới: ${rel!!.label}" to ORANGE
            rel != null -> "Đang dùng bản mới nhất. Không cần làm gì." to GREEN
            else -> "Chưa biết có bản mới hay chưa (cần Internet để kiểm tra)." to MUTED
        }
        verdict.text = text
        verdict.setTextColor(color)

        val checkedAt = sl.settings.appCheckedAt
        val whenText = if (checkedAt > 0) " (kiểm tra lúc ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(checkedAt))})" else ""
        val sb = StringBuilder()
        sb.append("Đang chạy:  ").append(BuildConfig.VERSION_NAME).append(" (NewPipe ").append(BuildConfig.NEWPIPE_VERSION).append(")\n")
        sb.append("Mới nhất trên GitHub:  ").append(rel?.label ?: "chưa kiểm tra").append(if (rel != null) whenText else "")
        if (lastError != null) sb.append("\nLần kiểm tra gần nhất lỗi: ").append(lastError)
        info.text = sb.toString()

        btnInstall.isEnabled = !busy && newer
        btnInstall.text = if (newer) "Tải và cài ${rel!!.label}" else "Tải và cài"
        btnCheck.isEnabled = !busy
        btnCheck.text = if (busy) "Đang xử lý..." else LABEL_CHECK
    }

    private companion object {
        const val LABEL_CHECK = "Kiểm tra bản mới"
        const val MUTED = 0xFFA398B4.toInt()
        const val ORANGE = 0xFFFFB02E.toInt()
        const val GREEN = 0xFF5BD68A.toInt()
    }
}
