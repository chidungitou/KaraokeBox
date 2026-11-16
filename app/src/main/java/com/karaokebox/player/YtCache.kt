package com.karaokebox.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.data.yt.ResolvedMedia
import com.karaokebox.data.yt.YoutubeRepository
import com.karaokebox.data.yt.YtDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Cache bài YouTube trên ổ box (mặc định 2 GB, tự giảm nếu ổ còn ít chỗ trống). Khi đầy xóa phần lâu chưa hát nhất,
 * nhưng ưu tiên giữ bài Yêu thích / bài hát nhiều lần ([PinnedAwareEvictor]).
 * Lưu ở thư mục riêng của app (KHÔNG phải thư mục cache) nên hệ điều hành hoặc "Xóa bộ nhớ đệm" không dọn mất.
 * Bài đã nằm đủ trong cache còn được phát thẳng không cần lấy link YouTube (xem [offlineEntry]).
 * Khóa cache = videoId + itag + dung lượng/độ dài (KHÔNG dùng URL vì URL YouTube hết hạn sau vài giờ).
 */
@OptIn(UnstableApi::class)
object YtCache {
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var pinned: Set<String> = emptySet()

    private const val RESERVE_BYTES = 1_536L * 1024 * 1024   // luôn chừa chỗ trống này cho hệ thống / app khác
    private const val MIN_BYTES = 256L * 1024 * 1024

    /** Mở sẵn cache ở nền (gọi từ luồng IO) để bài đầu tiên không bị chậm vì nạp chỉ mục. */
    fun warmUp(ctx: Context, mb: Int) { get(ctx, mb) }

    /** Danh sách videoId cần giữ lại khi cache đầy (cập nhật từ danh sách Yêu thích / bài hát nhiều). */
    fun setPinned(videoIds: Set<String>) { pinned = videoIds }

    private fun videoIdOfKey(key: String): String? =
        if (key.startsWith(YT_KEY_PREFIX)) key.substring(YT_KEY_PREFIX.length).substringBefore(':') else null

    private fun isPinnedKey(key: String): Boolean = videoIdOfKey(key)?.let { it in pinned } == true

    @Synchronized
    fun get(ctx: Context, mb: Int): SimpleCache? {
        if (mb <= 0) return null
        cache?.let { return it }
        return try {
            val dir = cacheDir(ctx)
            val maxBytes = effectiveMaxBytes(dir, mb)
            SimpleCache(dir, PinnedAwareEvictor(maxBytes) { key -> isPinnedKey(key) }, StandaloneDatabaseProvider(ctx.applicationContext))
                .also { cache = it }
        } catch (e: Throwable) {
            YtDiag.note("Không mở được cache YouTube: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Thư mục riêng cho cache; lần đầu chuyển cache cũ (trong thư mục cache của app) sang để không mất bài đã lưu. */
    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "ytcache")
        if (!dir.exists()) {
            val old = File(ctx.externalCacheDir ?: ctx.cacheDir, "yt")
            if (old.exists() && !old.renameTo(dir)) old.deleteRecursively()   // không chuyển được thì xóa cho nhẹ ổ
        }
        dir.mkdirs()
        return dir
    }

    /** Dung lượng tối đa thật sự: không quá cài đặt và không ăn hết chỗ trống của ổ (luôn chừa [RESERVE_BYTES]). */
    private fun effectiveMaxBytes(dir: File, mb: Int): Long {
        val want = mb * 1024L * 1024L
        val used = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Throwable) { 0L }
        val free = try { dir.usableSpace } catch (_: Throwable) { 0L }
        val room = used + free - RESERVE_BYTES
        val max = minOf(want, maxOf(room, MIN_BYTES))
        YtDiag.note("Cache YouTube: tối đa ${max / 1_048_576} MB (cài đặt $mb MB, đang dùng ${used / 1_048_576} MB, ổ còn trống ${free / 1_048_576} MB)")
        return max
    }

    fun usedBytes(): Long = try { cache?.cacheSpace ?: 0L } catch (_: Throwable) { 0L }

