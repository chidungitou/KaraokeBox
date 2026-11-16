package com.karaokebox.data.yt

import com.karaokebox.BuildConfig
import com.karaokebox.data.db.SongEntity
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.server.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- mô hình

data class YtVideo(val videoId: String, val title: String, val channel: String, val durationMs: Long)
data class YtSearchPage(val items: List<YtVideo>, val cursor: String?)

/** Sức khỏe của YouTube trên box: OK | DEGRADED (chập chờn) | DOWN (tạm thời không dùng được). */
data class YtStatus(
    val health: String = "OK",
    val code: String = "",
    val message: String = "",
    val failures: Int = 0,
    val since: Long = System.currentTimeMillis(),
    val newpipe: String = BuildConfig.NEWPIPE_VERSION
)

/** Kết quả giải link: hai luồng riêng (hình + tiếng), hoặc một luồng duy nhất (progressive / HLS). */
sealed class ResolvedMedia {
    data class Split(val videoUrl: String, val audioUrl: String, val height: Int) : ResolvedMedia()
    data class Single(val url: String, val hls: Boolean) : ResolvedMedia()
}

/** Bài YouTube được lưu trong bảng songs với filePath = "yt:<videoId>", extension = "yt". */
const val YT_EXT = "yt"
const val YT_PREFIX = "yt:"
val SongEntity.isYoutube: Boolean get() = extension == YT_EXT
val SongEntity.youtubeId: String get() = filePath.removePrefix(YT_PREFIX)

// ---------------------------------------------------------------- repository

/**
 * Tìm kiếm và giải link YouTube bằng NewPipeExtractor (không cần API key).
 * Tất cả hàm suspend tự chuyển sang luồng IO.
 */
class YoutubeRepository(private val settings: SettingsStore) {

    private val lock = Any()
    @Volatile private var inited = false

