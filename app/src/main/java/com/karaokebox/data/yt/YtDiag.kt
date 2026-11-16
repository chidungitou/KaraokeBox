package com.karaokebox.data.yt

import android.os.Build
import com.karaokebox.BuildConfig
import com.karaokebox.ServiceLocator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nhật ký chẩn đoán YouTube: ghi mọi lỗi tìm kiếm / lấy link / phát kèm loại lỗi và vài dòng stack trace,
 * để biết chính xác khi nào YouTube đổi cách trả dữ liệu và cần nâng NewPipeExtractor.
 * Xem trên điện thoại: http://<ip-box>:8080/api/youtube/diag
 */
object YtDiag {
    private const val MAX_BYTES = 192 * 1024
    private const val KEEP_BYTES = 96 * 1024
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)   // không an toàn đa luồng: chỉ dùng trong lock

    private fun file() = File(ServiceLocator.app.filesDir, "yt_diag.log")

    fun note(text: String) = write("[note] $text")

    fun log(op: String, videoId: String?, code: String, detail: String?, t: Throwable?) {
        val sb = StringBuilder("[").append(op).append("]")
        if (videoId != null) sb.append(" id=").append(videoId)
        sb.append(" code=").append(code)
        if (!detail.isNullOrBlank()) sb.append(" | ").append(detail.take(300))
        if (t != null) {
            sb.append("\n    ").append(t.javaClass.name).append(": ").append((t.message ?: "").take(300))
            var root: Throwable = t
            var guard = 0
            while (guard++ < 8) {
                val c = root.cause ?: break
                if (c === root) break
                root = c
            }
            if (root !== t) {
                sb.append("\n    gốc: ").append(root.javaClass.name).append(": ").append((root.message ?: "").take(300))
            }
            // ưu tiên các dòng của NewPipe / app để dễ thấy chỗ hỏng
            val frames = root.stackTrace
            val picked = frames.filter { it.className.startsWith("org.schabi") || it.className.startsWith("com.karaokebox") }
                .ifEmpty { frames.toList() }
                .take(6)
            for (f in picked) sb.append("\n      at ").append(f.toString())
        }
        write(sb.toString())
    }

    private fun write(line: String) {
        try {
            synchronized(lock) {
                val f = file()
                if (f.length() > MAX_BYTES) trim(f)
                f.appendText("${fmt.format(Date())} $line\n", Charsets.UTF_8)
            }
        } catch (_: Throwable) {
        }
    }

    private fun trim(f: File) {
        val bytes = f.readBytes()
        val start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
        f.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    /** Nội dung đầy đủ (tiêu đề thông tin máy + log) để xem trên trình duyệt. */
    fun read(): String {
        val sb = StringBuilder()
        sb.append("Karaoke Box ${BuildConfig.VERSION_NAME} | NewPipeExtractor ${BuildConfig.NEWPIPE_VERSION}\n")
        sb.append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}\n")
        try {
            val s = ServiceLocator.youtube.status.value
            sb.append("Trạng thái YouTube: ${s.health} ${s.code} ${s.message} (lỗi liên tiếp: ${s.failures})\n")
        } catch (_: Throwable) {
        }
        sb.append("----------------------------------------\n")
        try {
            val f = file()
            if (f.exists()) sb.append(f.readText(Charsets.UTF_8)) else sb.append("(chưa có lỗi nào được ghi lại)\n")
        } catch (e: Throwable) {
            sb.append("(không đọc được log: ${e.message})\n")
        }
        return sb.toString()
    }
}