    /** Trả null nếu URL thiếu thông tin định danh (khi đó không cache để tránh trộn nhầm dữ liệu). */
    fun keyFor(videoId: String, url: String, kind: String): String? = try {
        val u = Uri.parse(url)
        val itag = u.getQueryParameter("itag")
        val size = u.getQueryParameter("clen") ?: u.getQueryParameter("dur")
        if (itag.isNullOrEmpty() || size.isNullOrEmpty()) null else "yt:$videoId:$kind$itag:$size"
    } catch (_: Exception) {
        null
    }

    /** Cả luồng hình và tiếng của bài này đã nằm ĐỦ trong cache (biết đủ độ dài và không thiếu đoạn nào)? */
    private fun fullyCached(c: SimpleCache, key: String): Boolean {
        val len = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return len != C.LENGTH_UNSET.toLong() && len > 0 && c.isCached(key, 0, len)
    }

    /**
     * Trả về thông tin để phát bài [videoId] thẳng từ cache (không cần lấy link YouTube) hoặc null nếu cache chưa đủ.
     * Không dùng bản đã lưu có chất lượng cao hơn mức [maxHeight] đang cài.
     */
    fun offlineEntry(ctx: Context, mb: Int, videoId: String, maxHeight: Int): YtOfflineIndex.Entry? {
        val e = YtOfflineIndex.get(ctx, videoId) ?: return null
        if (e.height > maxHeight) return null
        val c = get(ctx, mb) ?: return null
        return try {
            if (fullyCached(c, e.vKey) && fullyCached(c, e.aKey)) e else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Nguồn dữ liệu "cache thuần": không bao giờ ra mạng (thiếu dữ liệu thì báo lỗi ngay thay vì chờ). */
    fun cacheOnly(ctx: Context, mb: Int): DataSource.Factory? {
        val c = get(ctx, mb) ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory { NoNetworkDataSource() }
    }

    fun wrap(ctx: Context, mb: Int, upstream: DataSource.Factory): DataSource.Factory {
        val c = get(ctx, mb) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

/**
 * Tải trước phần đầu (~30-50 giây) của bài YouTube kế tiếp vào cache khi bài hiện tại đang hát,
 * để lúc chuyển bài hình/tiếng lên ngay, phần còn lại tải tiếp trong lúc hát.
 */
@OptIn(UnstableApi::class)
class YoutubePreloader(private val ctx: Context, private val settings: SettingsStore) {
    @Volatile private var preloadedId: String? = null
    private var job: Job? = null

    fun preload(scope: CoroutineScope, videoId: String, media: ResolvedMedia) {
        if (preloadedId == videoId) return
        val split = media as? ResolvedMedia.Split ?: return
        val aKey = YtCache.keyFor(videoId, split.audioUrl, "a") ?: return
        val vKey = YtCache.keyFor(videoId, split.videoUrl, "v") ?: return
        val cache = YtCache.get(ctx, settings.youtubeCacheMb) ?: return
        preloadedId = videoId
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                cacheHead(cache, split.audioUrl, aKey, AUDIO_BYTES, self)   // tiếng trước (nhỏ, bắt buộc có)
                cacheHead(cache, split.videoUrl, vKey, VIDEO_BYTES, self)
            } catch (e: Exception) {
                preloadedId = null   // lần sau thử lại
                if (isActive) YtDiag.note("Preload $videoId lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun cacheHead(cache: SimpleCache, url: String, key: String, bytes: Long, self: Job?) {
        val upstream = DataSource.Factory {
            ChunkedHttpDataSource(userAgentFor = { u -> YoutubeRepository.userAgentFor(u) })
        }
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
        val spec = DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(bytes).build()
        val writer = CacheWriter(ds, spec, null, null)
        val h = self?.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
        } finally {
            h?.dispose()
        }
    }

    private companion object {
        const val AUDIO_BYTES = 1_500_000L
        const val VIDEO_BYTES = 6_000_000L
    }
}

private const val YT_KEY_PREFIX = "yt:"

/** Upstream giả cho phát từ cache: mở là báo lỗi, đảm bảo không có request mạng nào. */
@OptIn(UnstableApi::class)
private class NoNetworkDataSource : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {}
    override fun open(dataSpec: DataSpec): Long = throw IOException("Dữ liệu không còn đủ trong cache")
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Dữ liệu không còn đủ trong cache")
    override fun getUri(): Uri? = null
    override fun close() {}
}