    private class Cursor(val extractor: SearchExtractor, val page: Page)
    private val cursors = object : LinkedHashMap<String, Cursor>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cursor>?) = size > 16
    }

    private fun ensureInit() {
        if (inited) return
        synchronized(lock) {
            if (inited) return
            NewPipe.init(YoutubeDownloader(), Localization("vi", "VN"), ContentCountry("VN"))
            inited = true
        }
    }

    // ------------------------------------------------------------ sức khỏe YouTube

    private val _status = MutableStateFlow(YtStatus())
    val status: StateFlow<YtStatus> = _status
    private val healthLock = Any()
    private var consecutiveFailures = 0
    private var monitorStarted = false

    /** Một thao tác YouTube thành công (hoặc YouTube trả lời đúng kiểu "video này bị chặn") → coi như hoạt động tốt. */
    fun reportOk() {
        synchronized(healthLock) {
            consecutiveFailures = 0
            if (_status.value.health != "OK") {
                _status.value = YtStatus()
                YtDiag.note("YouTube hoạt động trở lại")
            }
        }
    }

    /** Chỉ lỗi mang tính hệ thống (mạng, bị chặn, YouTube đổi dữ liệu...) mới tính; video riêng tư/chặn vùng thì không. */
    fun reportFailure(code: String, message: String) {
        if (code !in SYSTEMIC) return
        synchronized(healthLock) {
            // YT_BOT là YouTube chủ động chặn (không phải chập chờn): coi là DOWN ngay, vì có cooldown nên sẽ không có nhiều lần thử liên tiếp
            consecutiveFailures = if (code == "YT_BOT") maxOf(consecutiveFailures + 1, DOWN_AFTER) else consecutiveFailures + 1
            val n = consecutiveFailures
            if (n < DEGRADED_AFTER) return
            val h = if (n >= DOWN_AFTER) "DOWN" else "DEGRADED"
            val cur = _status.value
            if (cur.health != h) YtDiag.note("Trạng thái YouTube: ${cur.health} -> $h ($code)")
            _status.value = YtStatus(h, code, hint(code, message), n, if (cur.health == h) cur.since else System.currentTimeMillis())
        }
    }

    /** Lỗi khi PHÁT (đã tự nối lại nhiều lần mà vẫn hỏng): ghi log và tính vào sức khỏe. */
    fun reportPlaybackFailure(videoId: String, errorName: String, t: Throwable?) {
        YtDiag.log("play", videoId, "YT_PLAYBACK", errorName, t)
        reportFailure("YT_PLAYBACK", "phát bị lỗi liên tục ($errorName)")
    }

    private fun hint(code: String, message: String): String = when (code) {
        "YT_CHANGED" -> "YouTube vừa đổi cách trả dữ liệu, cần cập nhật thư viện NewPipeExtractor." + updateSuffix()
        "YT_BOT" -> "YouTube đang tạm chặn box, app tự thử lại sau khoảng ${waitMinutes()} phút"
        "YT_NETWORK" -> "box không kết nối được Internet hoặc YouTube"
        else -> message.substringAfter(": ", message)
    }

    /** Gợi ý ngắn kèm vào cảnh báo trên điện thoại / TV: có bản mới thì nêu số bản, và chỉ chỗ xem hướng dẫn. */
    private fun updateSuffix(): String {
        val latest = settings.npLatest
        val newer = latest.isNotEmpty() && NewPipeUpdateChecker.isNewer(latest)
        return if (newer) " Đã có bản mới $latest: trên TV mở Cài đặt > YouTube để xem cách nâng."
        else " Trên TV mở Cài đặt > YouTube để xem hướng dẫn."
    }

    /**
     * Khi YouTube đang lỗi, cứ 45 giây xem có thể thử lại chưa để báo "đã hoạt động lại" mà không cần ai bấm.
     * Đang trong thời gian chờ (cooldown) sau khi bị chặn thì KHÔNG gửi gì lên YouTube.
     */
    fun startHealthMonitor(scope: CoroutineScope) {
        if (monitorStarted) return
        monitorStarted = true
        scope.launch {
            while (true) {
                delay(45_000)
                if (_status.value.health != "OK") {
                    probe(manual = false)
                    // YouTube đang lỗi: tranh thủ xem NewPipeExtractor có bản mới chưa (tối đa 1 lần/giờ)
                    if (_status.value.health != "OK") NewPipeUpdateChecker.check(settings, 60 * 60_000L)
                }
            }
        }
    }

    @Volatile private var lastManualProbeAt = 0L

    /**
     * Nút "Thử lại" trên điện thoại: kiểm tra ngay và trả về trạng thái mới. Được phép bỏ qua cooldown
     * (vd vừa reset modem để đổi IP) nhưng cách nhau tối thiểu [MANUAL_MIN_GAP_MS] để bấm dồn không làm YouTube chặn lâu hơn.
     */
    suspend fun checkNow(): YtStatus {
        val now = System.currentTimeMillis()
        if (now - lastManualProbeAt >= MANUAL_MIN_GAP_MS) {
            lastManualProbeAt = now
            probe(manual = true)
        }
        return _status.value
    }

    /**
     * Một lần thử nhẹ để biết YouTube đã dùng lại được chưa. Nếu đang bị chặn ở bước LẤY LINK (trường hợp hay gặp:
     * tìm kiếm vẫn chạy nhưng không phát được) thì thử đúng bước đó, nếu không thì thử tìm kiếm.
     */
    private suspend fun probe(manual: Boolean) {
        try {
            if (resolveBackoff.strikes > 0) resolve(PROBE_VIDEO_ID, bypassCooldown = manual, singleAttempt = true)
            else search("karaoke", null, bypassCooldown = manual)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Link đã lấy sẵn (còn hạn) hoặc null. */
    fun peek(videoId: String): ResolvedMedia? = cacheGet(videoId)

    // ------------------------------------------------------------ tìm kiếm

    suspend fun search(query: String, cursorId: String?, bypassCooldown: Boolean = false): YtSearchPage = withContext(Dispatchers.IO) {
        try {
            if (!bypassCooldown) searchBackoff.remainingMs().takeIf { it > 0 }?.let { throw coolingDown(it) }
            ensureInit()
            val (extractor, page) = if (cursorId != null) {
                val c = synchronized(cursors) { cursors.remove(cursorId) }
                    ?: throw ApiException("YT_CURSOR_EXPIRED", "Phiên tìm kiếm đã hết hạn, hãy tìm lại")
                c.extractor to c.extractor.getPage(c.page)
            } else {
                val ex = ServiceList.YouTube.getSearchExtractor(
                    query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
                )
                ex.fetchPage()
                ex to ex.initialPage
            }

            val items = page.items
                .filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamType.VIDEO_STREAM && !it.isShortFormContent && it.duration > 0 }
                .mapNotNull { it.toYtVideo() }

            var next: String? = null
            val np: Page? = page.nextPage
            if (page.hasNextPage() && np != null) {
                val id = UUID.randomUUID().toString().take(12)
                synchronized(cursors) { cursors[id] = Cursor(extractor, np) }
                next = id
            }
            searchBackoff.clear()
            // Tìm kiếm chạy được không có nghĩa là lấy link đã hết bị chặn: đang cooldown lấy link thì để bước đó tự báo OK
            if (resolveBackoff.strikes == 0) reportOk()
            YtSearchPage(items, next)
        } catch (e: ApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val api = mapError(e, "Không tìm được trên YouTube")
            YtDiag.log("search", null, api.code, api.message, e)
            if (api.code == "YT_BOT") noteBlocked(searchBackoff, "tìm kiếm")
            reportFailure(api.code, api.message ?: "")
            throw api
        }
    }

    private fun StreamInfoItem.toYtVideo(): YtVideo? {
        val id = VIDEO_ID.find(url)?.groupValues?.get(1) ?: return null
        return YtVideo(id, name ?: "(không tên)", uploaderName ?: "YouTube", duration * 1000L)
    }

    // ------------------------------------------------------------ gợi ý khi gõ

    suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            ensureInit()
            ServiceList.YouTube.suggestionExtractor.suggestionList(query).take(8)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ giải link

    private class Cached(val media: ResolvedMedia, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    private fun cacheGet(id: String): ResolvedMedia? {
        val c = cache[id] ?: return null
        if (System.currentTimeMillis() - c.at > CACHE_TTL_MS) { cache.remove(id); return null }
        return c.media
    }

    private fun cachePut(id: String, m: ResolvedMedia) {
        if (cache.size >= 40) cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
        cache[id] = Cached(m, System.currentTimeMillis())
    }

    /** Xóa link đã lưu (vd: link hết hạn làm player báo lỗi) để lần sau lấy link mới. */
    fun invalidate(videoId: String) { cache.remove(videoId); failedAt.remove(videoId) }

    /** Đổi chất lượng YouTube: bỏ hết link đã lấy sẵn để các bài sau được giải link lại theo mức mới. */
    fun clearResolveCache() { cache.clear() }

    /**
     * Lấy link phát. Dùng lại link đã lấy sẵn (còn hạn), và tự thử lại khi gặp lỗi tạm thời
     * (mạng chập chờn, YouTube trả dữ liệu lạ...). Lỗi vĩnh viễn (riêng tư, bị chặn, trực tiếp...) báo ngay.
     */
    suspend fun resolve(videoId: String, bypassCooldown: Boolean = false, singleAttempt: Boolean = false): ResolvedMedia {
        cacheGet(videoId)?.let { return it }
        // Đang trong thời gian chờ sau khi bị chặn: không gửi thêm request nào (gửi dồn chỉ làm bị chặn lâu hơn)
        if (!bypassCooldown) resolveBackoff.remainingMs().takeIf { it > 0 }?.let { throw coolingDown(it) }
        var last: ApiException? = null
        val attempts = if (singleAttempt) 1 else MAX_ATTEMPTS
        for (attempt in 1..attempts) {
            try {
                val m = resolveOnce(videoId)
                cachePut(videoId, m)
                failedAt.remove(videoId)
                resolveBackoff.clear()
                searchBackoff.clear()
                reportOk()
                return m
            } catch (e: ApiException) {
                YtDiag.log("resolve#$attempt", videoId, e.code, e.message, e.cause ?: e)
                if (e.code in NO_RETRY) {
                    // YouTube vẫn trả lời bình thường (chỉ riêng video này không phát được)
                    if (e.code != "YT_NO_STREAM") reportOk()
                    throw e
                }
                if (e.code == "YT_BOT") {
                    // Thử lại ngay vô ích và còn làm IP bị chặn lâu hơn: dừng, bắt đầu cooldown tăng dần
                    noteBlocked(resolveBackoff, "lấy link")
                    reportFailure(e.code, e.message ?: "")
                    throw e
                }
                last = e
                if (attempt < attempts) delay(1000L * attempt)
            }
        }
        val err = last ?: ApiException("YT_ERROR", "Không lấy được link YouTube", 502)
        reportFailure(err.code, err.message ?: "")
        throw err
    }

    // ------------------------------------------------------------ cooldown khi bị chặn (YT_BOT)

    /** Thời gian chờ tăng dần sau mỗi lần bị chặn liên tiếp: 2 → 4 → 8 → 15 → 30 phút. Có kết quả thành công thì về 0. */
    private class Backoff {
        @Volatile var until = 0L
        @Volatile var strikes = 0
        fun remainingMs(): Long = (until - System.currentTimeMillis()).coerceAtLeast(0L)
        @Synchronized fun hit(): Long {
            strikes++
            val ms = minOf(BOT_BASE_MS shl (strikes - 1).coerceAtMost(10), BOT_MAX_MS)
            until = System.currentTimeMillis() + ms
            return ms
        }
        @Synchronized fun clear() { strikes = 0; until = 0L }
    }

    private val resolveBackoff = Backoff()   // bị chặn ở bước lấy link phát
    private val searchBackoff = Backoff()    // bị chặn ở bước tìm kiếm

    private fun noteBlocked(b: Backoff, what: String) {
        val ms = b.hit()
        YtDiag.note("YouTube chặn bước $what (lần ${b.strikes}), tạm nghỉ ${ms / 60_000.0} phút rồi mới thử lại")
    }

    private fun waitMinutes(): Int {
        val ms = maxOf(resolveBackoff.remainingMs(), searchBackoff.remainingMs())
        return ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }

    private fun coolingDown(remainingMs: Long): ApiException {
        val min = ((remainingMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
        return ApiException("YT_BOT", "YouTube đang tạm chặn box, thử lại sau khoảng $min phút", 503)
    }

    /** Còn bao lâu nữa (ms) thì được thử lấy link lại; 0 = được thử ngay. Dùng để bài đang chờ biết khi nào thử tiếp. */
    fun resolveCooldownMs(): Long = resolveBackoff.remainingMs()

    /**
     * Lấy sẵn link của bài kế tiếp ở nền (khi bài hiện tại đang hát) để chuyển bài mượt và biết trước bài lỗi.
     * Không lặp lại quá dồn dập: bài vừa lỗi sẽ nghỉ 60 giây mới thử lại.
     */
    fun prefetch(videoId: String, scope: CoroutineScope, onPermanentError: (ApiException) -> Unit = {}) {
        if (cacheGet(videoId) != null) return
        if (resolveBackoff.remainingMs() > 0) return   // đang bị chặn: đừng gửi thêm, tới lượt hát sẽ tự thử lại
        val lastFail = failedAt[videoId]
        if (lastFail != null && System.currentTimeMillis() - lastFail < 60_000) return
        if (!inflight.add(videoId)) return
        scope.launch {
            try {
                resolve(videoId)
            } catch (e: ApiException) {
                failedAt[videoId] = System.currentTimeMillis()
                if (e.code in NO_RETRY) onPermanentError(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAt[videoId] = System.currentTimeMillis()
            } finally {
                inflight.remove(videoId)
            }
        }
    }

    private suspend fun resolveOnce(videoId: String): ResolvedMedia = withContext(Dispatchers.IO) {
        try {
            ensureInit()
            val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            ex.fetchPage()

            val type = ex.streamType
            if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) {
                throw ApiException("YT_LIVE", "Đây là video phát trực tiếp, không thêm được", 502)
            }
            val maxH = settings.youtubeMaxHeight

            // Ưu tiên: H.264 (HiSilicon giải mã phần cứng) tách riêng + AAC, để đạt 720p/1080p.
            val video = ex.videoOnlyStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc1") }
                .filter { it.height in 1..maxH }
                .maxWithOrNull(compareBy<VideoStream>({ it.height }, { it.bitrate }))

            val audioAll = ex.audioStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
            val audio = (audioAll.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .ifEmpty { audioAll })
                .maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }

            if (video != null && audio != null) {
                return@withContext ResolvedMedia.Split(video.content, audio.content, video.height)
            }

            // Dự phòng 1: luồng ghép sẵn hình+tiếng (thường 360p/720p)
            val muxed = ex.videoStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.height in 1..maxOf(maxH, 360) }
                .maxByOrNull { it.height }
            if (muxed != null) return@withContext ResolvedMedia.Single(muxed.content, hls = false)

            // Dự phòng 2: HLS
            val hls = try { ex.hlsUrl } catch (_: Exception) { null }
            if (!hls.isNullOrBlank()) return@withContext ResolvedMedia.Single(hls, hls = true)

            throw ApiException("YT_NO_STREAM", "Không tìm thấy luồng phát phù hợp cho video này")
        } catch (e: ApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw mapError(e, "Không lấy được link YouTube")
        }
    }

    // ------------------------------------------------------------ lỗi

    private fun mapError(e: Exception, prefix: String): ApiException {
        val (code, reason) = when (e) {
            is AgeRestrictedContentException -> "YT_UNAVAILABLE" to "video giới hạn độ tuổi"
            is GeographicRestrictionException -> "YT_UNAVAILABLE" to "video bị chặn ở khu vực này"
            is PrivateContentException -> "YT_UNAVAILABLE" to "video riêng tư"
            is PaidContentException -> "YT_UNAVAILABLE" to "video trả phí"
            is SignInConfirmNotBotException -> "YT_BOT" to "YouTube yêu cầu xác minh không phải robot, thử lại sau ít phút"
            is ContentNotAvailableException -> "YT_UNAVAILABLE" to "video không còn khả dụng"
            is ReCaptchaException -> "YT_BOT" to "YouTube đang giới hạn truy cập, thử lại sau"
            is UnknownHostException, is ConnectException -> "YT_NETWORK" to "box không kết nối được Internet"
            is SocketTimeoutException -> "YT_NETWORK" to "kết nối tới YouTube quá chậm (hết thời gian chờ)"
            is IOException -> "YT_NETWORK" to "lỗi mạng khi nói chuyện với YouTube"
            is ExtractionException -> "YT_CHANGED" to "YouTube vừa đổi cách trả dữ liệu, có thể cần cập nhật thư viện của app"
            else -> "YT_ERROR" to (e.message ?: e.javaClass.simpleName)
        }
        return ApiException(code, "$prefix: $reason", 502, e)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val CACHE_TTL_MS = 3 * 3600 * 1000L          // link YouTube sống ~6 giờ, giữ 3 giờ cho chắc
        /** Lỗi vĩnh viễn: thử lại cũng vô ích nên báo ngay. */
        private val NO_RETRY = setOf("YT_UNAVAILABLE", "YT_LIVE", "YT_NO_STREAM")
        /** Lỗi mang tính hệ thống: nhiều lần liên tiếp nghĩa là YouTube (hoặc mạng) đang hỏng. */
        private val SYSTEMIC = setOf("YT_CHANGED", "YT_BOT", "YT_NETWORK", "YT_ERROR", "YT_PLAYBACK")
        private const val BOT_BASE_MS = 2 * 60_000L          // lần chặn đầu: nghỉ 2 phút, các lần sau gấp đôi
        private const val BOT_MAX_MS = 30 * 60_000L
        private const val MANUAL_MIN_GAP_MS = 10_000L        // nút "Thử lại" cách nhau tối thiểu
        private const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"     // video luôn có sẵn, chỉ để thử lấy link
        private const val DEGRADED_AFTER = 2
        private const val DOWN_AFTER = 3
        private val VIDEO_ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")
        val VIDEO_ID_VALID = Regex("^[A-Za-z0-9_-]{11}$")

        private const val DEFAULT_UA = YoutubeDownloader.USER_AGENT

        /** User-Agent phải khớp client YouTube đã sinh ra link, nếu không sẽ bị 403. */
        fun userAgentFor(url: String): String = try {
            when {
                YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
                YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
                YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
                else -> DEFAULT_UA
            }
        } catch (_: Exception) {
            DEFAULT_UA
        }
    }
}
