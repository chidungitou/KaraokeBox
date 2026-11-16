package com.karaokebox.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karaokebox.BuildConfig
import com.karaokebox.ServiceLocator
import com.karaokebox.data.yt.NewPipeUpdateChecker
import com.karaokebox.data.yt.YtDiag
import com.karaokebox.ui.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cài đặt > YouTube: cho biết bản NewPipeExtractor đang dùng, bản mới nhất trên GitHub, tình trạng YouTube
 * hiện tại và hướng dẫn nâng cấp từng bước (kèm sẵn số phiên bản cần gõ).
 * Điều khiển được hoàn toàn bằng remote hồng ngoại (D-pad).
 */
class YoutubeUpdateActivity : AppCompatActivity() {
    private val sl = ServiceLocator
    private lateinit var verdict: TextView
    private lateinit var info: TextView
    private lateinit var guide: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnYt: Button
    private var lastError: String? = null
    private var checking = false
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        fun tv(size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; setTextColor(color)
        }

        col.addView(tv(28f).apply { text = "Cập nhật YouTube (NewPipeExtractor)" })
        col.addView(tv(16f, MUTED).apply {
            text = "Thư viện này giúp tìm và phát bài từ YouTube. YouTube hay đổi nên thỉnh thoảng cần nâng phiên bản. Bài trong máy không bị ảnh hưởng."
            setPadding(0, dp(8), 0, dp(16))
        })
        verdict = tv(22f).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(verdict)
        info = tv(18f).apply { setLineSpacing(0f, 1.25f) }
        col.addView(info)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(16)) }
        btnCheck = Button(this).apply {
            text = LABEL_CHECK
            setOnClickListener { runCheck(force = true) }
        }
        btnYt = Button(this).apply {
            text = LABEL_TEST
            setOnClickListener { runYoutubeTest() }
        }
        val btnLog = Button(this).apply {
            text = "Xem nhật ký lỗi"
            setOnClickListener { showLog() }
        }
        row.addView(btnCheck); row.addView(btnYt); row.addView(btnLog)
        col.addView(row)

        guide = tv(17f).apply { setLineSpacing(0f, 1.2f) }
        col.addView(guide)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF14111B.toInt())
            isFillViewport = true
            addView(col)
        }
        setContentView(scroll)
        btnCheck.requestFocus()

        render()
        runCheck(force = false)   // tự kiểm tra nếu lần trước đã lâu (> 6 giờ)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun runCheck(force: Boolean) {
        if (checking) return
        checking = true
        btnCheck.isEnabled = false
        btnCheck.text = "Đang kiểm tra..."
        lifecycleScope.launch {
            try {
                val r = NewPipeUpdateChecker.check(sl.settings, if (force) 0L else 6 * 3_600_000L)
                lastError = r.error
            } finally {
                checking = false
                btnCheck.isEnabled = true
                btnCheck.text = LABEL_CHECK
                render()
            }
        }
    }

    private fun runYoutubeTest() {
        if (testing) return
        testing = true
        btnYt.isEnabled = false
        btnYt.text = "Đang thử..."
        lifecycleScope.launch {
            try {
                sl.youtube.checkNow()
            } finally {
                testing = false
                btnYt.isEnabled = true
                btnYt.text = LABEL_TEST
                render()
            }
        }
    }

    private fun showLog() {
        val full = YtDiag.read()
        val tail = if (full.length > 3000) "…\n" + full.takeLast(3000) else full
        val si = sl.serverInfo.value
        val url = if (si != null) "http://${si.ip}:${si.port}/api/youtube/diag" else "http://<ip-box>:8080/api/youtube/diag"
        AlertDialog.Builder(this)
            .setTitle("Nhật ký YouTube")
            .setMessage("$tail\n\nXem đầy đủ trên điện thoại: $url")
            .setPositiveButton("Đóng", null)
            .show()
    }

    // ------------------------------------------------------------ hiển thị

    private fun render() {
        val current = BuildConfig.NEWPIPE_VERSION
        val latest = sl.settings.npLatest.ifEmpty { null }
        val st = sl.youtube.status.value
        val newer = latest != null && NewPipeUpdateChecker.isNewer(latest, current)
        val broken = st.health != "OK"

        val (text, color) = when {
            newer && broken -> "YouTube đang lỗi và đã có bản mới ($latest). Nên cập nhật ngay." to RED
            newer -> "Đã có bản mới: $latest. Nên cập nhật." to ORANGE
            broken && st.code == "YT_CHANGED" ->
                "YouTube đã đổi nhưng chưa có bản thư viện mới hơn. Thường cần chờ vài giờ đến vài ngày, hãy kiểm tra lại sau." to ORANGE
            broken -> "YouTube đang lỗi (${st.code}), có thể không phải do thư viện. Xem nhật ký lỗi." to ORANGE
            latest != null -> "Đang dùng bản mới nhất. Không cần làm gì." to GREEN
            else -> "Chưa biết có bản mới hay chưa (cần Internet để kiểm tra)." to MUTED
        }
        verdict.text = text
        verdict.setTextColor(color)

        val checkedAt = sl.settings.npCheckedAt
        val whenText = if (checkedAt > 0) " (kiểm tra lúc ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(checkedAt))})" else ""
        val healthText = when (st.health) {
            "OK" -> "Bình thường"
            "DEGRADED" -> "Chập chờn"
            else -> "Tạm thời không dùng được"
        }
        val sb = StringBuilder()
        sb.append("Đang dùng trong app:  ").append(current).append('\n')
        sb.append("Bản mới nhất trên GitHub:  ").append(latest ?: "chưa kiểm tra").append(if (latest != null) whenText else "").append('\n')
        if (lastError != null) sb.append("Lần kiểm tra gần nhất lỗi: ").append(lastError).append('\n')
        sb.append("Tình trạng YouTube:  ").append(healthText)
        if (broken) sb.append("  [").append(st.code).append(", lỗi liên tiếp: ").append(st.failures).append("]")
        info.text = sb.toString()

        guide.text = guideText(latest)
    }

    private fun guideText(latest: String?): String {
        val target = latest ?: "<số phiên bản mới>"
        val si = sl.serverInfo.value
        val ip = si?.ip ?: "<ip-box>"
        return "CÁCH NÂNG CẤP (làm trên máy tính, khoảng 5 phút)\n\n" +
            "1. Mở project KaraokeBox bằng Android Studio.\n\n" +
            "2. Mở file gradle.properties ở thư mục gốc, sửa đúng MỘT dòng thành:\n" +
            "        newpipeVersion=$target\n\n" +
            "3. Bấm \"Sync Now\", rồi Build > Build APK(s). Lần đầu JitPack có thể mất vài phút để dựng bản mới.\n\n" +
            "4. Cài đè lên box (giữ nguyên chữ ký debug):\n" +
            "        adb connect $ip\n" +
            "        adb install -r app/build/outputs/apk/debug/app-debug.apk\n\n" +
            "5. Mở lại Karaoke Box, vào lại màn hình này và bấm \"$LABEL_TEST\".\n\n" +
            "Hàng chờ, yêu thích, tone và thư viện nhạc được giữ nguyên.\n\n" +
            "Danh sách các bản: ${NewPipeUpdateChecker.RELEASES_URL}\n\n" +
            "Lưu ý: nếu Gradle báo lỗi biên dịch ở thư mục data/yt sau khi nâng, thư viện đã đổi cách dùng (API) nên cần sửa code, " +
            "không chỉ đổi số phiên bản. Có thể quay lại số cũ để app chạy như trước."
    }

    private companion object {
        const val LABEL_CHECK = "Kiểm tra bản mới"
        const val LABEL_TEST = "Thử YouTube ngay"
        const val MUTED = 0xFFA398B4.toInt()
        const val ORANGE = 0xFFFFB02E.toInt()
        const val RED = 0xFFFF6B6B.toInt()
        const val GREEN = 0xFF5BD68A.toInt()
    }
}
