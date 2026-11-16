package com.karaokebox.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Giống LeastRecentlyUsedCacheEvictor (xóa phần lâu chưa dùng nhất khi cache đầy) nhưng ưu tiên GIỮ các bài "được ghim"
 * (bài Yêu thích, bài hát nhiều lần): chỉ khi không còn gì khác để xóa mới xóa tới bài ghim.
 * [isPinned] nhận khóa cache (dạng "yt:<videoId>:v136:12345") và trả về true nếu bài đó cần giữ.
 */
@OptIn(UnstableApi::class)
class PinnedAwareEvictor(
    private val maxBytes: Long,
    private val isPinned: (String) -> Boolean
) : CacheEvictor {

    // xếp theo lần dùng gần nhất (cũ nhất đứng đầu)
    private val spans = TreeSet<CacheSpan>(Comparator { a, b ->
        val d = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (d == 0L) a.compareTo(b) else if (a.lastTouchTimestamp < b.lastTouchTimestamp) -1 else 1
    })
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && spans.isNotEmpty()) {
            // Cũ nhất trong các bài KHÔNG ghim; hết bài thường thì mới đành xóa cả bài ghim (để không vượt giới hạn ổ)
            val victim = spans.firstOrNull { !isPinned(it.key) } ?: spans.first()
            cache.removeSpan(victim)   // gọi lại onSpanRemoved → cập nhật spans / currentSize
        }
    }
}
