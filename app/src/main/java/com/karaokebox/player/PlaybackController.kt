package com.karaokebox.player

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.karaokebox.core.net.InternetProbe
import com.karaokebox.data.db.HistoryDao
import com.karaokebox.data.db.PlayHistoryEntity
import com.karaokebox.data.db.SongEntity
import com.karaokebox.data.repo.QueueEntry
import com.karaokebox.data.repo.QueueRepository
import com.karaokebox.data.repo.SettingsStore
import com.karaokebox.data.repo.SongRepository
import com.karaokebox.data.yt.YoutubeRepository
import com.karaokebox.data.yt.YtStatus
import com.karaokebox.data.yt.YtVideo
import com.karaokebox.data.yt.isYoutube
import com.karaokebox.data.yt.youtubeId
import com.karaokebox.player.audio.ChannelSelectAudioProcessor.Mode
import com.karaokebox.game.GameEngine
import com.karaokebox.game.nowMs
import com.karaokebox.player.audio.StudioPreset
import com.karaokebox.server.ApiException
import com.karaokebox.server.PlayerSnapshot
import com.karaokebox.server.ToastEvent
import com.karaokebox.server.toDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Nguồn trạng thái duy nhất. MỌI hàm suspend ở đây phải được gọi trên luồng Main
 * (ExoPlayer yêu cầu). Router/Activity đều tuân thủ điều này.
 */
