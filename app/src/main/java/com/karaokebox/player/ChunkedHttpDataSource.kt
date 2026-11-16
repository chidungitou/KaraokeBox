package com.karaokebox.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import kotlin.math.min

/**
 * Đọc link googlevideo theo từng đoạn Range (mặc định 10 MiB) thay vì một request mở đầu dài.
 * YouTube hay giới hạn tốc độ / trả 403 với request dài, đây là cách yt-dlp và NewPipe dùng.
 * ExoPlayer vẫn thấy một nguồn liên tục: các đoạn được nối trong read().
 */
@OptIn(UnstableApi::class)
class ChunkedHttpDataSource(
    private val userAgentFor: (String) -> String,
    private val chunkSize: Long = 10L * 1024 * 1024
) : BaseDataSource(/* isNetwork = */ true) {

    private val unset = C.LENGTH_UNSET.toLong()

    private var spec: DataSpec? = null
    private var upstream: DefaultHttpDataSource? = null
    private var nextPos = 0L          // vị trí tuyệt đối của byte kế tiếp cần đọc
    private var endPos = unset        // vị trí kết thúc (không gồm) của yêu cầu gốc, nếu biết
    private var totalLength = unset   // tổng dung lượng file (từ Content-Range)
    private var remaining = 0L        // số byte còn lại trong đoạn hiện tại (unset = không rõ)
    private var finished = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        spec = dataSpec
        nextPos = dataSpec.position
        endPos = if (dataSpec.length == unset) unset else dataSpec.position + dataSpec.length
        totalLength = unset
        finished = false
        openChunk()
        opened = true
        transferStarted(dataSpec)
        return when {
            endPos != unset -> endPos - dataSpec.position
            totalLength != unset -> (totalLength - dataSpec.position).coerceAtLeast(0)
            else -> unset
        }
    }

    private fun openChunk() {
        closeUpstream()
        val s = spec ?: return
        var len = chunkSize
        if (endPos != unset) len = min(len, endPos - nextPos)

        val src = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgentFor(s.uri.toString()))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .createDataSource()

        val got = src.open(s.buildUpon().setPosition(nextPos).setLength(len).build())
        upstream = src
        remaining = if (got == unset) unset else min(got, len)
        if (totalLength == unset) totalLength = parseTotal(src.responseHeaders)
    }

    private fun parseTotal(h: Map<String, List<String>>): Long {
        val v = h.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull() ?: return unset
        val total = v.substringAfterLast('/', "").trim()
        return total.toLongOrNull() ?: unset
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (finished) return C.RESULT_END_OF_INPUT
            val up = upstream
            if (up == null || remaining == 0L) {
                if ((endPos != unset && nextPos >= endPos) || (totalLength != unset && nextPos >= totalLength)) {
                    finished = true
                    return C.RESULT_END_OF_INPUT
                }
                try {
                    openChunk()
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // 416: đã đọc hết file nhưng server không báo tổng dung lượng
                    if (e.responseCode == 416) { finished = true; return C.RESULT_END_OF_INPUT }
                    throw e
                }
                continue
            }
            val want = if (remaining == unset) length else min(length.toLong(), remaining).toInt()
            val n = up.read(buffer, offset, want)
            if (n == C.RESULT_END_OF_INPUT) {
                if (remaining == unset) { finished = true; return C.RESULT_END_OF_INPUT }
                throw EOFException("Đoạn dữ liệu YouTube bị cắt giữa chừng")
            }
            nextPos += n
            if (remaining != unset) remaining -= n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        closeUpstream()
        spec = null
        if (opened) { opened = false; transferEnded() }
    }

    private fun closeUpstream() {
        try { upstream?.close() } catch (_: Exception) {}
        upstream = null
    }
}
