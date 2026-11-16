package com.karaokebox.data.yt

import com.google.gson.JsonParser
import com.karaokebox.BuildConfig
import com.karaokebox.data.repo.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Hỏi GitHub xem NewPipeExtractor đã có bản mới hơn bản đang build trong app chưa.
 * Chỉ để NHẮC: thư viện được biên dịch cùng app nên muốn nâng vẫn phải build lại APK
 * (màn hình Cài đặt > YouTube hướng dẫn từng bước).
 */
object NewPipeUpdateChecker {
    const val RELEASES_URL = "https://github.com/TeamNewPipe/NewPipeExtractor/releases"
    private const val API_URL = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"

    /** error != null: lần kiểm tra này thất bại; latest là giá trị đã lưu từ lần trước (nếu có). */
    data class Result(val latest: String?, val error: String?)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Lần thử gần nhất (kể cả thất bại), chỉ trong bộ nhớ: tránh gọi GitHub dồn dập khi mạng/GitHub lỗi. */
    @Volatile private var lastTryAt = 0L

    /** true nếu [latest] (vd "v0.27.0") mới hơn [current]. Không đọc được số thì coi là không mới hơn. */
    fun isNewer(latest: String, current: String = BuildConfig.NEWPIPE_VERSION): Boolean {
        val a = parts(latest)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> {
        val out = ArrayList<Int>()
        for (seg in v.trim().removePrefix("v").removePrefix("V").split('.')) {
            val n = seg.takeWhile { it.isDigit() }.toIntOrNull() ?: return emptyList()
            out.add(n)
        }
        return out
    }

    /**
     * Kiểm tra bản mới nhất. [minIntervalMs] > 0: nếu vừa kiểm tra trong khoảng đó thì trả luôn giá trị đã lưu
     * (không gọi mạng). 0 = bắt buộc gọi (nút "Kiểm tra bản mới").
     */
    suspend fun check(settings: SettingsStore, minIntervalMs: Long = 0L): Result = withContext(Dispatchers.IO) {
        val saved = settings.npLatest.ifEmpty { null }
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0 &&
            ((saved != null && now - settings.npCheckedAt < minIntervalMs) || now - lastTryAt < minIntervalMs)
        ) {
            return@withContext Result(saved, null)
        }
        lastTryAt = now
        try {
            val req = Request.Builder().url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KaraokeBox/" + BuildConfig.VERSION_NAME)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = if (resp.code == 403 || resp.code == 429) "GitHub tạm giới hạn truy cập (mã ${resp.code}), thử lại sau ít phút"
                    else "GitHub trả mã ${resp.code}"
                    return@withContext Result(saved, msg)
                }
                val body = resp.body?.string().orEmpty()
                val tag = JsonParser.parseString(body).asJsonObject.get("tag_name")?.asString?.trim().orEmpty()
                if (tag.isEmpty() || parts(tag).isEmpty()) {
                    return@withContext Result(saved, "Không đọc được số phiên bản từ GitHub")
                }
                settings.npLatest = tag
                settings.npCheckedAt = System.currentTimeMillis()
                Result(tag, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(saved, "Không kết nối được GitHub (${e.javaClass.simpleName}). Kiểm tra Internet của box.")
        }
    }
}