class PlaybackController(
    private val ctx: Context,
    private val songRepo: SongRepository,
    private val queue: QueueRepository,
    private val history: HistoryDao,
    private val settings: SettingsStore,
    private val youtube: YoutubeRepository,
    private val game: GameEngine,
    private val scope: CoroutineScope
) {
    val player = KaraokePlayer(ctx)
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot

    private val _toasts = MutableSharedFlow<ToastEvent>(extraBufferCapacity = 32)
    val toasts: SharedFlow<ToastEvent> = _toasts

    private var current: SongEntity? = null
    private var vocalOn = true
    private var audioMode = "NONE"
    private var durationSaved = false
    private var announcedNext = false    // đã báo "bài tiếp theo" cho bài đang phát chưa
    private var loadJob: Job? = null     // đang giải link YouTube
    private var recoveries = 0          // số lần đã tự nối lại bài đang hát sau lỗi / ngắt sớm (khi VẪN có mạng)
    private val MAX_RECOVERIES = 3
    private var currentEntry: QueueEntry? = null   // mục hàng chờ của bài đang hát (chủ bài + để đưa lại hàng chờ nếu bị ngắt vì mất mạng)
    private var songEpoch = 0            // tăng mỗi khi sang bài mới / dừng: để các tiến trình nối lại cũ tự bỏ cuộc
    private var recoverJob: Job? = null  // tiến trình nối lại / chờ mạng của bài đang hát
    private var netWaiting = false       // đang giữ bài và chờ Internet trở lại
    private var playingOfflineId: String? = null   // bài YouTube đang phát thẳng từ cache (không qua YouTube)
    private var ytDownToastAt = 0L       // lần gần nhất đã báo "YouTube tạm thời không dùng được" (tránh báo trùng với thông báo giữ bài)
    private var offlineSince = 0L        // thời điểm bắt đầu mất mạng (elapsedRealtime), 0 = đang có mạng
    private var stallSince = 0L          // bắt đầu kẹt buffering (không tải thêm được gì)
    private var lastBuffered = -1L
    private var steadySince = 0L         // bắt đầu phát ổn định liên tục
    private val STALL_MS = 15_000L           // kẹt buffering quá lâu không có lỗi → coi như đứt mạng
    private val STEADY_RESET_MS = 30_000L    // phát ổn định chừng này thì coi như qua sự cố, đếm lại số lần nối lại
    private val OFFLINE_SKIP_AFTER_MS = 45_000L   // mất mạng quá lâu → hát bài trong máy (nếu có) thay vì treo
    private val YT_BLOCK_SKIP_AFTER_MS = 20_000L  // YouTube chặn lấy link quá lâu → hát bài trong máy (nếu có) trong lúc chờ
    private var loadToken = 0            // bỏ kết quả giải link của bài đã bị thay thế
    private var keyShift = 0             // tone hiện tại (nửa cung), nhớ riêng từng bài
    private var tempoPct = 100           // tốc độ hiện tại (%), reset về 100 khi sang bài mới
    private val MIN_KEY = -6
    private val MAX_KEY = 6
    private val preloader = YoutubePreloader(ctx, settings)
    private var gameJob: Job? = null     // trò chơi cuối bài đang chạy (bài kế tiếp chờ nó xong hoặc bị bỏ qua)
    private var songStartedAt = 0L       // lúc bắt đầu bài đang hát (nowMs), để chỉ quay vòng khi bài được hát đủ lâu

    /** Sức khỏe YouTube (OK / DEGRADED / DOWN) để điện thoại hiện cảnh báo. */
    val ytStatus: StateFlow<YtStatus> get() = youtube.status

    init {
        applyDefaultVolumeOnce()
        applyStudioFx()   // hiệu ứng âm thanh phòng thu theo cài đặt đã lưu
        player.exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    saveDurationOnce()
                    current?.let { if (it.isYoutube && playingOfflineId != it.youtubeId) youtube.reportOk() }   // phát từ cache không chứng tỏ YouTube đã dùng lại được
                }
                if (state == Player.STATE_ENDED) {
                    // Bài YouTube kết thúc quá sớm so với độ dài thật (luồng bị cắt giữa chừng) → nối lại, đừng qua bài
                    if (endedTooEarly() && tryRecover("ENDED_EARLY", null)) return
                    onSongEnded()
                    return
                }
                refresh()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()

            override fun onTracksChanged(tracks: Tracks) {
                applyAudio()
                refresh()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("KaraokePlayer", "Lỗi phát: ${error.errorCodeName}", error)
                // Mất mạng: giữ bài, chờ có mạng rồi hát tiếp đúng vị trí. Lỗi khác (link hết hạn...): nối lại tối đa 3 lần rồi mới bỏ qua bài
                if (tryRecover(error.errorCodeName, error)) return
                giveUpCurrent(error.errorCodeName, error)
            }
        })
        // Hiệu chỉnh vị trí cho client mỗi 5 giây khi đang có bài
        scope.launch {
            while (true) {
                delay(5000)
                if (current != null) refresh()
            }
        }
        // Giám sát YouTube: khi đang lỗi tự thử lại định kỳ; báo trên TV + điện thoại khi hỏng / hoạt động lại
        youtube.startHealthMonitor(scope)
        // Mở sẵn cache YouTube ở nền (lần đầu nạp chỉ mục có thể mất vài trăm ms) để bài đầu tiên không bị chậm
        scope.launch(Dispatchers.IO) { try { YtCache.warmUp(ctx, settings.youtubeCacheMb) } catch (_: Throwable) { } }
        // Cài đặt đổi giữa chừng (điện thoại / TV): áp dụng ngay cho bài đang hát
        scope.launch {
            settings.changes.collect { key ->
                when (key) {
                    // Đổi quy ước kênh ca sĩ: tính lại kênh đang phát (vocalOn giữ nguyên)
                    SettingsStore.K_LEFT_IS_VOCAL -> if (current != null) { applyAudio(); refresh() }
                    // Chất lượng YouTube: bài sau giải link theo mức mới
                    SettingsStore.K_YT_MAX_HEIGHT -> youtube.clearResolveCache()
                    // Hiệu ứng phòng thu / cường độ: áp ngay cho bài đang hát
                    SettingsStore.K_AUDIO_FX, SettingsStore.K_AUDIO_FX_LEVEL -> applyStudioFx()
                }
            }
        }
        scope.launch {
            var last = "OK"
            youtube.status.collect { s ->
                if (s.health != last) {
                    if (s.health == "DOWN") {
                        ytDownToastAt = SystemClock.elapsedRealtime()
                        _toasts.tryEmit(ToastEvent("error", "YouTube tạm thời không dùng được: ${s.message}. Bài trong máy vẫn hát bình thường."))
                    } else if (s.health == "OK" && last == "DOWN") {
                        _toasts.tryEmit(ToastEvent("info", "YouTube đã hoạt động trở lại"))
                    }
                    last = s.health
                }
            }
        }
        // Khi bài đang hát chạy được 50%: báo tên bài kế tiếp lên màn hình TV
        scope.launch {
            while (true) {
                delay(1000)
                prefetchNext()
                checkNextUp()
                watchPlayback()
                if (current != null && player.exo.isPlaying) game.session.touch()   // đang có nhạc: mốc "nghỉ hát" của trò chơi
            }
        }
    }

    // ---------------------------------------------------------------- lệnh

    /** [ownerId] = mã điện thoại (X-Client-Id) để xếp hàng công bằng giữa các máy; mặc định dùng tên hiển thị. */
    suspend fun enqueue(songId: Long, priority: Boolean, by: String, ownerId: String = by): QueueEntry {
        val song = songRepo.getById(songId) ?: throw ApiException("SONG_NOT_FOUND", "Không tìm thấy bài hát", 404)
        return enqueueSong(song, priority, by, ownerId)
    }

    suspend fun enqueueYoutube(video: YtVideo, priority: Boolean, by: String, ownerId: String = by): QueueEntry {
        val song = withContext(Dispatchers.IO) { songRepo.upsertYoutube(video) }
        val entry = enqueueSong(song, priority, by, ownerId)
        validateYoutube(song, entry.itemId, by)
        return entry
    }

    /**
     * Kiểm tra ngay khi thêm bài: video lỗi vĩnh viễn (riêng tư, chặn vùng, trực tiếp...) thì báo cho mọi người
     * và gỡ khỏi hàng chờ, thay vì để tới lượt hát mới báo lỗi. Link lấy được cũng được lưu sẵn để phát nhanh.
     */
    private fun validateYoutube(song: SongEntity, itemId: Long, by: String) {
        if (cachedOffline(song)) return   // đã có đủ trong cache: chắc chắn phát được, khỏi hỏi YouTube
        youtube.prefetch(song.youtubeId, scope) { e ->
            scope.launch {
                if (queue.remove(itemId)) {
                    _toasts.tryEmit(ToastEvent("error", "${song.title}: ${e.message ?: "không phát được"} (đã bỏ khỏi danh sách chờ)", by))
                }
            }
        }
    }

    private suspend fun enqueueSong(song: SongEntity, priority: Boolean, by: String, ownerId: String): QueueEntry {
        val wasPlaying = current != null
        // Bài thường được xếp xen kẽ theo người thêm (nếu bật trong Cài đặt); "Ưu tiên" vẫn chen lên đầu như cũ
        val entry = queue.add(song, by, priority, ownerId, settings.fairQueue, currentEntry?.ownerId)
        if (priority && wasPlaying) {
            // Bài vừa chen lên đầu hàng chờ: TV chạy chữ ở trên cùng để mọi người biết
            announcePriority(song, by)
        } else {
            _toasts.tryEmit(ToastEvent("song_added", "$by vừa thêm: ${song.title}", by, song.toDto()))
        }
        if (current == null) next()
        return entry
    }

    /** Đưa bài lên đầu hàng chờ (từ điện thoại hoặc remote TV) và báo cho mọi người. */
    suspend fun moveToTop(itemId: Long, by: String): Boolean {
        val before = queue.state.value.items
        val entry = before.firstOrNull { it.itemId == itemId } ?: return false
        val wasFirst = before.first().itemId == itemId
        val ok = queue.moveToTop(itemId)
        if (ok && !wasFirst) announcePriority(entry.song, by)
        return ok
    }

    /** Kéo thả đổi thứ tự; nếu bài được kéo lên vị trí đầu tiên thì cũng báo như "ưu tiên". */
    suspend fun moveItem(itemId: Long, toIndex: Int, by: String): Boolean {
        val before = queue.state.value.items
        val entry = before.firstOrNull { it.itemId == itemId } ?: return false
        val wasFirst = before.first().itemId == itemId
        val ok = queue.move(itemId, toIndex)
        if (ok && toIndex <= 0 && !wasFirst) announcePriority(entry.song, by)
        return ok
    }

    private fun announcePriority(song: SongEntity, by: String) {
        // Đã qua 50% mà chưa báo "tiếp theo": banner ưu tiên đã thay vai trò đó, khỏi báo trùng
        if (current != null && isPastHalf()) announcedNext = true
        _toasts.tryEmit(ToastEvent("song_priority", "$by đưa lên hát tiếp: ${song.title}", by, song.toDto()))
    }

    private fun isPastHalf(): Boolean {
        val p = player.exo
        val d = p.duration
        return d != C.TIME_UNSET && d > 0 && p.currentPosition * 2 >= d
    }

    /** Bài YouTube đã biết độ dài mà phát dừng cách cuối > 5 giây = bị ngắt sớm, không phải hết bài thật. */
    private fun endedTooEarly(): Boolean {
        val cur = current ?: return false
        if (!cur.isYoutube || cur.durationMs < 30_000) return false
        return player.exo.currentPosition < cur.durationMs - 5_000
    }

    /**
     * Gặp sự cố khi đang hát. Trả về true = đã nhận xử lý (nối lại / chờ mạng, làm ngầm); false = hết cách, gọi [giveUpCurrent].
     * - Bài trong máy: nối lại tối đa 3 lần như trước.
     * - Bài YouTube: kiểm tra mạng. MẤT MẠNG → giữ bài và chờ ([waitForInternet]); CÒN MẠNG → nối lại tối đa 3 lần.
     */
    private fun tryRecover(errName: String?, err: Throwable?): Boolean {
        val cur = current ?: return false
        val pos = player.exo.currentPosition.coerceAtLeast(0)
        if (!cur.isYoutube) {
            if (recoveries >= MAX_RECOVERIES) return false
            recoveries++
            val n = recoveries
            _toasts.tryEmit(ToastEvent("info", "Gián đoạn (${errName ?: "?"}), đang nối lại bài hát…"))
            scope.launch {
                delay(800L * n)
                if (current?.id != cur.id) return@launch   // trong lúc chờ đã chuyển bài / dừng
                startSong(cur, retry = true, resumeMs = pos)
            }
            return true
        }
        if (playingOfflineId == cur.youtubeId) {   // phát từ cache mà lỗi (cache hỏng / thiếu): lần sau đi đường YouTube bình thường
            YtOfflineIndex.forget(ctx, cur.youtubeId)
            playingOfflineId = null
        }
        recoverJob?.cancel()
        recoverJob = scope.launch { recoverYoutube(cur, pos, errName, err) }
        return true
    }

    private suspend fun recoverYoutube(cur: SongEntity, pos: Long, errName: String?, err: Throwable?) {
        val epoch = songEpoch
        val online = InternetProbe.canReachYoutube()
        if (epoch != songEpoch || current?.id != cur.id) return   // trong lúc kiểm tra đã chuyển bài / dừng
        if (!online) { waitForInternet(cur, pos, epoch); return }
        // Có mạng mà vẫn lỗi (link hết hạn, 403, luồng bị cắt, mạng quá chậm...)
        if (recoveries >= MAX_RECOVERIES) { giveUpCurrent(errName ?: "STALL", err); return }
        recoveries++
        val why = when (errName) {
            null, "ENDED_EARLY" -> "Bài bị ngắt sớm"
            "STALL" -> "Mạng chậm hoặc chập chờn"
            else -> "Gián đoạn ($errName)"
        }
        _toasts.tryEmit(ToastEvent("info", "$why, đang nối lại bài hát…"))
        youtube.invalidate(cur.youtubeId)
        delay(800L * recoveries)
        if (epoch != songEpoch || current?.id != cur.id) return
        startSong(cur, retry = true, resumeMs = pos)
    }

    /**
     * Mất Internet giữa bài: KHÔNG bỏ qua bài (nếu bỏ, bài kế cũng lỗi theo và cả hàng chờ bị "ăn" hết).
     * Giữ nguyên bài + vị trí, cứ 3 giây kiểm tra một lần, có mạng là hát tiếp ngay chỗ đang dở.
     * Mất mạng quá [OFFLINE_SKIP_AFTER_MS] mà trong hàng chờ có bài trong máy → hát bài đó trước (bài YouTube bị ngắt được đưa
     * lại ngay sau đó). Không có bài nào hát được khi offline thì tiếp tục chờ.
     */
    private suspend fun waitForInternet(cur: SongEntity, pos: Long, epoch: Int) {
        if (!netWaiting) {
            netWaiting = true
            if (offlineSince == 0L) offlineSince = SystemClock.elapsedRealtime()
            _toasts.tryEmit(ToastEvent("error", "Mất kết nối Internet. Giữ nguyên bài hát, tự nối lại khi có mạng…"))
        }
        var lastNag = SystemClock.elapsedRealtime()
        while (true) {
            delay(3_000)
            if (epoch != songEpoch || current?.id != cur.id) { netWaiting = false; return }
            // Luồng tự phục hồi trước khi ta kịp phát hiện (chỉ xảy ra khi bị kẹt buffering): để yên
            if (player.exo.playbackState == Player.STATE_READY && player.exo.playWhenReady) {
                netWaiting = false; offlineSince = 0L; return
            }
            val online = InternetProbe.canReachYoutube()
            if (epoch != songEpoch || current?.id != cur.id) { netWaiting = false; return }
            if (online) break
            val now = SystemClock.elapsedRealtime()
            if (now - offlineSince >= OFFLINE_SKIP_AFTER_MS && skipToOfflineSong(cur)) { netWaiting = false; return }
            if (now - lastNag >= 60_000) {
                lastNag = now
                _toasts.tryEmit(ToastEvent("error", "Vẫn chưa có Internet. Bài \"${cur.title}\" đang được giữ, có mạng sẽ hát tiếp."))
            }
        }
        netWaiting = false
        offlineSince = 0L
        _toasts.tryEmit(ToastEvent("info", "Đã có mạng trở lại, đang nối lại bài hát…"))
        youtube.invalidate(cur.youtubeId)   // đứt lâu thì link cũ có thể đã hết hạn
        startSong(cur, retry = true, resumeMs = pos)
    }

    /** Mất mạng lâu: chuyển sang bài trong máy đầu tiên của hàng chờ; bài YouTube bị ngắt được đưa lại vị trí kế tiếp. */
    private suspend fun skipToOfflineSong(cur: SongEntity): Boolean =
        skipToLocalSong(cur, "Chưa có mạng: hát bài trong máy trước, bài \"${cur.title}\" sẽ hát lại sau.")

    /** Bài YouTube đã nằm đủ trong cache trên box (hát được không cần Internet / YouTube). */
    private fun cachedOffline(song: SongEntity): Boolean =
        song.isYoutube && YtCache.offlineEntry(ctx, settings.youtubeCacheMb, song.youtubeId, settings.youtubeMaxHeight) != null

    /** Như [skipToOfflineSong] nhưng dùng thông báo [message] tùy lý do (mất mạng / YouTube chặn). */
    private suspend fun skipToLocalSong(cur: SongEntity, message: String): Boolean {
        val local = queue.state.value.items.firstOrNull { !it.song.isYoutube || cachedOffline(it.song) } ?: return false
        if (!queue.moveToTop(local.itemId)) return false
        currentEntry?.let { queue.insertAt(it, 1) }
        _toasts.tryEmit(ToastEvent("info", message))
        scope.launch { next() }   // coroutine riêng: next() sẽ hủy tiến trình chờ hiện tại
        return true
    }

    /**
     * YouTube đang tạm chặn box (cooldown ở [YoutubeRepository]). Giữ nguyên bài trong lúc chờ, hết thời gian chờ
     * (hoặc người dùng bấm "Thử lại" và YouTube dùng được) thì tự lấy link lại. Chờ lâu mà hàng chờ có bài trong máy → hát bài đó trước.
     */
    private suspend fun waitForYoutube(cur: SongEntity, pos: Long, epoch: Int) {
        val start = SystemClock.elapsedRealtime()
        // Vừa báo "YouTube tạm thời không dùng được" thì thôi, không báo thêm một thông báo nữa
        if (start - ytDownToastAt > 10_000L) {
            _toasts.tryEmit(ToastEvent("info", "YouTube đang tạm chặn. Bài \"${cur.title}\" được giữ lại, sẽ tự hát khi thử lại được."))
        }
        while (true) {
            delay(youtube.resolveCooldownMs().coerceIn(3_000L, 15_000L))
            if (epoch != songEpoch || current?.id != cur.id) return   // trong lúc chờ đã chuyển bài / dừng
            if (SystemClock.elapsedRealtime() - start >= YT_BLOCK_SKIP_AFTER_MS &&
                skipToLocalSong(cur, "YouTube đang tạm chặn: hát bài trong máy trước, bài \"${cur.title}\" sẽ hát lại sau.")
            ) return
            if (youtube.resolveCooldownMs() <= 0L) break
        }
        startSong(cur, retry = true, resumeMs = pos)
    }

    /** Hết cách với bài đang hát: báo lỗi rồi qua bài kế tiếp. */
    private fun giveUpCurrent(errName: String, err: Throwable?) {
        val title = current?.title ?: ""
        current?.takeIf { it.isYoutube }?.let { youtube.reportPlaybackFailure(it.youtubeId, errName, err) }
        _toasts.tryEmit(ToastEvent("error", "Không phát được: $title ($errName)"))
        scope.launch { next() }
    }

    /**
     * Chạy mỗi giây: (1) phát ổn định 30 giây thì đếm lại số lần nối lại; (2) bài YouTube kẹt buffering 15 giây
     * không tải thêm được gì mà ExoPlayer cũng chưa báo lỗi (nguyên nhân gây "treo") → coi như đứt mạng và xử lý.
     */
    private fun watchPlayback() {
        val cur = current
        if (cur == null) { stallSince = 0L; lastBuffered = -1L; steadySince = 0L; return }
        val p = player.exo
        val now = SystemClock.elapsedRealtime()
        if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
            if (steadySince == 0L) steadySince = now
            else if (now - steadySince >= STEADY_RESET_MS) { recoveries = 0; offlineSince = 0L }
        } else {
            steadySince = 0L
        }
        val stalling = cur.isYoutube && !netWaiting && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING &&
            loadJob?.isActive != true && recoverJob?.isActive != true
        if (!stalling) { stallSince = 0L; lastBuffered = -1L; return }
        val buf = p.bufferedPosition
        if (stallSince == 0L || buf != lastBuffered) { stallSince = now; lastBuffered = buf; return }
        if (now - stallSince >= STALL_MS) {
            stallSince = 0L; lastBuffered = -1L
            if (!tryRecover("STALL", null)) giveUpCurrent("STALL", null)
        }
    }

    private val warnedBadNext = HashSet<String>()

    /** Khi bài hiện tại đã phát ổn định: lấy sẵn link YouTube của bài kế tiếp (và cảnh báo sớm nếu bài đó không phát được). */
    private fun prefetchNext() {
        if (current == null || player.exo.playbackState != Player.STATE_READY) return
        val next = queue.state.value.items.firstOrNull()?.song ?: return
        if (!next.isYoutube) return
        if (cachedOffline(next)) return   // sẽ phát thẳng từ cache: không cần lấy link
        // Đã có link: tải sẵn ~30-50 giây đầu của bài kế tiếp vào cache để chuyển bài mượt
        youtube.peek(next.youtubeId)?.let { preloader.preload(scope, next.youtubeId, it); return }
        youtube.prefetch(next.youtubeId, scope) { e ->
            if (warnedBadNext.add(next.youtubeId))
                _toasts.tryEmit(ToastEvent("error", "Bài kế tiếp có thể không phát được: ${next.title}. ${e.message ?: ""}"))
        }
    }

    private fun checkNextUp() {
        if (announcedNext || current == null) return
        val p = player.exo
        if (p.playbackState != Player.STATE_READY || !p.playWhenReady) return
        if (!isPastHalf()) return
        val next = queue.state.value.items.firstOrNull() ?: return
        announcedNext = true
        _toasts.tryEmit(ToastEvent("next_up", "Tiếp theo: ${next.song.title}", next.addedBy, next.song.toDto()))
    }

    /** Sang bài kế tiếp (phím Phải / nút Qua bài). Nếu trò chơi cuối bài đang hiện thì dọn nó đi. */
    suspend fun next() {
        cancelGame()
        advance()
    }

    private suspend fun advance() {
        val e = queue.popFirst()
        if (e == null) {
            loadToken++
            loadJob?.cancel()
            resetRecovery()
            current = null
            currentEntry = null
            audioMode = "NONE"
            keyShift = 0; tempoPct = 100
            player.setPitchTempo(0, 100)
            player.exo.stop()
            player.exo.clearMediaItems()
            refresh()
            return
        }
        startSong(e.song, entry = e)
    }

    /** Hủy mọi tiến trình nối lại / chờ mạng của bài cũ (gọi khi sang bài mới hoặc dừng hẳn). */
    private fun resetRecovery() {
        songEpoch++
        recoverJob?.cancel()
        recoverJob = null
        netWaiting = false
        offlineSince = 0L
        stallSince = 0L; lastBuffered = -1L; steadySince = 0L
    }

    private suspend fun startSong(song: SongEntity, retry: Boolean = false, resumeMs: Long = 0L, entry: QueueEntry? = null) {
        current = song
        if (!retry) { recoveries = 0; announcedNext = false; resetRecovery(); currentEntry = entry; songStartedAt = nowMs(); game.session.onSongStart() }
        durationSaved = false
        playingOfflineId = null
        audioMode = "NONE"
        player.channelProcessor.mode = Mode.PASS
        if (!retry) { keyShift = settings.songKey(song.id); tempoPct = 100 }   // tone nhớ theo từng bài
        player.setPitchTempo(keyShift, tempoPct)
        loadJob?.cancel()
        val token = ++loadToken
        if (song.isYoutube) {
            // Dừng bài cũ ngay, rồi giải link ở nền (cần Internet, mất 1-3 giây)
            player.exo.stop()
            player.exo.clearMediaItems()
            player.exo.playWhenReady = true
            refresh()
            loadJob = scope.launch {
                try {
                    // Bài đã nằm đủ trong cache trên box: phát thẳng, không cần Internet và không gửi request nào lên YouTube
                    val off = withContext(Dispatchers.IO) {
                        YtCache.offlineEntry(ctx, settings.youtubeCacheMb, song.youtubeId, settings.youtubeMaxHeight)
                    }
                    if (token != loadToken) return@launch
                    if (off != null && player.loadOffline(song.youtubeId, off, resumeMs, settings.youtubeCacheMb)) {
                        playingOfflineId = song.youtubeId
                        refresh()
                        return@launch
                    }
                    val media = youtube.resolve(song.youtubeId)
                    if (token != loadToken) return@launch
                    player.loadYoutube(song.youtubeId, media, resumeMs, settings.youtubeCacheMb)
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token != loadToken) return@launch
                    val code = (e as? ApiException)?.code
                    if (code == "YT_BOT") {
                        // YouTube tạm chặn box: KHÔNG bỏ bài (bỏ thì cả hàng chờ bị "ăn" hết). Giữ bài, hết thời gian chờ thì tự thử lại.
                        if (token != loadToken) return@launch
                        val epoch = songEpoch
                        recoverJob?.cancel()
                        recoverJob = scope.launch { waitForYoutube(song, resumeMs, epoch) }
                        return@launch
                    }
                    val permanent = code != null && code in PERMANENT_YT_ERRORS   // riêng video này không phát được
                    if (!permanent && (code == "YT_NETWORK" || !InternetProbe.canReachYoutube(4_000))) {
                        // Mất mạng lúc lấy link: giữ bài, chờ có mạng rồi thử lại (không bỏ qua, tránh "ăn" hết hàng chờ)
                        if (token != loadToken) return@launch
                        val epoch = songEpoch
                        recoverJob?.cancel()
                        recoverJob = scope.launch { waitForInternet(song, resumeMs, epoch) }
                        return@launch
                    }
                    _toasts.tryEmit(ToastEvent("error", "Không phát được: ${song.title}. ${e.message ?: ""}"))
                    scope.launch { next() }   // coroutine riêng: next() sẽ hủy loadJob hiện tại
                }
            }
        } else {
            player.load(song.filePath, resumeMs)
            refresh()
        }
        if (!retry) withContext(Dispatchers.IO) {
            songRepo.markPlayed(song.id)
            history.insert(PlayHistoryEntity(songId = song.id, completed = false))
        }
    }

    /**
     * Chưa có bài nào đang phát nhưng hàng chờ còn bài (vd: hàng chờ được khôi phục sau khi tắt/mở máy)
     * → bắt đầu hát từ đầu hàng chờ, không cần thêm bài mới.
     */
    private suspend fun startQueueOrThrow() {
        if (queue.state.value.items.isEmpty()) throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        next()
    }

    suspend fun toggle() {
        if (current == null) { startQueueOrThrow(); return }
        if (player.exo.playWhenReady) player.exo.pause() else player.exo.play()
        refresh()
    }

    suspend fun play() {
        if (current == null) { startQueueOrThrow(); return }
        player.exo.play(); refresh()
    }

    fun pause() {
        if (current == null) throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        player.exo.pause(); refresh()
    }

    fun replay() {
        if (current == null) throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        cancelGame()   // hát lại bài thì bỏ vòng xoay đang hiện, không được tự qua bài sau đó
        player.exo.seekTo(0)
        player.exo.play()
        announcedNext = false
        refresh()
    }

    /** Báo một thông tin ngắn lên TV (chữ chạy) và các điện thoại. */
    private fun applyStudioFx() {
        player.studioProcessor.configure(StudioPreset.fromKey(settings.audioFx), settings.audioFxLevel)
    }

    /** Mã điện thoại đã chọn bài đang hát (null nếu không rõ, vd bài khôi phục sau khi mở lại app). */
    val currentOwnerId: String? get() = currentEntry?.ownerId
    val hasCurrent: Boolean get() = current != null

    fun announce(message: String) {
        _toasts.tryEmit(ToastEvent("info", message))
    }

    fun seek(ms: Long) {
        if (current == null) throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        player.exo.seekTo(ms.coerceAtLeast(0)); refresh()
    }

    fun setVocal(on: Boolean?) {
        if (current == null) throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        if (audioMode == "NONE") {
            throw ApiException("CANNOT_SEPARATE", "Bài này không tách được lời (chỉ có 1 luồng âm thanh)")
        }
        vocalOn = on ?: !vocalOn
        applyAudio()
        refresh()
    }

    /** Đảo track/kênh ca sĩ <-> beat cho riêng bài đang phát (khi file đặt ngược quy ước). */
    suspend fun swapAudio() {
        val song = current ?: throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        val mode = if (song.audioMode == "SWAPPED") "AUTO" else "SWAPPED"
        current = song.copy(audioMode = mode)
        withContext(Dispatchers.IO) { songRepo.updateAudioMode(song.id, mode) }
        applyAudio()
        refresh()
    }

    /** Đổi tone: delta = cộng/trừ nửa cung, value = đặt thẳng (0 = tone gốc). Nhớ riêng cho từng bài. */
    fun setKey(delta: Int?, value: Int?) {
        val song = current ?: throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        if (delta == null && value == null) throw ApiException("BAD_REQUEST", "Thiếu delta hoặc value")
        keyShift = (value ?: (keyShift + (delta ?: 0))).coerceIn(MIN_KEY, MAX_KEY)
        settings.setSongKey(song.id, keyShift)
        player.setPitchTempo(keyShift, tempoPct)
        val label = when {
            keyShift > 0 -> "Tone: +$keyShift nửa cung"
            keyShift < 0 -> "Tone: $keyShift nửa cung"
            else -> "Tone gốc"
        }
        _toasts.tryEmit(ToastEvent("info", label))
        refresh()
    }

    /** Đổi tốc độ 70%..130%: delta = cộng/trừ, value = đặt thẳng (100 = gốc). Chỉ áp cho bài đang hát. */
    fun setTempo(delta: Int?, value: Int?) {
        if (current == null) throw ApiException("NOTHING_PLAYING", "Chưa có bài nào đang phát")
        if (delta == null && value == null) throw ApiException("BAD_REQUEST", "Thiếu delta hoặc value")
        tempoPct = (value ?: (tempoPct + (delta ?: 0))).coerceIn(70, 130)
        player.setPitchTempo(keyShift, tempoPct)
        _toasts.tryEmit(ToastEvent("info", if (tempoPct == 100) "Tốc độ gốc" else "Tốc độ: $tempoPct%"))
        refresh()
    }

    fun setVolume(level: Int?, delta: Int?) {
        val maxIdx = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = when {
            level != null -> (level.coerceIn(0, 100) * maxIdx / 100f).roundToInt()
            delta != null -> {
                val step = max(1, (abs(delta) * maxIdx / 100f).roundToInt())
                cur + if (delta >= 0) step else -step
            }
            else -> throw ApiException("BAD_REQUEST", "Thiếu level hoặc delta")
        }.coerceIn(0, maxIdx)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        refresh()
    }

    fun stopAll() {
        cancelGame()
        loadToken++
        loadJob?.cancel()
        resetRecovery()
        current = null
        currentEntry = null
        keyShift = 0; tempoPct = 100
        player.exo.stop()
        player.exo.clearMediaItems()
        refresh()
    }

    // ------------------------------------------------------------ trò chơi cuối bài

    /**
     * Bài kết thúc bình thường. Nếu trò chơi cuối bài bật và tới lượt thì quay vòng xoay trên TV (tối đa ~20 giây),
     * xong mới sang bài kế tiếp. Bài hát chưa đủ [MIN_SONG_FOR_GAME_MS] (vd tua thẳng tới cuối) thì không quay.
     */
    private fun onSongEnded() {
        cancelGame()
        gameJob = scope.launch {
            val playedLongEnough = nowMs() - songStartedAt >= MIN_SONG_FOR_GAME_MS
            if (game.shouldPlay(naturalEnd = playedLongEnough)) {
                refresh()   // điện thoại thấy "đã hết bài" trong lúc TV đang quay
                game.play(test = false)
            }
            gameJob = null
            advance()
        }
    }

    /** Bỏ qua vòng đang hiện (phím OK/Back trên TV hoặc chủ phòng): bài kế tiếp phát ngay. */
    fun skipGame() = game.skip()

    private fun cancelGame() {
        gameJob?.cancel()
        gameJob = null
        game.dismiss()
    }

    // ------------------------------------------------------------ nội bộ

    private fun saveDurationOnce() {
        val song = current ?: return
        val d = player.exo.duration
        if (durationSaved || d == C.TIME_UNSET || d <= 0) return
        durationSaved = true
        scope.launch(Dispatchers.IO) { songRepo.updateDuration(song.id, d) }
    }

    private fun applyAudio() {
        val song = current
        if (song == null) {
            audioMode = "NONE"; return
        }
        if (song.isYoutube) {   // video YouTube chỉ có 1 luồng tiếng: không tách lời / kênh
            audioMode = "NONE"
            player.channelProcessor.mode = Mode.PASS
            return
        }
        val groups = player.audioGroups()
        val swapped = song.audioMode == "SWAPPED"
        when {
            groups.size >= 2 -> {
                audioMode = "TRACK"
                val vocalIdx = if (swapped) 1 else 0
                val beatIdx = 1 - vocalIdx
                player.channelProcessor.mode = Mode.PASS
                player.selectAudioGroup(if (vocalOn) vocalIdx else beatIdx)
            }
            groups.size == 1 && groups[0].getTrackFormat(0).channelCount.let { it == 2 || it == Format.NO_VALUE } -> {
                val vocalLeft = settings.leftIsVocal != swapped
                audioMode = if (vocalLeft) "LR_LEFT_VOCAL" else "LR_RIGHT_VOCAL"
                player.channelProcessor.mode = if (vocalOn == vocalLeft) Mode.LEFT else Mode.RIGHT
            }
            else -> {
                audioMode = "NONE"
                player.channelProcessor.mode = Mode.PASS
            }
        }
    }

    /** Lần chạy đầu tiên: đặt âm lượng hệ thống về [DEFAULT_VOLUME_PCT]%. Các lần sau giữ nguyên mức người dùng đã chỉnh. */
    private fun applyDefaultVolumeOnce() {
        if (settings.defaultVolumeApplied) return
        settings.defaultVolumeApplied = true
        try {
            val maxIdx = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (DEFAULT_VOLUME_PCT * maxIdx / 100f).roundToInt().coerceIn(0, maxIdx)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {
        }
    }

    private fun currentVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun refresh() {
        val p = player.exo
        val song = current
        val status = when {
            song == null -> "IDLE"
            p.playbackState == Player.STATE_ENDED -> "ENDED"
            !p.playWhenReady -> "PAUSED"
            p.playbackState == Player.STATE_READY -> "PLAYING"
            else -> "LOADING"
        }
        val dur = if (p.duration == C.TIME_UNSET) (song?.durationMs ?: 0L) else p.duration
        _snapshot.value = PlayerSnapshot(
            status = status,
            nowPlaying = song?.toDto(),
            positionMs = if (song == null) 0 else p.currentPosition.coerceAtLeast(0),
            durationMs = dur.coerceAtLeast(0),
            vocalOn = vocalOn,
            audioMode = audioMode,
            canSeparateVocal = audioMode != "NONE",
            volume = currentVolumePercent(),
            maxVolume = 100,
            serverTime = System.currentTimeMillis(),
            keyShift = keyShift,
            tempoPct = tempoPct
        )
    }

    private companion object {
        /** Âm lượng mặc định (%) khi mới cài app. */
        const val DEFAULT_VOLUME_PCT = 80
        /** Bài phải được hát ít nhất chừng này mới tính vào trò chơi cuối bài. */
        const val MIN_SONG_FOR_GAME_MS = 45_000L
        /** Lỗi riêng của từng video (xem YoutubeRepository.NO_RETRY): chờ mạng cũng vô ích nên bỏ qua bài ngay. */
        val PERMANENT_YT_ERRORS = setOf("YT_UNAVAILABLE", "YT_LIVE", "YT_NO_STREAM")
    }
}
